package com.scheduleassistant.app.service

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.scheduleassistant.app.data.db.AlarmAuditEntity
import com.scheduleassistant.app.data.db.AppDatabase
import com.scheduleassistant.app.data.db.SyncEventEntity
import com.scheduleassistant.app.data.diagnostics.DiagnosticsManager
import com.scheduleassistant.app.data.model.ScheduleEntity
import com.scheduleassistant.app.data.model.ScheduleStatus
import com.scheduleassistant.app.data.model.SyncEvent
import com.scheduleassistant.app.data.projection.ProjectionEngine
import com.scheduleassistant.app.engine.HlcClock
import com.scheduleassistant.app.ui.AlarmRingingActivity
import com.scheduleassistant.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

fun parseIsoTimestamp(isoString: String): Long? {
    val patterns = arrayOf(
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mmXXX",
        "yyyy-MM-dd'T'HH:mm"
    )
    for (p in patterns) {
        try {
            val df = SimpleDateFormat(p, Locale.US).apply {
                if (p.endsWith("'Z'")) timeZone = TimeZone.getTimeZone("UTC")
            }
            val date = df.parse(isoString)
            if (date != null) return date.time
        } catch (_: Exception) {}
    }
    return null
}

fun getAlarmRequestCode(entityId: String, offset: Int = 0): Int {
    return (entityId.hashCode() and 0x3FFFFFFF) + offset
}

