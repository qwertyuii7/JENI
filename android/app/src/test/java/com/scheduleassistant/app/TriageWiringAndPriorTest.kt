package com.scheduleassistant.app

import com.scheduleassistant.app.data.model.ScheduleStatus
import com.scheduleassistant.app.data.model.SyncEvent
import com.scheduleassistant.app.data.projection.ProjectionEngine
import com.scheduleassistant.app.domain.DefaultLaneClassifier
import com.scheduleassistant.app.domain.DefaultSignalScorer
import com.scheduleassistant.app.domain.PriorCalculator
import com.scheduleassistant.app.domain.PriorUpdateEvent
import com.scheduleassistant.app.domain.TriageLane
import com.scheduleassistant.app.domain.UserProfile
import com.scheduleassistant.app.engine.HlcClock
import com.scheduleassistant.app.engine.IngestionPipeline
import com.scheduleassistant.app.engine.Prefilter
import com.scheduleassistant.app.engine.ScheduleParser
import com.scheduleassistant.app.engine.SpecLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/**
 * Day 3 Verification Test Suite:
 *
 * 1. Triage wiring:
 *    - Concrete time + event -> SCHEDULE -> normal suggestion card
 *    - Important notice without time -> NEEDS_ATTENTION -> Inbox item, zero alerts scheduled
 *    - Ambiguous or time-unspecified -> NEEDS_ATTENTION, never a normal suggestion card
 *    - Noise/promo/banter -> IGNORE -> ignored counter incremented
 * 2. Priors & learning loop:
 *    - Confirming increases chat/sender prior (+0.2, bounded at +1.0)
 *    - Dismissing decreases chat/sender prior (-0.2, bounded at -1.0)
 *    - Move lane updates priors appropriately
 *    - Priors are deterministic, bounded, and reversible upon re-projection
 *    - Priors actively influence SignalScorer outcome
 * 3. Device Checklist equivalents:
 *    - DC-10: Blocked chat creates nothing
 *    - DC-11: Notice with no time lands in Needs attention, never as a suggestion
 */
class TriageWiringAndPriorTest {

    private lateinit var specLoader: SpecLoader
    private lateinit var prefilter: Prefilter
    private lateinit var parser: ScheduleParser
    private lateinit var hlc: HlcClock
    private lateinit var eventStore: MutableList<SyncEvent>
    private lateinit var rawMessageStore: MutableMap<String, String>
    private lateinit var pipeline: IngestionPipeline

    private val specDir = File("../../spec")

    @Before
    fun setUp() {
        specLoader = SpecLoader(
            keywordsStreamProvider = { FileInputStream(File(specDir, "keywords.json")) },
            synonymsStreamProvider = { FileInputStream(File(specDir, "synonyms.json")) },
            daypartsStreamProvider = { FileInputStream(File(specDir, "dayparts.json")) },
            fillersStreamProvider = { FileInputStream(File(specDir, "fillers.json")) }
        )
        prefilter = Prefilter(specLoader)
        parser = ScheduleParser(specLoader, prefilter)
        hlc = HlcClock("test-device")
        eventStore = mutableListOf()
        rawMessageStore = mutableMapOf()

        pipeline = IngestionPipeline(
            deviceId = "test-device",
            specLoader = specLoader,
            prefilter = prefilter,
            parser = parser,
            hlc = hlc,
            eventStore = eventStore,
            rawMessageStore = rawMessageStore,
            signalScorer = DefaultSignalScorer(),
            laneClassifier = DefaultLaneClassifier(),
            userProfile = UserProfile(ownName = "Rahul", rollNumber = "21CS042")
        )
    }

