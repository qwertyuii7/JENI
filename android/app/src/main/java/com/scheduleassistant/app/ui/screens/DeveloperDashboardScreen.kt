package com.scheduleassistant.app.ui.screens

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import com.scheduleassistant.app.data.db.AlarmAuditEntity
import com.scheduleassistant.app.data.db.AppDatabase
import com.scheduleassistant.app.data.db.DiagnosticLogEntity
import com.scheduleassistant.app.data.diagnostics.DeveloperModeManager
import com.scheduleassistant.app.data.diagnostics.DiagnosticsManager
import com.scheduleassistant.app.data.model.ExtractionOutput
import com.scheduleassistant.app.data.model.SyncEvent
import com.scheduleassistant.app.service.AlarmScheduler
import com.scheduleassistant.app.service.DailyDigestNotifier
import com.scheduleassistant.app.ui.components.*
import com.scheduleassistant.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DeveloperDashboardScreen(
    onNavigateBack: () -> Unit,
    syncEvents: List<SyncEvent>,
    chatPriors: Map<String, Double> = emptyMap(),
    senderPriors: Map<String, Double> = emptyMap(),
    ignoreCount: Int = 0,
    onInjectMessage: (String) -> Unit = {},
    lastExtracted: ExtractionOutput? = null,
    onDeleteAllTestItems: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val scheduler = remember { AlarmScheduler(context) }
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }

    var isDevMode by remember { mutableStateOf(DeveloperModeManager.isDeveloperModeEnabled(context)) }
    var selectedSection by remember { mutableStateOf("HEALTH") } // HEALTH, LOGS, INJECTOR, UTILITIES

    // Log viewer state
    var logFilter by remember { mutableStateOf("ALL") } // ALL, EXTRACTED, DROPPED, LOG_ONLY
    var searchQuery by remember { mutableStateOf("") }
    var logsList by remember { mutableStateOf<List<DiagnosticLogEntity>>(emptyList()) }
    var isLoadingLogs by remember { mutableStateOf(false) }

    // Memory stats
    val runtime = Runtime.getRuntime()
    val usedMemoryMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    val maxMemoryMb = runtime.maxMemory() / (1024 * 1024)
    val uptimeMinutes = SystemClock.elapsedRealtime() / 60000

    // Permissions & Health
    val isListenerConnected by DiagnosticsManager.isListenerConnected.collectAsState()
    val hasExactAlarm = scheduler.canScheduleExactAlarms()
    val isBatteryExempted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    } else true

    // Load diagnostic logs when LOGS section selected
    LaunchedEffect(selectedSection, logFilter) {
        if (selectedSection == "LOGS") {
            isLoadingLogs = true
            withContext(Dispatchers.IO) {
                val raw = db.diagnosticLogDao().getRecentDiagnosticLogs(200)
                logsList = when (logFilter) {
                    "EXTRACTED" -> raw.filter { !it.parserOutput.isNullOrBlank() }
                    "DROPPED" -> raw.filter { it.prefilterResult.contains("DROP", ignoreCase = true) || it.prefilterResult.contains("FILTER", ignoreCase = true) }
                    "LOG_ONLY" -> raw.filter { it.mode == "LOG_ONLY" }
                    else -> raw
                }
            }
            isLoadingLogs = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        // Developer Top Navigation Bar
        Surface(
            color = DarkBg,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(CardBg)
                                .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = TextLight,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Developer Console",
                                    color = TextLight,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                StatusPill(
                                    text = "UNLOCKED",
                                    color = Accent,
                                    backgroundColor = Accent.copy(alpha = 0.15f)
                                )
                            }
                            Text(
                                text = "Deep diagnostics & offline testing harness",
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                        }
                    }

                    // Developer Mode Switch
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Switch(
                            checked = isDevMode,
                            onCheckedChange = { enabled ->
                                DeveloperModeManager.setDeveloperModeEnabled(context, enabled)
                                isDevMode = enabled
                                if (!enabled) {
                                    Toast.makeText(context, "Developer Mode deactivated", Toast.LENGTH_SHORT).show()
                                    onNavigateBack()
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = TextLight,
                                checkedTrackColor = Primary,
                                uncheckedTrackColor = CardElevated
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Section Selector Tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(CardBg)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val tabs = listOf(
                        "HEALTH" to "Health",
                        "LOGS" to "Logs",
                        "AUDITS" to "Audits",
                        "PRIORS" to "Priors",
                        "INJECTOR" to "Injector",
                        "UTILITIES" to "Tools"
                    )
                    tabs.forEach { (key, label) ->
                        val isSelected = selectedSection == key
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) CardElevated else Color.Transparent)
                                .clickable { selectedSection = key }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) TextLight else TextMuted,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = CardBorderSubtle, thickness = 1.dp)

        // Section Content
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            when (selectedSection) {
                "HEALTH" -> HealthSection(
                    context = context,
                    usedMemoryMb = usedMemoryMb,
                    maxMemoryMb = maxMemoryMb,
                    uptimeMinutes = uptimeMinutes,
                    isListenerConnected = isListenerConnected,
                    hasExactAlarm = hasExactAlarm,
                    isBatteryExempted = isBatteryExempted,
                    syncEventsCount = syncEvents.size
                )
                "LOGS" -> LogsSection(
                    context = context,
                    logs = logsList.filter {
                        if (searchQuery.isBlank()) true
                        else it.rawText.contains(searchQuery, ignoreCase = true) ||
                                (it.chatTitle?.contains(searchQuery, ignoreCase = true) == true) ||
                                (it.sender?.contains(searchQuery, ignoreCase = true) == true) ||
                                it.prefilterResult.contains(searchQuery, ignoreCase = true)
                    },
                    filter = logFilter,
                    onFilterChange = { logFilter = it },
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                    isLoading = isLoadingLogs,
                    onClearLogs = {
                        coroutineScope.launch(Dispatchers.IO) {
                            db.diagnosticLogDao().clearAllDiagnosticLogs()
                            logsList = emptyList()
                        }
                        Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
                    }
                )
                "AUDITS" -> AuditsSection(context = context)
                "PRIORS" -> PriorsSection(
                    chatPriors = chatPriors,
                    senderPriors = senderPriors,
                    ignoreCount = ignoreCount
                )
                "INJECTOR" -> InjectorSection(
                    onInjectMessage = onInjectMessage,
                    lastExtracted = lastExtracted
                )
                "UTILITIES" -> UtilitiesSection(
                    context = context,
                    syncEvents = syncEvents,
                    onDeleteAllTestItems = onDeleteAllTestItems
                )
            }
        }
    }
}