class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    fun getNextAlarmClock(): AlarmManager.AlarmClockInfo? {
        return alarmManager.nextAlarmClock
    }

    fun getNextAlarmClockFormatted(): String {
        return DiagnosticsManager.getNextAlarmClockLabel(context)
    }

    fun getActiveScheduledCount(): Int {
        return DiagnosticsManager.getActiveScheduledCount(context)
    }

    fun recordAudit(
        entityId: String,
        triggerTime: String,
        api: String,
        result: String,
        errorMessage: String? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                val audit = AlarmAuditEntity(
                    entityId = entityId,
                    triggerTime = triggerTime,
                    api = api,
                    result = result,
                    errorMessage = errorMessage,
                    timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }.format(Date())
                )
                db.alarmAuditDao().insertAudit(audit)
            } catch (_: Exception) {}
        }
    }

    /**
     * Requirement 1 & 4: One mechanism only (AlarmManager.setAlarmClock + AlarmRingingService).
     * Reminder is default (lead time configurable in Settings).
     * Alarm is opt-in per event (enableAlarm parameter).
     */
    fun scheduleAlertsForSchedule(schedule: ScheduleEntity, enableAlarm: Boolean = false): Boolean {
        // HARD INVARIANT: time_unspecified or unconfirmed schedules NEVER produce alarms/reminders
        if (schedule.time_unspecified || schedule.start == null || schedule.status != ScheduleStatus.CONFIRMED) {
            recordAudit(
                entityId = schedule.id,
                triggerTime = schedule.start ?: "UNSPECIFIED",
                api = "AlarmScheduler.gate",
                result = "SKIPPED",
                errorMessage = "Gate blocked: time_unspecified=${schedule.time_unspecified}, status=${schedule.status}"
            )
            return false
        }

        val startTime = try {
            parseIsoTimestamp(schedule.start!!) ?: return false
        } catch (e: Exception) {
            recordAudit(
                entityId = schedule.id,
                triggerTime = schedule.start ?: "",
                api = "AlarmScheduler.parse",
                result = "ERROR",
                errorMessage = "Failed to parse start time: ${e.message}"
            )
            return false
        }

        val nowMs = System.currentTimeMillis()
        if (startTime <= nowMs) {
            recordAudit(
                entityId = schedule.id,
                triggerTime = schedule.start ?: "",
                api = "AlarmScheduler.leadTime",
                result = "SKIPPED",
                errorMessage = "Event start time is already in the past"
            )
            return false
        }

        val reminderLeadMinutes = DiagnosticsManager.getReminderLeadTimeMinutes(context)
        val alarmLeadMinutes = DiagnosticsManager.getAlarmLeadTimeMinutes(context)

        val reminderTargetMs = startTime - reminderLeadMinutes * 60 * 1000L
        val alarmTargetMs = startTime - alarmLeadMinutes * 60 * 1000L

        // Requirement 3: If reminder time is already past but event is in the future,
        // fire reminder immediately. Never skip silently; audit it.
        val effectiveReminderTimeMs = if (reminderTargetMs > nowMs) {
            reminderTargetMs
        } else {
            nowMs + 1000L // Fire immediately (1 second from now)
        }

        val isImmediateReminder = reminderTargetMs <= nowMs
        val reminderScheduled = scheduleAlarmClock(
            id = getAlarmRequestCode(schedule.id, 1),
            triggerMs = effectiveReminderTimeMs,
            title = "🔔 Reminder: ${schedule.title}",
            scheduleId = schedule.id,
            type = "notification",
            isImmediate = isImmediateReminder
        )

        // Requirement 4: Alarm is opt-in per event
        if (enableAlarm) {
            val effectiveAlarmTimeMs = if (alarmTargetMs > nowMs) {
                alarmTargetMs
            } else {
                nowMs + 1000L
            }
            val isImmediateAlarm = alarmTargetMs <= nowMs
            scheduleAlarmClock(
                id = getAlarmRequestCode(schedule.id, 2),
                triggerMs = effectiveAlarmTimeMs,
                title = "⏰ ALARM: ${schedule.title}",
                scheduleId = schedule.id,
                type = "alarm",
                isImmediate = isImmediateAlarm
            )
        }

        return reminderScheduled
    }

    /**
     * Requirement 2: Cancelling an event cancels its old reminder/alarm.
     */
    fun cancelAlertsForSchedule(scheduleId: String) {
        val req1 = getAlarmRequestCode(scheduleId, 1)
        val req2 = getAlarmRequestCode(scheduleId, 2)
        val alarmIntent = Intent(context, AlarmReceiver::class.java)
        val p1 = PendingIntent.getBroadcast(context, req1, alarmIntent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        val p2 = PendingIntent.getBroadcast(context, req2, alarmIntent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        p1?.let { alarmManager.cancel(it) }
        p2?.let { alarmManager.cancel(it) }

        AlarmRingingService.stopRinging(context, scheduleId)
        DiagnosticsManager.unregisterScheduledAlerts(context, scheduleId)

        recordAudit(
            entityId = scheduleId,
            triggerTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date()),
            api = "AlarmManager.cancel",
            result = "SUCCESS",
            errorMessage = null
        )
    }

    /**
     * Requirement 3: On delete, cancel reminder and alarm PendingIntents,
     * stop ringing alarm, unregister scheduled count, and write audit row with action: delete.
     */
    fun deleteScheduleAlerts(scheduleId: String) {
        val req1 = getAlarmRequestCode(scheduleId, 1)
        val req2 = getAlarmRequestCode(scheduleId, 2)
        val alarmIntent = Intent(context, AlarmReceiver::class.java)
        val p1 = PendingIntent.getBroadcast(context, req1, alarmIntent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        val p2 = PendingIntent.getBroadcast(context, req2, alarmIntent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        p1?.let { alarmManager.cancel(it) }
        p2?.let { alarmManager.cancel(it) }

        AlarmRingingService.stopRinging(context, scheduleId)
        DiagnosticsManager.unregisterScheduledAlerts(context, scheduleId)

        recordAudit(
            entityId = scheduleId,
            triggerTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date()),
            api = "delete",
            result = "SUCCESS",
            errorMessage = "Cancelled alerts and stopped ringing for deleted schedule"
        )
    }

    /**
     * Requirement 4: On edit, cancel old alerts, reschedule new alerts, write audit row with action: edit.
     */
    fun editScheduleAlerts(schedule: ScheduleEntity, enableAlarm: Boolean = false): Boolean {
        cancelAlertsForSchedule(schedule.id)
        val scheduled = scheduleAlertsForSchedule(schedule, enableAlarm = enableAlarm)
        recordAudit(
            entityId = schedule.id,
            triggerTime = schedule.start ?: "",
            api = "edit",
            result = if (scheduled) "SUCCESS" else "SKIPPED",
            errorMessage = "Updated alerts for edited schedule"
        )
        return scheduled
    }

    /**
     * Requirement 2: On undo / restore, reschedule alerts if start time is still in the future.
     */
    fun restoreScheduleAlerts(schedule: ScheduleEntity, enableAlarm: Boolean = false): Boolean {
        val scheduled = scheduleAlertsForSchedule(schedule, enableAlarm = enableAlarm)
        recordAudit(
            entityId = schedule.id,
            triggerTime = schedule.start ?: "",
            api = "restore",
            result = if (scheduled) "SUCCESS" else "SKIPPED",
            errorMessage = "Restored alerts on undo"
        )
        return scheduled
    }

    /**
     * Requirement 2: Rescheduling an event replaces old reminder/alarm with new one.
     */
    fun rescheduleAlertsForSchedule(schedule: ScheduleEntity, enableAlarm: Boolean = false): Boolean {
        cancelAlertsForSchedule(schedule.id)
        val scheduled = scheduleAlertsForSchedule(schedule, enableAlarm = enableAlarm)
        recordAudit(
            entityId = schedule.id,
            triggerTime = schedule.start ?: "",
            api = "AlarmScheduler.reschedule",
            result = if (scheduled) "SUCCESS" else "ERROR",
            errorMessage = "Replaced previous alert with updated start time"
        )
        return scheduled
    }

    fun scheduleAlarmClock(
        id: Int,
        triggerMs: Long,
        title: String,
        scheduleId: String,
        type: String,
        isImmediate: Boolean = false
    ): Boolean {
        val showIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val showPendingIntent = PendingIntent.getActivity(
            context, id, showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("EXTRA_TITLE", title)
            putExtra("EXTRA_SCHEDULE_ID", scheduleId)
            putExtra("EXTRA_TYPE", type)
            putExtra("EXTRA_TRIGGER_MS", triggerMs)
        }
        val alarmPendingIntent = PendingIntent.getBroadcast(
            context, id, alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val timeStr = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(triggerMs))

        val apiName = if (isImmediate) {
            "AlarmManager.setAlarmClock (Immediate $type)"
        } else {
            "AlarmManager.setAlarmClock ($type)"
        }

        return try {
            val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerMs, showPendingIntent)
            alarmManager.setAlarmClock(alarmClockInfo, alarmPendingIntent)

            val alertType = if (type == "alarm") "Alarm" else "Reminder"
            val cleanTitle = title.replace(Regex("^[🔔⏰]\\s*(Reminder:|ALARM:)?\\s*"), "").trim()
            DiagnosticsManager.registerScheduledAlert(context, scheduleId, alertType, cleanTitle, triggerMs)

            // Requirement 7: Show getNextAlarmClock right after scheduling
            val nextAlarmStr = DiagnosticsManager.getNextAlarmClockInfo(context)
            val auditNote = if (isImmediate) {
                "Lead time was past, scheduled immediately; nextAlarm=$nextAlarmStr"
            } else {
                "nextAlarm=$nextAlarmStr"
            }

            recordAudit(
                entityId = if (type == "alarm") "alarm-$scheduleId" else "rem-$scheduleId",
                triggerTime = timeStr,
                api = apiName,
                result = "SUCCESS",
                errorMessage = auditNote
            )
            true
        } catch (e: SecurityException) {
            recordAudit(
                entityId = if (type == "alarm") "alarm-$scheduleId" else "rem-$scheduleId",
                triggerTime = timeStr,
                api = apiName,
                result = "ERROR",
                errorMessage = "SecurityException: Exact alarms not permitted (canScheduleExactAlarms=${canScheduleExactAlarms()})"
            )
            false
        } catch (e: Exception) {
            recordAudit(
                entityId = if (type == "alarm") "alarm-$scheduleId" else "rem-$scheduleId",
                triggerTime = timeStr,
                api = apiName,
                result = "ERROR",
                errorMessage = "${e.javaClass.simpleName}: ${e.message}"
            )
            false
        }
    }

    // Requirement 2: Alarm self-test, independent of log-only and of the event pipeline
    suspend fun scheduleTestAlarm(triggerMs: Long): com.scheduleassistant.app.data.db.AlarmTestEntity {
        val db = AppDatabase.getInstance(context)
        val testId = "test-alarm-${UUID.randomUUID()}"
        val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val record = com.scheduleassistant.app.data.db.AlarmTestEntity(
            id = testId,
            createdAt = nowIso,
            scheduledTimeMs = triggerMs,
            status = "SCHEDULED"
        )
        db.alarmTestDao().insertTestRecord(record)
        scheduleTestAlarmClock(testId, triggerMs, firedAfterReboot = false)
        return record
    }

    fun scheduleTestAlarmClock(testId: String, triggerMs: Long, firedAfterReboot: Boolean = false) {
        val id = testId.hashCode()
        val showIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val showPendingIntent = PendingIntent.getActivity(
            context, id, showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("EXTRA_IS_TEST_ALARM", true)
            putExtra("EXTRA_TEST_ALARM_ID", testId)
            putExtra("EXTRA_SCHEDULED_TIME_MS", triggerMs)
            putExtra("EXTRA_FIRED_AFTER_REBOOT", firedAfterReboot)
        }
        val alarmPendingIntent = PendingIntent.getBroadcast(
            context, id, alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val timeStr = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(triggerMs))

        try {
            val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerMs, showPendingIntent)
            alarmManager.setAlarmClock(alarmClockInfo, alarmPendingIntent)
            recordAudit(
                entityId = testId,
                triggerTime = timeStr,
                api = "AlarmManager.setAlarmClock (Test)",
                result = "SUCCESS",
                errorMessage = null
            )
        } catch (e: SecurityException) {
            recordAudit(
                entityId = testId,
                triggerTime = timeStr,
                api = "AlarmManager.setAlarmClock (Test)",
                result = "ERROR",
                errorMessage = "SecurityException: Exact alarms not permitted (canScheduleExactAlarms=${canScheduleExactAlarms()})"
            )
        } catch (e: Exception) {
            recordAudit(
                entityId = testId,
                triggerTime = timeStr,
                api = "AlarmManager.setAlarmClock (Test)",
                result = "ERROR",
                errorMessage = "${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val isTestAlarm = intent.getBooleanExtra("EXTRA_IS_TEST_ALARM", false)

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "ScheduleAssistant:AlarmWakeLock"
        )
        wakeLock.acquire(10 * 1000L) // 10 seconds max

        val channelId = "schedule_alerts_channel"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Schedule Alarms & Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Critical alerts for upcoming classes, meetings, and deadlines"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Requirement 2: Handle Alarm Self-Test
        if (isTestAlarm) {
            val testId = intent.getStringExtra("EXTRA_TEST_ALARM_ID") ?: ""
            val scheduledTimeMs = intent.getLongExtra("EXTRA_SCHEDULED_TIME_MS", System.currentTimeMillis())
            val firedAfterReboot = intent.getBooleanExtra("EXTRA_FIRED_AFTER_REBOOT", false)
            val firedTimeMs = System.currentTimeMillis()
            val delayMs = firedTimeMs - scheduledTimeMs

            // Guard against double ringing (Requirement 1: one ringer per alarm id)
            if (!AlarmRingingService.isRinging(testId)) {
                try {
                    val ringIntent = Intent(context, AlarmRingingService::class.java).apply {
                        putExtra("EXTRA_TITLE", "Test Alarm Fired")
                        putExtra("EXTRA_SCHEDULE_ID", testId)
                        putExtra("EXTRA_IS_TEST", true)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(ringIntent)
                    } else {
                        context.startService(ringIntent)
                    }
                } catch (_: Exception) {}
            }

            val timeFormat = SimpleDateFormat("h:mm:ss a", Locale.US)
            val schedStr = timeFormat.format(Date(scheduledTimeMs))
            val fireStr = timeFormat.format(Date(firedTimeMs))

            val openIntent = Intent(context, MainActivity::class.java)
            val pendingOpen = PendingIntent.getActivity(
                context, testId.hashCode(), openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("🧪 Test Alarm Fired (${if (firedAfterReboot) "Post-Reboot" else "Normal"})")
                .setContentText("Sched: $schedStr | Actual: $fireStr | Delay: ${delayMs}ms")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(pendingOpen)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(testId.hashCode(), notification)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getInstance(context)
                    val record = db.alarmTestDao().getRecordById(testId)
                    if (record != null) {
                        db.alarmTestDao().updateTestRecord(
                            record.copy(
                                firedTimeMs = firedTimeMs,
                                delayMs = delayMs,
                                firedAfterReboot = firedAfterReboot,
                                status = "FIRED"
                            )
                        )
                    }
                } finally {
                    if (wakeLock.isHeld) {
                        wakeLock.release()
                    }
                }
            }
            return
        }

        val title = intent.getStringExtra("EXTRA_TITLE") ?: "Schedule Alert"
        val scheduleId = intent.getStringExtra("EXTRA_SCHEDULE_ID") ?: ""
        val type = intent.getStringExtra("EXTRA_TYPE") ?: "alarm"
        val triggerMs = intent.getLongExtra("EXTRA_TRIGGER_MS", System.currentTimeMillis())

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isInteractive = pm?.isInteractive ?: false
        val isKeyguardLocked = km?.isKeyguardLocked ?: false
        val canUseFsi = DiagnosticsManager.canUseFullScreenIntent(context)
        val fullScreenSet = (type == "alarm")

        // Wake the screen with wakeup flag (Requirement 3)
        val fireWakeLock = pm?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "ScheduleAssistant:AlarmReceiverFireWakeLock"
        )
        fireWakeLock?.acquire(30_000L)

        var activityLaunchResult = "N/A"

        // Check if missed due to device downtime
        val isMissed = (System.currentTimeMillis() - triggerMs) > 60_000
        val displayTitle = if (isMissed) {
            val missedTimeStr = SimpleDateFormat("h:mm a", Locale.US).format(Date(triggerMs))
            if (type == "alarm") "⏰ MISSED ALARM (missed at $missedTimeStr): $title" else "🔔 Missed Reminder (missed at $missedTimeStr): $title"
        } else {
            title
        }

        // Requirement 1 & 3: For ALARM, start AlarmRingingService (handles foreground notification)
        // and launch AlarmRingingActivity directly to wake screen/keyguard.
        // DO NOT call notificationManager.notify here to prevent duplicate "one popup with two icons" bug.
        if (type == "alarm") {
            try {
                val ringIntent = Intent(context, AlarmRingingService::class.java).apply {
                    putExtra(AlarmRingingService.EXTRA_TITLE, title)
                    putExtra(AlarmRingingService.EXTRA_SCHEDULE_ID, scheduleId)
                    putExtra(AlarmRingingService.EXTRA_TRIGGER_MS, triggerMs)
                    putExtra(AlarmRingingService.EXTRA_IS_TEST, false)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(ringIntent)
                } else {
                    context.startService(ringIntent)
                }
            } catch (e: Exception) {
                activityLaunchResult = "SERVICE_START_FAILED: ${e.message}"
            }

            try {
                val fsiIntent = Intent(context, AlarmRingingActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(AlarmRingingService.EXTRA_TITLE, title)
                    putExtra(AlarmRingingService.EXTRA_SCHEDULE_ID, scheduleId)
                    putExtra(AlarmRingingService.EXTRA_TRIGGER_MS, triggerMs)
                }
                context.startActivity(fsiIntent)
                activityLaunchResult = "LAUNCHED"
            } catch (e: Exception) {
                activityLaunchResult = "ACTIVITY_LAUNCH_FAILED: ${e.message}"
            }
        } else {
            // Reminders: post standard high-importance notification
            val notifId = getAlarmRequestCode(scheduleId, 1)
            val openIntent = Intent(context, MainActivity::class.java)
            val pendingOpen = PendingIntent.getActivity(
                context,
                notifId + 50,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(displayTitle)
                .setContentText("Tap to open Schedule Assistant")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(pendingOpen)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(notifId, notification)
        }

        // Audit fire-time metrics (Requirement 3)
        val fireAuditDetails = JSONObject().apply {
            put("interactive", isInteractive)
            put("keyguard_locked", isKeyguardLocked)
            put("can_use_full_screen_intent", canUseFsi)
            put("full_screen_set", fullScreenSet)
            put("activity_launch_result", activityLaunchResult)
            put("alarm_type", type)
        }.toString()

        val scheduler = AlarmScheduler(context)
        scheduler.recordAudit(
            entityId = if (type == "alarm") "alarm-$scheduleId" else "rem-$scheduleId",
            triggerTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(triggerMs)),
            api = "AlarmReceiver.fire",
            result = "SUCCESS",
            errorMessage = fireAuditDetails
        )

        // Record reminder_fired event into Room database
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                val deviceId = "android-device"
                val hlc = HlcClock(deviceId)
                val nextSeq = db.eventDao().getNextSeq(deviceId)

                val payload = JSONObject().apply {
                    put("schedule_id", scheduleId)
                    put("title", title)
                    put("type", type)
                    put("idempotency_key", "fire-$scheduleId-$triggerMs")
                    put("missed", isMissed)
                    put("fired_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date()))
                }

                val firedEvent = SyncEventEntity(
                    id = UUID.randomUUID().toString(),
                    timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date()),
                    deviceId = deviceId,
                    seq = nextSeq,
                    hlc = hlc.now(),
                    type = "reminder_fired",
                    entityId = if (type == "alarm") "alarm-$scheduleId" else "rem-$scheduleId",
                    payload = payload.toString()
                )
                db.eventDao().insertEvent(firedEvent)
            } finally {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
            }
        }
    }
}

