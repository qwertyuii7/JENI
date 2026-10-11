package com.scheduleassistant.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.scheduleassistant.app.data.db.AppDatabase
import com.scheduleassistant.app.data.db.RawMessageEntity
import com.scheduleassistant.app.data.db.SyncEventEntity
import com.scheduleassistant.app.data.model.SyncEvent
import com.scheduleassistant.app.engine.HlcClock
import com.scheduleassistant.app.engine.IngestionPipeline
import com.scheduleassistant.app.engine.NotificationDispatcher
import com.scheduleassistant.app.engine.Prefilter
import com.scheduleassistant.app.engine.ScheduleParser
import com.scheduleassistant.app.engine.SpecLoader
import com.scheduleassistant.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class ScheduleForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "schedule_foreground_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Schedule Assistant Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Schedule Assistant Active")
            .setContentText("Listening for incoming schedule messages (offline)")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class ScheduleNotificationListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private lateinit var pipeline: IngestionPipeline

    override fun onCreate() {
        super.onCreate()
        val specLoader = SpecLoader(
            keywordsStreamProvider = { assets.open("spec/keywords.json") },
            synonymsStreamProvider = { assets.open("spec/synonyms.json") },
            daypartsStreamProvider = { assets.open("spec/dayparts.json") }
        )
        val prefilter = Prefilter(specLoader)
        val parser = ScheduleParser(specLoader, prefilter)
        val hlc = HlcClock("android-phone")

        pipeline = IngestionPipeline(
            deviceId = "android-phone",
            specLoader = specLoader,
            prefilter = prefilter,
            parser = parser,
            hlc = hlc,
            eventStore = mutableListOf(),
            rawMessageStore = mutableMapOf(),
            dispatcher = object : NotificationDispatcher {
                override fun onAlert(title: String, body: String, type: String) {
                    showNotificationAlert(title, body)
                }
            }
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return

        // Intercept WhatsApp and WhatsApp Business
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") {
            return
        }

        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: return

        if (text.isBlank()) return

        serviceScope.launch {
            val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())

            val db = AppDatabase.getInstance(applicationContext)

            // 1. Store raw message in prunable raw_messages table
            val rawMsgId = "raw-${UUID.randomUUID()}"
            db.rawMessageDao().insertRawMessage(
                RawMessageEntity(id = rawMsgId, rawText = text, source = "whatsapp", createdAt = nowIso)
            )

            // 2. Process message through pipeline
            val result = pipeline.processMessage(text)

            // 3. Persist generated sync events into Room database
            if (result.status == "PENDING" && result.entityId != null) {
                val nextSeq = db.eventDao().getNextSeq("android-phone")
                val hlcNow = HlcClock("android-phone").now()

                val payloadJson = JSONObject().apply {
                    put("type", result.extraction?.type)
                    put("title", result.extraction?.title)
                    put("start", result.extraction?.start)
                    put("start_date", result.extraction?.start_date)
                    put("time_unspecified", result.extraction?.time_unspecified)
                    put("ambiguous", result.extraction?.ambiguous)
                    put("source_quote", result.extraction?.source_quote)
                    put("confidence", result.extraction?.confidence)
                    put("raw_text", text)
                    put("raw_message_id", rawMsgId)
                }

                val eventEntity = SyncEventEntity(
                    id = UUID.randomUUID().toString(),
                    timestamp = nowIso,
                    deviceId = "android-phone",
                    seq = nextSeq,
                    hlc = hlcNow,
                    type = "event_extracted",
                    entityId = result.entityId,
                    payload = payloadJson.toString()
                )
                db.eventDao().insertEvent(eventEntity)

                // Surface instant heads-up card to user
                showNotificationAlert(
                    "New Schedule Found: ${result.extraction?.title}",
                    "Tap to review & confirm: \"${result.extraction?.source_quote}\""
                )
            }
        }
    }

    private fun showNotificationAlert(title: String, message: String) {
        val channelId = "schedule_pending_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Schedule Detection Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            putExtra("SCREEN_ROUTE", "pending")
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 1002, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