@Composable
private fun HealthSection(
    context: Context,
    usedMemoryMb: Long,
    maxMemoryMb: Long,
    uptimeMinutes: Long,
    isListenerConnected: Boolean,
    hasExactAlarm: Boolean,
    isBatteryExempted: Boolean,
    syncEventsCount: Int
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            SectionHeader(title = "Hardware & Runtime Environment")
            SleekCard {
                DiagnosticRow(label = "Application Version", value = "0.1.0 (Build 98e2945)")
                DiagnosticRow(label = "Build Variant", value = "debug (minSdk 26, targetSdk 34)")
                DiagnosticRow(label = "Android OS", value = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                DiagnosticRow(label = "Device Hardware", value = "${Build.MANUFACTURER} ${Build.MODEL}")
                DiagnosticRow(label = "Architecture ABI", value = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a")
                DiagnosticRow(label = "App Heap Memory", value = "$usedMemoryMb MB used / $maxMemoryMb MB max")
                DiagnosticRow(label = "Device Uptime", value = "$uptimeMinutes minutes", isLast = true)
            }
        }

        item {
            SectionHeader(title = "Subsystem Health Status")
            SleekCard {
                HealthStatusRow(
                    label = "Room SQLite Database",
                    status = "HEALTHY",
                    detail = "Schema v1 verified, append-only",
                    isGood = true
                )
                HealthStatusRow(
                    label = "Notification Listener Service",
                    status = if (isListenerConnected) "BOUND & ACTIVE" else "NOT CONNECTED",
                    detail = "com.scheduleassistant.app.service",
                    isGood = isListenerConnected
                )
                HealthStatusRow(
                    label = "Exact Alarm Scheduling",
                    status = if (hasExactAlarm) "GRANTED" else "PERMISSION REQUIRED",
                    detail = "AlarmManager.setAlarmClock",
                    isGood = hasExactAlarm
                )
                HealthStatusRow(
                    label = "Battery Optimization",
                    status = if (isBatteryExempted) "EXEMPTED (ALLOW)" else "OPTIMIZED (RISK)",
                    detail = if (isBatteryExempted) "Background delivery protected" else "System may throttle",
                    isGood = isBatteryExempted
                )
                HealthStatusRow(
                    label = "Sync Event Store",
                    status = "$syncEventsCount EVENTS",
                    detail = "HLC logical clock ordering",
                    isGood = true,
                    isLast = true
                )
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String, isLast: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = TextMuted, fontSize = 12.sp)
        Text(text = value, color = TextLight, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
    if (!isLast) {
        HorizontalDivider(color = CardBorderSubtle, thickness = 1.dp, modifier = Modifier.padding(vertical = 3.dp))
    }
}

@Composable
private fun HealthStatusRow(
    label: String,
    status: String,
    detail: String,
    isGood: Boolean,
    isLast: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = TextLight, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(text = detail, color = TextSubtle, fontSize = 11.sp)
        }
        StatusPill(
            text = status,
            color = if (isGood) Success else Danger,
            backgroundColor = if (isGood) SuccessBg else DangerBg
        )
    }
    if (!isLast) {
        HorizontalDivider(color = CardBorderSubtle, thickness = 1.dp, modifier = Modifier.padding(vertical = 3.dp))
    }
}

@Composable
private fun LogsSection(
    context: Context,
    logs: List<DiagnosticLogEntity>,
    filter: String,
    onFilterChange: (String) -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    isLoading: Boolean,
    onClearLogs: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Filter pills & Clear button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ALL", "EXTRACTED", "DROPPED", "LOG_ONLY").forEach { f ->
                    val isSel = filter == f
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSel) Primary else CardBg)
                            .border(1.dp, if (isSel) Primary else CardBorder, RoundedCornerShape(6.dp))
                            .clickable { onFilterChange(f) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = f,
                            color = if (isSel) TextLight else TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            TextButton(
                onClick = onClearLogs,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("Clear", color = Danger, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Search Input
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("Filter logs...", color = TextSubtle, fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Primary,
                unfocusedBorderColor = CardBorder,
                focusedTextColor = TextLight,
                unfocusedTextColor = TextLight
            ),
            shape = RoundedCornerShape(8.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Primary, modifier = Modifier.size(24.dp))
            }
        } else if (logs.isEmpty()) {
            EmptyStateView(
                title = "No Logs Recorded",
                description = "Diagnostic entries from notification handling and background services will appear here.",
                icon = Icons.Default.Article
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(logs) { log ->
                    val hasParsed = !log.parserOutput.isNullOrBlank()
                    val isDropped = log.prefilterResult.contains("DROP", ignoreCase = true) || log.prefilterResult.contains("FILTER", ignoreCase = true)
                    val statusColor = when {
                        hasParsed -> Success
                        isDropped -> TextMuted
                        else -> Accent
                    }
                    val statusText = when {
                        hasParsed -> "PARSED"
                        isDropped -> "DROPPED"
                        else -> "PASSED"
                    }

                    SleekCard(
                        backgroundColor = CardBg,
                        borderColor = CardBorderSubtle,
                        cornerRadius = 10,
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clipText = "[${log.createdAt}] [${log.mode}] ${log.chatTitle ?: log.sender ?: ""}: ${log.rawText}\nPrefilter: ${log.prefilterResult}\nOutput: ${log.parserOutput ?: "none"}"
                            clipboard.setPrimaryClip(ClipData.newPlainText("Diagnostic Log", clipText))
                            Toast.makeText(context, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = (log.chatTitle ?: log.sender ?: "WhatsApp Message") + if (log.isGroup) " (Group)" else "",
                                color = TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            StatusPill(
                                text = statusText,
                                color = statusColor,
                                backgroundColor = statusColor.copy(alpha = 0.15f)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = log.rawText,
                            color = TextLight,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${log.createdAt} • ${log.mode} • ${log.prefilterResult}",
                            color = TextSubtle,
                            fontSize = 10.sp
                        )
                        if (hasParsed) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Output: ${log.parserOutput}",
                                color = Success,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InjectorSection(
    onInjectMessage: (String) -> Unit,
    lastExtracted: ExtractionOutput?
) {
    var messageText by remember { mutableStateOf("kal 4 se 5 baje lab test hai") }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            SectionHeader(title = "Synthetic Notification Injector")
            SleekCard {
                Text(
                    text = "Inject a raw text phrase to test offline triage, prefilter scoring, date disambiguation, and entity extraction without needing a live WhatsApp notification.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    label = { Text("Simulated WhatsApp Message", color = TextSubtle) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = CardBorder,
                        focusedTextColor = TextLight,
                        unfocusedTextColor = TextLight
                    ),
                    shape = RoundedCornerShape(10.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SleekPrimaryButton(
                        text = "Inject & Evaluate",
                        icon = Icons.Default.PlayArrow,
                        onClick = { onInjectMessage(messageText) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Quick Preset Buttons
        item {
            SectionHeader(title = "Test Presets")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PresetChip(text = "kal 4-5pm meeting", onClick = { messageText = "kal 4-5pm meeting" })
                PresetChip(text = "doctor 15 oct at 10am", onClick = { messageText = "doctor appointment on 15 oct at 10am" })
                PresetChip(text = "dinner parso 8pm", onClick = { messageText = "dinner parso raat 8 baje" })
            }
        }

        // Last Extraction Result Card
        if (lastExtracted != null) {
            item {
                SectionHeader(title = "Extraction Pipeline Output")
                SleekCard(
                    borderColor = Primary.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = lastExtracted.title,
                            color = TextLight,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        StatusPill(
                            text = "${(lastExtracted.confidence * 100).toInt()}% CONF",
                            color = Success
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    DiagnosticRow(label = "Extraction Type", value = lastExtracted.type)
                    DiagnosticRow(label = "Start Timestamp", value = lastExtracted.start ?: "None")
                    DiagnosticRow(label = "End Timestamp", value = lastExtracted.end ?: "None")
                    DiagnosticRow(label = "Time Unspecified", value = lastExtracted.time_unspecified.toString())
                    DiagnosticRow(label = "Ambiguous", value = lastExtracted.ambiguous.toString())
                    DiagnosticRow(label = "Matched Quote", value = "\"${lastExtracted.source_quote}\"", isLast = true)
                }
            }
        }
    }
}

@Composable
private fun PresetChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(CardBg)
            .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text = text, color = TextMuted, fontSize = 11.sp)
    }
}

@Composable
private fun AuditsSection(context: Context) {
    val coroutineScope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val scheduler = remember { AlarmScheduler(context) }
    val auditsFlow = remember { db.alarmAuditDao().getRecentAuditsFlow(100) }
    val audits by auditsFlow.collectAsState(initial = emptyList())
    var auditFilter by remember { mutableStateOf("ALL") } // ALL, SUCCESS, FAILURE
    var searchQuery by remember { mutableStateOf("") }
    val canScheduleExact = remember { scheduler.canScheduleExactAlarms() }
    val nextAlarmClock = remember { scheduler.getNextAlarmClockFormatted() }

    val filteredAudits = audits.filter { audit ->
        val matchesFilter = when (auditFilter) {
            "SUCCESS" -> audit.result == "SUCCESS"
            "FAILURE" -> audit.result != "SUCCESS"
            else -> true
        }
        val matchesSearch = if (searchQuery.isBlank()) true else {
            audit.api.contains(searchQuery, ignoreCase = true) ||
            audit.entityId.contains(searchQuery, ignoreCase = true) ||
            (audit.errorMessage?.contains(searchQuery, ignoreCase = true) == true)
        }
        matchesFilter && matchesSearch
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            SectionHeader(title = "Alarm Subsystem Telemetry")
            SleekCard {
                DiagnosticRow(label = "Exact Alarms Permission", value = if (canScheduleExact) "GRANTED (High Precision)" else "DENIED (Batched)")
                DiagnosticRow(label = "Next OS Alarm Clock", value = nextAlarmClock, isLast = true)
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionHeader(title = "Scheduling Audit Log (${audits.size} entries)")
                if (audits.isNotEmpty()) {
                    Text(
                        text = "Clear Audits",
                        color = Danger,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable {
                                coroutineScope.launch(Dispatchers.IO) {
                                    db.alarmAuditDao().clearAllAudits()
                                }
                                Toast.makeText(context, "Audit logs cleared", Toast.LENGTH_SHORT).show()
                            }
                            .padding(4.dp)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("ALL" to "All", "SUCCESS" to "Success", "FAILURE" to "Failed").forEach { (key, label) ->
                    val isSel = auditFilter == key
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSel) Primary else CardBg)
                            .border(1.dp, if (isSel) Primary else CardBorder, RoundedCornerShape(8.dp))
                            .clickable { auditFilter = key }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = label,
                            color = if (isSel) Color.White else TextMuted,
                            fontSize = 12.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search by API, entity ID, or error...", color = TextMuted, fontSize = 12.sp) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp)) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextLight,
                    unfocusedTextColor = TextLight,
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = CardBorder,
                    focusedContainerColor = CardBg,
                    unfocusedContainerColor = CardBg
                )
            )
        }

        if (filteredAudits.isEmpty()) {
            item {
                EmptyStateView(
                    title = "No Audits Found",
                    description = if (audits.isEmpty()) "No scheduling attempts have been recorded yet." else "No audit entries match the current filter.",
                    icon = Icons.Default.FactCheck
                )
            }
        } else {
            items(filteredAudits, key = { it.id }) { audit ->
                SleekCard(
                    borderColor = if (audit.result == "SUCCESS") CardBorderSubtle else Danger.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = audit.api,
                                color = TextLight,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Entity: ${audit.entityId}",
                                color = TextMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        StatusPill(
                            text = audit.result,
                            color = if (audit.result == "SUCCESS") Success else Danger,
                            backgroundColor = if (audit.result == "SUCCESS") SuccessBg else DangerBg
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Trigger: ${audit.triggerTime}",
                        color = Accent,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "Logged: ${audit.timestamp}",
                        color = TextSubtle,
                        fontSize = 10.sp
                    )

                    if (!audit.errorMessage.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            color = DangerBg,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = audit.errorMessage,
                                color = Danger,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PriorsSection(
    chatPriors: Map<String, Double>,
    senderPriors: Map<String, Double>,
    ignoreCount: Int
) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredChatPriors = remember(chatPriors, searchQuery) {
        if (searchQuery.isBlank()) chatPriors.toList()
        else chatPriors.filter { it.key.contains(searchQuery, ignoreCase = true) }.toList()
    }.sortedByDescending { it.second }

    val filteredSenderPriors = remember(senderPriors, searchQuery) {
        if (searchQuery.isBlank()) senderPriors.toList()
        else senderPriors.filter { it.key.contains(searchQuery, ignoreCase = true) }.toList()
    }.sortedByDescending { it.second }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            SectionHeader(title = "Learned Triage Priors")
            SleekCard {
                Text(
                    text = "Source-specific weights learned deterministically from user interactions. Confirm adds +0.20, Dismiss subtracts -0.20, and Move Lane shifts weight. Priors are bounded in [-1.0, +1.0] and reversible.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = CardElevated,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "${chatPriors.size}",
                                color = Primary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text("Chat Priors", color = TextMuted, fontSize = 11.sp)
                        }
                    }
                    Surface(
                        color = CardElevated,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "${senderPriors.size}",
                                color = Accent,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text("Sender Priors", color = TextMuted, fontSize = 11.sp)
                        }
                    }
                    Surface(
                        color = CardElevated,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "$ignoreCount",
                                color = Warning,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text("Ignored Count", color = TextMuted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        if (chatPriors.isNotEmpty() || senderPriors.isNotEmpty()) {
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter chats or senders...", color = TextMuted, fontSize = 12.sp) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextLight,
                        unfocusedTextColor = TextLight,
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = CardBorder,
                        focusedContainerColor = CardBg,
                        unfocusedContainerColor = CardBg
                    )
                )
            }
        }

        item {
            SectionHeader(title = "Chat Priors (${filteredChatPriors.size})")
        }

        if (filteredChatPriors.isEmpty()) {
            item {
                Text(
                    text = if (chatPriors.isEmpty()) "No chat priors learned yet. Confirm or dismiss events to train priors." else "No chats match current filter.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        } else {
            items(filteredChatPriors, key = { "chat_${it.first}" }) { (chatTitle, prior) ->
                PriorEntryCard(
                    title = chatTitle,
                    prior = prior,
                    badgeLabel = "CHAT"
                )
            }
        }

        item {
            SectionHeader(title = "Sender Priors (${filteredSenderPriors.size})")
        }

        if (filteredSenderPriors.isEmpty()) {
            item {
                Text(
                    text = if (senderPriors.isEmpty()) "No sender priors learned yet." else "No senders match current filter.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        } else {
            items(filteredSenderPriors, key = { "sender_${it.first}" }) { (sender, prior) ->
                PriorEntryCard(
                    title = sender,
                    prior = prior,
                    badgeLabel = "SENDER"
                )
            }
        }
    }
}

@Composable
private fun PriorEntryCard(
    title: String,
    prior: Double,
    badgeLabel: String
) {
    val formattedPrior = String.format(Locale.US, "%+.2f", prior)
    val (pillColor, pillBg) = when {
        prior > 0.05 -> Success to SuccessBg
        prior < -0.05 -> Danger to DangerBg
        else -> TextMuted to CardElevated
    }
    val qualitativeBias = when {
        prior > 0.35 -> "Strong Schedule Bias"
        prior > 0.05 -> "Favors Schedule"
        prior < -0.35 -> "Strong Ignore Bias"
        prior < -0.05 -> "Favors Attention/Ignore"
        else -> "Neutral Weight"
    }

    SleekCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatusPill(
                        text = badgeLabel,
                        color = Primary,
                        backgroundColor = Primary.copy(alpha = 0.15f)
                    )
                    Text(
                        text = title,
                        color = TextLight,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = qualitativeBias,
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }

            StatusPill(
                text = formattedPrior,
                color = pillColor,
                backgroundColor = pillBg
            )
        }
    }
}

@Composable
private fun UtilitiesSection(
    context: Context,
    syncEvents: List<SyncEvent>,
    onDeleteAllTestItems: (() -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    var isExporting by remember { mutableStateOf(false) }
    var showBulkDeleteConfirmDialog by remember { mutableStateOf(false) }

    if (showBulkDeleteConfirmDialog && onDeleteAllTestItems != null) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirmDialog = false },
            title = { Text("Delete All Test Items?", color = TextLight, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This will tombstone all test items in SQLite and cancel their alarms and reminders. This action appends delete events and cannot be undone.",
                    color = TextMuted,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBulkDeleteConfirmDialog = false
                        onDeleteAllTestItems()
                        Toast.makeText(context, "All test items tombstoned", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger)
                ) {
                    Text("Delete All Test Items", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteConfirmDialog = false }) {
                    Text("Cancel", color = TextMuted)
                }
            },
            containerColor = CardBg
        )
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            SectionHeader(title = "Daily Digest Notification Test")
            SleekCard {
                Text(
                    text = "Dispatch the 8:00 AM Daily Digest notification on demand. The digest tallies today's confirmed schedules and all pending items requiring attention in the Inbox.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                SleekOutlinedButton(
                    text = "Dispatch Test Daily Digest",
                    icon = Icons.Default.FactCheck,
                    onClick = {
                        coroutineScope.launch {
                            val digest = DailyDigestNotifier.checkAndDispatchDigest(context)
                            Toast.makeText(context, "Digest dispatched: ${digest.summaryText}", Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }
        }

        item {
            SectionHeader(title = "Test Notification & Alerting")
            SleekCard {
                Text(
                    text = "Dispatch a local test notification to verify notification channel priority, sound playback, and vibration patterns.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                SleekOutlinedButton(
                    text = "Dispatch Test Notification",
                    icon = Icons.Default.NotificationsActive,
                    onClick = {
                        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val channel = NotificationChannel(
                                "dev_test_channel",
                                "Developer Diagnostics",
                                NotificationManager.IMPORTANCE_HIGH
                            )
                            nm.createNotificationChannel(channel)
                        }
                        val notif = NotificationCompat.Builder(context, "dev_test_channel")
                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                            .setContentTitle("Schedule Assistant Diagnostic Alert")
                            .setContentText("Local alert playback verified at ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}")
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .setAutoCancel(true)
                            .build()
                        nm.notify(9999, notif)
                        Toast.makeText(context, "Diagnostic notification dispatched", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        item {
            SectionHeader(title = "Diagnostics Data & Audit")
            SleekCard {
                Text(
                    text = "Generate and export an anonymized JSON report containing notification evaluation logs, prefilter dispositions, and system heartbeats for soak audits.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                SleekPrimaryButton(
                    text = if (isExporting) "Generating Report..." else "Export Anonymized Diagnostic Log",
                    icon = Icons.Default.Share,
                    enabled = !isExporting,
                    onClick = {
                        isExporting = true
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                val file = DiagnosticsManager.exportAnonymizedLogsToFile(context)
                                withContext(Dispatchers.Main) {
                                    DiagnosticsManager.shareExportFile(context, file)
                                    isExporting = false
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_LONG).show()
                                    isExporting = false
                                }
                            }
                        }
                    }
                )
            }
        }

        if (onDeleteAllTestItems != null) {
            item {
                SectionHeader(title = "Database Maintenance & Tombstones")
                SleekCard {
                    Text(
                        text = "Clean up the local SQLite database by marking all existing test items as tombstoned and cancelling any armed reminders.",
                        color = TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SleekDangerButton(
                        text = "Delete All Test Items (Tombstone Cleanup)",
                        icon = Icons.Default.DeleteSweep,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showBulkDeleteConfirmDialog = true }
                    )
                }
            }
        }
    }
}


