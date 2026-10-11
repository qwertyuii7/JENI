package com.scheduleassistant.app.data.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
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

    val DEFAULT_ALLOWED_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

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
        return getPrefs(context).getBoolean(KEY_LOG_ONLY_MODE, true) // Default ON for soak testing
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
                    exitReasons?.forEach { exitInfo ->
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

    // Anonymization Engine for Log Export
    fun anonymizeLogEntry(
        entry: DiagnosticLogEntity,
        namePseudonymMap: MutableMap<String, String>
    ): JSONObject {
        fun getPseudonym(originalName: String?): String? {
            if (originalName.isNullOrBlank()) return null
            val trimmed = originalName.trim()
            return namePseudonymMap.getOrPut(trimmed) {
                "[PERSON_${namePseudonymMap.size + 1}]"
            }
        }

        // Phone number regex: international + national formats
        val phoneRegex = Regex("(?i)\\b(\\+?\\d{1,3}[- .]?)?\\(?\\d{3,5}\\)?[- .]?\\d{3,5}[- .]?\\d{3,5}\\b|\\b\\d{10,12}\\b")

        var anonymizedText = entry.rawText
        // 1. Mask phone numbers
        anonymizedText = phoneRegex.replace(anonymizedText, "[PHONE_MASKED]")

        // 2. Mask sender if present in text
        if (!entry.sender.isNullOrBlank()) {
            val pseudoSender = getPseudonym(entry.sender) ?: "[PERSON]"
            anonymizedText = anonymizedText.replace(entry.sender!!, pseudoSender)
        }

        val json = JSONObject()
        json.put("id", entry.id)
        json.put("timestamp", entry.createdAt)
        json.put("mode", entry.mode)

        val shape = JSONObject()
        shape.put("package_name", entry.packageName)
        shape.put("is_group", entry.isGroup)
        shape.put("sender_pseudonym", getPseudonym(entry.sender))
        shape.put("chat_pseudonym", if (entry.isGroup) getPseudonym(entry.chatTitle) else "[DIRECT_CHAT]")
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
        exportWrapper.put("export_version", 1)
        exportWrapper.put("target_device", "Samsung Galaxy A15 5G")
        exportWrapper.put("os_version", "Android 16 / One UI 8.5")
        exportWrapper.put("exported_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date()))
        exportWrapper.put("total_records", array.length())
        exportWrapper.put("records", array)

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
