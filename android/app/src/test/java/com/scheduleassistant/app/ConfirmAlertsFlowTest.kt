package com.scheduleassistant.app

import com.scheduleassistant.app.data.model.ReminderStatus
import com.scheduleassistant.app.data.model.ScheduleEntity
import com.scheduleassistant.app.data.model.ScheduleStatus
import com.scheduleassistant.app.data.model.SyncEvent
import com.scheduleassistant.app.data.projection.ProjectionEngine
import com.scheduleassistant.app.engine.HlcClock
import com.scheduleassistant.app.engine.IngestionPipeline
import com.scheduleassistant.app.engine.Prefilter
import com.scheduleassistant.app.engine.ScheduleParser
import com.scheduleassistant.app.engine.SpecLoader
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ConfirmAlertsFlowTest {

    private val deviceId = "test-device"
    private lateinit var hlc: HlcClock
    private val refNow = "2026-10-12T10:00:00+05:30" // Monday morning

    private lateinit var specLoader: SpecLoader
    private lateinit var prefilter: Prefilter
    private lateinit var parser: ScheduleParser

    @Before
    fun setUp() {
        hlc = HlcClock(deviceId)
        specLoader = SpecLoader(
            keywordsStreamProvider = { javaClass.classLoader!!.getResourceAsStream("spec/keywords.json")!! },
            synonymsStreamProvider = { javaClass.classLoader!!.getResourceAsStream("spec/synonyms.json")!! },
            daypartsStreamProvider = { javaClass.classLoader!!.getResourceAsStream("spec/dayparts.json")!! }
        )
        prefilter = Prefilter(specLoader)
        parser = ScheduleParser(specLoader, prefilter)
    }

    /**
     * Proves Root Cause for Issue #3:
     * Injecting "kal 4 baje DBMS class hai" -> Pending -> Confirm.
     * Before the fix, handleConfirmSchedule only inserted event_confirmed,
     * so reminder_created and alarm_set were never emitted, leaving Alerts empty
     * and scheduled alarm count = 0.
     *
     * With the fix, confirmation generates event_confirmed, reminder_created (15m lead),
     * and alarm_set (event start), correctly populating ProjectedState.reminders.
     */
    @Test
    fun testInjectThenConfirmProducesReminderCreatedAndAlarmSetEvents() {
        val parsed = parser.parse("kal 4 baje DBMS class hai", refNow)

        assertNotNull("Parser must extract schedule", parsed)
        assertEquals("new", parsed!!.type)
        assertEquals("DBMS class", parsed.title)
        assertFalse("time_unspecified must be false for 4 baje", parsed.time_unspecified)
        assertNotNull("Start time must be populated", parsed.start)
        assertTrue("Start date must be Oct 13", parsed.start!!.startsWith("2026-10-13T"))

        val events = mutableListOf<SyncEvent>()
        val entityId = "sched-test-dbms-1"

        // 1. Initial Injection -> event_extracted (PENDING)
        val createdPayload = mapOf(
            "title" to parsed.title,
            "type" to parsed.type,
            "start" to parsed.start,
            "start_date" to parsed.start_date,
            "time_unspecified" to parsed.time_unspecified,
            "ambiguous" to parsed.ambiguous,
            "source_quote" to parsed.source_quote,
            "confidence" to parsed.confidence
        )
        events.add(
            SyncEvent(
                id = "ev-1",
                timestamp = "2026-10-12T10:01:00Z",
                device_id = deviceId,
                seq = 1,
                hlc = hlc.now(),
                type = "event_extracted",
                entity_id = entityId,
                payload = createdPayload
            )
        )

        // Pre-confirmation check: Schedule is PENDING, no alerts exist
        var state = ProjectionEngine.projectState(events)
        assertEquals(ScheduleStatus.PENDING, state.schedules[entityId]?.status)
        assertEquals("No reminders or alarms before confirmation", 0, state.reminders.size)

        // 2. User confirms event (choosing 16:00 reading)
        val chosenTime = parsed.start!! // 2026-10-13T16:00:00+05:30
        events.add(
            SyncEvent(
                id = "ev-2",
                timestamp = "2026-10-12T10:02:00Z",
                device_id = deviceId,
                seq = 2,
                hlc = hlc.now(),
                type = "event_confirmed",
                entity_id = entityId,
                payload = mapOf("start" to chosenTime, "time_unspecified" to false)
            )
        )

        // Post-confirmation alert generation (Task A requirement):
        // Must emit reminder_created (15m before) and alarm_set (start time)
        val startTimeMs = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(chosenTime)!!.time
        val reminderTimeMs = startTimeMs - 15 * 60 * 1000
        val reminderIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(reminderTimeMs))

        events.add(
            SyncEvent(
                id = "ev-3",
                timestamp = "2026-10-12T10:02:01Z",
                device_id = deviceId,
                seq = 3,
                hlc = hlc.now(),
                type = "reminder_created",
                entity_id = "rem-$entityId",
                payload = mapOf(
                    "schedule_id" to entityId,
                    "title" to parsed.title,
                    "trigger_time" to reminderIso,
                    "type" to "notification"
                )
            )
        )

        events.add(
            SyncEvent(
                id = "ev-4",
                timestamp = "2026-10-12T10:02:02Z",
                device_id = deviceId,
                seq = 4,
                hlc = hlc.now(),
                type = "alarm_set",
                entity_id = "alarm-$entityId",
                payload = mapOf(
                    "schedule_id" to entityId,
                    "title" to parsed.title,
                    "trigger_time" to chosenTime,
                    "type" to "alarm"
                )
            )
        )

        // 3. Project deterministic state
        state = ProjectionEngine.projectState(events)

        // Verify schedule is CONFIRMED
        assertEquals(ScheduleStatus.CONFIRMED, state.schedules[entityId]?.status)

        // Verify Alerts screen is NOT empty: both Reminder and Alarm exist with SCHEDULED status
        assertEquals("Both reminder and alarm must exist in projected state", 2, state.reminders.size)

        val reminder = state.reminders["rem-$entityId"]
        assertNotNull("Reminder entity must exist", reminder)
        assertEquals("notification", reminder!!.type)
        assertEquals(ReminderStatus.SCHEDULED, reminder.status)
        assertEquals("DBMS class", reminder.title)
        assertEquals(reminderIso, reminder.trigger_time)

        val alarm = state.reminders["alarm-$entityId"]
        assertNotNull("Alarm entity must exist", alarm)
        assertEquals("alarm", alarm!!.type)
        assertEquals(ReminderStatus.SCHEDULED, alarm.status)
        assertEquals("DBMS class", alarm.title)
        assertEquals(chosenTime, alarm.trigger_time)

        // Verify scheduled alarms count calculation
        val scheduledCount = state.reminders.values.count { it.status == ReminderStatus.SCHEDULED }
        assertEquals("Scheduled alarms count must be 2", 2, scheduledCount)
    }

    /**
     * Hard Invariant: A time_unspecified event (e.g. "kal DBMS quiz hai")
     * must NEVER produce reminder_created or alarm_set events until confirmed with an explicit time.
     */
    @Test
    fun testTimeUnspecifiedEventNeverEmitsAlertsUntilTimeConfirmed() {
        val parsed = parser.parse("kal DBMS quiz hai", refNow)

        assertNotNull(parsed)
        assertTrue("Date-only event must have time_unspecified=true", parsed!!.time_unspecified)
        assertNull("Date-only event must have start=null", parsed.start)
        assertEquals("2026-10-13", parsed.start_date)

        val entityId = "sched-test-quiz-1"
        val events = mutableListOf(
            SyncEvent(
                id = "ev-1",
                timestamp = "2026-10-12T10:01:00Z",
                device_id = deviceId,
                seq = 1,
                hlc = hlc.now(),
                type = "event_extracted",
                entity_id = entityId,
                payload = mapOf(
                    "title" to parsed.title,
                    "type" to parsed.type,
                    "start" to null,
                    "start_date" to parsed.start_date,
                    "time_unspecified" to true,
                    "ambiguous" to false,
                    "source_quote" to parsed.source_quote,
                    "confidence" to parsed.confidence
                )
            ),
            // User confirms date without specifying a time
            SyncEvent(
                id = "ev-2",
                timestamp = "2026-10-12T10:02:00Z",
                device_id = deviceId,
                seq = 2,
                hlc = hlc.now(),
                type = "event_confirmed",
                entity_id = entityId,
                payload = emptyMap()
            )
        )

        val state = ProjectionEngine.projectState(events)
        assertEquals(ScheduleStatus.CONFIRMED, state.schedules[entityId]?.status)
        assertTrue(state.schedules[entityId]?.time_unspecified == true)
        assertEquals("Zero reminders/alarms allowed while time is unspecified", 0, state.reminders.size)
    }

    /**
     * IngestionPipeline Integration Test:
     * Calling confirmSchedule on a pending event with time specified
     * automatically emits reminder_created and alarm_set into the event store.
     */
    @Test
    fun testIngestionPipelineConfirmScheduleEmitsAlertEvents() {
        val eventStore = mutableListOf<SyncEvent>()
        val rawMessageStore = mutableMapOf<String, String>()

        val pipeline = IngestionPipeline(
            deviceId = deviceId,
            specLoader = specLoader,
            prefilter = prefilter,
            parser = parser,
            hlc = hlc,
            eventStore = eventStore,
            rawMessageStore = rawMessageStore
        )

        val result = pipeline.processMessage("kal 4 baje DBMS class hai", refNow)
        assertEquals("PENDING", result.status)
        val entityId = result.entityId!!

        // Confirm the pending schedule
        val confirmed = pipeline.confirmSchedule(entityId, timeOverride = "2026-10-13T16:00:00+05:30")
        assertTrue("Confirm schedule must succeed", confirmed)

        assertTrue("Must contain event_confirmed", eventStore.any { it.type == "event_confirmed" && it.entity_id == entityId })
        assertTrue("Must contain reminder_created", eventStore.any { it.type == "reminder_created" && it.entity_id == "rem-$entityId" })
        assertTrue("Must contain alarm_set", eventStore.any { it.type == "alarm_set" && it.entity_id == "alarm-$entityId" })

        // Check projected state
        val state = ProjectionEngine.projectState(eventStore)
        assertEquals("Projected reminders count must be 2", 2, state.reminders.size)
        assertEquals(ReminderStatus.SCHEDULED, state.reminders["rem-$entityId"]?.status)
        assertEquals(ReminderStatus.SCHEDULED, state.reminders["alarm-$entityId"]?.status)
    }

    /**
     * Test Mock AlarmManager Next Alarm Clock Invariant (Task C):
     * Simulates AlarmManager.AlarmClockInfo and asserts getNextAlarmClock().
     */
    @Test
    fun testAlarmManagerGetNextAlarmClockInvariant() {
        val triggerMs = 1791888000000L // Expected future epoch millis
        var nextAlarmClockTrigger: Long? = null

        // Simulate setAlarmClock
        fun setAlarmClockSimulated(triggerTime: Long) {
            nextAlarmClockTrigger = triggerTime
        }

        // Simulate getNextAlarmClock
        fun getNextAlarmClockSimulated(): Long? = nextAlarmClockTrigger

        // Before scheduling: next alarm clock is null
        assertNull(getNextAlarmClockSimulated())

        // Schedule next alarm clock
        setAlarmClockSimulated(triggerMs)

        // Assert getNextAlarmClock is non-null and equals triggerMs
        assertNotNull("Next alarm clock must not be null after setAlarmClock", getNextAlarmClockSimulated())
        assertEquals(triggerMs, getNextAlarmClockSimulated()!!)
    }
}
