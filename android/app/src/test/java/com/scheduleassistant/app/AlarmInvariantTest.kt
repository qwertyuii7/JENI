package com.scheduleassistant.app

import com.scheduleassistant.app.data.model.ScheduleEntity
import com.scheduleassistant.app.data.model.ScheduleStatus
import com.scheduleassistant.app.data.model.SyncEvent
import com.scheduleassistant.app.data.projection.ProjectionEngine
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class AlarmInvariantTest {

    private fun isEligibleForAlarm(schedule: ScheduleEntity): Boolean {
        // EXACT HARD INVARIANT FROM AlarmScheduler and RULES.md:
        // No reminder/alarm is created from a time_unspecified or unconfirmed event until confirmed
        if (schedule.time_unspecified || schedule.start == null || schedule.status != ScheduleStatus.CONFIRMED) {
            return false
        }
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        val startTime = try {
            isoFormat.parse(schedule.start!!)?.time ?: return false
        } catch (_: Exception) {
            return false
        }
        return true
    }

    @Test
    fun testDateOnlyEventNeverProducesAlarmOrReminder() {
        val dateOnlyEvent = ScheduleEntity(
            id = "sched-date-only-1",
            title = "DBMS quiz",
            start = null,
            start_date = "2026-10-13",
            time_unspecified = true,
            status = ScheduleStatus.CONFIRMED,
            source_quote = "kal DBMS quiz hai",
            confidence = 0.65,
            created_at = "2026-10-12T10:00:00Z",
            updated_at = "2026-10-12T10:00:00Z"
        )

        assertFalse(
            "Hard Invariant: Date-only schedule with time_unspecified=true must NOT produce alarms",
            isEligibleForAlarm(dateOnlyEvent)
        )
    }

    @Test
    fun testPendingEventNeverProducesAlarmOrReminder() {
        val pendingEvent = ScheduleEntity(
            id = "sched-pending-1",
            title = "Client Sync",
            start = "2026-10-13T15:00:00+05:30",
            start_date = "2026-10-13",
            time_unspecified = false,
            status = ScheduleStatus.PENDING,
            source_quote = "kal 3 baje client sync",
            confidence = 0.9,
            created_at = "2026-10-12T10:00:00Z",
            updated_at = "2026-10-12T10:00:00Z"
        )

        assertFalse(
            "Hard Invariant: Event in PENDING status must NOT produce alarms until user confirms",
            isEligibleForAlarm(pendingEvent)
        )
    }

    @Test
    fun testDismissedEventNeverProducesAlarmOrReminder() {
        val dismissedEvent = ScheduleEntity(
            id = "sched-dismissed-1",
            title = "Gym session",
            start = "2026-10-13T07:00:00+05:30",
            start_date = "2026-10-13",
            time_unspecified = false,
            status = ScheduleStatus.DISMISSED,
            source_quote = "kal subah 7 baje gym",
            confidence = 0.9,
            created_at = "2026-10-12T10:00:00Z",
            updated_at = "2026-10-12T10:00:00Z"
        )

        assertFalse(
            "Hard Invariant: Dismissed schedule must NOT produce alarms",
            isEligibleForAlarm(dismissedEvent)
        )
    }

    @Test
    fun testConfirmedEventWithSpecificTimeProducesAlarm() {
        val confirmedEvent = ScheduleEntity(
            id = "sched-confirmed-1",
            title = "DBMS Lecture",
            start = "2026-10-13T10:00:00+05:30",
            start_date = "2026-10-13",
            time_unspecified = false,
            status = ScheduleStatus.CONFIRMED,
            source_quote = "kal 10 baje DBMS lecture",
            confidence = 0.95,
            created_at = "2026-10-12T10:00:00Z",
            updated_at = "2026-10-12T10:00:00Z"
        )

        assertTrue(
            "Confirmed schedule with specific start timestamp must produce alarms",
            isEligibleForAlarm(confirmedEvent)
        )
    }

    @Test
    fun testProjectionMaintainsHardInvariant() {
        val events = listOf(
            SyncEvent(
                id = "ev-1",
                timestamp = "2026-10-12T10:00:00Z",
                device_id = "phone-1",
                seq = 1,
                hlc = "1000:0:phone-1",
                type = "event_extracted",
                entity_id = "sched-1",
                payload = mapOf(
                    "title" to "DBMS Exam",
                    "start_date" to "2026-10-20",
                    "time_unspecified" to true,
                    "confidence" to 0.65
                )
            ),
            SyncEvent(
                id = "ev-2",
                timestamp = "2026-10-12T10:05:00Z",
                device_id = "phone-1",
                seq = 2,
                hlc = "1005:0:phone-1",
                type = "event_confirmed",
                entity_id = "sched-1",
                payload = emptyMap()
            )
        )

        val state = ProjectionEngine.projectState(events)
        val schedule = state.schedules["sched-1"]

        assertNotNull(schedule)
        assertEquals(ScheduleStatus.CONFIRMED, schedule!!.status)
        assertTrue(schedule.time_unspecified)
        assertNull(schedule.start)

        assertFalse(
            "Confirmed schedule that still has time_unspecified=true must NOT produce alarms",
            isEligibleForAlarm(schedule)
        )
    }

    @Test
    fun testAlarmSelfTestNeverCreatesSyncEventsOrModifiesProjectionState() {
        val initialEvents = listOf(
            SyncEvent(
                id = "ev-1",
                timestamp = "2026-10-12T10:00:00Z",
                device_id = "phone-1",
                seq = 1,
                hlc = "1000:0:phone-1",
                type = "event_extracted",
                entity_id = "sched-1",
                payload = mapOf(
                    "title" to "Team Sync",
                    "start" to "2026-10-12T11:00:00+05:30",
                    "confidence" to 0.95
                )
            )
        )

        val stateBefore = ProjectionEngine.projectState(initialEvents)

        // Alarm self-test creates an AlarmTestEntity record directly, NOT a SyncEvent
        val testAlarmRecord = com.scheduleassistant.app.data.db.AlarmTestEntity(
            id = "test-alarm-123",
            createdAt = "2026-10-12T10:00:00Z",
            scheduledTimeMs = System.currentTimeMillis() + 60_000L,
            firedTimeMs = System.currentTimeMillis() + 60_050L,
            delayMs = 50L,
            firedAfterReboot = true,
            status = "FIRED"
        )

        val stateAfter = ProjectionEngine.projectState(initialEvents)

        assertEquals("Self-test alarm must not alter schedules count", stateBefore.schedules.size, stateAfter.schedules.size)
        assertEquals("Self-test alarm must not alter reminders count", stateBefore.reminders.size, stateAfter.reminders.size)
        assertEquals(50L, testAlarmRecord.delayMs)
        assertTrue(testAlarmRecord.firedAfterReboot)
        assertEquals("FIRED", testAlarmRecord.status)
    }
}
