package com.scheduleassistant.app

import com.scheduleassistant.app.data.model.ScheduleEntity
import com.scheduleassistant.app.data.model.ScheduleStatus
import com.scheduleassistant.app.data.model.SyncEvent
import com.scheduleassistant.app.engine.HlcClock
import com.scheduleassistant.app.engine.IngestionPipeline
import com.scheduleassistant.app.engine.Prefilter
import com.scheduleassistant.app.engine.ScheduleParser
import com.scheduleassistant.app.engine.SpecLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/**
 * Deep Bug Analysis & Regression Suite.
 *
 * Verifies fixes for:
 * 1. Year Rollover: Late December reference now with January explicit date rolls to next year.
 * 2. Cross-Midnight Time Range: Overnight event (e.g. 10 pm to 2 am) end date is startDate + 1 day.
 * 3. Exact Noon and Midnight: "at noon" resolves to 12:00, "at midnight" resolves to 00:00.
 * 4. Numeric Date Delimiters: Supports "15-10-2026" and "15.10.2026" in addition to "15/10/2026".
 * 5. Devanagari Month Names: Supports "15 अक्टूबर" and "20 नवम्बर".
 * 6. IngestionPipeline Deletion Invariant: Deleted schedules must not match incoming cancels/reschedules.
 */
class DeepBugFixAnalysisTest {

    private lateinit var specLoader: SpecLoader
    private lateinit var prefilter: Prefilter
    private lateinit var parser: ScheduleParser

    private fun findSpecRoot(): File {
        val candidates = listOf(
            File("../../spec"),
            File("../spec"),
            File("spec"),
            File("../../../spec")
        )
        for (cand in candidates) {
            if (cand.exists() && cand.isDirectory && File(cand, "keywords.json").exists()) {
                return cand.canonicalFile
            }
        }
        error("Could not locate root /spec directory")
    }

    @Before
    fun setUp() {
        val specRoot = findSpecRoot()
        specLoader = SpecLoader(
            keywordsStreamProvider = { FileInputStream(File(specRoot, "keywords.json")) },
            synonymsStreamProvider = { FileInputStream(File(specRoot, "synonyms.json")) },
            daypartsStreamProvider = { FileInputStream(File(specRoot, "dayparts.json")) },
            fillersStreamProvider = { FileInputStream(File(specRoot, "fillers.json")) }
        )
        prefilter = Prefilter(specLoader)
        parser = ScheduleParser(specLoader, prefilter)
    }

    @Test
    fun testYearRolloverWhenReferenceIsInLateDecember() {
        // Reference is Dec 25, 2026. Message announces exam on Jan 15 at 10 am without specifying year.
        val refDec = "2026-12-25T10:00:00+05:30"
        val output = parser.parse("exam on 15 jan at 10 am", refDec)

        assertNotNull("Should parse explicit date in next month", output)
        assertEquals("Title should be exam", "exam", output!!.title)
        assertEquals("Date should roll over to 2027", "2027-01-15", output.start_date)
        assertEquals("Start ISO should be 2027", "2027-01-15T10:00:00+05:30", output.start)
    }

    @Test
    fun testCrossMidnightTimeRangeEndDateRollsOver() {
        // Event runs 10 pm to 2 am
        val ref = "2026-10-12T10:00:00+05:30"
        val output = parser.parse("kal hackathon kickoff 10 pm to 2 am", ref)

        assertNotNull(output)
        assertEquals("Start date is Oct 13", "2026-10-13", output!!.start_date)
        assertEquals("Start time is 22:00", "2026-10-13T22:00:00+05:30", output.start)
        assertNotNull("End time must exist", output.end)
        // CRITICAL: End time is 2 AM on the NEXT day (2026-10-14), not the same day!
        assertEquals("End ISO must roll over past midnight to Oct 14", "2026-10-14T02:00:00+05:30", output.end)
    }

