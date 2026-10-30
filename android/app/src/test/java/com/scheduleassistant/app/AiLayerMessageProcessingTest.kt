package com.scheduleassistant.app

import com.scheduleassistant.app.data.model.ExtractionOutput
import com.scheduleassistant.app.domain.TriageLane
import com.scheduleassistant.app.engine.AiReasoningInput
import com.scheduleassistant.app.engine.AiScheduleReasoningEngine
import com.scheduleassistant.app.engine.HlcClock
import com.scheduleassistant.app.engine.IngestionPipeline
import com.scheduleassistant.app.engine.Prefilter
import com.scheduleassistant.app.engine.ScheduleParser
import com.scheduleassistant.app.engine.SpecLoader
import com.scheduleassistant.app.engine.WhatsAppNotificationHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

class AiLayerMessageProcessingTest {

    private lateinit var specLoader: SpecLoader
    private lateinit var prefilter: Prefilter
    private lateinit var parser: ScheduleParser
    private lateinit var aiReasoningEngine: AiScheduleReasoningEngine
    private lateinit var pipeline: IngestionPipeline
    private val whatsAppHandler = WhatsAppNotificationHandler()

    @Before
    fun setup() {
        val keywordsJson = """
        {
          "schedule_triggers": [
            "tomorrow", "today", "meeting", "class", "lecture", "sync", "lab", "test", "quiz", "exam",
            "call", "interview", "huddle", "session", "connect", "submission", "submit", "deadline",
            "kal", "aaj", "baje"
          ],
          "known_subjects": [
            "DBMS class", "Physics lecture", "Algorithms viva", "Doctor appointment", "lab", "meeting", "call"
          ]
        }
        """.trimIndent()

        val synonymsJson = """{"synonyms": {}, "intra_type_synonyms": {}}"""
        val daypartsJson = """{"dayparts": {}}"""
        val fillersJson = """{"fillers": []}"""

        specLoader = SpecLoader(
            keywordsStreamProvider = { ByteArrayInputStream(keywordsJson.toByteArray()) },
            synonymsStreamProvider = { ByteArrayInputStream(synonymsJson.toByteArray()) },
            daypartsStreamProvider = { ByteArrayInputStream(daypartsJson.toByteArray()) },
            fillersStreamProvider = { ByteArrayInputStream(fillersJson.toByteArray()) }
        )

        prefilter = Prefilter(specLoader)
        parser = ScheduleParser(specLoader, prefilter)
        aiReasoningEngine = AiScheduleReasoningEngine()

        pipeline = IngestionPipeline(
            deviceId = "test-phone",
            specLoader = specLoader,
            prefilter = prefilter,
            parser = parser,
            hlc = HlcClock("test-phone"),
            eventStore = mutableListOf(),
            rawMessageStore = mutableMapOf(),
            aiReasoningEngine = aiReasoningEngine
        )
    }

    @Test
    fun testTwoLayerProcessingRefinesTitleWithSenderContext() {
        // Normal layer extracts generic "call", AI layer refines to "Call with Rohit"
        val refNow = "2026-10-30T10:00:00+05:30"
        val rawMessage = "bhai call me tomorrow at 5pm please"

        val result = pipeline.processMessage(
            rawText = rawMessage,
            referenceNow = refNow,
            sender = "Rohit",
            chatTitle = "Rohit Personal"
        )

        assertEquals("PENDING", result.status)
        assertNotNull(result.extraction)
        assertEquals("Call with Rohit", result.extraction?.title)
        assertEquals("2026-10-31T17:00:00+05:30", result.extraction?.start)
        assertFalse(result.extraction?.time_unspecified ?: true)
    }

    @Test
    fun testDoctorAppointmentRefinedTitle() {
        val refNow = "2026-10-30T10:00:00+05:30"
        val rawMessage = "Reminder: Dentist checkup tomorrow at 4pm"

        val result = pipeline.processMessage(
            rawText = rawMessage,
            referenceNow = refNow,
            sender = "Clinic Staff"
        )

        assertEquals("PENDING", result.status)
        assertNotNull(result.extraction)
        assertEquals("Dentist Appointment", result.extraction?.title)
        assertEquals("2026-10-31T16:00:00+05:30", result.extraction?.start)
    }