/**
 * Requirement 7: Boot recovery re-registers all future alarms from the event log;
 * audit entries per attempt.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON") {

            CoroutineScope(Dispatchers.IO).launch {
                DiagnosticsManager.recordHeartbeat(
                    context,
                    "BOOT_COMPLETED",
                    "Action: $action, restoring scheduled alarms and self-tests"
                )

                val db = AppDatabase.getInstance(context)
                val scheduler = AlarmScheduler(context)

                // Restore pending self-test alarms (marked firedAfterReboot = true)
                val pendingTestAlarms = db.alarmTestDao().getPendingRecords(System.currentTimeMillis())
                for (testAlarm in pendingTestAlarms) {
                    scheduler.scheduleTestAlarmClock(testAlarm.id, testAlarm.scheduledTimeMs, firedAfterReboot = true)
                    scheduler.recordAudit(
                        entityId = testAlarm.id,
                        triggerTime = "${testAlarm.scheduledTimeMs}",
                        api = "BootReceiver.restoreTestAlarm",
                        result = "SUCCESS",
                        errorMessage = "Restored test alarm on device reboot"
                    )
                }

                // Restore regular alarms on reboot from Room database
                val entities = db.eventDao().getAllEvents()
                val syncEvents = entities.map { e ->
                    val payloadJson = JSONObject(e.payload)
                    val map = mutableMapOf<String, Any?>()
                    val keys = payloadJson.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        map[k] = payloadJson.opt(k)
                    }
                    SyncEvent(
                        id = e.id,
                        timestamp = e.timestamp,
                        device_id = e.deviceId,
                        seq = e.seq,
                        hlc = e.hlc,
                        type = e.type,
                        entity_id = e.entityId,
                        payload = map
                    )
                }

                val state = ProjectionEngine.projectState(syncEvents)
                for (schedule in state.schedules.values) {
                    if (schedule.status == ScheduleStatus.CONFIRMED && !schedule.time_unspecified && schedule.start != null) {
                        // Check if an alarm_set event exists for this schedule in syncEvents
                        val hasAlarmEvent = syncEvents.any { it.type == "alarm_set" && (it.entity_id == "alarm-${schedule.id}" || it.payload["schedule_id"] == schedule.id) }
                        val success = scheduler.scheduleAlertsForSchedule(schedule, enableAlarm = hasAlarmEvent)
                        scheduler.recordAudit(
                            entityId = schedule.id,
                            triggerTime = schedule.start ?: "",
                            api = "BootReceiver.restoreScheduleAlerts",
                            result = if (success) "SUCCESS" else "SKIPPED",
                            errorMessage = if (success) "Restored alerts on device reboot" else "Start time in past or gate blocked"
                        )
                    }
                }
            }
        }
    }
}
