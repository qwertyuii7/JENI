package com.scheduleassistant.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.scheduleassistant.app.data.diagnostics.DiagnosticsManager
import com.scheduleassistant.app.ui.AlarmRingingActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class ActiveRingingAlarm(
    val entityId: String,
    val title: String,
    val triggerTimeMs: Long = System.currentTimeMillis(),
    val isTest: Boolean = false
)

class AlarmRingingService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var autoStopRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "ScheduleAssistant:AlarmRingingWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 minutes max fallback
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        when (action) {
            ACTION_DISMISS, ACTION_DISMISS_ALARM -> {
                val entityId = intent.getStringExtra(EXTRA_SCHEDULE_ID) ?: ""
                if (entityId.isNotEmpty()) {
                    handleDismissAlarm(entityId)
                } else {
                    handleDismissAll()
                }
                return START_NOT_STICKY
            }
            ACTION_DISMISS_ALL, ACTION_NOTIFICATION_DISMISSED -> {
                handleDismissAll()
                return START_NOT_STICKY
            }
        }

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Scheduled Alarm"
        val scheduleId = intent?.getStringExtra(EXTRA_SCHEDULE_ID) ?: "default-${System.currentTimeMillis()}"
        val isTest = intent?.getBooleanExtra(EXTRA_IS_TEST, false) ?: false
        val triggerMs = intent?.getLongExtra(EXTRA_TRIGGER_MS, System.currentTimeMillis()) ?: System.currentTimeMillis()

        // 1. Record per-alarm ringer state (Requirement 1)
        val alarmItem = ActiveRingingAlarm(
            entityId = scheduleId,
            title = title,
            triggerTimeMs = triggerMs,
            isTest = isTest
        )
        _activeAlarms[scheduleId] = alarmItem
        _activeAlarmsFlow.value = _activeAlarms.values.toList()

        createChannels()
        updateForegroundNotification()
        playAlarmAudio()
        startVibration()
        scheduleAutoStopTimer()

        return START_NOT_STICKY
    }

    private fun handleDismissAlarm(entityId: String) {
        if (entityId.isNotEmpty()) {
            _activeAlarms.remove(entityId)
            _activeAlarmsFlow.value = _activeAlarms.values.toList()
        }

        if (_activeAlarms.isEmpty()) {
            stopAudioAndVibrations()
            cancelAutoStopTimer()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        } else {
            updateForegroundNotification()
        }
    }

    private fun handleDismissAll() {
        _activeAlarms.clear()
        _activeAlarmsFlow.value = emptyList()
        stopAudioAndVibrations()
        cancelAutoStopTimer()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun scheduleAutoStopTimer() {
        cancelAutoStopTimer()
        val autoStopMinutes = DiagnosticsManager.getAlarmAutoStopMinutes(applicationContext)
        val timeoutMs = autoStopMinutes * 60 * 1000L

        autoStopRunnable = Runnable {
            if (_activeAlarms.isNotEmpty()) {
                val alarmsToNotify = _activeAlarms.values.toList()
                // Post missed alarm notifications (Requirement 2)
                for (alarm in alarmsToNotify) {
                    postMissedAlarmNotification(alarm, autoStopMinutes)
                }
                handleDismissAll()
            }
        }
        handler.postDelayed(autoStopRunnable!!, timeoutMs)
    }

    private fun cancelAutoStopTimer() {
        autoStopRunnable?.let { handler.removeCallbacks(it) }
        autoStopRunnable = null
    }

    private fun postMissedAlarmNotification(alarm: ActiveRingingAlarm, autoStopMin: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingOpen = PendingIntent.getActivity(
            this,
            (alarm.entityId.hashCode() and 0x3FFFFFFF) + 100,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val missedNotif = NotificationCompat.Builder(this, MISSED_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("⏰ Missed Alarm: ${alarm.title}")
            .setContentText("Alarm auto-stopped after $autoStopMin min")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pendingOpen)
            .setAutoCancel(true)
            .build()

        val notifId = MISSED_NOTIF_BASE + (alarm.entityId.hashCode() and 0x3FFFFFFF)
        nm.notify(notifId, missedNotif)
    }

    private fun updateForegroundNotification() {
        val alarms = _activeAlarms.values.toList()
        if (alarms.isEmpty()) return

        val displayTitle = if (alarms.size == 1) {
            "⏰ ${alarms[0].title}"
        } else {
            "⏰ ${alarms.size} Alarms Ringing"
        }

        val detailsText = if (alarms.size == 1) {
            "Alarm is ringing. Tap to open or dismiss."
        } else {
            "Alarms: " + alarms.joinToString(", ") { it.title }
        }

        val fullScreenIntent = Intent(this, AlarmRingingActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TITLE, alarms.first().title)
            putExtra(EXTRA_SCHEDULE_ID, alarms.first().entityId)
            putExtra(EXTRA_IS_TEST, alarms.any { it.isTest })
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Dismiss All Intent
        val dismissAllIntent = Intent(this, AlarmRingingService::class.java).apply {
            action = ACTION_DISMISS_ALL
        }
        val dismissAllPending = PendingIntent.getService(
            this,
            NOTIFICATION_ID + 1,
            dismissAllIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Swiping / deleting notification stops all ringing alarms (Requirement 2)
        val deleteIntent = Intent(this, AlarmRingingService::class.java).apply {
            action = ACTION_NOTIFICATION_DISMISSED
        }
        val deletePending = PendingIntent.getService(
            this,
            NOTIFICATION_ID + 2,
            deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(displayTitle)
            .setContentText(detailsText)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss All", dismissAllPending)
            .setDeleteIntent(deletePending)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun playAlarmAudio() {
        if (ringtone != null && ringtone?.isPlaying == true) return
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            ringtone = RingtoneManager.getRingtone(applicationContext, alarmUri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    isLooping = true
                }
                play()
            }
        } catch (_: Exception) {}
    }

    private fun startVibration() {
        if (vibrator != null) return
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            val pattern = longArrayOf(0, 800, 500, 800, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (_: Exception) {}
    }

    private fun stopAudioAndVibrations() {
        try {
            ringtone?.stop()
            ringtone = null
            vibrator?.cancel()
            vibrator = null
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopAudioAndVibrations()
        cancelAutoStopTimer()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        super.onDestroy()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ringerChannel = NotificationChannel(
                CHANNEL_ID,
                "Ringing Alarms",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Audible ringing alarms for scheduled events"
                setBypassDnd(true)
                enableVibration(true)
            }
            nm.createNotificationChannel(ringerChannel)

            val missedChannel = NotificationChannel(
                MISSED_CHANNEL_ID,
                "Missed Alarms",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for automatically stopped missed alarms"
            }
            nm.createNotificationChannel(missedChannel)
        }
    }

    companion object {
        const val CHANNEL_ID = "schedule_ringing_alarm_channel"
        const val MISSED_CHANNEL_ID = "schedule_missed_alarms_channel"
        const val NOTIFICATION_ID = 9001
        const val MISSED_NOTIF_BASE = 9100

        const val ACTION_DISMISS = "com.scheduleassistant.app.action.DISMISS_ALARM"
        const val ACTION_DISMISS_ALARM = "com.scheduleassistant.app.action.DISMISS_ALARM"
        const val ACTION_DISMISS_ALL = "com.scheduleassistant.app.action.DISMISS_ALL"
        const val ACTION_NOTIFICATION_DISMISSED = "com.scheduleassistant.app.action.NOTIFICATION_DISMISSED"

        const val EXTRA_TITLE = "EXTRA_TITLE"
        const val EXTRA_SCHEDULE_ID = "EXTRA_SCHEDULE_ID"
        const val EXTRA_IS_TEST = "EXTRA_IS_TEST"
        const val EXTRA_TRIGGER_MS = "EXTRA_TRIGGER_MS"

        private val _activeAlarms = ConcurrentHashMap<String, ActiveRingingAlarm>()
        private val _activeAlarmsFlow = MutableStateFlow<List<ActiveRingingAlarm>>(emptyList())
        val activeAlarmsFlow: StateFlow<List<ActiveRingingAlarm>> = _activeAlarmsFlow.asStateFlow()

        val currentlyRingingId: String?
            get() = _activeAlarms.keys().toList().firstOrNull()

        fun isRinging(alarmId: String): Boolean = _activeAlarms.containsKey(alarmId)

        fun isAnyRinging(): Boolean = _activeAlarms.isNotEmpty()

        fun getActiveAlarms(): List<ActiveRingingAlarm> = _activeAlarms.values.toList()

        fun getActiveAlarmsJson(): JSONArray {
            val arr = JSONArray()
            for (alarm in _activeAlarms.values) {
                val obj = JSONObject().apply {
                    put("id", alarm.entityId)
                    put("title", alarm.title)
                    put("trigger_time_ms", alarm.triggerTimeMs)
                    put("is_test", alarm.isTest)
                }
                arr.put(obj)
            }
            return arr
        }

        fun dismissAlarm(context: Context, scheduleId: String) {
            val intent = Intent(context, AlarmRingingService::class.java).apply {
                action = ACTION_DISMISS_ALARM
                putExtra(EXTRA_SCHEDULE_ID, scheduleId)
            }
            context.startService(intent)
        }

        fun dismissAll(context: Context) {
            val intent = Intent(context, AlarmRingingService::class.java).apply {
                action = ACTION_DISMISS_ALL
            }
            context.startService(intent)
        }

        fun stopRinging(context: Context, alarmId: String? = null) {
            if (alarmId != null) {
                dismissAlarm(context, alarmId)
            } else {
                dismissAll(context)
            }
        }

        fun stopAll(context: Context) {
            dismissAll(context)
        }

        fun resetForTest() {
            _activeAlarms.clear()
            _activeAlarmsFlow.value = emptyList()
        }

        fun setCurrentlyRingingForTest(alarmId: String?) {
            _activeAlarms.clear()
            if (alarmId != null) {
                _activeAlarms[alarmId] = ActiveRingingAlarm(alarmId, "Test Alarm")
            }
            _activeAlarmsFlow.value = _activeAlarms.values.toList()
        }
    }
}
