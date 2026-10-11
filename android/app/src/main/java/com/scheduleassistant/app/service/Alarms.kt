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
import androidx.core.app.NotificationCompat
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
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun scheduleAlertsForSchedule(schedule: ScheduleEntity): Boolean {
        // HARD INVARIANT: time_unspecified or unconfirmed schedules NEVER produce alarms/reminders
        if (schedule.time_unspecified || schedule.start == null || schedule.status != ScheduleStatus.CONFIRMED) {
            return false
        }

        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        val startTime = try {
            isoFormat.parse(schedule.start!!)?.time ?: return false
        } catch (_: Exception) {
            return false
        }

        val alarmTimeMs = startTime - 30 * 60 * 1000 // T - 30m
        val reminderTimeMs = startTime - 15 * 60 * 1000 // T - 15m

        // Schedule exact AlarmClock (fires even in Doze mode)
        if (alarmTimeMs > System.currentTimeMillis()) {
            scheduleAlarmClock(
                id = schedule.id.hashCode(),
                triggerMs = alarmTimeMs,
                title = "⏰ ALARM: ${schedule.title}",
                scheduleId = schedule.id,
                type = "alarm"
            )
        }

        // Schedule notification reminder
        if (reminderTimeMs > System.currentTimeMillis()) {
            scheduleAlarmClock(
                id = schedule.id.hashCode() + 1,
                triggerMs = reminderTimeMs,
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

        val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerMs, showPendingIntent)
        alarmManager.setAlarmClock(alarmClockInfo, alarmPendingIntent)
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

        val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerMs, showPendingIntent)
        alarmManager.setAlarmClock(alarmClockInfo, alarmPendingIntent)
    }

    fun cancelAlertsForSchedule(scheduleId: String) {
        val id = scheduleId.hashCode()
        val alarmIntent = Intent(context, AlarmReceiver::class.java)
        val p1 = PendingIntent.getBroadcast(context, id, alarmIntent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        val p2 = PendingIntent.getBroadcast(context, id + 1, alarmIntent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        p1?.let { alarmManager.cancel(it) }
        p2?.let { alarmManager.cancel(it) }
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

        // Requirement 2: Handle Alarm Self-Test (Must not create events or touch parser state)
        if (isTestAlarm) {
            val testId = intent.getStringExtra("EXTRA_TEST_ALARM_ID") ?: ""
            val scheduledTimeMs = intent.getLongExtra("EXTRA_SCHEDULED_TIME_MS", System.currentTimeMillis())
            val firedAfterReboot = intent.getBooleanExtra("EXTRA_FIRED_AFTER_REBOOT", false)
            val firedTimeMs = System.currentTimeMillis()
            val delayMs = firedTimeMs - scheduledTimeMs

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
                    entityId = "rem-$scheduleId",
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
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED || action == "android.intent.action.QUICKBOOT_POWERON") {
            DiagnosticsManager.recordBootRecovery(context)
            CoroutineScope(Dispatchers.IO).launch {
                DiagnosticsManager.recordHeartbeat(context, "BOOT_COMPLETED", "Action: $action")

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
