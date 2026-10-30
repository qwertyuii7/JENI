package com.scheduleassistant.app.voice

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import com.scheduleassistant.app.data.db.AppDatabase
import com.scheduleassistant.app.data.db.SyncEventEntity
import com.scheduleassistant.app.data.diagnostics.DiagnosticsManager
import com.scheduleassistant.app.data.model.ScheduleEntity
import com.scheduleassistant.app.data.model.ScheduleStatus
import com.scheduleassistant.app.domain.TriageLane
import com.scheduleassistant.app.engine.HlcClock
import com.scheduleassistant.app.service.AlarmScheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

enum class VoiceStatus {
    IDLE,
    GENERATING,
    SPEAKING,
    ERROR
}

data class VoiceOption(
    val id: String,
    val name: String,
    val description: String,
    val isOffline: Boolean = false
)

data class ParsedAlarmCommand(
    val title: String,
    val targetCalendar: Calendar,
    val isoStart: String,
    val friendlyDay: String,
    val friendlyTime: String
)

sealed class VoiceAction {
    data class CreateAlarm(val cmd: ParsedAlarmCommand) : VoiceAction()
    data class DeleteAlarm(val targetSchedule: ScheduleEntity, val reason: String) : VoiceAction()
    data class DeleteAllAlarms(val targets: List<ScheduleEntity>) : VoiceAction()
    data class UpdateAlarm(
        val targetSchedule: ScheduleEntity,
        val newCalendar: Calendar,
        val newIsoStart: String,
        val newFriendlyTime: String
    ) : VoiceAction()
    data class ConfirmSchedule(val targetSchedule: ScheduleEntity) : VoiceAction()
    data class ConfirmAllPending(val targets: List<ScheduleEntity>) : VoiceAction()
    data class ReadAlarms(val targetDay: String, val alarms: List<ScheduleEntity>) : VoiceAction()
    data class ReadInbox(val pendingItems: List<ScheduleEntity>) : VoiceAction()
    data class ReadSchedule(val query: String) : VoiceAction()
    data class ConversationalResponse(val text: String) : VoiceAction()
}

object VoiceSecretaryManager {

    private const val TAG = "VoiceSecretary"
    private const val PREFS_NAME = "schedule_assistant_voice_prefs"
    private const val KEY_VOICE_ENABLED = "voice_enabled"
    private const val KEY_ELEVENLABS_KEY = "elevenlabs_api_key"
    private const val KEY_SELECTED_VOICE_ID = "selected_voice_id"
    private const val KEY_AUTO_SPEAK_CONFIRMATION = "auto_speak_confirmation"

    // Default API Key provided by user
    const val DEFAULT_API_KEY = "sk_8ebeccda0d237cd55a02c84d9ebbb8f34e8d6b396b369bae"
    const val DEFAULT_VOICE_ID = "EXAVITQu4vr4xnSDxMaL" // Sarah (Warm & Clear, Free Tier verified)

    val AVAILABLE_VOICES = listOf(
        VoiceOption("EXAVITQu4vr4xnSDxMaL", "Sarah", "Warm, clear & confident (ElevenLabs)", isOffline = false),
        VoiceOption("pFZP5JQG7iQjIQuC4Bku", "Lily", "Soft, calm & conversational (ElevenLabs)", isOffline = false),
        VoiceOption("JBFqnCBsd6RMkjVDRZzb", "George", "Crisp, professional international (ElevenLabs)", isOffline = false),
        VoiceOption("ON_DEVICE", "Android Offline TTS", "100% Offline Google Text-to-Speech", isOffline = true)
    )

    private val _status = MutableStateFlow(VoiceStatus.IDLE)
    val status: StateFlow<VoiceStatus> = _status.asStateFlow()

