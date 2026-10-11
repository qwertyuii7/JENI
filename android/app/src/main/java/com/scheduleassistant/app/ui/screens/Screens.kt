package com.scheduleassistant.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import java.util.Locale
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
fun SettingsScreen() {
    val context = LocalContext.current
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Settings & Device Health",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = TextLight
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Permissions & Background Execution", fontWeight = FontWeight.Bold, color = TextLight)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Notification Listener Access", color = TextLight, fontSize = 14.sp)
                            Text("Required to read WhatsApp schedule updates", color = TextMuted, fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                            }
                        ) {
                            Text("Open")
                        }
                    }

                    Divider(color = CardBorder)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Battery Optimization Exemption", color = TextLight, fontSize = 14.sp)
                            Text(if (isIgnoringBatteryOptimizations) "Exempted (Protected)" else "Optimized (Risk of OS kill)", color = if (isIgnoringBatteryOptimizations) Success else Warning, fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            }
                        ) {
                            Text("Configure")
                        }
                    }

                    Divider(color = CardBorder)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Active Timezone", color = TextMuted, fontSize = 14.sp)
                        Text("Asia/Kolkata (+05:30)", color = TextLight, fontWeight = FontWeight.SemiBold)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Clock Ordering Engine", color = TextMuted, fontSize = 14.sp)
                        Text("Hybrid Logical Clock (HLC)", color = Primary, fontWeight = FontWeight.SemiBold)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Local Database", color = TextMuted, fontSize = 14.sp)
                        Text("Room SQLite (Append-Only)", color = TextLight, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