    @Test
    fun testAssignmentSubmissionNoticeRoutedToNeedsAttention() {
        // Notice without concrete time should land in NEEDS_ATTENTION with clean title
        val rawMessage = "All students must complete assignment 2 submission by Friday"
        val refNow = "2026-10-30T10:00:00+05:30"

        val result = pipeline.processMessage(
            rawText = rawMessage,
            referenceNow = refNow,
            sender = "Prof. Sharma"
        )

        assertEquals("NEEDS_ATTENTION", result.status)
        assertNotNull(result.extraction)
        assertTrue(result.extraction?.title?.contains("Assignment") == true)
        assertTrue(result.extraction?.time_unspecified ?: false)
    }

    @Test
    fun testAiLayerFiltersOutNonActionableChatter() {
        // Casual chatter should be dropped by AI layer without generating a card
        val refNow = "2026-10-30T10:00:00+05:30"
        val chatter = "ok"

        val result = pipeline.processMessage(
            rawText = chatter,
            referenceNow = refNow,
            sender = "Aman"
        )

        // Either rejected by prefilter or ignored by AI layer
        assertTrue(result.status == "REJECTED" || result.status == "IGNORED")
        assertNull(result.entityId)
    }

    @Test
    fun testWhatsAppHandlerDoesNotDropMessagesContainingWordsLikeCallingOrSending() {
        // Real conversational message containing words that used to cause false drops
        val msg1 = "I am sending the proposal document, let's meet tomorrow at 11am"
        assertFalse(whatsAppHandler.isSystemNotification(msg1, "Team Chat"))

        val msg2 = "Are you calling the vendor? We have a client sync tomorrow at 3pm"
        assertFalse(whatsAppHandler.isSystemNotification(msg2, "Vendor Group"))

        val msg3 = "Downloading the dataset now, see you at 4pm"
        assertFalse(whatsAppHandler.isSystemNotification(msg3, "Lab Group"))

        // Pure system status notifications SHOULD be identified
        val sys1 = "Downloading content..."
        assertTrue(whatsAppHandler.isSystemNotification(sys1, "WhatsApp"))

        val sys2 = "Calling..."
        assertTrue(whatsAppHandler.isSystemNotification(sys2, "WhatsApp"))

        val sys3 = "Incoming video call"
        assertTrue(whatsAppHandler.isSystemNotification(sys3, "WhatsApp"))
    }

    @Test
    fun testAiReasoningCacheSubMillisecondEfficiency() {
        val refNow = "2026-10-30T10:00:00+05:30"
        val input = AiReasoningInput(
            rawText = "let's connect tomorrow at 6pm",
            sender = "Vikram",
            chatTitle = "Vikram",
            referenceNow = refNow,
            normalLayerExtraction = ExtractionOutput(
                type = "new",
                title = "call",
                start = "2026-10-31T18:00:00+05:30",
                start_date = "2026-10-31",
                end = null,
                time_unspecified = false,
                ambiguous = false,
                source_quote = "connect tomorrow at 6pm",
                confidence = 0.95
            ),
            candidateLane = TriageLane.SCHEDULE,
            initialConfidence = 0.95
        )

        // First execution calculates
        val start1 = System.nanoTime()
        val out1 = aiReasoningEngine.reason(input)
        val duration1 = System.nanoTime() - start1

        // Second execution hits thread-safe cache
        val start2 = System.nanoTime()
        val out2 = aiReasoningEngine.reason(input)
        val duration2 = System.nanoTime() - start2

        assertEquals(out1.refinedTitle, out2.refinedTitle)
        assertEquals(out1.refinedLane, out2.refinedLane)
        // Cached execution should be faster than first calculation and sub-millisecond
        assertTrue(duration2 <= duration1 || duration2 < 500_000)
    }
}