    @Test
    fun testNoonAndMidnightParsing() {
        val ref = "2026-10-12T10:00:00+05:30"

        val noonOutput = parser.parse("tomorrow meeting at noon", ref)
        assertNotNull("Meeting at noon should be recognized", noonOutput)
        assertFalse("Noon is a concrete time, not unspecified", noonOutput!!.time_unspecified)
        assertEquals("Noon start time must be 12:00:00", "2026-10-13T12:00:00+05:30", noonOutput.start)

        val midnightOutput = parser.parse("tomorrow meeting at midnight", ref)
        assertNotNull("Meeting at midnight should be recognized", midnightOutput)
        assertFalse("Midnight is a concrete time, not unspecified", midnightOutput!!.time_unspecified)
        assertEquals("Midnight start time must be 00:00:00", "2026-10-13T00:00:00+05:30", midnightOutput.start)
    }

    @Test
    fun testNumericDateDelimitersDashAndDot() {
        val ref = "2026-10-01T10:00:00+05:30"

        val dashOutput = parser.parse("DBMS class on 15-10-2026 at 4 pm", ref)
        assertNotNull("Date with dashes should parse", dashOutput)
        assertEquals("2026-10-15", dashOutput!!.start_date)
        assertEquals("2026-10-15T16:00:00+05:30", dashOutput.start)

        val dotOutput = parser.parse("OS lecture on 20.10 at 11 am", ref)
        assertNotNull("Date with dots should parse", dotOutput)
        assertEquals("2026-10-20", dotOutput!!.start_date)
        assertEquals("2026-10-20T11:00:00+05:30", dotOutput.start)
    }

    @Test
    fun testDevanagariMonthNamesInExplicitDate() {
        val ref = "2026-10-01T10:00:00+05:30"
        val output = parser.parse("15 अक्टूबर को सुबह 10 बजे परीक्षा होगी", ref)

        assertNotNull("Devanagari date 15 अक्टूबर should parse", output)
        assertEquals("2026-10-15", output!!.start_date)
        assertEquals("2026-10-15T10:00:00+05:30", output.start)
    }

    @Test
    fun testIngestionPipelineDoesNotMatchDeletedSchedules() {
        val events = mutableListOf<SyncEvent>()
        val rawMessages = mutableMapOf<String, String>()
        val hlc = HlcClock("test-device")

        val pipeline = IngestionPipeline(
            deviceId = "test-device",
            specLoader = specLoader,
            prefilter = prefilter,
            parser = parser,
            hlc = hlc,
            eventStore = events,
            rawMessageStore = rawMessages
        )

        val ref = "2026-10-12T10:00:00+05:30"

        // 1. Ingest initial DBMS class
        val r1 = pipeline.processMessage("kal 4 baje DBMS class hai", ref)
        assertEquals("PENDING", r1.status)
        val entityId = r1.entityId!!

        // 2. Confirm it
        pipeline.confirmSchedule(entityId)

        // 3. User deletes the schedule
        val deleteEvent = SyncEvent(
            id = "del-event-1",
            timestamp = "2026-10-12T10:05:00Z",
            device_id = "test-device",
            seq = 10,
            hlc = "1005:0:test-device",
            type = "schedule_deleted",
            entity_id = entityId,
            payload = mapOf("reason" to "user")
        )
        events.add(deleteEvent)

        // 4. Later a cancellation arrives: "DBMS class cancelled"
        val r2 = pipeline.processMessage("DBMS class cancelled", ref)

        // It must NOT match the deleted schedule entityId!
        if (r2.candidates.isNotEmpty()) {
            assertFalse(
                "Deleted schedule $entityId must not appear in candidates",
                r2.candidates.any { it.id == entityId }
            )
        }
        val matchedTarget = r2.extraction?.target?.matched_entity_id
        assertTrue(
            "Target matched_entity_id must not match deleted schedule $entityId",
            matchedTarget != entityId
        )
    }
}