    private val _lastSpokenText = MutableStateFlow<String?>(null)
    val lastSpokenText: StateFlow<String?> = _lastSpokenText.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var androidTts: TextToSpeech? = null
    private var isTtsInitialized = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        if (androidTts == null) {
            androidTts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val result = androidTts?.setLanguage(Locale("en", "IN"))
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        androidTts?.language = Locale.US
                    }
                    isTtsInitialized = true
                    Log.d(TAG, "Android local TTS initialized successfully")
                } else {
                    Log.w(TAG, "Android local TTS initialization failed code: $status")
                }
            }
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isVoiceEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_VOICE_ENABLED, true)
    }

    fun setVoiceEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_VOICE_ENABLED, enabled).apply()
    }

    fun getApiKey(context: Context): String {
        return getPrefs(context).getString(KEY_ELEVENLABS_KEY, DEFAULT_API_KEY) ?: DEFAULT_API_KEY
    }

    fun setApiKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_ELEVENLABS_KEY, key.trim()).apply()
    }

    fun getSelectedVoiceId(context: Context): String {
        return getPrefs(context).getString(KEY_SELECTED_VOICE_ID, DEFAULT_VOICE_ID) ?: DEFAULT_VOICE_ID
    }

    fun setSelectedVoiceId(context: Context, voiceId: String) {
        getPrefs(context).edit().putString(KEY_SELECTED_VOICE_ID, voiceId).apply()
    }

    fun isAutoSpeakConfirmation(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_SPEAK_CONFIRMATION, true)
    }

    fun setAutoSpeakConfirmation(context: Context, auto: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_SPEAK_CONFIRMATION, auto).apply()
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun stop() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            mediaPlayer = null
            androidTts?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio", e)
        } finally {
            _status.value = VoiceStatus.IDLE
        }
    }

    fun speak(
        context: Context,
        text: String,
        onComplete: () -> Unit = {}
    ) {
        if (text.isBlank()) return
        val appContext = context.applicationContext

        if (!isVoiceEnabled(appContext)) {
            Log.d(TAG, "Voice secretary disabled in settings; skipping audio")
            return
        }

        stop()
        _lastSpokenText.value = text
        _status.value = VoiceStatus.GENERATING

        val voiceId = getSelectedVoiceId(appContext)
        val apiKey = getApiKey(appContext)
        val hasNet = isNetworkAvailable(appContext)

        // If user chose on-device TTS or device is offline, use native TTS
        if (voiceId == "ON_DEVICE" || !hasNet || apiKey.isBlank()) {
            speakWithAndroidTts(appContext, text, onComplete)
            return
        }

        scope.launch {
            try {
                val cacheDir = File(appContext.cacheDir, "voice_cache").apply { mkdirs() }
                val cacheKey = sha256("$voiceId:$text")
                val cachedFile = File(cacheDir, "$cacheKey.mp3")

                if (cachedFile.exists() && cachedFile.length() > 0) {
                    Log.d(TAG, "Playing cached voice file: ${cachedFile.name} (${cachedFile.length()} bytes)")
                    playAudioFile(cachedFile, onComplete)
                    return@launch
                }

                // Call ElevenLabs API
                val url = URL("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 15000
                    doOutput = true
                    doInput = true
                    setRequestProperty("xi-api-key", apiKey)
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "audio/mpeg")
                }

                val body = JSONObject().apply {
                    put("text", text)
                    put("model_id", "eleven_turbo_v2_5")
                    put("voice_settings", JSONObject().apply {
                        put("stability", 0.5)
                        put("similarity_boost", 0.75)
                    })
                }

                conn.outputStream.use { os ->
                    os.write(body.toString().toByteArray(Charsets.UTF_8))
                    os.flush()
                }

                val responseCode = conn.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val tempFile = File(cacheDir, "$cacheKey.tmp")
                    conn.inputStream.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (tempFile.renameTo(cachedFile)) {
                        Log.d(TAG, "ElevenLabs audio saved successfully: ${cachedFile.length()} bytes")
                        playAudioFile(cachedFile, onComplete)
                    } else {
                        playAudioFile(tempFile, onComplete)
                    }
                } else {
                    val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
                    Log.w(TAG, "ElevenLabs TTS failed: $responseCode - $err. Falling back to native TTS.")
                    withContext(Dispatchers.Main) {
                        speakWithAndroidTts(appContext, text, onComplete)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during voice synthesis; falling back to offline TTS", e)
                withContext(Dispatchers.Main) {
                    speakWithAndroidTts(appContext, text, onComplete)
                }
            }
        }
    }

    private fun playAudioFile(file: File, onComplete: () -> Unit) {
        mainHandler.post {
            try {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .build()
                    )
                    setDataSource(file.absolutePath)
                    setOnPreparedListener { mp ->
                        _status.value = VoiceStatus.SPEAKING
                        mp.start()
                    }
                    setOnCompletionListener { mp ->
                        mp.release()
                        mediaPlayer = null
                        _status.value = VoiceStatus.IDLE
                        onComplete()
                    }
                    setOnErrorListener { mp, what, extra ->
                        Log.e(TAG, "MediaPlayer error: $what, extra: $extra")
                        mp.release()
                        mediaPlayer = null
                        _status.value = VoiceStatus.ERROR
                        false
                    }
                    prepareAsync()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start media player", e)
                _status.value = VoiceStatus.ERROR
            }
        }
    }

    private fun speakWithAndroidTts(context: Context, text: String, onComplete: () -> Unit) {
        if (androidTts == null || !isTtsInitialized) {
            initialize(context)
        }
        _status.value = VoiceStatus.SPEAKING
        val params = Bundle()
        val utteranceId = UUID.randomUUID().toString()
        androidTts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)

        // Reset status after estimated duration
        val words = text.split("\\s+".toRegex()).size
        val durationMs = ((words / 2.5f) * 1000).toLong().coerceAtLeast(1500L)
        mainHandler.postDelayed({
            if (_status.value == VoiceStatus.SPEAKING) {
                _status.value = VoiceStatus.IDLE
            }
            onComplete()
        }, durationMs)
    }

    // ─── Query Intelligence for Voice Assistant ───────────────────────────────────

    fun buildScheduleAnswer(
        query: String,
        schedules: List<ScheduleEntity>
    ): String {
        val cleanQuery = query.lowercase().trim()
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        val calTomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val tomorrowStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calTomorrow.time)

        // 0. Voice Alarm Scheduling Command
        val alarmCmd = parseAlarmCommand(query)
        if (alarmCmd != null) {
            return "Done! I've set an alarm for ${alarmCmd.friendlyDay} at ${alarmCmd.friendlyTime} for '${alarmCmd.title}'."
        }

        // Conversational Greetings & Identity
        val isGreeting = cleanQuery == "hi" || cleanQuery == "hello" || cleanQuery == "hey" ||
                cleanQuery.startsWith("hi ") || cleanQuery.startsWith("hello ") ||
                cleanQuery.contains("who are you") || cleanQuery.contains("what can you do") ||
                cleanQuery == "help" || cleanQuery.contains("namaste")
        if (isGreeting) {
            return "Hello! I'm your schedule secretary. You can ask me what is on your schedule for today or tomorrow, check your pending reminders, or ask for your morning briefing."
        }

        // Gratitude & Farewell
        if (cleanQuery.contains("thank") || cleanQuery.contains("bye") || cleanQuery.contains("goodbye")) {
            return "You're very welcome! I'm here anytime you need help staying on schedule. Have a great day!"
        }

        // Morning Briefing request
        if (cleanQuery.contains("morning briefing") || cleanQuery.contains("brief me") || cleanQuery == "morning") {
            return buildMorningBriefing(schedules)
        }

        // Pending / Inbox query
        if (cleanQuery.contains("pending") || cleanQuery.contains("inbox") || cleanQuery.contains("unconfirmed") || cleanQuery.contains("triage") || cleanQuery.contains("review")) {
            val pendingList = schedules.filter { it.status == ScheduleStatus.PENDING && !it.deleted }
            return if (pendingList.isEmpty()) {
                "You have no pending items to review. Your triage inbox is completely clear!"
            } else {
                val count = pendingList.size
                val firstTitle = pendingList.first().title
                "You have $count pending reminder${if (count > 1) "s" else ""} awaiting your review in your inbox, including $firstTitle."
            }
        }

        // Free time / Availability query
        if (cleanQuery.contains("free") || cleanQuery.contains("available") || cleanQuery.contains("busy")) {
            val todayEvents = schedules.filter { sched ->
                sched.status == ScheduleStatus.CONFIRMED &&
                        !sched.deleted &&
                        (sched.start?.startsWith(todayStr) == true || sched.start_date == todayStr)
            }.sortedBy { it.start ?: "" }
            return if (todayEvents.isEmpty()) {
                "You are completely free today! Your schedule is wide open with no events."
            } else {
                val firstTime = formatFriendlyTime(todayEvents.first().start)
                "You have ${todayEvents.size} event${if (todayEvents.size > 1) "s" else ""} today. You are busy from $firstTime for ${todayEvents.first().title}."
            }
        }

        val isTomorrowQuery = cleanQuery.contains("tomorrow") || cleanQuery.contains("kal")
        val targetDateStr = if (isTomorrowQuery) tomorrowStr else todayStr
        val dayWord = if (isTomorrowQuery) "tomorrow" else "today"

        val relevantEvents = schedules.filter { sched ->
            sched.status == ScheduleStatus.CONFIRMED &&
                    !sched.deleted &&
                    (sched.start?.startsWith(targetDateStr) == true || sched.start_date == targetDateStr)
        }.sortedBy { it.start ?: "" }

        if (relevantEvents.isEmpty()) {
            return "You have nothing scheduled for $dayWord! Your agenda is completely clear."
        }

        val count = relevantEvents.size
        val sb = StringBuilder()
        sb.append("You have $count event${if (count > 1) "s" else ""} for $dayWord. ")

        relevantEvents.forEachIndexed { index, event ->
            val timeFriendly = formatFriendlyTime(event.start)
            val ordinal = when (index) {
                0 -> "First,"
                1 -> "Next,"
                2 -> "Then,"
                else -> "Also,"
            }
            sb.append("$ordinal ${event.title}")
            if (timeFriendly.isNotBlank()) {
                sb.append(" at $timeFriendly")
            }
            sb.append(". ")
        }

        return sb.toString().trim()
    }

    fun buildMorningBriefing(schedules: List<ScheduleEntity>): String {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val todayEvents = schedules.filter { sched ->
            sched.status == ScheduleStatus.CONFIRMED &&
                    !sched.deleted &&
                    (sched.start?.startsWith(todayStr) == true || sched.start_date == todayStr)
        }
        val pendingCount = schedules.count { it.status == ScheduleStatus.PENDING && !it.deleted }

        if (todayEvents.isEmpty() && pendingCount == 0) {
            return "Good morning! You are all caught up for today with no events or pending items."
        }

        val sb = StringBuilder("Good morning! ")
        if (todayEvents.isNotEmpty()) {
            sb.append("You have ${todayEvents.size} event${if (todayEvents.size > 1) "s" else ""} today: ")
            todayEvents.take(3).forEach { ev ->
                val time = formatFriendlyTime(ev.start)
                sb.append("${ev.title}${if (time.isNotBlank()) " at $time" else ""}, ")
            }
        } else {
            sb.append("No classes or meetings scheduled for today. ")
        }

        if (pendingCount > 0) {
            sb.append("You have $pendingCount new item${if (pendingCount > 1) "s" else ""} to review in your inbox.")
        }

        return sb.toString().trim()
    }

    fun formatFriendlyTime(isoDate: String?): String {
        if (isoDate.isNullOrBlank()) return ""
        return try {
            val clean = isoDate.replace("Z", "+00:00")
            val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            val d = df.parse(clean)
            if (d != null) {
                SimpleDateFormat("h:mm a", Locale.US).format(d)
            } else ""
        } catch (_: Exception) {
            try {
                val parts = isoDate.split("T")
                if (parts.size > 1) {
                    parts[1].substring(0, 5)
                } else ""
            } catch (_: Exception) { "" }
        }
    }

    // ─── Voice-to-Alarm Scheduling Engine ──────────────────────────────────────────

    fun parseAlarmCommand(query: String): ParsedAlarmCommand? {
        val lower = query.lowercase().trim()
        val isAlarmRequest = lower.contains("alarm") ||
                lower.contains("wake me up") ||
                lower.contains("wake me") ||
                lower.contains("remind me") ||
                lower.contains("reminder") ||
                (lower.contains("baje") && (lower.contains("lagao") || lower.contains("set") || lower.contains("karo") || lower.contains("ka")))

        if (!isAlarmRequest) return null

        val now = Calendar.getInstance()

        // ─── 1. Relative Offsets (e.g. "after 5 min", "in 10 minutes", "after 1 hour", "5 min baad") ───
        val relMinMatch = Regex("(?i)\\b(?:after|in)\\s+(\\d+)\\s*(?:min|mins|minute|minutes)\\b").find(lower)
            ?: Regex("(?i)\\b(\\d+)\\s*(?:min|mins|minute|minutes)\\s*(?:baad|later|from now)\\b").find(lower)

        val relHourMatch = Regex("(?i)\\b(?:after|in)\\s+(\\d+)\\s*(?:hour|hours|hr|hrs)\\b").find(lower)
            ?: Regex("(?i)\\b(\\d+)\\s*(?:hour|hours|hr|hrs)\\s*(?:baad|later|from now)\\b").find(lower)

        val isHalfHour = Regex("(?i)\\b(?:after|in)\\s+(?:half\\s+an?\\s+hour|30\\s*mins?)\\b").containsMatchIn(lower) ||
            Regex("(?i)\\b(?:aadhe|aadha)\\s+ghante?\\s*(?:baad|me)?\\b").containsMatchIn(lower)
        val isOneHour = Regex("(?i)\\b(?:after|in)\\s+(?:an?\\s+hour|one\\s+hour)\\b").containsMatchIn(lower) ||
            Regex("(?i)\\b(?:ek|1)\\s+ghante?\\s*(?:baad|me)?\\b").containsMatchIn(lower)

        val relOffsetMinutes: Int? = when {
            relMinMatch != null -> relMinMatch.groupValues[1].toInt()
            relHourMatch != null -> relHourMatch.groupValues[1].toInt() * 60
            isHalfHour -> 30
            isOneHour -> 60
            else -> null
        }

        if (relOffsetMinutes != null && relOffsetMinutes > 0) {
            val targetCal = (now.clone() as Calendar).apply {
                add(Calendar.MINUTE, relOffsetMinutes)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val cleanForTitle = lower
                .replace(Regex("(?i)\\b(?:set\\s+an?\\s+alarm|set\\s+alarm|alarm|wake\\s+me\\s+up|wake\\s+me|remind\\s+me|reminder)\\b"), "")
                .replace(Regex("(?i)\\b(?:after|in)\\s+\\d+\\s*(?:min|mins|minute|minutes|hour|hours|hr|hrs)\\b"), "")
                .replace(Regex("(?i)\\b\\d+\\s*(?:min|mins|minute|minutes|hour|hours|hr|hrs)\\s*(?:baad|later|from now)\\b"), "")
                .replace(Regex("(?i)\\b(?:after|in)\\s+half\\s+an?\\s+hour\\b"), "")
                .replace(Regex("(?i)\\b(?:after|in)\\s+an?\\s+hour\\b"), "")
                .replace(Regex("(?i)\\b(?:aadhe|aadha|ek|1)\\s+ghante?\\s*(?:baad|me)?\\b"), "")
                .replace(Regex("(?i)\\bfor\\s+me\\b"), "")
                .trim()

            val forMatch = Regex("(?i)\\b(?:for|to|about)\\s+([a-zA-Z0-9\\s]{2,30})$").find(cleanForTitle)
            val rawTitle = forMatch?.groupValues?.get(1)?.trim()

            val title = when {
                !rawTitle.isNullOrBlank() -> rawTitle.split("\\s+".toRegex()).joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                }
                lower.contains("wake me up") || lower.contains("wake me") -> "Wake Up"
                else -> "Alarm"
            }

            val isoStart = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(targetCal.time)
            val friendlyTime = SimpleDateFormat("h:mm a", Locale.US).format(targetCal.time)
            val friendlyDay = if (targetCal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)) "today" else "tomorrow"

            return ParsedAlarmCommand(
                title = title,
                targetCalendar = targetCal,
                isoStart = isoStart,
                friendlyDay = friendlyDay,
                friendlyTime = friendlyTime
            )
        }

        // ─── 2. Absolute Time Parsing (e.g. 7:00 AM, 5 PM, 6:30) ───
        val isTomorrow = lower.contains("tomorrow") || lower.contains("kal")

        // Match patterns like "7:30 am", "7 am", "17:00", "7:30", "7"
        val timeRegex = Regex("(?i)\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|o'clock|baje)?\\b")
        val match = timeRegex.findAll(lower).firstOrNull { m ->
            val h = m.groupValues[1].toInt()
            h in 1..24
        } ?: return null

        var hour = match.groupValues[1].toInt()
        val min = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        val ampm = match.groupValues[3].lowercase()

        if (ampm == "pm" && hour < 12) {
            hour += 12
        } else if (ampm == "am" && hour == 12) {
            hour = 0
        } else if (ampm.isEmpty() || ampm == "baje" || ampm == "o'clock") {
            if (lower.contains("subah") || lower.contains("morning")) {
                if (hour == 12) hour = 0
            } else if (lower.contains("shaam") || lower.contains("raat") || lower.contains("dopahar") ||
                lower.contains("evening") || lower.contains("night") || lower.contains("afternoon")) {
                if (hour < 12) hour += 12
            } else if (lower.contains("wake me up") && hour in 4..11) {
                // Wake up 6 -> 6 AM
            } else if (hour in 1..6) {
                val currentHour = now.get(Calendar.HOUR_OF_DAY)
                if (currentHour in 12..23) {
                    if (hour + 12 > currentHour) {
                        hour += 12
                    }
                }
            }
        }

        val targetCal = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, min)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (isTomorrow) {
            targetCal.add(Calendar.DAY_OF_YEAR, 1)
        } else {
            if (targetCal.before(now)) {
                targetCal.add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        // Title extraction: check after time match first, then before
        val timeEnd = match.range.last + 1
        val afterTime = if (timeEnd < query.length) query.substring(timeEnd).trim() else ""
        val forAfterMatch = Regex("(?i)\\b(?:for|to|about)\\s+([a-zA-Z0-9\\s]{2,30})$").find(afterTime)

        val rawTitle = if (forAfterMatch != null) {
            forAfterMatch.groupValues[1].trim()
        } else {
            val beforeTime = query.substring(0, match.range.first).trim()
                .replace(Regex("(?i)\\s+(?:at|on|for)$"), "")
            val forBeforeMatch = Regex("(?i)\\b(?:for|to|about)\\s+([a-zA-Z0-9\\s]{2,30})$").find(beforeTime)
            forBeforeMatch?.groupValues?.get(1)?.trim()
        }

        val title = when {
            !rawTitle.isNullOrBlank() -> rawTitle.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            lower.contains("wake me up") || lower.contains("wake me") -> "Wake Up"
            else -> "Alarm"
        }

        val isoStart = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(targetCal.time)
        val friendlyTime = SimpleDateFormat("h:mm a", Locale.US).format(targetCal.time)
        val friendlyDay = if (targetCal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)) "today" else "tomorrow"

        return ParsedAlarmCommand(
            title = title,
            targetCalendar = targetCal,
            isoStart = isoStart,
            friendlyDay = friendlyDay,
            friendlyTime = friendlyTime
        )
    }

    suspend fun scheduleAlarmFromVoice(
        context: Context,
        cmd: ParsedAlarmCommand
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val appContext = context.applicationContext
            val db = AppDatabase.getInstance(appContext)
            val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())

            val deviceId = "android-phone"
            val hlcNow = HlcClock(deviceId).now()
            val nextSeq = db.eventDao().getNextSeq(deviceId)
            val newScheduleId = "sched-${UUID.randomUUID()}"

            // 1. event_extracted
            val extractPayload = JSONObject().apply {
                put("title", cmd.title)
                put("start", cmd.isoStart)
                put("time_unspecified", false)
                put("lane", "SCHEDULE")
                put("confidence", 1.0)
                put("source_quote", "Voice Command")
            }
            val extractEvent = SyncEventEntity(
                id = UUID.randomUUID().toString(),
                timestamp = nowIso,
                deviceId = deviceId,
                seq = nextSeq,
                hlc = hlcNow,
                type = "event_extracted",
                entityId = newScheduleId,
                payload = extractPayload.toString()
            )
            db.eventDao().insertEvent(extractEvent)

            // 2. event_confirmed
            val confirmPayload = JSONObject().apply {
                put("confirmed_at", nowIso)
            }
            val confirmEvent = SyncEventEntity(
                id = UUID.randomUUID().toString(),
                timestamp = nowIso,
                deviceId = deviceId,
                seq = nextSeq + 1,
                hlc = hlcNow,
                type = "event_confirmed",
                entityId = newScheduleId,
                payload = confirmPayload.toString()
            )
            db.eventDao().insertEvent(confirmEvent)

            // 3. alarm_set
            val alarmPayload = JSONObject().apply {
                put("schedule_id", newScheduleId)
                put("title", cmd.title)
                put("trigger_time", cmd.isoStart)
                put("type", "audible_alarm")
            }
            val alarmEntity = SyncEventEntity(
                id = UUID.randomUUID().toString(),
                timestamp = nowIso,
                deviceId = deviceId,
                seq = nextSeq + 2,
                hlc = hlcNow,
                type = "alarm_set",
                entityId = "alarm-$newScheduleId",
                payload = alarmPayload.toString()
            )
            db.eventDao().insertEvent(alarmEntity)

            // 4. Arm real device alarm via AlarmScheduler
            val schedule = ScheduleEntity(
                id = newScheduleId,
                title = cmd.title,
                start = cmd.isoStart,
                status = ScheduleStatus.CONFIRMED,
                lane = TriageLane.SCHEDULE,
                has_alarm = true
            )
            withContext(Dispatchers.Main) {
                val scheduler = AlarmScheduler(appContext)
                scheduler.scheduleAlertsForSchedule(schedule, enableAlarm = true)
            }
            Log.d(TAG, "Voice alarm armed successfully for ${cmd.isoStart} (${cmd.title})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule alarm from voice", e)
            false
        }
    }

    fun isMatchingTime(isoDate: String?, targetHour: Int, targetMinute: Int): Boolean {
        if (isoDate.isNullOrBlank()) return false
        return try {
            val clean = isoDate.replace("Z", "+00:00")
            val d = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(clean) ?: return false
            val cal = Calendar.getInstance().apply { time = d }
            val h = cal.get(Calendar.HOUR_OF_DAY)
            val m = cal.get(Calendar.MINUTE)
            (h == targetHour && m == targetMinute) ||
                    (targetHour in 1..11 && (h % 12 == targetHour % 12) && m == targetMinute)
        } catch (_: Exception) {
            val hStr = "%02d".format(targetHour)
            val mStr = "%02d".format(targetMinute)
            isoDate.contains("T$hStr:$mStr")
        }
    }

    fun parseIsoDateOrNow(isoDate: String?): Date {
        if (isoDate.isNullOrBlank()) return Date()
        return try {
            val clean = isoDate.replace("Z", "+00:00")
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(clean) ?: Date()
        } catch (_: Exception) {
            Date()
        }
    }

    fun resolveVoiceAction(
        query: String,
        schedules: List<ScheduleEntity>
    ): VoiceAction {
        val lower = query.lowercase().trim()
        val activeAlarms = schedules.filter {
            it.status == ScheduleStatus.CONFIRMED && !it.deleted && (it.has_alarm || it.start != null)
        }
        val pendingItems = schedules.filter {
            it.status == ScheduleStatus.PENDING && !it.deleted
        }

        // 1. DELETE / CANCEL ALARM INTENT
        val isDeleteIntent = Regex("(?i)\\b(cancel|delete|remove|turn off|stop|band karo|hatao|dismiss)\\b").containsMatchIn(lower) &&
                (lower.contains("alarm") || lower.contains("wake up") || lower.contains("reminder") || lower.contains("baje"))

        if (isDeleteIntent) {
            val isAll = lower.contains("all alarms") || lower.contains("all my alarms") || lower.contains("sab alarm") || lower.contains("every alarm")
            if (isAll) {
                return if (activeAlarms.isNotEmpty()) {
                    VoiceAction.DeleteAllAlarms(activeAlarms)
                } else {
                    VoiceAction.ConversationalResponse("You have no active alarms to cancel.")
                }
            }

            val timeMatch = Regex("(?i)\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b").find(lower)
            val target = if (timeMatch != null) {
                var h = timeMatch.groupValues[1].toInt()
                val m = timeMatch.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
                val ampm = timeMatch.groupValues[3].lowercase()
                if (ampm == "pm" && h < 12) h += 12
                else if (ampm == "am" && h == 12) h = 0
                activeAlarms.firstOrNull { isMatchingTime(it.start, h, m) }
            } else {
                val titleMatch = activeAlarms.firstOrNull { alarm ->
                    val words = alarm.title.lowercase().split("\\s+".toRegex())
                    words.any { w -> w.length > 2 && lower.contains(w) }
                }
                titleMatch ?: activeAlarms.minByOrNull { it.start ?: "" }
            }

            return if (target != null) {
                VoiceAction.DeleteAlarm(target, query)
            } else {
                VoiceAction.ConversationalResponse("I couldn't find an active alarm matching that time or title to cancel.")
            }
        }

        // 2. CONFIRM PENDING / INBOX ITEMS INTENT (Parsed from WhatsApp / Messages)
        val isConfirmIntent = Regex("(?i)\\b(confirm|accept|approve|haan confirm|add to schedule)\\b").containsMatchIn(lower) &&
                !lower.contains("alarm")

        if (isConfirmIntent) {
            val isAll = lower.contains("all") || lower.contains("everything") || lower.contains("sab")
            if (isAll) {
                return if (pendingItems.isNotEmpty()) {
                    VoiceAction.ConfirmAllPending(pendingItems)
                } else {
                    VoiceAction.ConversationalResponse("You don't have any pending items to confirm.")
                }
            }

            val timeMatch = Regex("(?i)\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b").find(lower)
            val target = if (timeMatch != null) {
                var h = timeMatch.groupValues[1].toInt()
                val m = timeMatch.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
                val ampm = timeMatch.groupValues[3].lowercase()
                if (ampm == "pm" && h < 12) h += 12
                else if (ampm == "am" && h == 12) h = 0
                pendingItems.firstOrNull { isMatchingTime(it.start, h, m) }
            } else {
                val titleMatch = pendingItems.firstOrNull { item ->
                    val words = item.title.lowercase().split("\\s+".toRegex())
                    words.any { w -> w.length > 2 && lower.contains(w) }
                }
                titleMatch ?: pendingItems.firstOrNull()
            }

            return if (target != null) {
                VoiceAction.ConfirmSchedule(target)
            } else {
                VoiceAction.ConversationalResponse("I couldn't find any pending items to confirm. Your inbox is clear.")
            }
        }

        // 3. READ INBOX / PARSED MESSAGES INTENT
        val isInboxQuery = (lower.contains("inbox") || lower.contains("parsed") || lower.contains("whatsapp") || lower.contains("pending message") || lower.contains("new message")) &&
                (lower.contains("what") || lower.contains("read") || lower.contains("show") || lower.contains("any") || lower.contains("check") || lower.contains("kya"))

        if (isInboxQuery) {
            return VoiceAction.ReadInbox(pendingItems)
        }

        // 4. UPDATE / RESCHEDULE ALARM INTENT
        val isUpdateIntent = Regex("(?i)\\b(change|reschedule|move|postpone|delay|shift|push back|snooze|badal do)\\b").containsMatchIn(lower) &&
                (lower.contains("alarm") || lower.contains("wake up") || lower.contains("reminder") || lower.contains("baje") || lower.contains("class") || lower.contains("meeting") || lower.contains("it"))

        if (isUpdateIntent) {
            // Check relative delay: e.g. "delay my alarm by 15 minutes", "postpone 30 mins", "push back 1 hour"
            val relDelayMin = Regex("(?i)\\b(?:by|for|to)?\\s*(\\d+)\\s*(?:min|mins|minute|minutes)\\b").find(lower)?.groupValues?.get(1)?.toIntOrNull()
            val relDelayHour = Regex("(?i)\\b(?:by|for|to)?\\s*(\\d+)\\s*(?:hour|hours|hr|hrs)\\b").find(lower)?.groupValues?.get(1)?.toIntOrNull()
            val isRelDelay = Regex("(?i)\\b(delay|postpone|push back|snooze)\\b").containsMatchIn(lower) || lower.contains("later")

            if (isRelDelay && (relDelayMin != null || relDelayHour != null)) {
                val delayMinutes = (relDelayMin ?: 0) + (relDelayHour ?: 0) * 60
                val target = activeAlarms.minByOrNull { it.start ?: "" }
                if (target != null) {
                    val baseDate = parseIsoDateOrNow(target.start)
                    val cal = Calendar.getInstance().apply {
                        time = baseDate
                        add(Calendar.MINUTE, delayMinutes)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }

                    val newIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(cal.time)
                    val newFriendly = SimpleDateFormat("h:mm a", Locale.US).format(cal.time)
                    return VoiceAction.UpdateAlarm(target, cal, newIso, newFriendly)
                } else {
                    return VoiceAction.ConversationalResponse("I couldn't find an active alarm to delay.")
                }
            }

            // Check if user said "change alarm to after X min"
            val relToMin = Regex("(?i)\\b(?:to\\s+)?(?:after|in)\\s+(\\d+)\\s*(?:min|mins|minute|minutes)\\b").find(lower)?.groupValues?.get(1)?.toIntOrNull()
            if (relToMin != null) {
                val target = activeAlarms.minByOrNull { it.start ?: "" }
                if (target != null) {
                    val cal = Calendar.getInstance().apply {
                        add(Calendar.MINUTE, relToMin)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val newIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(cal.time)
                    val newFriendly = SimpleDateFormat("h:mm a", Locale.US).format(cal.time)
                    return VoiceAction.UpdateAlarm(target, cal, newIso, newFriendly)
                }
            }

            // Absolute time update: e.g. "change 7 AM alarm to 7:30 AM" or "change alarm to 8 AM"
            val timeMatches = Regex("(?i)\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b").findAll(lower)
                .filter { m ->
                    val afterEnd = lower.substring((m.range.last + 1).coerceAtMost(lower.length)).trim()
                    !afterEnd.startsWith("min") && !afterEnd.startsWith("minute")
                }
                .toList()

            if (timeMatches.isNotEmpty()) {
                val (fromTimeMatch, toTimeMatch) = if (timeMatches.size >= 2) {
                    Pair(timeMatches[0], timeMatches[1])
                } else {
                    Pair(null, timeMatches[0])
                }

                val target = if (fromTimeMatch != null) {
                    var fh = fromTimeMatch.groupValues[1].toInt()
                    val fm = fromTimeMatch.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
                    val fampm = fromTimeMatch.groupValues[3].lowercase()
                    if (fampm == "pm" && fh < 12) fh += 12
                    else if (fampm == "am" && fh == 12) fh = 0
                    activeAlarms.firstOrNull { isMatchingTime(it.start, fh, fm) }
                } else {
                    val titleMatch = activeAlarms.firstOrNull { alarm ->
                        val words = alarm.title.lowercase().split("\\s+".toRegex())
                        words.any { w -> w.length > 2 && lower.contains(w) }
                    }
                    titleMatch ?: activeAlarms.minByOrNull { it.start ?: "" }
                }

                if (target != null) {
                    var th = toTimeMatch.groupValues[1].toInt()
                    val tm = toTimeMatch.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
                    val tampm = toTimeMatch.groupValues[3].lowercase()
                    if (tampm == "pm" && th < 12) th += 12
                    else if (tampm == "am" && th == 12) th = 0
                    else if (tampm.isEmpty()) {
                        val origCal = Calendar.getInstance().apply { time = parseIsoDateOrNow(target.start) }
                        val origH = origCal.get(Calendar.HOUR_OF_DAY)
                        if (origH >= 12 && th < 12) th += 12
                        else if (origH < 12 && th in 1..6) th += 12
                    }

                    val cal = Calendar.getInstance().apply {
                        time = parseIsoDateOrNow(target.start)
                        set(Calendar.HOUR_OF_DAY, th)
                        set(Calendar.MINUTE, tm)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val newIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(cal.time)
                    val newFriendly = SimpleDateFormat("h:mm a", Locale.US).format(cal.time)
                    return VoiceAction.UpdateAlarm(target, cal, newIso, newFriendly)
                } else {
                    return VoiceAction.ConversationalResponse("I couldn't find an existing alarm to reschedule. You can say 'set an alarm' to create one.")
                }
            }
        }

        // 5. READ ALARMS INTENT
        val isReadAlarmsIntent = (lower.contains("alarm") || lower.contains("alarms")) &&
                (lower.contains("what") || lower.contains("list") || lower.contains("show") ||
                 lower.contains("any") || lower.contains("check") || lower.contains("kya") ||
                 lower.contains("tell me"))

        if (isReadAlarmsIntent) {
            val isTomorrow = lower.contains("tomorrow") || lower.contains("kal")
            val targetDateStr = if (isTomorrow) {
                val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
                SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
            } else {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            }
            val dayWord = if (isTomorrow) "tomorrow" else "today"
            val matchingAlarms = activeAlarms.filter { it.start?.startsWith(targetDateStr) == true || it.start_date == targetDateStr }
            return VoiceAction.ReadAlarms(dayWord, matchingAlarms)
        }

        // 6. CREATE ALARM INTENT
        val alarmCmd = parseAlarmCommand(query)
        if (alarmCmd != null) {
            return VoiceAction.CreateAlarm(alarmCmd)
        }

        // 7. GENERAL SCHEDULE / CONVERSATION
        val answer = buildScheduleAnswer(query, schedules)
        return VoiceAction.ConversationalResponse(answer)
    }

    suspend fun deleteAlarmFromVoice(
        context: Context,
        target: ScheduleEntity
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val appContext = context.applicationContext
            val db = AppDatabase.getInstance(appContext)
            val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())

            val deviceId = "android-phone"
            val hlcNow = HlcClock(deviceId).now()
            val nextSeq = db.eventDao().getNextSeq(deviceId)

            val dismissPayload = JSONObject().apply {
                put("chat_title", target.chat_title)
                put("sender", target.sender)
                put("reason", "voice_command_delete")
            }

            val dismissEntity = SyncEventEntity(
                id = UUID.randomUUID().toString(),
                timestamp = nowIso,
                deviceId = deviceId,
                seq = nextSeq,
                hlc = hlcNow,
                type = "event_dismissed",
                entityId = target.id,
                payload = dismissPayload.toString()
            )
            db.eventDao().insertEvent(dismissEntity)

            withContext(Dispatchers.Main) {
                val scheduler = AlarmScheduler(appContext)
                scheduler.cancelAlertsForSchedule(target.id)
            }
            Log.d(TAG, "Voice alarm deleted successfully: ${target.id} (${target.title})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete alarm from voice", e)
            false
        }
    }

    suspend fun rescheduleAlarmFromVoice(
        context: Context,
        target: ScheduleEntity,
        newStartIso: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val appContext = context.applicationContext
            val db = AppDatabase.getInstance(appContext)
            val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())

            val deviceId = "android-phone"
            val hlcNow = HlcClock(deviceId).now()
            val nextSeq = db.eventDao().getNextSeq(deviceId)

            val payload = JSONObject().apply {
                put("start", newStartIso)
                put("new_start", newStartIso)
                put("old_start", target.start)
                put("time_unspecified", false)
                put("reason", "voice_command_reschedule")
            }

            val rescheduleEntity = SyncEventEntity(
                id = UUID.randomUUID().toString(),
                timestamp = nowIso,
                deviceId = deviceId,
                seq = nextSeq,
                hlc = hlcNow,
                type = "event_rescheduled",
                entityId = target.id,
                payload = payload.toString()
            )
            db.eventDao().insertEvent(rescheduleEntity)

            val updatedTarget = target.copy(start = newStartIso, time_unspecified = false, has_alarm = true)
            withContext(Dispatchers.Main) {
                val scheduler = AlarmScheduler(appContext)
                scheduler.rescheduleAlertsForSchedule(updatedTarget, enableAlarm = true)
            }
            Log.d(TAG, "Voice alarm rescheduled successfully: ${target.id} to $newStartIso")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reschedule alarm from voice", e)
            false
        }
    }

    suspend fun deleteAllAlarmsFromVoice(
        context: Context,
        targets: List<ScheduleEntity>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val appContext = context.applicationContext
            val db = AppDatabase.getInstance(appContext)
            val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())

            val deviceId = "android-phone"
            val hlcNow = HlcClock(deviceId).now()
            var seq = db.eventDao().getNextSeq(deviceId)
            val scheduler = AlarmScheduler(appContext)

            for (target in targets) {
                val dismissPayload = JSONObject().apply {
                    put("chat_title", target.chat_title)
                    put("sender", target.sender)
                    put("reason", "voice_command_delete_all")
                }
                val dismissEntity = SyncEventEntity(
                    id = UUID.randomUUID().toString(),
                    timestamp = nowIso,
                    deviceId = deviceId,
                    seq = seq++,
                    hlc = hlcNow,
                    type = "event_dismissed",
                    entityId = target.id,
                    payload = dismissPayload.toString()
                )
                db.eventDao().insertEvent(dismissEntity)
                scheduler.cancelAlertsForSchedule(target.id)
            }
            Log.d(TAG, "Voice deleted all ${targets.size} alarms successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete all alarms from voice", e)
            false
        }
    }

    suspend fun confirmScheduleFromVoice(
        context: Context,
        target: ScheduleEntity,
        enableAlarm: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val appContext = context.applicationContext
            val db = AppDatabase.getInstance(appContext)
            val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())

            val deviceId = "android-phone"
            val hlcNow = HlcClock(deviceId).now()
            var nextSeq = db.eventDao().getNextSeq(deviceId)

            val confirmPayload = JSONObject().apply {
                put("confirmed_at", nowIso)
                put("chat_title", target.chat_title)
                put("sender", target.sender)
                put("lane", "SCHEDULE")
            }
            val confirmEvent = SyncEventEntity(
                id = UUID.randomUUID().toString(),
                timestamp = nowIso,
                deviceId = deviceId,
                seq = nextSeq++,
                hlc = hlcNow,
                type = "event_confirmed",
                entityId = target.id,
                payload = confirmPayload.toString()
            )
            db.eventDao().insertEvent(confirmEvent)

            if (enableAlarm && !target.time_unspecified && target.start != null) {
                val alarmPayload = JSONObject().apply {
                    put("schedule_id", target.id)
                    put("title", target.title)
                    put("trigger_time", target.start)
                    put("type", "audible_alarm")
                }
                val alarmEntity = SyncEventEntity(
                    id = UUID.randomUUID().toString(),
                    timestamp = nowIso,
                    deviceId = deviceId,
                    seq = nextSeq,
                    hlc = hlcNow,
                    type = "alarm_set",
                    entityId = "alarm-${target.id}",
                    payload = alarmPayload.toString()
                )
                db.eventDao().insertEvent(alarmEntity)
            }

            val updatedSchedule = target.copy(status = ScheduleStatus.CONFIRMED, has_alarm = enableAlarm)
            val scheduler = AlarmScheduler(appContext)
            scheduler.scheduleAlertsForSchedule(updatedSchedule, enableAlarm = enableAlarm)
            Log.d(TAG, "Voice confirmed schedule ${target.id} (${target.title}) with alarm=$enableAlarm")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to confirm schedule from voice", e)
            false
        }
    }
}