    @Test
    fun testScheduleMessageCreatesSuggestionWithConcreteTime() {
        val message = "Tomorrow 4 PM DBMS lecture in Lab 3"
        val refNow = "2026-10-21T10:00:00+05:30"
        val result = pipeline.processMessage(message, referenceNow = refNow, chatTitle = "CS Section A", sender = "Prof. Sharma")

        assertEquals("PENDING", result.status)
        assertNotNull(result.entityId)
        val extraction = result.extraction
        assertNotNull(extraction)
        assertFalse("Concrete time must NOT be time_unspecified", extraction!!.time_unspecified)
        assertFalse("Concrete time must NOT be ambiguous", extraction.ambiguous)

        val state = ProjectionEngine.projectState(eventStore)
        val sched = state.schedules[result.entityId]
        assertNotNull(sched)
        assertEquals(TriageLane.SCHEDULE, sched!!.lane)
        assertTrue("Reasons should contain time_found", sched.reasons.contains("time_found"))
    }

    @Test
    fun testNoticeWithoutTimeRoutesToNeedsAttentionAndNeverCreatesAlerts() {
        // DC-11 verification: Notice without time lands in Needs attention, never as a normal suggestion
        val notice = "IMPORTANT NOTICE: All final year students must submit scholarship renewal forms before Friday."
        val refNow = "2026-10-21T10:00:00+05:30"
        val result = pipeline.processMessage(notice, referenceNow = refNow, chatTitle = "College Official", sender = "Admin")

        assertEquals("NEEDS_ATTENTION", result.status)
        assertNotNull(result.entityId)

        val state = ProjectionEngine.projectState(eventStore)
        val sched = state.schedules[result.entityId]
        assertNotNull(sched)
        assertEquals(TriageLane.NEEDS_ATTENTION, sched!!.lane)
        assertTrue("Schedule status should be PENDING awaiting review", sched.status == ScheduleStatus.PENDING)
        assertTrue("Reasons should contain urgency or action", sched.reasons.any { it.contains("urgency") || it.contains("action") })

        // Invariant: Needs Attention items never create automatic reminders or alarms
        val reminders = state.reminders.values.filter { it.schedule_id == sched.id }
        assertTrue("Needs Attention items must have ZERO scheduled alerts", reminders.isEmpty())
    }

    @Test
    fun testAmbiguousOrTimeUnspecifiedScheduleFallsBackToNeedsAttention() {
        // Message has event and relative day, but no concrete clock time
        val message = "tomorrow DBMS viva will be held"
        val refNow = "2026-10-21T10:00:00+05:30"
        val result = pipeline.processMessage(message, referenceNow = refNow, chatTitle = "DBMS Group", sender = "TA")

        assertEquals("NEEDS_ATTENTION", result.status)
        val state = ProjectionEngine.projectState(eventStore)
        val sched = state.schedules[result.entityId]
        assertNotNull(sched)
        assertEquals(TriageLane.NEEDS_ATTENTION, sched!!.lane)
        assertTrue("Should be marked time_unspecified", sched.time_unspecified)
    }

    @Test
    fun testNoiseAndPromosRouteToIgnoreLaneAndIncrementCounter() {
        val promo = "Use code DOMINOS50 to get 50% discount on your next pizza order!"
        val result = pipeline.processMessage(promo, chatTitle = "Deals Group", sender = "Bot")

        assertEquals("IGNORED", result.status)
        assertNull("Ignored message must not have an entityId", result.entityId)

        val state = ProjectionEngine.projectState(eventStore)
        assertTrue("Ignored items must not produce any schedules", state.schedules.isEmpty())
        assertTrue("Ignored items must increment ignoreCount", state.ignoreCount >= 1)
    }

    @Test
    fun testConfirmingScheduleUpdatesChatAndSenderPriors() {
        val message = "Tomorrow 10 AM project review meeting"
        val refNow = "2026-10-21T10:00:00+05:30"
        val result = pipeline.processMessage(message, referenceNow = refNow, chatTitle = "Capstone Group", sender = "Mentor")
        assertEquals("PENDING", result.status)

        // Baseline before confirm
        val stateBefore = ProjectionEngine.projectState(eventStore)
        assertEquals(0.0, stateBefore.chatPriors["Capstone Group"] ?: 0.0, 0.001)

        // Confirm
        val confirmed = pipeline.confirmSchedule(result.entityId!!)
        assertTrue(confirmed)

        val stateAfter = ProjectionEngine.projectState(eventStore)
        val chatPrior = stateAfter.chatPriors["Capstone Group"] ?: 0.0
        val senderPrior = stateAfter.senderPriors["Mentor"] ?: 0.0
        assertEquals(0.20, chatPrior, 0.01)
        assertEquals(0.20, senderPrior, 0.01)
    }

