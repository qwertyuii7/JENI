package com.scheduleassistant.app.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import com.scheduleassistant.app.data.model.ScheduleEntity
import com.scheduleassistant.app.ui.theme.*
import com.scheduleassistant.app.voice.VoiceAction
import com.scheduleassistant.app.voice.VoiceSecretaryManager
import com.scheduleassistant.app.voice.VoiceStatus
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSecretaryDialog(
    schedules: List<ScheduleEntity>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val voiceStatus by VoiceSecretaryManager.status.collectAsState()

    var userTranscript by remember { mutableStateOf<String?>(null) }
    var assistantResponse by remember { mutableStateOf<String?>(null) }
    var isListening by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Tap the mic to talk with Sarah") }
    var typedQuery by remember { mutableStateOf("") }
    var rmsLevel by remember { mutableStateOf(0f) }

    val activeVoiceName = remember {
        val voiceId = VoiceSecretaryManager.getSelectedVoiceId(context)
        VoiceSecretaryManager.AVAILABLE_VOICES.firstOrNull { it.id == voiceId }?.name ?: "Sarah"
    }

    val coroutineScope = rememberCoroutineScope()

    // Agent response handler: updates UI & triggers ElevenLabs / Offline TTS
    fun handleUserUtterance(text: String) {
        val cleanText = text.trim()
        if (cleanText.isBlank()) return
        userTranscript = cleanText

        val action = VoiceSecretaryManager.resolveVoiceAction(cleanText, schedules)
        when (action) {
            is VoiceAction.CreateAlarm -> {
                val answer = "Done! I've set an alarm for ${action.cmd.friendlyDay} at ${action.cmd.friendlyTime} for '${action.cmd.title}'. You're all set!"
                assistantResponse = answer
                statusMessage = "Alarm armed: ${action.cmd.friendlyTime}"
                VoiceSecretaryManager.speak(context, answer) {
                    statusMessage = "Tap mic to give another command"
                }
                coroutineScope.launch {
                    VoiceSecretaryManager.scheduleAlarmFromVoice(context, action.cmd)
                }
            }
            is VoiceAction.DeleteAlarm -> {
                val timeStr = VoiceSecretaryManager.formatFriendlyTime(action.targetSchedule.start)
                val answer = "Alarm cancelled: $timeStr for '${action.targetSchedule.title}'."
                assistantResponse = answer
                statusMessage = "Alarm cancelled: $timeStr"
                VoiceSecretaryManager.speak(context, answer) {
                    statusMessage = "Tap mic to give another command"
                }
                coroutineScope.launch {
                    VoiceSecretaryManager.deleteAlarmFromVoice(context, action.targetSchedule)
                }
            }
            is VoiceAction.DeleteAllAlarms -> {
                val count = action.targets.size
                val answer = "Cancelled all $count active alarm${if (count > 1) "s" else ""}."
                assistantResponse = answer
                statusMessage = "Cancelled $count alarms"
                VoiceSecretaryManager.speak(context, answer) {
                    statusMessage = "Tap mic to give another command"
                }
                coroutineScope.launch {
                    VoiceSecretaryManager.deleteAllAlarmsFromVoice(context, action.targets)
                }
            }
            is VoiceAction.UpdateAlarm -> {
                val oldTime = VoiceSecretaryManager.formatFriendlyTime(action.targetSchedule.start)
                val answer = "Alarm updated: moved '${action.targetSchedule.title}' from $oldTime to ${action.newFriendlyTime}."
                assistantResponse = answer
                statusMessage = "Alarm updated: ${action.newFriendlyTime}"
                VoiceSecretaryManager.speak(context, answer) {
                    statusMessage = "Tap mic to give another command"
                }
                coroutineScope.launch {
                    VoiceSecretaryManager.rescheduleAlarmFromVoice(context, action.targetSchedule, action.newIsoStart)
                }
            }
            is VoiceAction.ConfirmSchedule -> {
                val timeStr = VoiceSecretaryManager.formatFriendlyTime(action.targetSchedule.start)
                val answer = "Confirmed '${action.targetSchedule.title}' for $timeStr. Alarm armed!"
                assistantResponse = answer
                statusMessage = "Confirmed: ${action.targetSchedule.title}"
                VoiceSecretaryManager.speak(context, answer) {
                    statusMessage = "Tap mic to give another command"
                }
                coroutineScope.launch {
                    VoiceSecretaryManager.confirmScheduleFromVoice(context, action.targetSchedule, enableAlarm = true)
                }
            }
            is VoiceAction.ConfirmAllPending -> {
                val count = action.targets.size
                val answer = "Confirmed all $count pending items from your messages and armed alarms."
                assistantResponse = answer
                statusMessage = "Confirmed $count items"
                VoiceSecretaryManager.speak(context, answer) {
                    statusMessage = "Tap mic to give another command"
                }
                coroutineScope.launch {
                    action.targets.forEach { target ->
                        VoiceSecretaryManager.confirmScheduleFromVoice(context, target, enableAlarm = true)
                    }
                }
            }
            is VoiceAction.ReadAlarms -> {
                val answer = if (action.alarms.isEmpty()) {
                    "You have no active alarms set for ${action.targetDay}."
                } else {
                    val count = action.alarms.size
                    val sb = StringBuilder("You have $count alarm${if (count > 1) "s" else ""} for ${action.targetDay}: ")
                    action.alarms.forEachIndexed { i, a ->
                        val t = VoiceSecretaryManager.formatFriendlyTime(a.start)
                        sb.append("${if (i > 0) ", " else ""}$t for '${a.title}'")
                    }
                    sb.toString()
                }
                assistantResponse = answer
                statusMessage = "Read ${action.alarms.size} alarms"
                VoiceSecretaryManager.speak(context, answer) {
                    statusMessage = "Tap mic to ask another question"
                }
            }
            is VoiceAction.ReadInbox -> {
                val answer = if (action.pendingItems.isEmpty()) {
                    "Your inbox is completely clear. No unconfirmed messages from WhatsApp."
                } else {
                    val count = action.pendingItems.size
                    val sb = StringBuilder("You have $count pending item${if (count > 1) "s" else ""} from your messages: ")
                    action.pendingItems.forEachIndexed { i, item ->
                        val t = VoiceSecretaryManager.formatFriendlyTime(item.start)
                        sb.append("${if (i > 0) ", " else ""}'${item.title}' at $t")
                    }
                    sb.append(". Say 'confirm' to add them to your schedule.")
                    sb.toString()
                }
                assistantResponse = answer
                statusMessage = "Inbox: ${action.pendingItems.size} items"
                VoiceSecretaryManager.speak(context, answer) {
                    statusMessage = "Tap mic to give another command"
                }
            }
            is VoiceAction.ConversationalResponse -> {
                assistantResponse = action.text
                statusMessage = "Ready"
                VoiceSecretaryManager.speak(context, action.text) {
                    statusMessage = "Tap mic to ask another question"
                }
            }
            is VoiceAction.ReadSchedule -> {
                val answer = VoiceSecretaryManager.buildScheduleAnswer(action.query, schedules)
                assistantResponse = answer
                statusMessage = "Speaking schedule..."
                VoiceSecretaryManager.speak(context, answer) {
                    statusMessage = "Tap mic to ask another question"
                }
            }
        }
    }

    // ─── Dual-Mode Speech Recognition ──────────────────────────────────────────

    // Mode B: System Speech Recognition Sheet (100% reliable across all Android OEMs/Skins)
    val systemSpeechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isListening = false
        rmsLevel = 0f
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val heardText = matches?.firstOrNull()?.trim()
            if (!heardText.isNullOrBlank()) {
                handleUserUtterance(heardText)
            } else {
                statusMessage = "Didn't hear any words. Tap the mic to try again."
            }
        } else {
            statusMessage = "Tap mic to talk with Sarah"
        }
    }

    fun launchSystemSpeechInput() {
        VoiceSecretaryManager.stop()
        isListening = false
        rmsLevel = 0f
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Sarah about your schedule...")
            }
            systemSpeechLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Voice input not supported on this device. You can type below.", Toast.LENGTH_SHORT).show()
        }
    }

    // Mode A: In-Process SpeechRecognizer
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    DisposableEffect(Unit) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            } catch (_: Exception) {
                speechRecognizer = null
            }
        }
        onDispose {
            try {
                speechRecognizer?.destroy()
            } catch (_: Exception) {}
            VoiceSecretaryManager.stop()
        }
    }

    fun startListeningWithRecognizer() {
        VoiceSecretaryManager.stop()

        if (speechRecognizer == null || !SpeechRecognizer.isRecognitionAvailable(context)) {
            launchSystemSpeechInput()
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Sarah about your schedule...")
        }

        try {
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    statusMessage = "Listening... Speak now"
                }

                override fun onBeginningOfSpeech() {
                    statusMessage = "Hearing your voice..."
                }

                override fun onRmsChanged(rmsdB: Float) {
                    rmsLevel = rmsdB.coerceIn(0f, 10f)
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListening = false
                    statusMessage = "Processing your question..."
                }

                override fun onError(error: Int) {
                    isListening = false
                    rmsLevel = 0f
                    // If in-app recognizer encounters OEM binding, client, or permission error,
                    // gracefully fallback to system speech input activity!
                    if (error == SpeechRecognizer.ERROR_CLIENT ||
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        error == SpeechRecognizer.ERROR_AUDIO ||
                        error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                    ) {
                        launchSystemSpeechInput()
                    } else if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        statusMessage = "Didn't catch that. Tap mic to try again, or ask below."
                    } else {
                        launchSystemSpeechInput()
                    }
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    rmsLevel = 0f
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val heardText = matches?.firstOrNull()?.trim()
                    if (!heardText.isNullOrBlank()) {
                        handleUserUtterance(heardText)
                    } else {
                        statusMessage = "Didn't catch any words. Tap the mic to try again."
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = matches?.firstOrNull()
                    if (!partial.isNullOrBlank()) {
                        userTranscript = partial
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            speechRecognizer?.startListening(intent)
            isListening = true
            statusMessage = "Listening... Speak now"
        } catch (e: Exception) {
            launchSystemSpeechInput()
        }
    }

    // Runtime Permission Launcher for RECORD_AUDIO
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startListeningWithRecognizer()
        } else {
            Toast.makeText(
                context,
                "Microphone permission is required to talk with Sarah. Please allow it.",
                Toast.LENGTH_LONG
            ).show()
            statusMessage = "Microphone permission needed"
        }
    }

    fun onMicClicked() {
        if (voiceStatus == VoiceStatus.SPEAKING) {
            VoiceSecretaryManager.stop()
            statusMessage = "Playback stopped"
            return
        }

        if (isListening) {
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {}
            isListening = false
            statusMessage = "Stopped listening"
            return
        }

        val hasMicPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMicPermission) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startListeningWithRecognizer()
        }
    }

    // Initial greeting when opening the Talking Agent
    LaunchedEffect(Unit) {
        if (assistantResponse == null && userTranscript == null) {
            val greeting = "Hi! I'm Sarah, your schedule secretary. How can I help you today?"
            assistantResponse = greeting
            VoiceSecretaryManager.speak(context, greeting)
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            VoiceSecretaryManager.stop()
            onDismiss()
        },
        containerColor = DarkBg,
        dragHandle = { BottomSheetDefaults.DragHandle(color = CardBorder) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        shape = CircleShape,
                        color = Primary.copy(alpha = 0.2f),
                        border = BorderStroke(1.dp, Primary.copy(alpha = 0.4f))
                    ) {
                        Text("🎙️", fontSize = 22.sp, modifier = Modifier.padding(6.dp))
                    }
                    Column {
                        Text(
                            text = "AI Voice Secretary",
                            color = TextLight,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Speaking with $activeVoiceName · ElevenLabs",
                            color = Accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Status Badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CardBg,
                    border = BorderStroke(1.dp, CardBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        voiceStatus == VoiceStatus.SPEAKING -> Success
                                        isListening -> Primary
                                        voiceStatus == VoiceStatus.GENERATING -> Warning
                                        voiceStatus == VoiceStatus.ERROR -> Danger
                                        else -> TextMuted
                                    }
                                )
                        )
                        Text(
                            text = when {
                                voiceStatus == VoiceStatus.SPEAKING -> "Speaking"
                                isListening -> "Listening"
                                voiceStatus == VoiceStatus.GENERATING -> "Thinking"
                                voiceStatus == VoiceStatus.ERROR -> "Offline TTS"
                                else -> "Ready"
                            },
                            color = TextLight,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Animated Visualizer Orb / Waveform
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val idlePulse by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.08f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "idle_pulse"
            )

            val rmsScale = if (isListening) (1f + (rmsLevel / 15f)).coerceIn(1f, 1.4f) else 1f
            val orbScale = when {
                voiceStatus == VoiceStatus.SPEAKING -> idlePulse * 1.12f
                isListening -> rmsScale
                voiceStatus == VoiceStatus.GENERATING -> idlePulse * 1.05f
                else -> idlePulse
            }

            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = when {
                                voiceStatus == VoiceStatus.SPEAKING -> listOf(Primary, Accent.copy(alpha = 0.5f), Color.Transparent)
                                isListening -> listOf(Success, Primary.copy(alpha = 0.4f), Color.Transparent)
                                voiceStatus == VoiceStatus.GENERATING -> listOf(Warning, Primary.copy(alpha = 0.3f), Color.Transparent)
                                else -> listOf(CardElevated, CardBg, Color.Transparent)
                            }
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = { onMicClicked() },
                    modifier = Modifier
                        .size((76 * orbScale).dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                voiceStatus == VoiceStatus.SPEAKING -> Danger
                                isListening -> Success
                                else -> Primary
                            }
                        )
                ) {
                    Icon(
                        imageVector = when {
                            voiceStatus == VoiceStatus.SPEAKING -> Icons.Default.Stop
                            isListening -> Icons.Default.Mic
                            else -> Icons.Default.Mic
                        },
                        contentDescription = "Microphone",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            // Status Text & Fallback Direct Button
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = when {
                        voiceStatus == VoiceStatus.SPEAKING -> "Sarah is speaking · Tap red button to stop"
                        isListening -> "Listening to your voice... Speak clearly"
                        voiceStatus == VoiceStatus.GENERATING -> "Preparing speech with ElevenLabs..."
                        else -> statusMessage
                    },
                    color = if (isListening) Success else TextLight,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Tap the glowing mic or use system voice input below",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }

            // System Voice Input Button (Always Available Fallback)
            OutlinedButton(
                onClick = { launchSystemSpeechInput() },
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, CardBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextLight),
                modifier = Modifier.fillMaxWidth(0.85f).height(38.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open Google / Samsung Voice Input", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            // Live Conversation Box (User utterance & Assistant response)
            if (userTranscript != null || assistantResponse != null) {
                Surface(
                    color = CardBg,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, CardBorderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (userTranscript != null) {
                            Row(
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("👤", fontSize = 14.sp)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("You", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = "\"${userTranscript}\"",
                                        color = TextLight,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        if (assistantResponse != null) {
                            HorizontalDivider(color = CardBorderSubtle, thickness = 0.5.dp)

                            Row(
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("✨", fontSize = 14.sp)
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(activeVoiceName, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        IconButton(
                                            onClick = {
                                                assistantResponse?.let {
                                                    VoiceSecretaryManager.speak(context, it)
                                                }
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.VolumeUp,
                                                contentDescription = "Replay",
                                                tint = TextMuted,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = assistantResponse!!,
                                        color = TextLight,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        lineHeight = 20.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Quick Question Chips
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Quick Questions",
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickChip(
                        title = "What's my schedule today?",
                        emoji = "📅",
                        modifier = Modifier.weight(1f),
                        onClick = { handleUserUtterance("what is my schedule for today") }
                    )
                    QuickChip(
                        title = "What's on tomorrow?",
                        emoji = "☀️",
                        modifier = Modifier.weight(1f),
                        onClick = { handleUserUtterance("what is on tomorrow") }
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickChip(
                        title = "Set alarm 7:00 AM",
                        emoji = "⏰",
                        modifier = Modifier.weight(1f),
                        onClick = { handleUserUtterance("set an alarm for 7:00 AM tomorrow") }
                    )
                    QuickChip(
                        title = "Read Morning Briefing",
                        emoji = "🌅",
                        modifier = Modifier.weight(1f),
                        onClick = { handleUserUtterance("morning briefing") }
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickChip(
                        title = "What alarms are set?",
                        emoji = "🔔",
                        modifier = Modifier.weight(1f),
                        onClick = { handleUserUtterance("what alarms are set") }
                    )
                    QuickChip(
                        title = "Cancel 7:00 AM alarm",
                        emoji = "❌",
                        modifier = Modifier.weight(1f),
                        onClick = { handleUserUtterance("cancel my 7:00 AM alarm") }
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickChip(
                        title = "Check parsed messages",
                        emoji = "📬",
                        modifier = Modifier.weight(1f),
                        onClick = { handleUserUtterance("what messages are in my inbox") }
                    )
                    QuickChip(
                        title = "Confirm pending items",
                        emoji = "✅",
                        modifier = Modifier.weight(1f),
                        onClick = { handleUserUtterance("confirm pending reminders") }
                    )
                }
            }

            // Interactive Text Query Input (Type question for Sarah)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = CardBg,
                border = BorderStroke(1.dp, CardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = typedQuery,
                        onValueChange = { typedQuery = it },
                        placeholder = {
                            Text("Or type a question for Sarah...", color = TextMuted, fontSize = 13.sp)
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = TextLight,
                            unfocusedTextColor = TextLight
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (typedQuery.isNotBlank()) {
                                val query = typedQuery
                                typedQuery = ""
                                focusManager.clearFocus()
                                handleUserUtterance(query)
                            }
                        }),
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(
                        onClick = {
                            if (typedQuery.isNotBlank()) {
                                val query = typedQuery
                                typedQuery = ""
                                focusManager.clearFocus()
                                handleUserUtterance(query)
                            }
                        },
                        enabled = typedQuery.isNotBlank()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (typedQuery.isNotBlank()) Primary else TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickChip(
    title: String,
    emoji: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = CardBg,
        border = BorderStroke(1.dp, CardBorder),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(emoji, fontSize = 14.sp)
            Text(
                text = title,
                color = TextLight,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}
