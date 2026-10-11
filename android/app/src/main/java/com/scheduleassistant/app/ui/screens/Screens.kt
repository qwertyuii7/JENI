package com.scheduleassistant.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.scheduleassistant.app.data.db.AppDatabase
import com.scheduleassistant.app.data.db.SoakCheckpointEntity
import com.scheduleassistant.app.data.diagnostics.DiagnosticsManager
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scheduleassistant.app.data.model.ExtractionOutput
import com.scheduleassistant.app.data.model.ScheduleEntity
import com.scheduleassistant.app.data.model.ScheduleStatus
import com.scheduleassistant.app.data.model.SyncEvent
import com.scheduleassistant.app.ui.theme.*

@Composable
fun HomeScreen(
    schedules: List<ScheduleEntity>,
    isListenerConnected: Boolean = true,
    onNavigateToPending: () -> Unit,
    onNavigateToDebug: () -> Unit
) {
    val context = LocalContext.current
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)

    val pendingCount = schedules.count { it.status == ScheduleStatus.PENDING }
    val confirmedCount = schedules.count { it.status == ScheduleStatus.CONFIRMED }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Schedule Assistant",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = TextLight
            )
            Text(
                text = "Offline-First On-Device Engine (Asia/Kolkata)",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted
            )
        }

        // Requirement 4: Visible warning when notification listener is down
        if (!isListenerConnected) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0x33EF4444)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Error, contentDescription = null, tint = Danger)
                            Spacer(Modifier.width(8.dp))
                            Text("Notification Listener Disconnected", color = Danger, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Schedule Assistant is not receiving WhatsApp notifications. Listener service is inactive or unbound.",
                            color = TextLight,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Danger)
                        ) {
                            Text("Reconnect / Open Settings", color = Color.White)
                        }
                    }
                }
            }
        }

        // Battery Optimization Warning Banner
        if (!isIgnoringBatteryOptimizations) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0x33F59E0B)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Warning)
                            Spacer(Modifier.width(8.dp))
                            Text("Background Optimization Warning", color = Warning, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Android may kill notification listener in background. Exclude this app from battery optimization.",
                            color = TextLight,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Warning)
                        ) {
                            Text("Disable Optimization", color = Color.Black)
                        }
                    }
                }
            }
        }

        // Stat Cards
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier.weight(1f).clickable { onNavigateToPending() },
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Pending Review", color = TextMuted, fontSize = 12.sp)
                        Text("$pendingCount", color = Warning, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Confirmed", color = TextMuted, fontSize = 12.sp)
                        Text("$confirmedCount", color = Success, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Text(
                text = "Today's Schedule",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextLight
            )
        }

        val todayConfirmed = schedules.filter { it.status == ScheduleStatus.CONFIRMED }
        if (todayConfirmed.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No schedules confirmed yet. Use Debug screen to test notification ingestion.", color = TextMuted)
                }
            }
        } else {
            items(todayConfirmed) { item ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(item.title, fontWeight = FontWeight.Bold, color = TextLight, fontSize = 16.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(item.start ?: item.start_date ?: "Time unspecified", color = Accent, fontSize = 13.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("\"${item.source_quote}\"", color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
fun PendingScreen(
    schedules: List<ScheduleEntity>,
    onConfirm: (String, String?) -> Unit,
    onDismiss: (String) -> Unit
) {
    val pendingList = schedules.filter { it.status == ScheduleStatus.PENDING }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Pending Review Queue",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = TextLight
            )
            Text(
                text = "One-tap confirm or choose alternate AM/PM reading",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted
            )
        }

        if (pendingList.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    Text("No items waiting for review.", color = TextMuted)
                }
            }
        } else {
            items(pendingList) { item ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(item.title, fontWeight = FontWeight.Bold, color = TextLight, fontSize = 17.sp)
                            Badge(containerColor = if (item.ambiguous) Warning else Primary) {
                                Text("${(item.confidence * 100).toInt()}% Conf", color = Color.White)
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = item.start ?: "Date: ${item.start_date} (Time Unspecified)",
                            color = Accent,
                            fontSize = 14.sp
                        )

                        Spacer(Modifier.height(8.dp))
                        // Minimal source quote in terminal box
                        Surface(
                            color = Color(0x33000000),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Source: \"${item.source_quote}\"",
                                color = Color(0xFF38BDF8),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(8.dp)
                            )
                        }

                        // Ambiguous AM/PM Dual Reading buttons
                        if (item.ambiguous && item.start != null) {
                            Spacer(Modifier.height(8.dp))
                            Text("Ambiguous AM/PM detected — choose reading:", color = Warning, fontSize = 12.sp)
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { onConfirm(item.id, item.start) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Warning)
                                ) {
                                    Text("Confirm Reading A", color = Color.Black, fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        // 12-hour flip
                                        val dateStr = item.start!!.split("T")[0]
                                        val timeStr = item.start!!.split("T")[1].substring(0, 5)
                                        val h = timeStr.split(":")[0].toInt()
                                        val m = timeStr.split(":")[1].toInt()
                                        val altH = if (h < 12) h + 12 else h - 12
                                        val altIso = String.format(Locale.US, "%sT%02d:%02d:00+05:30", dateStr, altH, m)
                                        onConfirm(item.id, altIso)
                                    }
                                ) {
                                    Text("Confirm Reading B", color = TextLight, fontSize = 12.sp)
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!item.ambiguous && item.start != null) {
                                Button(
                                    onClick = { onConfirm(item.id, null) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Success)
                                ) {
                                    Text("Confirm", color = Color.White)
                                }
                            }
                            Button(
                                onClick = { onDismiss(item.id) },
                                colors = ButtonDefaults.buttonColors(containerColor = Danger)
                            ) {
                                Text("Dismiss", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AlertsScreen(
    reminders: List<com.scheduleassistant.app.data.model.ReminderEntity>
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Alarms & Reminders",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = TextLight
            )
            Text(
                text = "Exact AlarmManager.setAlarmClock alerts",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted
            )
        }

        if (reminders.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    Text("No active alarms or reminders.", color = TextMuted)
                }
            }
        } else {
            items(reminders) { rem ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (rem.type == "alarm") "⏰ ALARM" else "🔔 REMINDER",
                                    fontWeight = FontWeight.Bold,
                                    color = if (rem.type == "alarm") Warning else Accent,
                                    fontSize = 12.sp
                                )
                            }
                            Text(rem.title, fontWeight = FontWeight.SemiBold, color = TextLight, fontSize = 16.sp)
                            Text("Trigger: ${rem.trigger_time}", color = TextMuted, fontSize = 12.sp)
                        }

                        Badge(containerColor = if (rem.status.name == "FIRED") Success else Primary) {
                            Text(rem.status.name, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DebugScreen(
    onInjectMessage: (String) -> Unit,
    lastExtracted: ExtractionOutput?,
    events: List<SyncEvent>
) {
    var textInput by remember { mutableStateOf("kal 4 baje DBMS class hai") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Debug & Inspection Screen",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = TextLight
            )
            Text(
                text = "Inject fake WhatsApp notifications to test offline pipeline",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Paste or Type Message Text", fontWeight = FontWeight.Bold, color = TextLight)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextLight,
                            unfocusedTextColor = TextLight
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { onInjectMessage(textInput) },
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Text("Inject as Fake Notification")
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("Quick Sample Chips:", color = TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SuggestionChip(
                            onClick = { textInput = "kal 4 baje DBMS class hai" },
                            label = { Text("kal 4 baje", fontSize = 11.sp) }
                        )
                        SuggestionChip(
                            onClick = { textInput = "meeting Friday 11 pe shift ho gayi" },
                            label = { Text("Friday 11 pe", fontSize = 11.sp) }
                        )
                    }
                }
            }
        }

        if (lastExtracted != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Last Parser Extraction Output", fontWeight = FontWeight.Bold, color = Accent)
                        Spacer(Modifier.height(4.dp))
                        Text("Type: ${lastExtracted.type} | Title: ${lastExtracted.title}", color = TextLight, fontSize = 13.sp)
                        Text("Start: ${lastExtracted.start ?: "Date Only: " + lastExtracted.start_date}", color = TextLight, fontSize = 13.sp)
                        Text("Ambiguous: ${lastExtracted.ambiguous} | Conf: ${lastExtracted.confidence}", color = TextLight, fontSize = 13.sp)
                        Text("Quote: \"${lastExtracted.source_quote}\"", color = Color(0xFF38BDF8), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        }

        item {
            Text("Room Event-Sourced Log (Append-Only)", fontWeight = FontWeight.Bold, color = TextLight)
        }

        items(events.takeLast(10).reversed()) { ev ->
            Surface(
                color = CardBg,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(ev.type, fontWeight = FontWeight.Bold, color = Primary, fontSize = 13.sp)
                        Text("seq: ${ev.seq}", color = TextMuted, fontSize = 11.sp)
                    }
                    Text("HLC: ${ev.hlc}", color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("Entity: ${ev.entity_id}", color = TextLight, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun DiagnosticsScreen(
    scheduledAlarmsCount: Int = 0,
    onRequestNotificationPermission: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val lifecycleOwner = LocalLifecycleOwner.current

    // Requirement 2: Dynamically re-read battery optimization on every resume (never assume persisted after reboot)
    var isIgnoringBatteryOptimizations by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isListenerConnected by DiagnosticsManager.isListenerConnected.collectAsState()
    val isForegroundRunning by DiagnosticsManager.isForegroundServiceRunning.collectAsState()

    // Requirement 4: Log-only mode must stay on
    var isLogOnlyMode by remember { mutableStateOf(DiagnosticsManager.isLogOnlyMode(context)) }
    var isExporting by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var soakRecordMessage by remember { mutableStateOf<String?>(null) }

    val db = remember { AppDatabase.getInstance(context) }
    val soakFlow = remember { db.soakCheckpointDao().getAllSoakCheckpointsFlow() }
    val soakCheckpoints by soakFlow.collectAsState(initial = emptyList())

    val lastNotificationTime = remember { DiagnosticsManager.getLastNotificationReceivedTime(context) }
    val lastBootTime = remember { DiagnosticsManager.getLastBootRecoveryTime(context) }

    val isListenerAccessGranted = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    val isNotificationPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    val timeFormatter = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Diagnostics & Soak Audit",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = TextLight
            )
            Text(
                text = "Samsung Galaxy A15 5G • Android 16 • One UI 8.5",
                style = MaterialTheme.typography.bodyMedium,
                color = Accent,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Listener Warning Banner if Down
        if (!isListenerConnected) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0x33EF4444)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Error, contentDescription = null, tint = Danger)
                            Spacer(Modifier.width(8.dp))
                            Text("Notification Listener Disconnected", color = Danger, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Listener service is unbound. WhatsApp schedule messages will NOT be ingested. (needs owner verification)",
                            color = TextLight,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Danger)
                        ) {
                            Text("Open Notification Access", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 1. Diagnostics State Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Core Hardware & Background Diagnostics", fontWeight = FontWeight.Bold, color = TextLight, fontSize = 16.sp)

                    // Restricted Settings Status (Requirement 2)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Restricted Settings (One UI 8.5 / Android 16)", color = TextLight, fontSize = 14.sp)
                            Text(
                                if (isListenerAccessGranted) "Sideload restrictions cleared. (needs owner verification)"
                                else "If Notification Access toggle is disabled, tap App Info -> ⋮ -> 'Allow restricted settings'. (needs owner verification)",
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Badge(containerColor = if (isListenerAccessGranted) Success else Warning) {
                            Text(if (isListenerAccessGranted) "ALLOWED" else "VERIFY", color = Color.White)
                        }
                    }

                    Divider(color = CardBorder)

                    // Samsung Sleeping / Deep Sleeping Status (Requirement 2)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Samsung Sleep / Deep Sleep Status", color = TextLight, fontSize = 14.sp)
                            Text(
                                "Settings -> Battery -> Background usage limits -> Never auto sleeping apps -> Add Schedule Assistant. (needs owner verification)",
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Badge(containerColor = Primary) {
                            Text("VERIFY MANUALLY", color = Color.White)
                        }
                    }

                    Divider(color = CardBorder)

                    // Battery Whitelist Status (Requirement 2: re-read after reboot)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Battery Whitelist (Live OS Query)", color = TextLight, fontSize = 14.sp)
                            Text(
                                if (isIgnoringBatteryOptimizations) "Exempted from Doze restrictions. Freshly queried. (needs owner verification)"
                                else "Optimized (Risk of OS sleep kill). Tap Configure. (needs owner verification)",
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Badge(containerColor = if (isIgnoringBatteryOptimizations) Success else Warning) {
                            Text(if (isIgnoringBatteryOptimizations) "WHITELISTED" else "OPTIMIZED", color = Color.Black)
                        }
                    }

                    Divider(color = CardBorder)

                    // Listener Connected State
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Notification Listener State", color = TextLight, fontSize = 14.sp)
                            Text(
                                if (isListenerConnected) "Connected to notification pipeline. (needs owner verification)"
                                else "Listener unbound / disconnected. (needs owner verification)",
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Badge(containerColor = if (isListenerConnected) Success else Danger) {
                            Text(if (isListenerConnected) "CONNECTED" else "DOWN", color = Color.White)
                        }
                    }

                    Divider(color = CardBorder)

                    // Last Notification Ingested
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Last Notification Ingested", color = TextLight, fontSize = 14.sp)
                            Text("WhatsApp message timestamp. (needs owner verification)", color = TextMuted, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (lastNotificationTime != null) timeFormatter.format(Date(lastNotificationTime)) else "Never",
                            color = if (lastNotificationTime != null) Accent else TextMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Divider(color = CardBorder)

                    // Foreground Service State
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Foreground Service", color = TextLight, fontSize = 14.sp)
                            Text("specialUse background service. (needs owner verification)", color = TextMuted, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Badge(containerColor = if (isForegroundRunning) Success else Warning) {
                            Text(if (isForegroundRunning) "ACTIVE" else "STOPPED", color = Color.White)
                        }
                    }

                    Divider(color = CardBorder)

                    // Notification Permission
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("POST_NOTIFICATIONS", color = TextLight, fontSize = 14.sp)
                            Text("System notification posting. (needs owner verification)", color = TextMuted, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        if (isNotificationPermissionGranted) {
                            Badge(containerColor = Success) { Text("GRANTED", color = Color.White) }
                        } else {
                            Button(
                                onClick = { onRequestNotificationPermission() },
                                colors = ButtonDefaults.buttonColors(containerColor = Warning)
                            ) {
                                Text("Grant", color = Color.Black, fontSize = 12.sp)
                            }
                        }
                    }

                    Divider(color = CardBorder)

                    // Count of Scheduled Alarms
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Scheduled Exact Alarms", color = TextLight, fontSize = 14.sp)
                            Text("AlarmManager.setAlarmClock (Doze-safe). (needs owner verification)", color = TextMuted, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "$scheduledAlarmsCount active",
                            color = Primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }

                    Divider(color = CardBorder)

                    // Last Boot-Recovery Time
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Last Boot Recovery Time", color = TextLight, fontSize = 14.sp)
                            Text("BOOT_COMPLETED alarm restoration. (needs owner verification)", color = TextMuted, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (lastBootTime != null) timeFormatter.format(Date(lastBootTime)) else "None since install",
                            color = TextLight,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // 2. Soak Testing Hourly Log Card (Requirement 4)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("48-Hour Soak Log (Hourly Checkpoints)", fontWeight = FontWeight.Bold, color = TextLight, fontSize = 16.sp)
                            Text("Log-only mode must stay ON during soak testing. (needs owner verification)", color = TextMuted, fontSize = 12.sp)
                        }
                        Badge(containerColor = Success) {
                            Text("LOG-ONLY ON", color = Color.White)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Log-Only Mode Toggle", color = TextLight, fontSize = 14.sp)
                            Text("Creates NO pending items or alarms while enabled.", color = TextMuted, fontSize = 12.sp)
                        }
                        Switch(
                            checked = isLogOnlyMode,
                            onCheckedChange = { checked ->
                                isLogOnlyMode = checked
                                DiagnosticsManager.setLogOnlyMode(context, checked)
                            }
                        )
                    }

                    Divider(color = CardBorder)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Recorded Checkpoints: ${soakCheckpoints.size}/48", color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    val cp = DiagnosticsManager.recordHourlyCheckpoint(context, "Manual Soak Checkpoint")
                                    soakRecordMessage = "Hour #${cp.hourIndex} recorded (${if (cp.listenerConnected) "Connected" else "Down"})"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Primary)
                        ) {
                            Text("+ Record Hourly Checkpoint", fontSize = 12.sp)
                        }
                    }

                    if (soakRecordMessage != null) {
                        Text(soakRecordMessage!!, color = Success, fontSize = 12.sp)
                    }

                    if (soakCheckpoints.isEmpty()) {
                        Text("No hourly checkpoints recorded yet. Tap '+ Record Hourly Checkpoint' at each soak milestone. (needs owner verification)", color = TextMuted, fontSize = 12.sp)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            soakCheckpoints.takeLast(5).reversed().forEach { cp ->
                                Surface(
                                    color = Color(0x33000000),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp).fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("Hour #${cp.hourIndex} • ${cp.timestamp.take(16).replace("T", " ")}", fontWeight = FontWeight.Bold, color = TextLight, fontSize = 12.sp)
                                            Text(
                                                "Captured: ${cp.notificationsCapturedCount} msgs • Last: ${if (cp.lastNotificationTime != null) timeFormatter.format(Date(cp.lastNotificationTime)) else "None"}",
                                                color = TextMuted,
                                                fontSize = 11.sp
                                            )
                                        }
                                        Badge(containerColor = if (cp.listenerConnected) Success else Danger) {
                                            Text(if (cp.listenerConnected) "CONNECTED" else "DOWN", color = Color.White, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Divider(color = CardBorder)

                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isExporting = true
                                try {
                                    val file = DiagnosticsManager.exportAnonymizedLogsToFile(context)
                                    DiagnosticsManager.shareExportFile(context, file)
                                    exportMessage = "Exported ${file.name}"
                                } catch (e: Exception) {
                                    exportMessage = "Export failed: ${e.message}"
                                } finally {
                                    isExporting = false
                                }
                            }
                        },
                        enabled = !isExporting,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isExporting) "Generating Anonymized Log..." else "Export Anonymized Soak Log (Share Sheet)", color = Color.Black)
                    }

                    if (exportMessage != null) {
                        Text(exportMessage!!, color = Success, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    scheduledAlarmsCount: Int = 0,
    onRequestNotificationPermission: () -> Unit = {}
) {
    DiagnosticsScreen(
        scheduledAlarmsCount = scheduledAlarmsCount,
        onRequestNotificationPermission = onRequestNotificationPermission
    )
}