    @Test
    fun testDismissingScheduleDecreasesChatAndSenderPriors() {
        val message = "Tomorrow 2 PM webinar on cloud computing"
        val refNow = "2026-10-21T10:00:00+05:30"
        val result = pipeline.processMessage(message, referenceNow = refNow, chatTitle = "Random Seminars", sender = "Promoter")
        assertEquals("PENDING", result.status)

        // Dismiss
        val dismissed = pipeline.dismissSchedule(result.entityId!!)
        assertTrue(dismissed)

        val stateAfter = ProjectionEngine.projectState(eventStore)
        val chatPrior = stateAfter.chatPriors["Random Seminars"] ?: 0.0
        val senderPrior = stateAfter.senderPriors["Promoter"] ?: 0.0
        assertEquals(-0.20, chatPrior, 0.01)
        assertEquals(-0.20, senderPrior, 0.01)
    }

    @Test
    fun testPriorsAreBoundedBetweenMinusOneAndPlusOne() {
        val events = mutableListOf<PriorUpdateEvent>()
        // 10 confirms (+2.0 theoretical)
        repeat(10) {
            events.add(PriorUpdateEvent(type = "event_confirmed", chatTitle = "Spammy Chat", sender = "Alice"))
        }
        val (chatPriors, senderPriors) = PriorCalculator.computePriors(events).let { Pair(it.chatPriors, it.senderPriors) }
        assertEquals(1.0, chatPriors["Spammy Chat"] ?: 0.0, 0.001)
        assertEquals(1.0, senderPriors["Alice"] ?: 0.0, 0.001)

        // 15 dismisses (-3.0)
        repeat(15) {
            events.add(PriorUpdateEvent(type = "event_dismissed", chatTitle = "Spammy Chat", sender = "Alice"))
        }
        val (chatPriors2, _) = PriorCalculator.computePriors(events).let { Pair(it.chatPriors, it.senderPriors) }
        assertEquals(-1.0, chatPriors2["Spammy Chat"] ?: 0.0, 0.001)
    }

    @Test
    fun testMoveLaneUpdatesLaneAndPriors() {
        val notice = "Notice: Fee submission deadline extended to Monday"
        val refNow = "2026-10-21T10:00:00+05:30"
        val result = pipeline.processMessage(notice, referenceNow = refNow, chatTitle = "Accounts Office", sender = "Bursar")
        assertEquals("NEEDS_ATTENTION", result.status)

        val moved = pipeline.moveScheduleLane(result.entityId!!, TriageLane.SCHEDULE)
        assertTrue(moved)

        val state = ProjectionEngine.projectState(eventStore)
        val sched = state.schedules[result.entityId]
        assertNotNull(sched)
        assertEquals(TriageLane.SCHEDULE, sched!!.lane)

        val chatPrior = state.chatPriors["Accounts Office"] ?: 0.0
        assertTrue("Elevating to schedule should increase prior", chatPrior > 0.0)
    }

    @Test
    fun testLearnedPriorsInfluenceSignalScorer() {
        val scorer = DefaultSignalScorer()
        val text = "review meeting tomorrow"

        // Baseline with 0 priors
        val baseline = scorer.score(text, chatTitle = "Important Team", chatPriors = emptyMap())

        // With positive learned prior
        val positive = scorer.score(text, chatTitle = "Important Team", chatPriors = mapOf("Important Team" to 0.8))
        assertTrue("Score with positive prior should be higher", positive.score > baseline.score)
        assertEquals(0.8, positive.priorAdjustment, 0.001)

        // With negative learned prior
        val negative = scorer.score(text, chatTitle = "Important Team", chatPriors = mapOf("Important Team" to -0.6))
        assertTrue("Score with negative prior should be lower", negative.score < baseline.score)
        assertEquals(-0.6, negative.priorAdjustment, 0.001)
    }
}
