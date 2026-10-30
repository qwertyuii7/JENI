package com.scheduleassistant.app.data.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.scheduleassistant.app.data.db.AppDatabase
import com.scheduleassistant.app.data.db.DiagnosticLogEntity
import com.scheduleassistant.app.data.db.HeartbeatLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

object DiagnosticsManager {

    private const val PREFS_NAME = "schedule_assistant_diagnostics"
    private const val KEY_LAST_NOTIFICATION_TIME = "last_notification_time"
    private const val KEY_LAST_BOOT_RECOVERY_TIME = "last_boot_recovery_time"
    private const val KEY_LOG_ONLY_MODE = "log_only_mode"
    private const val KEY_ALLOWED_PACKAGES = "allowed_packages"
    private const val KEY_REMINDER_LEAD_MINUTES = "reminder_lead_minutes"
    private const val KEY_ALARM_LEAD_MINUTES = "alarm_lead_minutes"
    private const val KEY_ALARM_AUTO_STOP_MINUTES = "alarm_auto_stop_minutes"
    private const val KEY_LAST_LOGGED_EXIT_TIMESTAMP = "last_logged_exit_timestamp"
    private const val KEY_CHAT_MESSAGES_COUNT = "chat_messages_count"
    private const val KEY_SYSTEM_NOTIFICATIONS_COUNT = "system_notifications_count"
    private const val KEY_CAPTURE_ALL_CHATS = "capture_all_chats"
    private const val KEY_ALLOWED_CHATS = "allowed_chats"
    private const val KEY_FIRST_RUN_PROMPT_SHOWN = "first_run_prompt_shown"
    private const val KEY_SCHEDULED_ALERTS = "scheduled_alerts"

    val DEFAULT_ALLOWED_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

    fun getReminderLeadTimeMinutes(context: Context): Int {
        return getPrefs(context).getInt(KEY_REMINDER_LEAD_MINUTES, 15)
    }

    fun setReminderLeadTimeMinutes(context: Context, minutes: Int) {
        getPrefs(context).edit().putInt(KEY_REMINDER_LEAD_MINUTES, minutes).apply()
    }

    fun getAlarmLeadTimeMinutes(context: Context): Int {
        return getPrefs(context).getInt(KEY_ALARM_LEAD_MINUTES, 0)
    }

    fun setAlarmLeadTimeMinutes(context: Context, minutes: Int) {
        getPrefs(context).edit().putInt(KEY_ALARM_LEAD_MINUTES, minutes).apply()
    }

    fun getAlarmAutoStopMinutes(context: Context): Int {
        return getPrefs(context).getInt(KEY_ALARM_AUTO_STOP_MINUTES, 5)
    }

    fun setAlarmAutoStopMinutes(context: Context, minutes: Int) {
        getPrefs(context).edit().putInt(KEY_ALARM_AUTO_STOP_MINUTES, minutes).apply()
    }

    fun incrementChatMessageCount(context: Context): Int {
        val current = getChatMessageCount(context) + 1
        getPrefs(context).edit().putInt(KEY_CHAT_MESSAGES_COUNT, current).apply()
        return current
    }

    fun getChatMessageCount(context: Context): Int {
        return getPrefs(context).getInt(KEY_CHAT_MESSAGES_COUNT, 0)
    }

    fun incrementSystemNotificationCount(context: Context): Int {
        val current = getSystemNotificationCount(context) + 1
        getPrefs(context).edit().putInt(KEY_SYSTEM_NOTIFICATIONS_COUNT, current).apply()
        return current
    }

    fun getSystemNotificationCount(context: Context): Int {
        return getPrefs(context).getInt(KEY_SYSTEM_NOTIFICATIONS_COUNT, 0)
    }

