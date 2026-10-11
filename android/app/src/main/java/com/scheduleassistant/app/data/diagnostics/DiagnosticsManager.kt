package com.scheduleassistant.app.data.diagnostics

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.FileProvider
import com.scheduleassistant.app.data.db.AppDatabase
import com.scheduleassistant.app.data.db.DiagnosticLogEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        return checkpoint
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
