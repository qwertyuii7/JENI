package com.scheduleassistant.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.scheduleassistant.app.data.db.ChatPreferenceEntity
import com.scheduleassistant.app.data.db.SmartPackStateEntity
import com.scheduleassistant.app.ui.theme.*
import java.text.DecimalFormat

// ─── Priority Enum ────────────────────────────────────────────────────────────
enum class ChatPriority(val label: String, val emoji: String, val color: Color) {
    HIGH("High", "🔥", Color(0xFFFF6B35)),
    NORMAL("Normal", "✅", Color(0xFF4CAF50)),
    LOW("Low", "🔕", Color(0xFF78909C)),
    MUTED("Muted", "🚫", Color(0xFFEF5350))
}

fun String.toChatPriority(): ChatPriority = ChatPriority.values()
    .firstOrNull { it.name == this } ?: ChatPriority.NORMAL

// ─── Chat Management Screen ───────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatManagementScreen(
    knownChats: List<String>,                           // discovered chat titles from events
    chatPreferences: List<ChatPreferenceEntity>,
    smartPackState: SmartPackStateEntity?,
    onSetPriority: (chatTitle: String, priority: ChatPriority) -> Unit,
    onToggleMute: (chatTitle: String, muted: Boolean) -> Unit,
    onStartSmartPackDownload: () -> Unit,
    onCancelSmartPackDownload: () -> Unit,
    onDeleteSmartPack: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val prefMap = remember(chatPreferences) {
        chatPreferences.associateBy { it.chatTitle }
    }

    // Merge known chats with any saved prefs (so explicitly saved chats also show)
    val allChats = remember(knownChats, chatPreferences) {
        (knownChats + chatPreferences.map { it.chatTitle }).distinct().sorted()
    }

    val isAiActive = smartPackState?.status == "DOWNLOADED" && smartPackState.sha256Verified

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Chat Management",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        if (isAiActive) {
                            Text(
                                "✨ AI Smart Mode Active",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFAB47BC)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        },
        containerColor = DarkBg
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Smart Pack Section
            item {
                SmartPackCard(
                    state = smartPackState,
                    onStartDownload = onStartSmartPackDownload,
                    onCancelDownload = onCancelSmartPackDownload,
                    onDeletePack = onDeleteSmartPack
                )
            }

            // Section header
            item {
                val localCtx = androidx.compose.ui.platform.LocalContext.current
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "WhatsApp Chats (${allChats.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    TextButton(
                        onClick = {
                            com.scheduleassistant.app.service.ScheduleNotificationListenerService.requestRebindService(localCtx)
                            com.scheduleassistant.app.service.ScheduleNotificationListenerService.triggerActiveNotificationScan()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("🔄 Scan Active", style = MaterialTheme.typography.labelSmall, color = Accent)
                    }
                }
            }

            if (allChats.isEmpty()) {
                item {
                    EmptyChatState()
                }
            } else {
                items(allChats, key = { it }) { chatTitle ->
                    val pref = prefMap[chatTitle]
                    val priority = pref?.priority?.toChatPriority() ?: ChatPriority.NORMAL
                    val isMuted = pref?.isMuted ?: false

                    ChatPreferenceCard(
                        chatTitle = chatTitle,
                        priority = priority,
                        isMuted = isMuted,
                        onSetPriority = { onSetPriority(chatTitle, it) },
                        onToggleMute = { onToggleMute(chatTitle, !isMuted) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

// ─── Smart Pack Card ──────────────────────────────────────────────────────────
@Composable
private fun SmartPackCard(
    state: SmartPackStateEntity?,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeletePack: () -> Unit
) {
    var showConfirmDialog by remember { mutableStateOf(false) }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🤖", style = MaterialTheme.typography.titleMedium)
                    Text("Download Smart Pack AI", color = TextPrimary, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This downloads the on-device Qwen2.5-0.5B AI model (~231 MB) directly to your phone for offline schedule extraction.",
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Surface(
                        color = CardElevated,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, CardBorderSubtle)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("📶 Data Connectivity", color = Primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "Download works over any connection (Wi-Fi or Mobile Data). If you are on mobile data, approx. 231 MB of data will be used.",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDialog = false
                        onStartDownload()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Download (231 MB)", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = CardBase
        )
    }

    val status = state?.status ?: "NOT_DOWNLOADED"
    val progressFraction = if ((state?.totalBytes ?: 0L) > 0L) {
        (state!!.downloadedBytes.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val progressAnim by animateFloatAsState(
        targetValue = progressFraction,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "download_progress"
    )

    val isDownloaded = status == "DOWNLOADED" && (state?.sha256Verified == true)
    val isDownloading = status == "DOWNLOADING"
    val hasFailed = status == "FAILED"

    val containerColor = when {
        isDownloaded -> Color(0xFF1B2E1F)
        isDownloading -> Color(0xFF1A2030)
        hasFailed -> Color(0xFF2E1B1B)
        else -> CardBase
    }

    val borderColor = when {
        isDownloaded -> Color(0xFF4CAF50).copy(alpha = 0.6f)
        isDownloading -> Primary.copy(alpha = 0.5f)
        hasFailed -> Color(0xFFEF5350).copy(alpha = 0.5f)
        else -> CardBorderSubtle
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🤖", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Smart Pack (Optional)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        "Qwen2.5-0.5B · On-device · ~231 MB · Privacy-first",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                if (isDownloaded) {
                    Surface(shape = CircleShape, color = Color(0xFF4CAF50).copy(alpha = 0.2f)) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.padding(4.dp).size(16.dp)
                        )
                    }
                }
            }

            // Description
            Text(
                when {
                    isDownloaded -> "✨ AI Smart Mode Active — Ambiguous messages get smarter analysis without leaving your device."
                    isDownloading -> "Downloading securely. Works over Wi-Fi or Mobile Data. SHA-256 verified."
                    hasFailed -> "Download failed. Check your data connection and try again."
                    else -> "Enables AI-assisted triage for ambiguous messages. Works offline. Downloadable over Wi-Fi or Mobile Data."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (hasFailed) Color(0xFFEF9A9A) else TextSecondary,
                lineHeight = 18.sp
            )

            // Progress bar
            if (isDownloading) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { progressAnim },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = Primary,
                        trackColor = DarkBg
                    )
                    val df = DecimalFormat("#.0")
                    val downloadedMb = df.format((state?.downloadedBytes ?: 0L) / 1_000_000f)
                    val totalMb = df.format((state?.totalBytes ?: 242_000_000L) / 1_000_000f)
                    Text(
                        "$downloadedMb / $totalMb MB  (${(progressAnim * 100).toInt()}%)",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }

            // Action buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    isDownloaded -> {
                        OutlinedButton(
                            onClick = onDeletePack,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF5350)),
                            border = BorderStroke(1.dp, Color(0xFFEF5350).copy(alpha = 0.5f)),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Remove Pack", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    isDownloading -> {
                        OutlinedButton(
                            onClick = onCancelDownload,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                            border = BorderStroke(1.dp, CardBorderSubtle),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("Cancel", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    else -> {
                        Button(
                            onClick = { showConfirmDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Primary),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (hasFailed) "Retry Download" else "Download Smart Pack",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Per-Chat Card ────────────────────────────────────────────────────────────
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatPreferenceCard(
    chatTitle: String,
    priority: ChatPriority,
    isMuted: Boolean,
    onSetPriority: (ChatPriority) -> Unit,
    onToggleMute: () -> Unit
) {
    val cardColor = if (isMuted) CardBase.copy(alpha = 0.6f) else CardBase
    val titleAlpha = if (isMuted) 0.5f else 1f

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cardColor,
        border = BorderStroke(1.dp, if (isMuted) CardBorderSubtle.copy(alpha = 0.4f) else CardBorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Chat title row + mute toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.Default.Forum,
                        contentDescription = null,
                        tint = if (isMuted) TextSecondary.copy(alpha = 0.4f) else Primary.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        chatTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary.copy(alpha = titleAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isMuted) "Listening Disabled" else "Listening Active",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isMuted) Color(0xFFEF5350) else Color(0xFF4CAF50),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Switch(
                        checked = !isMuted,
                        onCheckedChange = { onToggleMute() },
                        thumbContent = {
                            if (!isMuted) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(10.dp))
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Primary,
                            uncheckedThumbColor = Color(0xFFEF5350),
                            uncheckedTrackColor = Color(0xFFEF5350).copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.scale(0.8f)
                    )
                }
            }

            // Priority chips — hidden while muted
            AnimatedVisibility(
                visible = !isMuted,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ChatPriority.values()
                        .filter { it != ChatPriority.MUTED }
                        .forEach { p ->
                            val isSelected = priority == p && !isMuted
                            FilterChip(
                                selected = isSelected,
                                onClick = { onSetPriority(p) },
                                label = {
                                    Text(
                                        "${p.emoji} ${p.label}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = p.color.copy(alpha = 0.25f),
                                    selectedLabelColor = p.color,
                                    containerColor = DarkBg,
                                    labelColor = TextSecondary
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = isSelected,
                                    selectedBorderColor = p.color.copy(alpha = 0.7f),
                                    borderColor = CardBorderSubtle
                                )
                            )
                        }
                }
            }

            // Priority hint
            AnimatedVisibility(visible = !isMuted && priority != ChatPriority.NORMAL) {
                val hint = when (priority) {
                    ChatPriority.HIGH -> "📌 Messages from this chat get extra signal weight — fewer missed events"
                    ChatPriority.LOW -> "🔽 Lower signal weight — only very strong schedule signals pass"
                    else -> ""
                }
                if (hint.isNotEmpty()) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = priority.color.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

// ─── Empty state ──────────────────────────────────────────────────────────────
@Composable
private fun EmptyChatState() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("💬", style = MaterialTheme.typography.displaySmall)
            Text("No chats detected yet", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            Text(
                "Chats appear here as WhatsApp notifications are received",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            val localCtx = androidx.compose.ui.platform.LocalContext.current
            Button(
                onClick = {
                    com.scheduleassistant.app.service.ScheduleNotificationListenerService.requestRebindService(localCtx)
                    com.scheduleassistant.app.service.ScheduleNotificationListenerService.triggerActiveNotificationScan()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
            ) {
                Text("Scan Active Notifications Now", fontSize = 12.sp)
            }
        }
    }
}
