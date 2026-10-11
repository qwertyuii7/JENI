package com.scheduleassistant.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.scheduleassistant.app.data.db.AppDatabase
import com.scheduleassistant.app.data.db.DiagnosticLogEntity
import com.scheduleassistant.app.data.db.RawMessageEntity
import com.scheduleassistant.app.data.db.SyncEventEntity
import com.scheduleassistant.app.data.diagnostics.DiagnosticsManager
import com.scheduleassistant.app.engine.HlcClock
import com.scheduleassistant.app.engine.IngestionPipeline
import com.scheduleassistant.app.engine.NotificationDispatcher
import com.scheduleassistant.app.engine.Prefilter
import com.scheduleassistant.app.engine.ScheduleParser
import com.scheduleassistant.app.engine.SpecLoader
import com.scheduleassistant.app.engine.WhatsAppNotificationHandler
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

class ScheduleForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            DiagnosticsManager.recordHeartbeat(applicationContext, "SERVICE_CREATE", "ScheduleForegroundService onCreate")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DiagnosticsManager.setForegroundServiceRunning(true)

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

    override fun onDestroy() {
        DiagnosticsManager.setForegroundServiceRunning(false)
        CoroutineScope(Dispatchers.IO).launch {
            DiagnosticsManager.recordHeartbeat(applicationContext, "SERVICE_DESTROY", "ScheduleForegroundService onDestroy")
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        CoroutineScope(Dispatchers.IO).launch {
            DiagnosticsManager.recordHeartbeat(applicationContext, "SERVICE_TASK_REMOVED", "ScheduleForegroundService onTaskRemoved")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class ScheduleNotificationListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private lateinit var pipeline: IngestionPipeline
    private lateinit var prefilter: Prefilter
    private lateinit var parser: ScheduleParser
    private val whatsAppHandler = WhatsAppNotificationHandler()

    override fun onCreate() {
        super.onCreate()
        val specLoader = SpecLoader(
            keywordsStreamProvider = { assets.open("spec/keywords.json") },
            synonymsStreamProvider = { assets.open("spec/synonyms.json") },
            daypartsStreamProvider = { assets.open("spec/dayparts.json") }
        )
        prefilter = Prefilter(specLoader)
        parser = ScheduleParser(specLoader, prefilter)
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

    override fun onListenerConnected() {
        super.onListenerConnected()
        DiagnosticsManager.setListenerConnected(true)
        Log.i("ScheduleListener", "Notification listener connected successfully")
        serviceScope.launch {
            DiagnosticsManager.recordHeartbeat(applicationContext, "LISTENER_CONNECTED", "Notification listener connected successfully")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        DiagnosticsManager.setListenerConnected(false)
        Log.w("ScheduleListener", "Notification listener disconnected - attempting requestRebind")
        serviceScope.launch {
            DiagnosticsManager.recordHeartbeat(applicationContext, "LISTENER_DISCONNECTED", "Notification listener disconnected")
        }

        // Requirement 4: requestRebind on listener disconnect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                requestRebind(ComponentName(this, ScheduleNotificationListenerService::class.java))
            } catch (e: Exception) {
                Log.e("ScheduleListener", "Failed to requestRebind: ${e.message}", e)
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // Ignore our own package notifications to prevent self-trigger loops
        if (sbn.packageName == applicationContext.packageName) return

        // Ignore non-allowlisted packages (only log for WhatsApp packages)
        if (!whatsAppHandler.isWhatsAppPackage(sbn.packageName)) return

        // Process through WhatsAppNotificationHandler
        val extractedMessages = whatsAppHandler.extractMessages(applicationContext, sbn)

        // Log heartbeat onNotificationPosted for allowlisted WhatsApp package only
        serviceScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            val currentCount = db.diagnosticLogDao().getDiagnosticLogCount()
            DiagnosticsManager.recordHeartbeat(
                applicationContext,
                "NOTIFICATION_POSTED",
                "count: $currentCount, pkg: ${sbn.packageName}"
            )
        }

        if (extractedMessages.isEmpty()) return

        // Update last notification received time in diagnostics
        DiagnosticsManager.recordNotificationReceived(applicationContext, sbn.postTime)

        val isLogOnly = DiagnosticsManager.isLogOnlyMode(applicationContext)

        serviceScope.launch {
            val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())

            val db = AppDatabase.getInstance(applicationContext)

            for (msg in extractedMessages) {
                val filterResult = prefilter.prefilter(msg.cleanText)
                val extraction = if (filterResult.shouldParse) parser.parse(msg.cleanText) else null

                val parserOutputJson = extraction?.let { ext ->
                    JSONObject().apply {
                        put("type", ext.type)
                        put("title", ext.title)
                        put("start", ext.start)
                        put("start_date", ext.start_date)
                        put("time_unspecified", ext.time_unspecified)
                        put("ambiguous", ext.ambiguous)
                        put("confidence", ext.confidence)
                        put("source_quote", ext.source_quote)
                    }.toString()
                }

                // Record diagnostic log shape + parser output
                val diagLog = DiagnosticLogEntity(
                    id = UUID.randomUUID().toString(),
                    createdAt = nowIso,
                    packageName = msg.packageName,
                    chatTitle = msg.chatTitle,
                    sender = msg.sender,
                    isGroup = msg.isGroup,
                    rawText = msg.rawText,
                    prefilterResult = if (filterResult.shouldParse) "passed" else (filterResult.reason ?: "rejected"),
                    parserOutput = parserOutputJson,
                    mode = if (isLogOnly) "LOG_ONLY" else "NORMAL"
                )
                db.diagnosticLogDao().insertDiagnosticLog(diagLog)

                // Requirement 2: Log-only mode creates NO pending events or alarms
                if (isLogOnly) {
                    continue
                }

                // Normal Mode: persist raw message and ingest through pipeline
                val rawMsgId = "raw-${UUID.randomUUID()}"
                db.rawMessageDao().insertRawMessage(
                    RawMessageEntity(id = rawMsgId, rawText = msg.rawText, source = "whatsapp", createdAt = nowIso)
                )

                val result = pipeline.processMessage(msg.cleanText)

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
                        put("raw_text", msg.rawText)
                        put("raw_message_id", rawMsgId)
                        put("sender", msg.sender)
                        put("chat_title", msg.chatTitle)
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

                    showNotificationAlert(
                        "New Schedule Found: ${result.extraction?.title}",
                        "Tap to review & confirm: \"${result.extraction?.source_quote}\""
                    )
                }
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
