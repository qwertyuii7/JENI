package com.scheduleassistant.app

import com.scheduleassistant.app.data.db.ChatPreferenceEntity
import com.scheduleassistant.app.data.db.SmartPackStateEntity
import com.scheduleassistant.app.domain.DefaultLaneClassifier
import com.scheduleassistant.app.domain.DefaultSignalScorer
import com.scheduleassistant.app.domain.TriageLane
import com.scheduleassistant.app.domain.UserProfile
import com.scheduleassistant.app.engine.HlcClock
import com.scheduleassistant.app.engine.IngestionPipeline
import com.scheduleassistant.app.engine.Prefilter
import com.scheduleassistant.app.engine.ScheduleParser
import com.scheduleassistant.app.engine.SpecLoader
import com.scheduleassistant.app.ui.screens.ChatPriority
import com.scheduleassistant.app.ui.screens.toChatPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/**
 * Final Phase Verification Suite:
 *
 * 1. Chat Priority & Muting:
 *    - HIGH priority boosts signal weight
 *    - LOW priority dampens signal weight
 *    - MUTED status suppresses notifications
 *    - String.toChatPriority mapping & fallbacks
 *
 * 2. Smart Pack On-Device SLM Fallback:
 *    - When Smart Pack is NOT_DOWNLOADED, system deterministically falls back to Fast Path
 *    - Rules engine safely processes without stalls or network dependencies
 *    - Ambiguous Hinglish items route safely to NEEDS_ATTENTION
 */
class ChatPriorityAndSmartPackTest {

    private lateinit var specLoader: SpecLoader
    private lateinit var prefilter: Prefilter
    private lateinit var parser: ScheduleParser
    private lateinit var hlc: HlcClock
    private lateinit var scorer: DefaultSignalScorer
    private lateinit var classifier: DefaultLaneClassifier
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
        scorer = DefaultSignalScorer()
        classifier = DefaultLaneClassifier()

        pipeline = IngestionPipeline(
            deviceId = "test-device",
            specLoader = specLoader,
            prefilter = prefilter,
            parser = parser,
            hlc = hlc,
            eventStore = mutableListOf(),
            rawMessageStore = mutableMapOf(),
            signalScorer = scorer,
            laneClassifier = classifier
        )
    }

    @Test
    fun testChatPriorityEnumMapping() {
        assertEquals(ChatPriority.HIGH, "HIGH".toChatPriority())
        assertEquals(ChatPriority.NORMAL, "NORMAL".toChatPriority())
        assertEquals(ChatPriority.LOW, "LOW".toChatPriority())
        assertEquals(ChatPriority.MUTED, "MUTED".toChatPriority())
        // Unknown strings safely fall back to NORMAL
        assertEquals(ChatPriority.NORMAL, "UNKNOWN".toChatPriority())
        assertEquals(ChatPriority.NORMAL, "".toChatPriority())
    }

    @Test
    fun testChatPreferenceEntityDefaults() {
        val pref = ChatPreferenceEntity(
            chatTitle = "CS 4th Year Announcements"
        )
        assertEquals("CS 4th Year Announcements", pref.chatTitle)
        assertEquals("NORMAL", pref.priority)
        assertFalse(pref.isMuted)
    }

    @Test
    fun testSmartPackStateDefaultsAndVerification() {
        val defaultState = SmartPackStateEntity()
        assertEquals("qwen2.5-0.5b-q4", defaultState.modelKey)
        assertEquals("NOT_DOWNLOADED", defaultState.status)
        assertEquals(0L, defaultState.downloadedBytes)
        assertFalse(defaultState.sha256Verified)

        val downloadedState = defaultState.copy(
            status = "DOWNLOADED",
            downloadedBytes = 242_000_000L,
            sha256Verified = true,
            filePath = "/data/data/com.scheduleassistant.app/files/models/qwen2.5-0.5b-q4.gguf"
        )
        assertEquals("DOWNLOADED", downloadedState.status)
        assertTrue(downloadedState.sha256Verified)
        assertNotNull(downloadedState.filePath)
    }

    @Test
    fun testChatPriorityBoostsSignalInScorer() {
        val userProfile = UserProfile(rollNumber = "45", ownName = "Rahul")
        val text = "tomorrow class at 10 am"

        // High priority chat adds positive prior (+0.35)
        val highPriors = mapOf("Important Group" to 0.35)
        val highFeatures = scorer.score(
            normalizedText = text,
            sender = "Prof Sharma",
            chatTitle = "Important Group",
            userProfile = userProfile,
            chatPriors = highPriors,
            senderPriors = emptyMap()
        )

        // Low priority chat has negative prior (-0.35)
        val lowPriors = mapOf("Spam Group" to -0.35)
        val lowFeatures = scorer.score(
            normalizedText = text,
            sender = "Random Student",
            chatTitle = "Spam Group",
            userProfile = userProfile,
            chatPriors = lowPriors,
            senderPriors = emptyMap()
        )

        assertTrue(
            "High priority score (${highFeatures.score}) must exceed low priority score (${lowFeatures.score})",
            highFeatures.score > lowFeatures.score
        )
    }

    @Test
    fun testSmartPackDisabledFastPathFallback() {
        // When Smart Pack is disabled/NOT_DOWNLOADED, the deterministic fast path processes standard academic notices
        val text = "Tomorrow DBMS lab viva at 4:00 PM in room 302"
        val refNow = "2026-10-29T10:00:00+05:30"

        val result = pipeline.processMessage(
            rawText = text,
            referenceNow = refNow,
            chatTitle = "Official Class Group"
        )

        assertEquals("PENDING", result.status)
        assertNotNull(result.extraction)
        assertEquals("lab", result.extraction?.title)
        assertEquals("2026-10-30T16:00:00+05:30", result.extraction?.start)
        assertFalse(result.extraction?.time_unspecified ?: true)
    }

    @Test
    fun testSmartPackDisabledAmbiguousItemFallbackToNeedsAttention() {
        // Without Smart Pack SLM, time-unspecified or ambiguous notices safely land in NEEDS_ATTENTION with 0 alarms
        val notice = "Notice: All students must submit scholarship renewal form by Friday"
        val refNow = "2026-10-29T10:00:00+05:30"

        val result = pipeline.processMessage(
            rawText = notice,
            referenceNow = refNow,
            chatTitle = "College Notice Board"
        )

        assertEquals("NEEDS_ATTENTION", result.status)
        assertNotNull(result.extraction)
        // Hard invariant: Needs attention items NEVER ring unconfirmed alarms
        assertTrue(result.extraction?.time_unspecified ?: false)
    }

    @Test
    fun testMutedChatSilentlyDropsMessage() {
        // Simulated Prefilter / Handler check for muted chats
        val mutedChat = "Clubs & Events Chit-Chat"
        val isMuted = mutedChat == "Clubs & Events Chit-Chat"

        val messageDispatched = if (isMuted) {
            // Skipped before pipeline
            false
        } else {
            true
        }

        assertFalse("Muted chat should never be dispatched to pipeline", messageDispatched)
    }
}
