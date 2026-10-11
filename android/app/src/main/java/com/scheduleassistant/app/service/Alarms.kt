package com.scheduleassistant.app.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.AlarmClock
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
import com.scheduleassistant.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

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
        val next = alarmManager.nextAlarmClock ?: return "None"
        val df = SimpleDateFormat("EEE, MMM d, h:mm a", Locale.US)
        return df.format(Date(next.triggerTime))
    }

    private fun recordAudit(
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

    fun scheduleAlertsForSchedule(schedule: ScheduleEntity): Boolean {
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

        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        val startTime = try {
            isoFormat.parse(schedule.start!!)?.time ?: return false
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
        val alarmTimeMs = startTime
        val reminderTimeMs = startTime - 15 * 60 * 1000 // T - 15m

        // Product Decision (Task B):
        // 1. Alarm: Must actually RING. Implement via AlarmClock.ACTION_SET_ALARM with EXTRA_SKIP_UI=true
        // into the system Clock app (declared com.android.alarm.permission.SET_ALARM).
        try {
            val cal = Calendar.getInstance().apply { timeInMillis = startTime }
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val minute = cal.get(Calendar.MINUTE)

            val clockIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, schedule.title)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            if (clockIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(clockIntent)
                recordAudit(
                    entityId = "alarm-${schedule.id}",
                    triggerTime = schedule.start ?: "",
                    api = "AlarmClock.ACTION_SET_ALARM",
                    result = "SUCCESS",
                    errorMessage = null
                )
            } else {
                recordAudit(
                    entityId = "alarm-${schedule.id}",
                    triggerTime = schedule.start ?: "",
                    api = "AlarmClock.ACTION_SET_ALARM",
                    result = "ERROR",
                    errorMessage = "No system Clock app resolved for ACTION_SET_ALARM. Falling back to in-app ringing."
                )
            }
        } catch (e: Exception) {
            recordAudit(
                entityId = "alarm-${schedule.id}",
                triggerTime = schedule.start ?: "",
                api = "AlarmClock.ACTION_SET_ALARM",
                result = "ERROR",
                errorMessage = "${e.javaClass.simpleName}: ${e.message}"
            )
        }

        // 2. AlarmClock via AlarmManager.setAlarmClock for status bar icon and in-app fallback
        if (alarmTimeMs > nowMs) {
            scheduleAlarmClock(
                id = schedule.id.hashCode(),
                triggerMs = alarmTimeMs,
                title = "⏰ ${schedule.title}",
                scheduleId = schedule.id,
                type = "alarm"
            )
        }

        // 3. Notification Reminder at lead time (15m before)
        val effectiveReminderTimeMs = if (reminderTimeMs > nowMs) {
            reminderTimeMs
        } else if (alarmTimeMs > nowMs) {
            nowMs + 2000L // 2 seconds from now if lead time passed but event is in future
        } else {
            0L
        }

        if (effectiveReminderTimeMs > 0) {
            scheduleAlarmClock(
                id = schedule.id.hashCode() + 1,
                triggerMs = effectiveReminderTimeMs,
                title = "🔔 Reminder: ${schedule.title}",
                scheduleId = schedule.id,
                type = "notification"
            )
        }

        return true
    }

    private fun scheduleAlarmClock(id: Int, triggerMs: Long, title: String, scheduleId: String, type: String) {
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

        try {
            val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerMs, showPendingIntent)
            alarmManager.setAlarmClock(alarmClockInfo, alarmPendingIntent)
            recordAudit(
                entityId = if (type == "alarm") "alarm-$scheduleId" else "rem-$scheduleId",
                triggerTime = timeStr,
                api = "AlarmManager.setAlarmClock",
                result = "SUCCESS",
                errorMessage = null
            )
        } catch (e: SecurityException) {
            recordAudit(
                entityId = if (type == "alarm") "alarm-$scheduleId" else "rem-$scheduleId",
                triggerTime = timeStr,
                api = "AlarmManager.setAlarmClock",
                result = "ERROR",
                errorMessage = "SecurityException: Exact alarms not permitted (canScheduleExactAlarms=${canScheduleExactAlarms()})"
            )
        } catch (e: Exception) {
            recordAudit(
                entityId = if (type == "alarm") "alarm-$scheduleId" else "rem-$scheduleId",
                triggerTime = timeStr,
                api = "AlarmManager.setAlarmClock",
                result = "ERROR",
                errorMessage = "${e.javaClass.simpleName}: ${e.message}"
            )
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

    fun cancelAlertsForSchedule(scheduleId: String) {
        val id = scheduleId.hashCode()
        val alarmIntent = Intent(context, AlarmReceiver::class.java)
        val p1 = PendingIntent.getBroadcast(context, id, alarmIntent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        val p2 = PendingIntent.getBroadcast(context, id + 1, alarmIntent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        p1?.let { alarmManager.cancel(it) }
        p2?.let { alarmManager.cancel(it) }

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
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val isTestAlarm = intent.getBooleanExtra("EXTRA_IS_TEST_ALARM", false)

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScheduleAssistant:AlarmWakeLock")
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

            val timeFormat = SimpleDateFormat("h:mm:ss a", Locale.US)
            val schedStr = timeFormat.format(Date(scheduledTimeMs))
            val fireStr = timeFormat.format(Date(firedTimeMs))

            // Start audible and full-screen alarm ringing service
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

        // If it is an ALARM, start the audible ringing service
        if (type == "alarm") {
            try {
                val ringIntent = Intent(context, AlarmRingingService::class.java).apply {
                    putExtra("EXTRA_TITLE", title)
                    putExtra("EXTRA_SCHEDULE_ID", scheduleId)
                    putExtra("EXTRA_IS_TEST", false)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(ringIntent)
                } else {
                    context.startService(ringIntent)
                }
            } catch (_: Exception) {}
        }

        // Check if missed due to device downtime
        val isMissed = (System.currentTimeMillis() - triggerMs) > 60_000
        val displayTitle = if (isMissed) {
            val missedTimeStr = SimpleDateFormat("h:mm a", Locale.US).format(Date(triggerMs))
            if (type == "alarm") "⏰ MISSED ALARM (missed at $missedTimeStr): $title" else "🔔 Missed Reminder (missed at $missedTimeStr): $title"
        } else {
            title
        }

        val openIntent = Intent(context, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(context, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(displayTitle)
            .setContentText("Tap to open Schedule Assistant")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pendingOpen)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(scheduleId.hashCode(), notification)

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
                        scheduler.scheduleAlertsForSchedule(schedule)
                    }
                }
            }
        }
    }
}
