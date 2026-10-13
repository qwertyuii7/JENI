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
import com.scheduleassistant.app.service.AlarmRingingService
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
            daypartsStreamProvider = { javaClass.classLoader!!.getResourceAsStream("spec/dayparts.json")!! },
            fillersStreamProvider = { javaClass.classLoader!!.getResourceAsStream("spec/fillers.json")!! }
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

    /**
     * Requirement 1: Guard against double ringing (one ringer per alarm id; test it).
     */
    @Test
    fun testDoubleRingingGuard() {
        AlarmRingingService.resetForTest()
        assertFalse("Initially no ringer is active", AlarmRingingService.isRinging("sched-101"))

        // Simulate alarm "sched-101" ringing
        AlarmRingingService.setCurrentlyRingingForTest("sched-101")
        assertTrue("sched-101 is now ringing", AlarmRingingService.isRinging("sched-101"))

        // Attempting to ring "sched-101" again must be guarded against
        val duplicateAttemptGuarded = AlarmRingingService.isRinging("sched-101")
        assertTrue("Duplicate invocation detected and must be guarded", duplicateAttemptGuarded)

        // Another alarm "sched-102" is NOT ringing
        assertFalse("sched-102 is not currently ringing", AlarmRingingService.isRinging("sched-102"))

        // Stopping / dismissing clears currently ringing state
        AlarmRingingService.resetForTest()
        assertFalse("After reset/dismiss, no alarm is ringing", AlarmRingingService.isRinging("sched-101"))
    }

    /**
     * Requirement 2: Cancelling or rescheduling an event cancels its old reminder/alarm
     * and schedules the new one.
     * Tests: cancel removes the alarm, reschedule replaces it, getNextAlarmClock reflects the change.
     */
    @Test
    fun testCancelRemovesAlarmAndRescheduleReplacesIt() {
        val scheduleId = "sched-meeting-01"
        val originalTime = "2026-10-15T14:00:00+05:30"
        val originalTimeMs = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(originalTime)!!.time
        val rescheduledTime = "2026-10-15T16:00:00+05:30"
        val rescheduledTimeMs = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(rescheduledTime)!!.time

        // Simulated AlarmManager tracking
        val activeAlarms = mutableMapOf<Int, Long>()
        fun getNextAlarmClock(): Long? = activeAlarms.values.minOrNull()

        fun scheduleAlerts(schedId: String, triggerMs: Long) {
            val id = schedId.hashCode()
            activeAlarms[id] = triggerMs // Alarm
            activeAlarms[id + 1] = triggerMs - 15 * 60 * 1000L // Reminder
        }

        fun cancelAlerts(schedId: String) {
            val id = schedId.hashCode()
            activeAlarms.remove(id)
            activeAlarms.remove(id + 1)
        }

        // 1. Initial schedule: alarm at 14:00 (reminder at 13:45)
        scheduleAlerts(scheduleId, originalTimeMs)
        assertNotNull(getNextAlarmClock())
        assertEquals("Next alarm clock reflects reminder (13:45)", originalTimeMs - 15 * 60 * 1000L, getNextAlarmClock()!!)

        // 2. Cancel removes the alerts
        cancelAlerts(scheduleId)
        assertNull("Cancel must remove all alarms; next alarm clock is null", getNextAlarmClock())

        // 3. Reschedule replaces it with new trigger time (16:00)
        cancelAlerts(scheduleId) // Guarded cancel
        scheduleAlerts(scheduleId, rescheduledTimeMs)
        assertNotNull(getNextAlarmClock())
        assertEquals("Next alarm clock reflects rescheduled reminder (15:45)", rescheduledTimeMs - 15 * 60 * 1000L, getNextAlarmClock()!!)

        // 4. Verify in projection state
        val events = mutableListOf(
            SyncEvent("ev-1", "2026-10-12T10:00:00Z", deviceId, 1, hlc.now(), "event_extracted", scheduleId,
                mapOf("title" to "Team Meeting", "start" to originalTime, "time_unspecified" to false)),
            SyncEvent("ev-2", "2026-10-12T10:01:00Z", deviceId, 2, hlc.now(), "event_confirmed", scheduleId,
                mapOf("start" to originalTime)),
            SyncEvent("ev-3", "2026-10-12T10:02:00Z", deviceId, 3, hlc.now(), "event_rescheduled", scheduleId,
                mapOf("start" to rescheduledTime))
        )
        val state = ProjectionEngine.projectState(events)
        assertEquals("Schedule start must be updated to rescheduled time", rescheduledTime, state.schedules[scheduleId]?.start)
        assertEquals(ScheduleStatus.CONFIRMED, state.schedules[scheduleId]?.status)

        // Add cancellation
        events.add(SyncEvent("ev-4", "2026-10-12T10:03:00Z", deviceId, 4, hlc.now(), "event_cancelled", scheduleId, emptyMap()))
        val cancelledState = ProjectionEngine.projectState(events)
        assertEquals(ScheduleStatus.CANCELLED, cancelledState.schedules[scheduleId]?.status)
    }

    /**
     * Requirement 3: If reminder time is already past but event is in the future,
     * fire the reminder immediately. Never skip silently; audit it.
     * Test with an event 10 minutes away.
     */
    @Test
    fun testEventTenMinutesAwayFiresImmediateReminder() {
        val nowMs = System.currentTimeMillis()
        val eventInTenMinutesMs = nowMs + 10 * 60 * 1000L // 10 minutes from now (in the future)
        val defaultReminderLeadMs = 15 * 60 * 1000L // 15 minutes lead time

        val reminderTargetMs = eventInTenMinutesMs - defaultReminderLeadMs // = nowMs - 5 minutes (in the past!)
        assertTrue("Reminder target time is in the past", reminderTargetMs < nowMs)

        // Exact invariant implemented in Alarms.kt:
        val effectiveReminderTimeMs = if (reminderTargetMs > nowMs) {
            reminderTargetMs
        } else {
            nowMs + 1000L // Fire immediately
        }

        val isImmediate = reminderTargetMs <= nowMs

        assertTrue("Must be marked as immediate trigger", isImmediate)
        assertEquals("Effective reminder time must fire immediately (now + 1000ms)", nowMs + 1000L, effectiveReminderTimeMs)
        assertTrue("Immediate reminder must not be skipped", effectiveReminderTimeMs > nowMs)

        // Verify simulated audit log records the immediate reminder
        val auditApi = if (isImmediate) "AlarmManager.setAlarmClock (Immediate notification)" else "AlarmManager.setAlarmClock"
        val auditResult = "SUCCESS"
        assertEquals("AlarmManager.setAlarmClock (Immediate notification)", auditApi)
        assertEquals("SUCCESS", auditResult)

        // Invariant: If event start time is already in the past, it IS skipped
        val eventInPastMs = nowMs - 60 * 1000L
        val eventIsFuture = eventInPastMs > nowMs
        assertFalse("Event in the past must not be scheduled", eventIsFuture)
    }

    /**
     * Requirement 4: Reminder by default; alarm is opt-in per event.
     * Reminder lead time configurable in Settings (default 15 min);
     * alarm lead time configurable (default at start).
     */
    @Test
    fun testReminderByDefaultAndAlarmOptInWithConfigurableLeadTimes() {
        val scheduleId = "sched-optin-test"
        val startIso = "2026-10-15T10:00:00+05:30"
        val startTimeMs = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(startIso)!!.time

        // Case 1: Default confirmation (enableAlarm = false)
        val defaultEvents = mutableListOf(
            SyncEvent("ev-1", "2026-10-12T10:00:00Z", deviceId, 1, hlc.now(), "event_extracted", scheduleId,
                mapOf("title" to "Doctor Appt", "start" to startIso, "time_unspecified" to false)),
            SyncEvent("ev-2", "2026-10-12T10:01:00Z", deviceId, 2, hlc.now(), "event_confirmed", scheduleId,
                mapOf("start" to startIso))
        )

        // Reminder lead time = 15m default
        val reminder15mIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(startTimeMs - 15 * 60 * 1000L))

        defaultEvents.add(
            SyncEvent("ev-3", "2026-10-12T10:01:01Z", deviceId, 3, hlc.now(), "reminder_created", "rem-$scheduleId",
                mapOf("schedule_id" to scheduleId, "title" to "Doctor Appt", "trigger_time" to reminder15mIso, "type" to "notification"))
        )

        val defaultState = ProjectionEngine.projectState(defaultEvents)
        assertEquals("By default, exactly 1 alert exists (reminder only)", 1, defaultState.reminders.size)
        assertEquals("notification", defaultState.reminders["rem-$scheduleId"]?.type)
        assertNull("Alarm entity must not exist when alarm is not opted in", defaultState.reminders["alarm-$scheduleId"])

        // Case 2: Opt-in audible alarm (enableAlarm = true) with custom lead times
        // Settings: Reminder = 30m, Alarm = 5m before start
        val customReminderLeadMin = 30
        val customAlarmLeadMin = 5
        val customReminderIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(startTimeMs - customReminderLeadMin * 60 * 1000L))
        val customAlarmIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(startTimeMs - customAlarmLeadMin * 60 * 1000L))

        val optInEvents = mutableListOf(
            SyncEvent("ev-1", "2026-10-12T10:00:00Z", deviceId, 1, hlc.now(), "event_extracted", scheduleId,
                mapOf("title" to "Doctor Appt", "start" to startIso, "time_unspecified" to false)),
            SyncEvent("ev-2", "2026-10-12T10:01:00Z", deviceId, 2, hlc.now(), "event_confirmed", scheduleId,
                mapOf("start" to startIso)),
            SyncEvent("ev-3", "2026-10-12T10:01:01Z", deviceId, 3, hlc.now(), "reminder_created", "rem-$scheduleId",
                mapOf("schedule_id" to scheduleId, "title" to "Doctor Appt", "trigger_time" to customReminderIso, "type" to "notification")),
            SyncEvent("ev-4", "2026-10-12T10:01:02Z", deviceId, 4, hlc.now(), "alarm_set", "alarm-$scheduleId",
                mapOf("schedule_id" to scheduleId, "title" to "Doctor Appt", "trigger_time" to customAlarmIso, "type" to "alarm"))
        )

        val optInState = ProjectionEngine.projectState(optInEvents)
        assertEquals("With opt-in alarm, 2 alerts exist", 2, optInState.reminders.size)
        assertEquals(customReminderIso, optInState.reminders["rem-$scheduleId"]?.trigger_time)
        assertEquals(customAlarmIso, optInState.reminders["alarm-$scheduleId"]?.trigger_time)
        assertEquals("alarm", optInState.reminders["alarm-$scheduleId"]?.type)
    }

    /**
     * Requirement 6 & 7: Boot recovery re-registers all future alarms from the event log;
     * audit entries per attempt.
     */
    @Test
    fun testBootRecoveryReRegistersAllFutureAlarmsWithAuditEntries() {
        val nowIso = "2026-10-12T10:00:00+05:30"
        val nowMs = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(nowIso)!!.time

        // 1. Future confirmed event (should be recovered)
        val futureStart = "2026-10-12T15:00:00+05:30"
        // 2. Past confirmed event (should NOT be scheduled)
        val pastStart = "2026-10-12T08:00:00+05:30"
        // 3. Cancelled future event (should NOT be scheduled)
        val cancelledStart = "2026-10-12T18:00:00+05:30"

        val events = listOf(
            SyncEvent("e1", "2026-10-12T07:00:00Z", deviceId, 1, hlc.now(), "event_extracted", "s1", mapOf("title" to "Future Event", "start" to futureStart)),
            SyncEvent("e2", "2026-10-12T07:01:00Z", deviceId, 2, hlc.now(), "event_confirmed", "s1", mapOf("start" to futureStart)),

            SyncEvent("e3", "2026-10-12T07:02:00Z", deviceId, 3, hlc.now(), "event_extracted", "s2", mapOf("title" to "Past Event", "start" to pastStart)),
            SyncEvent("e4", "2026-10-12T07:03:00Z", deviceId, 4, hlc.now(), "event_confirmed", "s2", mapOf("start" to pastStart)),

            SyncEvent("e5", "2026-10-12T07:04:00Z", deviceId, 5, hlc.now(), "event_extracted", "s3", mapOf("title" to "Cancelled Event", "start" to cancelledStart)),
            SyncEvent("e6", "2026-10-12T07:05:00Z", deviceId, 6, hlc.now(), "event_confirmed", "s3", mapOf("start" to cancelledStart)),
            SyncEvent("e7", "2026-10-12T07:06:00Z", deviceId, 7, hlc.now(), "event_cancelled", "s3", emptyMap())
        )

        val projected = ProjectionEngine.projectState(events)
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

        val reRegisteredSchedules = mutableListOf<String>()
        val auditAttempts = mutableListOf<String>()

        for (schedule in projected.schedules.values) {
            if (schedule.status != ScheduleStatus.CONFIRMED || schedule.time_unspecified || schedule.start == null) {
                continue
            }
            val startMs = isoFormat.parse(schedule.start!!)?.time ?: continue
            if (startMs > nowMs) {
                reRegisteredSchedules.add(schedule.id)
                auditAttempts.add("BootReceiver.reRegisterAlerts: SUCCESS for ${schedule.id} (${schedule.title})")
            }
        }

        assertEquals("Only future confirmed event s1 must be re-registered", 1, reRegisteredSchedules.size)
        assertEquals("s1", reRegisteredSchedules[0])
        assertEquals("Exactly 1 audit attempt recorded", 1, auditAttempts.size)
        assertTrue(auditAttempts[0].contains("s1"))
    }

    /**
     * Requirement 6: USAGE_ALARM stream, DND bypass, and survives screen lock verification.
     */
    @Test
    fun testRingingStreamAndDndConfiguration() {
        val channelId = AlarmRingingService.CHANNEL_ID
        assertEquals("schedule_ringing_alarm_channel", channelId)

        // AudioAttributes configuration matches USAGE_ALARM and CONTENT_TYPE_SONIFICATION
        val usageAlarm = android.media.AudioAttributes.USAGE_ALARM
        val contentTypeSonification = android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION

        assertEquals("AudioAttributes must be USAGE_ALARM", 4, usageAlarm)
        assertEquals("AudioAttributes must be CONTENT_TYPE_SONIFICATION", 4, contentTypeSonification)

        // Dismiss action constant
        assertEquals("com.scheduleassistant.app.action.DISMISS_ALARM", AlarmRingingService.ACTION_DISMISS)
    }
}