    fun isFirstRunPromptShown(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_FIRST_RUN_PROMPT_SHOWN, false)
    }

    fun setFirstRunPromptShown(context: Context, shown: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_FIRST_RUN_PROMPT_SHOWN, shown).apply()
    }

    fun isCaptureAllChats(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_CAPTURE_ALL_CHATS, true)
    }

    fun setCaptureAllChats(context: Context, captureAll: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_CAPTURE_ALL_CHATS, captureAll).apply()
    }

    fun getAllowedChats(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_ALLOWED_CHATS, emptySet()) ?: emptySet()
    }

    fun setChatAllowed(context: Context, chatTitle: String, allowed: Boolean) {
        val trimmed = chatTitle.trim()
        if (trimmed.isEmpty()) return
        val current = getAllowedChats(context).toMutableSet()
        if (allowed) {
            current.add(trimmed)
        } else {
            current.remove(trimmed)
        }
        getPrefs(context).edit().putStringSet(KEY_ALLOWED_CHATS, current).apply()
    }

    fun isChatAllowlisted(context: Context, chatTitle: String?): Boolean {
        if (isCaptureAllChats(context)) return true
        if (chatTitle.isNullOrBlank()) return true
        return getAllowedChats(context).contains(chatTitle.trim())
    }

    data class ScheduledAlertRecord(
        val scheduleId: String,
        val type: String, // "Reminder" or "Alarm"
        val title: String,
        val triggerMs: Long
    )

    private val scheduledAlertsMap = java.util.concurrent.ConcurrentHashMap<String, ScheduledAlertRecord>()
    private var alertsLoaded = false

    private fun loadScheduledAlertsIfNeeded(context: Context) {
        if (alertsLoaded) return
        alertsLoaded = true
        try {
            val jsonStr = getPrefs(context).getString(KEY_SCHEDULED_ALERTS, null) ?: return
            val arr = JSONArray(jsonStr)
            val now = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val triggerMs = obj.getLong("triggerMs")
                if (triggerMs > now - 60000L) {
                    val rec = ScheduledAlertRecord(
                        scheduleId = obj.getString("scheduleId"),
                        type = obj.getString("type"),
                        title = obj.getString("title"),
                        triggerMs = triggerMs
                    )
                    scheduledAlertsMap[rec.scheduleId + "_" + rec.type] = rec
                }
            }
        } catch (_: Exception) {}
    }

    private fun saveScheduledAlerts(context: Context) {
        try {
            val now = System.currentTimeMillis()
            val arr = JSONArray()
            for (rec in scheduledAlertsMap.values) {
                if (rec.triggerMs > now - 60000L) {
                    val obj = JSONObject().apply {
                        put("scheduleId", rec.scheduleId)
                        put("type", rec.type)
                        put("title", rec.title)
                        put("triggerMs", rec.triggerMs)
                    }
                    arr.put(obj)
                }
            }
            getPrefs(context).edit().putString(KEY_SCHEDULED_ALERTS, arr.toString()).apply()
        } catch (_: Exception) {}
    }

    fun registerScheduledAlert(
        context: Context,
        scheduleId: String,
        type: String,
        title: String,
        triggerMs: Long
    ) {
        loadScheduledAlertsIfNeeded(context)
        val rec = ScheduledAlertRecord(scheduleId, type, title, triggerMs)
        scheduledAlertsMap[scheduleId + "_" + type] = rec
        saveScheduledAlerts(context)
    }

    fun unregisterScheduledAlerts(context: Context, scheduleId: String) {
        loadScheduledAlertsIfNeeded(context)
        val toRemove = scheduledAlertsMap.keys.filter { it.startsWith(scheduleId) }
        toRemove.forEach { scheduledAlertsMap.remove(it) }
        saveScheduledAlerts(context)
    }

    fun getActiveScheduledCount(context: Context): Int {
        loadScheduledAlertsIfNeeded(context)
        val now = System.currentTimeMillis()
        return scheduledAlertsMap.values.count { it.triggerMs > now }
    }

    fun getNextAlarmClockLabel(context: Context): String {
        return try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
            val nextAlarm = am?.nextAlarmClock ?: return "None"
            val triggerMs = nextAlarm.triggerTime
            val df = SimpleDateFormat("EEE, MMM d, h:mm a", Locale.US)
            val timeStr = df.format(Date(triggerMs))

            loadScheduledAlertsIfNeeded(context)
            val matched = scheduledAlertsMap.values.minByOrNull { Math.abs(it.triggerMs - triggerMs) }
            if (matched != null && Math.abs(matched.triggerMs - triggerMs) <= 3000L) {
                val label = if (matched.type.contains("alarm", ignoreCase = true)) "Alarm" else "Reminder"
                "$label: ${matched.title} ($timeStr)"
            } else {
                val pkg = nextAlarm.showIntent?.creatorPackage ?: "system"
                "Alarm ($pkg): $timeStr"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun canUseFullScreenIntent(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.canUseFullScreenIntent() ?: true
        } else {
            true
        }
    }

    private val _isListenerConnected = MutableStateFlow(false)
    val isListenerConnected: StateFlow<Boolean> = _isListenerConnected.asStateFlow()

    private val _isForegroundServiceRunning = MutableStateFlow(false)
    val isForegroundServiceRunning: StateFlow<Boolean> = _isForegroundServiceRunning.asStateFlow()

    fun setListenerConnected(connected: Boolean) {
        _isListenerConnected.value = connected
    }

    fun setForegroundServiceRunning(running: Boolean) {
        _isForegroundServiceRunning.value = running
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun recordNotificationReceived(context: Context, timestamp: Long = System.currentTimeMillis()) {
        getPrefs(context).edit().putLong(KEY_LAST_NOTIFICATION_TIME, timestamp).apply()
    }

    fun getLastNotificationReceivedTime(context: Context): Long? {
        val t = getPrefs(context).getLong(KEY_LAST_NOTIFICATION_TIME, -1L)
        return if (t > 0L) t else null
    }

    fun recordBootRecovery(context: Context, timestamp: Long = System.currentTimeMillis()) {
        getPrefs(context).edit().putLong(KEY_LAST_BOOT_RECOVERY_TIME, timestamp).apply()
    }

    fun getLastBootRecoveryTime(context: Context): Long? {
        val t = getPrefs(context).getLong(KEY_LAST_BOOT_RECOVERY_TIME, -1L)
        return if (t > 0L) t else null
    }

    fun isLogOnlyMode(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_LOG_ONLY_MODE, false) // Default OFF — cards are created and alerts fire normally
    }

    fun setLogOnlyMode(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_LOG_ONLY_MODE, enabled).apply()
    }

    suspend fun recordHourlyCheckpoint(context: Context, notes: String? = null): com.scheduleassistant.app.data.db.SoakCheckpointEntity {
        val db = AppDatabase.getInstance(context)
        val currentCount = db.soakCheckpointDao().getCheckpointCount()
        val hourIndex = currentCount + 1
        val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val checkpoint = com.scheduleassistant.app.data.db.SoakCheckpointEntity(
            id = UUID.randomUUID().toString(),
            hourIndex = hourIndex,
            timestamp = nowIso,
            listenerConnected = _isListenerConnected.value,
            lastNotificationTime = getLastNotificationReceivedTime(context),
            notificationsCapturedCount = db.diagnosticLogDao().getDiagnosticLogCount(),
            isLogOnlyMode = isLogOnlyMode(context),
            notes = notes
        )
        db.soakCheckpointDao().insertSoakCheckpoint(checkpoint)
        // Also log a manual checkpoint in heartbeat
        recordHeartbeat(context, "MANUAL_CHECKPOINT", notes ?: "Manual Checkpoint Hour #$hourIndex")
        return checkpoint
    }

    suspend fun recordHeartbeat(
        context: Context,
        eventType: String,
        details: String = "",
        timestampMs: Long = System.currentTimeMillis()
    ): HeartbeatLogEntity {
        val db = AppDatabase.getInstance(context)
        val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestampMs))

        val entity = HeartbeatLogEntity(
            id = UUID.randomUUID().toString(),
            timestamp = nowIso,
            eventType = eventType,
            details = details,
            listenerConnected = _isListenerConnected.value,
            notificationCount = db.diagnosticLogDao().getDiagnosticLogCount(),
            isLogOnlyMode = isLogOnlyMode(context)
        )
        db.heartbeatLogDao().insertHeartbeat(entity)
        return entity
    }

    fun logProcessStartAndExitInfo(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                recordHeartbeat(
                    context,
                    "PROCESS_START",
                    "Application.onCreate - PID ${android.os.Process.myPid()}"
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    val exitReasons = activityManager?.getHistoricalProcessExitReasons(context.packageName, 0, 10)
                    val prefs = getPrefs(context)
                    val lastLoggedExitTimestamp = prefs.getLong(KEY_LAST_LOGGED_EXIT_TIMESTAMP, 0L)
                    var maxExitTimestamp = lastLoggedExitTimestamp

                    exitReasons?.forEach { exitInfo ->
                        // Requirement 6: Dedupe EXIT_INFO heartbeat rows (never log same exit timestamp twice)
                        if (exitInfo.timestamp > lastLoggedExitTimestamp) {
                            val reasonStr = when (exitInfo.reason) {
                                ApplicationExitInfo.REASON_ANR -> "REASON_ANR"
                                ApplicationExitInfo.REASON_CRASH -> "REASON_CRASH"
                                ApplicationExitInfo.REASON_CRASH_NATIVE -> "REASON_CRASH_NATIVE"
                                ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "REASON_DEPENDENCY_DIED"
                                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "REASON_EXCESSIVE_RESOURCE_USAGE"
                                ApplicationExitInfo.REASON_EXIT_SELF -> "REASON_EXIT_SELF"
                                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "REASON_INITIALIZATION_FAILURE"
                                ApplicationExitInfo.REASON_LOW_MEMORY -> "REASON_LOW_MEMORY"
                                ApplicationExitInfo.REASON_OTHER -> "REASON_OTHER"
                                ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "REASON_PERMISSION_CHANGE"
                                ApplicationExitInfo.REASON_SIGNALED -> "REASON_SIGNALED"
                                ApplicationExitInfo.REASON_USER_REQUESTED -> "REASON_USER_REQUESTED"
                                else -> "UNKNOWN (${exitInfo.reason})"
                            }

                            val detailsJson = JSONObject().apply {
                                put("reason_code", exitInfo.reason)
                                put("reason_name", reasonStr)
                                put("timestamp", exitInfo.timestamp)
                                put("timestamp_iso", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                                    timeZone = TimeZone.getTimeZone("UTC")
                                }.format(Date(exitInfo.timestamp)))
                                put("description", exitInfo.description ?: "")
                                put("importance", exitInfo.importance)
                                put("status", exitInfo.status)
                                put("pss_kb", exitInfo.pss)
                                put("rss_kb", exitInfo.rss)
                            }

                            recordHeartbeat(
                                context,
                                "EXIT_INFO",
                                detailsJson.toString(),
                                exitInfo.timestamp
                            )
                            if (exitInfo.timestamp > maxExitTimestamp) {
                                maxExitTimestamp = exitInfo.timestamp
                            }
                        }
                    }
                    if (maxExitTimestamp > lastLoggedExitTimestamp) {
                        prefs.edit().putLong(KEY_LAST_LOGGED_EXIT_TIMESTAMP, maxExitTimestamp).apply()
                    }
                }
            } catch (e: Exception) {
                Log.e("DiagnosticsManager", "Failed to log process start or exit reasons: ${e.message}", e)
            }
        }
    }

    fun getAllowedPackages(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_ALLOWED_PACKAGES, DEFAULT_ALLOWED_PACKAGES) ?: DEFAULT_ALLOWED_PACKAGES
    }

    fun setAllowedPackages(context: Context, packages: Set<String>) {
        getPrefs(context).edit().putStringSet(KEY_ALLOWED_PACKAGES, packages).apply()
    }

    val phoneRegex = Regex("(?i)\\b(\\+?\\d{1,3}[- .]?)?\\(?\\d{3,5}\\)?[- .]?\\d{3,5}[- .]?\\d{3,5}\\b|\\b\\d{10,12}\\b")

    fun getPseudonym(originalName: String?, namePseudonymMap: MutableMap<String, String>): String? {
        if (originalName.isNullOrBlank()) return null
        val trimmed = originalName.trim()
        return namePseudonymMap.getOrPut(trimmed) {
            "[PERSON_${namePseudonymMap.size + 1}]"
        }
    }

    // Anonymization Engine for Log Export
    fun anonymizeLogEntry(
        entry: DiagnosticLogEntity,
        namePseudonymMap: MutableMap<String, String>
    ): JSONObject {

        var anonymizedText = entry.rawText
        // 1. Mask phone numbers
        anonymizedText = phoneRegex.replace(anonymizedText, "[PHONE_MASKED]")

        // 2. Mask sender if present in text
        if (!entry.sender.isNullOrBlank()) {
            val pseudoSender = getPseudonym(entry.sender, namePseudonymMap) ?: "[PERSON]"
            anonymizedText = anonymizedText.replace(entry.sender!!, pseudoSender)
        }

        val json = JSONObject()
        json.put("id", entry.id)
        json.put("timestamp", entry.createdAt)
        json.put("mode", entry.mode)

        val shape = JSONObject()
        shape.put("package_name", entry.packageName)
        shape.put("is_group", entry.isGroup)
        shape.put("sender_pseudonym", getPseudonym(entry.sender, namePseudonymMap))
        shape.put("chat_pseudonym", if (entry.isGroup) getPseudonym(entry.chatTitle, namePseudonymMap) else "[DIRECT_CHAT]")
        json.put("notification_shape", shape)

        json.put("anonymized_text", anonymizedText)
        json.put("prefilter_result", entry.prefilterResult)

        if (!entry.parserOutput.isNullOrBlank()) {
            try {
                val parserJson = JSONObject(entry.parserOutput!!)
                // Mask title if it contains phone numbers
                if (parserJson.has("title")) {
                    parserJson.put("title", phoneRegex.replace(parserJson.getString("title"), "[PHONE_MASKED]"))
                }
                json.put("parser_output", parserJson)
            } catch (_: Exception) {
                json.put("parser_output", entry.parserOutput)
            }
        } else {
            json.put("parser_output", JSONObject.NULL)
        }

        return json
    }

    fun getNextAlarmClockInfo(context: Context): String {
        return try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
            val nextAlarm = am?.nextAlarmClock
            if (nextAlarm != null) {
                val triggerMs = nextAlarm.triggerTime
                val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date(triggerMs))
                val pkg = nextAlarm.showIntent?.creatorPackage ?: "unknown"
                "$iso (pkg: $pkg)"
            } else {
                "None"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    suspend fun exportAnonymizedLogsToFile(context: Context): File {
        val db = AppDatabase.getInstance(context)
        val logs = db.diagnosticLogDao().getAllDiagnosticLogs()

        val logsDir = File(context.cacheDir, "logs").apply { mkdirs() }
        val dateStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(logsDir, "anonymized_schedule_diagnostics_$dateStamp.json")

        val nameMap = mutableMapOf<String, String>()
        val array = JSONArray()

        for (log in logs) {
            array.put(anonymizeLogEntry(log, nameMap))
        }

        val exportWrapper = JSONObject()
        exportWrapper.put("export_version", 2)
        exportWrapper.put("target_device", "Samsung Galaxy A15 5G")
        exportWrapper.put("os_version", "Android 16 / One UI 8.5")
        exportWrapper.put("exported_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date()))
        exportWrapper.put("total_records", array.length())
        exportWrapper.put("records", array)

        // Device & Runtime State
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        exportWrapper.put("can_use_full_screen_intent", canUseFullScreenIntent(context))
        exportWrapper.put("is_interactive", pm?.isInteractive ?: false)
        exportWrapper.put("is_keyguard_locked", km?.isKeyguardLocked ?: false)
        exportWrapper.put("next_alarm_clock", getNextAlarmClockInfo(context))
        exportWrapper.put("chat_messages_count", getChatMessageCount(context))
        exportWrapper.put("system_notifications_count", getSystemNotificationCount(context))
        exportWrapper.put("active_ringing_alarms", com.scheduleassistant.app.service.AlarmRingingService.getActiveAlarmsJson())

        // Requirement 4: Event log (type, entity, timestamp, seq, hlc - NO raw text)
        val events = db.eventDao().getAllEvents()
        val eventsArray = JSONArray()
        for (evt in events) {
            val evtObj = JSONObject()
            evtObj.put("id", evt.id)
            evtObj.put("type", evt.type)
            evtObj.put("entity_id", evt.entityId)
            evtObj.put("timestamp", evt.timestamp)
            evtObj.put("seq", evt.seq)
            evtObj.put("hlc", evt.hlc)
            eventsArray.put(evtObj)
        }
        exportWrapper.put("event_log", eventsArray)

        // Requirement 4: Debug-injection results (prefilter result, parser output, ambiguous flag)
        val debugInjectionsArray = JSONArray()
        for (log in logs) {
            if (log.mode == "DEBUG_INJECT" || log.packageName == "com.scheduleassistant.app.debug") {
                val diObj = JSONObject()
                diObj.put("id", log.id)
                diObj.put("timestamp", log.createdAt)
                diObj.put("prefilter_result", log.prefilterResult)
                var isAmbiguous = false
                if (!log.parserOutput.isNullOrBlank()) {
                    try {
                        val parserJson = JSONObject(log.parserOutput!!)
                        isAmbiguous = parserJson.optBoolean("ambiguous", false)
                        diObj.put("parser_output", parserJson)
                    } catch (_: Exception) {
                        diObj.put("parser_output", log.parserOutput)
                    }
                } else {
                    diObj.put("parser_output", JSONObject.NULL)
                }
                diObj.put("ambiguous", isAmbiguous)
                debugInjectionsArray.put(diObj)
            }
        }
        exportWrapper.put("debug_injections", debugInjectionsArray)

        val checkpoints = db.soakCheckpointDao().getAllSoakCheckpoints()
        val checkpointsArray = JSONArray()
        for (cp in checkpoints) {
            val cpObj = JSONObject()
            cpObj.put("hour_index", cp.hourIndex)
            cpObj.put("timestamp", cp.timestamp)
            cpObj.put("listener_connected", cp.listenerConnected)
            cpObj.put("last_notification_time", cp.lastNotificationTime ?: JSONObject.NULL)
            cpObj.put("notifications_captured_count", cp.notificationsCapturedCount)
            cpObj.put("is_log_only_mode", cp.isLogOnlyMode)
            cpObj.put("notes", cp.notes ?: JSONObject.NULL)
            checkpointsArray.put(cpObj)
        }
        exportWrapper.put("soak_checkpoints", checkpointsArray)

        // Requirement 4: Export heartbeat log
        val heartbeats = db.heartbeatLogDao().getAllHeartbeats()
        val heartbeatsArray = JSONArray()
        for (hb in heartbeats) {
            val hbObj = JSONObject()
            hbObj.put("id", hb.id)
            hbObj.put("timestamp", hb.timestamp)
            hbObj.put("event_type", hb.eventType)
            hbObj.put("details", hb.details)
            hbObj.put("listener_connected", hb.listenerConnected)
            hbObj.put("notification_count", hb.notificationCount)
            hbObj.put("is_log_only_mode", hb.isLogOnlyMode)
            heartbeatsArray.put(hbObj)
        }
        exportWrapper.put("heartbeat_logs", heartbeatsArray)

        // Requirement 4: Export exit reasons
        val exitReasonsArray = JSONArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val exitReasons = activityManager?.getHistoricalProcessExitReasons(context.packageName, 0, 10)
            exitReasons?.forEach { exitInfo ->
                val obj = JSONObject()
                obj.put("reason", exitInfo.reason)
                obj.put("description", exitInfo.description ?: "")
                obj.put("timestamp", exitInfo.timestamp)
                obj.put("importance", exitInfo.importance)
                obj.put("status", exitInfo.status)
                obj.put("pss", exitInfo.pss)
                obj.put("rss", exitInfo.rss)
                exitReasonsArray.put(obj)
            }
        }
        exportWrapper.put("historical_exit_reasons", exitReasonsArray)

        // Export alarm audit logs
        val audits = db.alarmAuditDao().getAllAudits()
        val auditsArray = JSONArray()
        for (a in audits) {
            val aObj = JSONObject()
            aObj.put("id", a.id)
            aObj.put("entity_id", a.entityId)
            aObj.put("trigger_time", a.triggerTime)
            aObj.put("api", a.api)
            aObj.put("result", a.result)
            aObj.put("error_message", a.errorMessage ?: JSONObject.NULL)
            aObj.put("timestamp", a.timestamp)
            auditsArray.put(aObj)
        }
        exportWrapper.put("alarm_audit_logs", auditsArray)

        // Requirement 3: Export Ingestion Decisions (group/DM, allowlisted or not, disposition, prefilter, parser - NO raw text)
        val decisions = db.ingestionDecisionDao().getRecentDecisions(500)
        val decisionsArray = JSONArray()
        for (d in decisions) {
            val dObj = JSONObject()
            dObj.put("id", d.id)
            dObj.put("timestamp", d.timestamp)
            dObj.put("package_name", d.packageName)
            dObj.put("chat_title", getPseudonym(d.chatTitle, nameMap))
            dObj.put("is_group", d.isGroup)
            dObj.put("is_allowlisted", d.isAllowlisted)
            dObj.put("disposition", d.disposition)
            dObj.put("prefilter_result", d.prefilterResult ?: JSONObject.NULL)
            if (!d.parserOutput.isNullOrBlank()) {
                try {
                    val pJson = JSONObject(d.parserOutput)
                    if (pJson.has("title")) {
                        pJson.put("title", phoneRegex.replace(pJson.getString("title"), "[PHONE_MASKED]"))
                    }
                    dObj.put("parser_output", pJson)
                } catch (_: Exception) {
                    dObj.put("parser_output", d.parserOutput)
                }
            } else {
                dObj.put("parser_output", JSONObject.NULL)
            }
            decisionsArray.put(dObj)
        }
        exportWrapper.put("ingestion_decisions", decisionsArray)
        exportWrapper.put("next_alarm_clock_label", getNextAlarmClockLabel(context))
        exportWrapper.put("scheduled_count", getActiveScheduledCount(context))
        exportWrapper.put("capture_all_chats", isCaptureAllChats(context))

        file.writeText(exportWrapper.toString(2))
        return file
    }

    fun shareExportFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Schedule Assistant Diagnostic Logs (Anonymized)")
            putExtra(Intent.EXTRA_TEXT, "Exported anonymized notification diagnostic logs from Schedule Assistant.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(shareIntent, "Share Anonymized Logs"))
    }
}

class HeartbeatWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val isIgnoring = pm?.isIgnoringBatteryOptimizations(applicationContext.packageName) ?: false
        val db = AppDatabase.getInstance(applicationContext)
        val currentNotificationCount = db.diagnosticLogDao().getDiagnosticLogCount()
        val details = JSONObject().apply {
            put("tick", "15min_workmanager")
            put("isIgnoringBatteryOptimizations", isIgnoring)
            put("systemUptimeMs", SystemClock.elapsedRealtime())
            put("aggregate_notifications", currentNotificationCount)
        }.toString()
        DiagnosticsManager.recordHeartbeat(applicationContext, "WORKMANAGER_TICK", details)
        return Result.success()
    }
}
