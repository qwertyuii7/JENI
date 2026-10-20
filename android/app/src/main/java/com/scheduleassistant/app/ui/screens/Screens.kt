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
import java.util.Calendar
import java.util.TimeZone
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.scheduleassistant.app.data.db.AppDatabase
import com.scheduleassistant.app.data.db.SoakCheckpointEntity
import com.scheduleassistant.app.data.diagnostics.DiagnosticsManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
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
import androidx.compose.ui.draw.clip
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
import com.scheduleassistant.app.data.diagnostics.DeveloperModeManager
import com.scheduleassistant.app.data.diagnostics.DeveloperTapResult
import com.scheduleassistant.app.ui.components.*
import com.scheduleassistant.app.ui.theme.*
import android.widget.Toast

@Composable
fun HomeScreen(
    schedules: List<ScheduleEntity>,
    reminders: List<com.scheduleassistant.app.data.model.ReminderEntity> = emptyList(),
    isListenerConnected: Boolean = true,
    onNavigateToPending: () -> Unit,
    onNavigateToDebug: () -> Unit,
    onDelete: (String) -> Unit = {},
    onRestore: (String) -> Unit = {},
    onEdit: (String, String, String?, Boolean) -> Unit = { _, _, _, _ -> }
) {
    val context = LocalContext.current
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)
    var isLogOnlyMode by remember { mutableStateOf(DiagnosticsManager.isLogOnlyMode(context)) }
    val canUseFsi = DiagnosticsManager.canUseFullScreenIntent(context)
    var showFirstRunPrompt by remember { mutableStateOf(!DiagnosticsManager.isFirstRunPromptShown(context)) }

    val pendingCount = schedules.count { it.status == ScheduleStatus.PENDING }
    val confirmedCount = schedules.count { it.status == ScheduleStatus.CONFIRMED }

    if (showFirstRunPrompt) {
        AlertDialog(
            onDismissRequest = {
                DiagnosticsManager.setFirstRunPromptShown(context, true)
                showFirstRunPrompt = false
            },
            title = {
                Text("Log-Only Mode Active", color = TextLight, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Log-Only mode is enabled by default after installation.\n\n" +
                    "While Log-Only mode is ON, Schedule Assistant captures and evaluates WhatsApp notifications for diagnostic audit, but will NOT create alerts or ring alarms.\n\n" +
                    "You can disable Log-Only mode anytime in Diagnostics & Soak Audit.",
                    color = TextMuted,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    DiagnosticsManager.setFirstRunPromptShown(context, true)
                    showFirstRunPrompt = false
                }) {
                    Text("Got It", color = Primary)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    DiagnosticsManager.setFirstRunPromptShown(context, true)
                    DiagnosticsManager.setLogOnlyMode(context, false)
                    isLogOnlyMode = false
                    showFirstRunPrompt = false
                }) {
                    Text("Turn OFF", color = TextMuted)
                }
            },
            containerColor = CardBg
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        AppTopHeader(
            isListenerActive = isListenerConnected,
            isLogOnly = isLogOnlyMode,
            onStatusClick = onNavigateToDebug
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Today's Overview",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextLight,
                    letterSpacing = (-0.5).sp
                )
                Text(
                    text = "Offline On-Device Engine • Asia/Kolkata",
                    fontSize = 13.sp,
                    color = TextMuted
                )
            }

            // Warning banners with sleek styling
            if (isLogOnlyMode) {
                item {
                    SleekCard(
                        backgroundColor = WarningBg,
                        borderColor = Warning.copy(alpha = 0.4f),
                        cornerRadius = 14
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = Warning, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Log-Only Mode Active", color = Warning, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Inbound WhatsApp messages will be analyzed and logged for diagnostics, but no alerts or alarms will be created.",
                            color = TextLight,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            if (!canUseFsi) {
                item {
                    SleekCard(
                        backgroundColor = DangerBg,
                        borderColor = Danger.copy(alpha = 0.4f),
                        cornerRadius = 14
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Danger, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Full-Screen Alarms Not Allowed", color = Danger, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Android requires full-screen intent permission so alarms can wake the screen and display ringing UI over the lockscreen.",
                            color = TextLight,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        SleekDangerButton(
                            text = "Enable Full-Screen Intent",
                            onClick = {
                                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                } else {
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                }
                                context.startActivity(intent)
                            }
                        )
                    }
                }
            }

            if (!isListenerConnected) {
                item {
                    SleekCard(
                        backgroundColor = DangerBg,
                        borderColor = Danger.copy(alpha = 0.4f),
                        cornerRadius = 14
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Error, contentDescription = null, tint = Danger, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Notification Listener Disconnected", color = Danger, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Schedule Assistant is not receiving WhatsApp notifications. Listener service is inactive or unbound.",
                            color = TextLight,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        SleekDangerButton(
                            text = "Reconnect / Open Settings",
                            onClick = {
                                context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                            }
                        )
                    }
                }
            }

            if (!isIgnoringBatteryOptimizations) {
                item {
                    SleekCard(
                        backgroundColor = WarningBg,
                        borderColor = Warning.copy(alpha = 0.4f),
                        cornerRadius = 14
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Warning, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Background Optimization Warning", color = Warning, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Android may kill notification listener in background. Exclude this app from battery optimization.",
                            color = TextLight,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        SleekOutlinedButton(
                            text = "Disable Optimization",
                            borderColor = Warning,
                            contentColor = Warning,
                            onClick = {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            }
                        )
                    }
                }
            }

            // Stat Glance Cards
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricGlanceCard(
                        title = "Pending",
                        value = "$pendingCount",
                        subtitle = if (pendingCount > 0) "Needs triage" else "All caught up",
                        icon = Icons.Default.Inbox,
                        iconTint = if (pendingCount > 0) Warning else Primary,
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateToPending
                    )

                    MetricGlanceCard(
                        title = "Confirmed",
                        value = "$confirmedCount",
                        subtitle = "Active events",
                        icon = Icons.Default.CheckCircle,
                        iconTint = Success,
                        modifier = Modifier.weight(1f)
                    )

                    val armedAlarmsCount = reminders.count { it.status.name == "SCHEDULED" }
                    MetricGlanceCard(
                        title = "Alarms",
                        value = "$armedAlarmsCount",
                        subtitle = "Exact alarms",
                        icon = Icons.Default.Alarm,
                        iconTint = Accent,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                SectionHeader(title = "Today's Agenda")
            }

            val todayConfirmed = schedules.filter { it.status == ScheduleStatus.CONFIRMED }
            if (todayConfirmed.isEmpty()) {
                item {
                    EmptyStateView(
                        title = "Your Agenda is Clear",
                        description = "No schedules confirmed yet. Inbound WhatsApp messages will be analyzed offline and queued for review.",
                        icon = Icons.Default.EventAvailable
                    )
                }
            } else {
                items(todayConfirmed, key = { it.id }) { item ->
                    ConfirmedScheduleCard(
                        item = item,
                        reminders = reminders,
                        onDelete = onDelete,
                        onEdit = onEdit
                    )
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

fun formatFriendlyDateTime(
    startIso: String?,
    endIso: String? = null,
    startDateStr: String? = null
): String {
    if (startIso.isNullOrBlank()) {
        if (!startDateStr.isNullOrBlank()) {
            return try {
                val inDf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val d = inDf.parse(startDateStr)
                if (d != null) {
                    val calNow = Calendar.getInstance()
                    val calEvent = Calendar.getInstance().apply { time = d }
                    val isToday = calNow.get(Calendar.YEAR) == calEvent.get(Calendar.YEAR) &&
                            calNow.get(Calendar.DAY_OF_YEAR) == calEvent.get(Calendar.DAY_OF_YEAR)
                    calNow.add(Calendar.DAY_OF_YEAR, 1)
                    val isTomorrow = calNow.get(Calendar.YEAR) == calEvent.get(Calendar.YEAR) &&
                            calNow.get(Calendar.DAY_OF_YEAR) == calEvent.get(Calendar.DAY_OF_YEAR)
                    val datePrefix = when {
                        isToday -> "Today"
                        isTomorrow -> "Tomorrow"
                        else -> SimpleDateFormat("EEE, MMM d", Locale.US).format(d)
                    }
                    "$datePrefix (Time unspecified)"
                } else {
                    "Date: $startDateStr"
                }
            } catch (e: Exception) {
                "Date: $startDateStr"
            }
        }
        return "Time unspecified"
    }

    return try {
        val cleanIso = startIso.replace("Z", "+00:00")
        val parsedDate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val zdt = java.time.ZonedDateTime.parse(cleanIso)
            Date.from(zdt.toInstant())
        } else {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(cleanIso)
        }

        if (parsedDate == null) return startIso

        val calNow = Calendar.getInstance()
        val calEvent = Calendar.getInstance().apply { time = parsedDate }

        val isToday = calNow.get(Calendar.YEAR) == calEvent.get(Calendar.YEAR) &&
                calNow.get(Calendar.DAY_OF_YEAR) == calEvent.get(Calendar.DAY_OF_YEAR)
        calNow.add(Calendar.DAY_OF_YEAR, 1)
        val isTomorrow = calNow.get(Calendar.YEAR) == calEvent.get(Calendar.YEAR) &&
                calNow.get(Calendar.DAY_OF_YEAR) == calEvent.get(Calendar.DAY_OF_YEAR)
        calNow.add(Calendar.DAY_OF_YEAR, -2)
        val isYesterday = calNow.get(Calendar.YEAR) == calEvent.get(Calendar.YEAR) &&
                calNow.get(Calendar.DAY_OF_YEAR) == calEvent.get(Calendar.DAY_OF_YEAR)

        val dayLabel = when {
            isToday -> "Today"
            isTomorrow -> "Tomorrow"
            isYesterday -> "Yesterday"
            else -> SimpleDateFormat("EEE, MMM d", Locale.US).format(parsedDate)
        }

        val timeFmt = SimpleDateFormat("h:mm a", Locale.US)
        val startTimeStr = timeFmt.format(parsedDate)

        if (!endIso.isNullOrBlank()) {
            try {
                val cleanEnd = endIso.replace("Z", "+00:00")
                val endParsed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val zdt = java.time.ZonedDateTime.parse(cleanEnd)
                    Date.from(zdt.toInstant())
                } else {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(cleanEnd)
                }
                if (endParsed != null) {
                    val endTimeStr = timeFmt.format(endParsed)
                    return "$dayLabel • $startTimeStr – $endTimeStr"
                }
            } catch (ignored: Exception) {}
        }

        "$dayLabel • $startTimeStr"
    } catch (e: Exception) {
        startIso
    }
}

fun getNoAlertScheduledReason(item: ScheduleEntity, reminders: List<com.scheduleassistant.app.data.model.ReminderEntity>): String? {
    val hasActiveReminder = reminders.any { it.schedule_id == item.id && it.status == com.scheduleassistant.app.data.model.ReminderStatus.SCHEDULED }
    if (hasActiveReminder) return null
    val start = item.start
    if (item.time_unspecified || start.isNullOrBlank()) return "time unspecified"
    val startMs = com.scheduleassistant.app.service.parseIsoTimestamp(start) ?: return "invalid time format"
    if (startMs <= System.currentTimeMillis()) return "event in past"
    return "no active alert"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmedScheduleCard(
    item: ScheduleEntity,
    reminders: List<com.scheduleassistant.app.data.model.ReminderEntity> = emptyList(),
    onDelete: (String) -> Unit,
    onEdit: (String, String, String?, Boolean) -> Unit
) {
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }

    var editTitle by remember(item.title) { mutableStateOf(item.title) }
    var editTime by remember(item.start) { mutableStateOf(item.start ?: item.start_date ?: "") }
    var editAlarm by remember(item.has_alarm) { mutableStateOf(item.has_alarm) }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete Schedule?", color = TextLight, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Are you sure you want to delete \"${item.title}\"? All associated reminders and alarms will be cancelled immediately.",
                    color = TextMuted,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDelete(item.id)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger)
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel", color = TextMuted)
                }
            },
            containerColor = CardBg
        )
    }

    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit Confirmed Schedule", color = TextLight, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("Title") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextLight,
                            unfocusedTextColor = TextLight,
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = CardBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editTime,
                        onValueChange = { editTime = it },
                        label = { Text("Start Time (ISO or HH:mm)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextLight,
                            unfocusedTextColor = TextLight,
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = CardBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Surface(
                        color = CardBorder,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (editAlarm) "⏰ Audible Alarm" else "🔔 Reminder Notification",
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (editAlarm) Warning else TextLight,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = if (editAlarm) "Rings aloud with alarm UI" else "Banner notification only",
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = editAlarm,
                                onCheckedChange = { editAlarm = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Warning
                                )
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showEditDialog = false
                        onEdit(item.id, editTitle, editTime.ifBlank { null }, editAlarm)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Text("Save", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Cancel", color = TextMuted)
                }
            },
            containerColor = CardBg
        )
    }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart || value == SwipeToDismissBoxValue.StartToEnd) {
                onDelete(item.id)
                true
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Danger, RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
            }
        }
    ) {
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.title, fontWeight = FontWeight.Bold, color = TextLight, fontSize = 16.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = formatFriendlyDateTime(item.start, item.end, item.start_date),
                            fontWeight = FontWeight.SemiBold,
                            color = Accent,
                            fontSize = 14.sp
                        )
                        val noAlertReason = getNoAlertScheduledReason(item, reminders)
                        if (noAlertReason != null) {
                            Spacer(Modifier.height(4.dp))
                            Surface(
                                color = Color(0x33EF4444),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "⚠️ no alert scheduled: $noAlertReason",
                                        color = Danger,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showEditDialog = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit", tint = Accent)
                        }
                        IconButton(onClick = { showDeleteConfirmDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Danger)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("\"${item.source_quote}\"", color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun PendingItemCard(
    item: ScheduleEntity,
    onConfirm: (String, String?, Boolean) -> Unit,
    onDismiss: (String) -> Unit,
    onEdit: ((String, String, String?, Boolean) -> Unit)? = null
) {
    var enableAlarm by remember { mutableStateOf(false) }
    var showPastEventDialog by remember { mutableStateOf(false) }
    var pastEventTargetTime by remember { mutableStateOf<String?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editTitle by remember(item.title) { mutableStateOf(item.title) }
    var editTime by remember(item.start) { mutableStateOf(item.start ?: item.start_date ?: "") }

    fun tryConfirm(targetTime: String?) {
        val effectiveTime = targetTime ?: item.start
        val startMs = effectiveTime?.let { com.scheduleassistant.app.service.parseIsoTimestamp(it) }
            ?: item.start_date?.let { com.scheduleassistant.app.service.parseIsoTimestamp("${it}T23:59:59+05:30") }
        if (startMs != null && startMs <= System.currentTimeMillis()) {
            pastEventTargetTime = effectiveTime
            showPastEventDialog = true
        } else {
            onConfirm(item.id, targetTime, enableAlarm)
        }
    }

    if (showPastEventDialog) {
        AlertDialog(
            onDismissRequest = { showPastEventDialog = false },
            title = { Text("Cannot Confirm Past Event", color = TextLight, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "The event \"${item.title}\" is set to a past time (${formatFriendlyDateTime(pastEventTargetTime ?: item.start, null, item.start_date)}).\n\nAlerts cannot be scheduled for events in the past. Would you like to edit the time or dismiss this event?",
                    color = TextMuted,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPastEventDialog = false
                        showEditDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) {
                    Text("Edit Time", color = Color.White)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showPastEventDialog = false }) {
                        Text("Cancel", color = TextMuted)
                    }
                    TextButton(
                        onClick = {
                            showPastEventDialog = false
                            onDismiss(item.id)
                        }
                    ) {
                        Text("Dismiss", color = Danger)
                    }
                }
            },
            containerColor = CardBg
        )
    }

    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit Event Time", color = TextLight, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("Title") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextLight,
                            unfocusedTextColor = TextLight,
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = CardBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editTime,
                        onValueChange = { editTime = it },
                        label = { Text("Start Time (ISO or YYYY-MM-DDTHH:MM)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextLight,
                            unfocusedTextColor = TextLight,
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = CardBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showEditDialog = false
                        onEdit?.invoke(item.id, editTitle, editTime.ifBlank { null }, enableAlarm)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Text("Save & Update", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Cancel", color = TextMuted)
                }
            },
            containerColor = CardBg
        )
    }

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
                text = formatFriendlyDateTime(item.start, item.end, item.start_date),
                fontWeight = FontWeight.SemiBold,
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

            // Requirement 4: Reminder by default; alarm is opt-in per event (toggle on Pending/Confirm card)
            Spacer(Modifier.height(10.dp))
            Surface(
                color = if (enableAlarm) Color(0x22F59E0B) else CardBorder,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (enableAlarm) "⏰ Audible Alarm (Opt-in Active)" else "🔔 Reminder Notification (Default)",
                            fontWeight = FontWeight.SemiBold,
                            color = if (enableAlarm) Warning else TextLight,
                            fontSize = 13.sp
                        )
                        Text(
                            text = if (enableAlarm) "Will ring aloud with full-screen Alarm UI" else "Banner notification only (default 15m lead)",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = enableAlarm,
                        onCheckedChange = { enableAlarm = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Warning
                        )
                    )
                }
            }

            // Ambiguous AM/PM Dual Reading buttons
            if (item.ambiguous && item.start != null) {
                Spacer(Modifier.height(8.dp))
                Text("Ambiguous AM/PM detected — choose reading:", color = Warning, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { tryConfirm(item.start) },
                        colors = ButtonDefaults.buttonColors(containerColor = Warning)
                    ) {
                        Text("Confirm Reading A", color = Color.Black, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            val dateStr = item.start!!.split("T")[0]
                            val timeStr = item.start!!.split("T")[1].substring(0, 5)
                            val h = timeStr.split(":")[0].toInt()
                            val m = timeStr.split(":")[1].toInt()
                            val altH = if (h < 12) h + 12 else h - 12
                            val altIso = String.format(Locale.US, "%sT%02d:%02d:00+05:30", dateStr, altH, m)
                            tryConfirm(altIso)
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
                        onClick = { tryConfirm(null) },
                        colors = ButtonDefaults.buttonColors(containerColor = Success)
                    ) {
                        Text("Confirm", color = Color.White)
                    }
                }
                Button(
                    onClick = { showEditDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) {
                    Text("Edit", color = Color.White)
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

@Composable
fun PendingScreen(
    schedules: List<ScheduleEntity>,
    onConfirm: (String, String?, Boolean) -> Unit,
    onDismiss: (String) -> Unit,
    onEdit: (String, String, String?, Boolean) -> Unit = { _, _, _, _ -> }
) {
    val pendingList = schedules.filter { it.status == ScheduleStatus.PENDING }
    // Requirement 7: Precision & "Maybe" List: Events with time_unspecified or ambiguous must NOT create a normal Pending card
    val normalPending = pendingList.filter { !it.time_unspecified && !it.ambiguous }
    val maybeList = pendingList.filter { it.time_unspecified || it.ambiguous }

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
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = TextLight,
                letterSpacing = (-0.5).sp
            )
            Text(
                text = "One-tap confirm or choose alternate AM/PM reading. Reminder by default; alarm opt-in.",
                fontSize = 13.sp,
                color = TextMuted
            )
        }

        if (normalPending.isNotEmpty()) {
            item {
                SectionHeader(title = "Pending Confirmations (${normalPending.size})")
            }
            items(normalPending, key = { it.id }) { item ->
                PendingItemCard(
                    item = item,
                    onConfirm = onConfirm,
                    onDismiss = onDismiss,
                    onEdit = onEdit
                )
            }
        } else if (maybeList.isEmpty()) {
            item {
                EmptyStateView(
                    title = "All Caught Up",
                    description = "No items waiting for review. New schedule proposals from WhatsApp notifications will appear here.",
                    icon = Icons.Default.CheckCircle
                )
            }
        }

        if (maybeList.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Maybe / Needs Clarification (${maybeList.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Warning
                    )
                    Badge(containerColor = Warning) {
                        Text("Low Precision", color = Color.Black)
                    }
                }
                Text(
                    text = "Items with ambiguous AM/PM times or unspecified hours. Select a reading or edit time to confirm.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
            items(maybeList, key = { it.id }) { item ->
                PendingItemCard(
                    item = item,
                    onConfirm = onConfirm,
                    onDismiss = onDismiss,
                    onEdit = onEdit
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(
    reminders: List<com.scheduleassistant.app.data.model.ReminderEntity>,
    confirmedSchedules: List<ScheduleEntity> = emptyList(),
    confirmedCount: Int = 0,
    onDelete: (String) -> Unit = {},
    onRestore: (String) -> Unit = {},
    onEdit: (String, String, String?, Boolean) -> Unit = { _, _, _, _ -> }
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val scheduler = remember { com.scheduleassistant.app.service.AlarmScheduler(context) }
    val canScheduleExact = remember { scheduler.canScheduleExactAlarms() }
    val nextAlarmClockStr = remember { scheduler.getNextAlarmClockFormatted() }
    val lastErrorFlow = remember { db.alarmAuditDao().getLastErrorFlow() }
    val lastError by lastErrorFlow.collectAsState(initial = null)
    val auditsFlow = remember { db.alarmAuditDao().getRecentAuditsFlow(20) }
    val recentAudits by auditsFlow.collectAsState(initial = emptyList())

    val confirmedWithoutAlerts = confirmedSchedules.filter { sched ->
        !reminders.any { it.schedule_id == sched.id && it.status == com.scheduleassistant.app.data.model.ReminderStatus.SCHEDULED }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "Alarms & Reminders",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = TextLight,
                letterSpacing = (-0.5).sp
            )
            Text(
                text = "Exact AlarmManager.setAlarmClock alerts & System Clock alarms",
                fontSize = 13.sp,
                color = TextMuted
            )
            Spacer(Modifier.height(10.dp))
            SleekDangerButton(
                text = "Stop All Ringing Alarms",
                icon = Icons.Default.Cancel,
                onClick = {
                    com.scheduleassistant.app.service.AlarmRingingService.stopAll(context)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Requirement 4: Any confirmed item without a scheduled alert shows a red "no alert scheduled: <reason>" badge on Home/Alerts
        if (confirmedWithoutAlerts.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Confirmed Schedules Without Active Alerts (${confirmedWithoutAlerts.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Danger
                    )
                }
                Text(
                    text = "These events are confirmed but have no active alarm or reminder scheduled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
            items(confirmedWithoutAlerts, key = { "unscheduled-${it.id}" }) { sched ->
                ConfirmedScheduleCard(
                    item = sched,
                    reminders = reminders,
                    onDelete = onDelete,
                    onEdit = onEdit
                )
            }
        }

        if (reminders.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "Why is this empty?",
                            fontWeight = FontWeight.Bold,
                            color = Accent,
                            fontSize = 15.sp
                        )

                        if (confirmedCount == 0) {
                            Text(
                                "• 0 confirmed schedules in database: Confirm an event in the Today or Pending tab to generate alerts.",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                        } else {
                            Text(
                                "• $confirmedCount confirmed event(s) exist, but no active alerts are scheduled (events may be in the past or time was unspecified).",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("canScheduleExactAlarms:", color = TextLight, fontSize = 13.sp)
                            Badge(containerColor = if (canScheduleExact) Success else Danger) {
                                Text(if (canScheduleExact) "GRANTED" else "DENIED", color = Color.White)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Next Alarm Clock:", color = TextLight, fontSize = 13.sp)
                            Text(nextAlarmClockStr, color = Primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }

                        if (lastError != null) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0x33EF4444)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("⚠️ Last Scheduling Error", color = Danger, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(
                                        "${lastError?.api}: ${lastError?.errorMessage}",
                                        color = TextLight,
                                        fontSize = 12.sp
                                    )
                                    Text("At: ${lastError?.timestamp}", color = TextMuted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            items(reminders, key = { it.id }) { rem ->
                var showDeleteConfirmDialog by remember { mutableStateOf(false) }
                var showEditDialog by remember { mutableStateOf(false) }
                var editTitle by remember(rem.title) { mutableStateOf(rem.title) }
                var editTime by remember(rem.trigger_time) { mutableStateOf(rem.trigger_time) }
                var editAlarm by remember(rem.type) { mutableStateOf(rem.type == "alarm") }

                if (showDeleteConfirmDialog) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirmDialog = false },
                        title = { Text("Delete Alert & Schedule?", color = TextLight, fontWeight = FontWeight.Bold) },
                        text = {
                            Text(
                                "Are you sure you want to delete this alert for \"${rem.title}\"? The schedule and all associated alerts will be cancelled.",
                                color = TextMuted,
                                fontSize = 14.sp
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showDeleteConfirmDialog = false
                                    onDelete(rem.schedule_id)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Danger)
                            ) {
                                Text("Delete", color = Color.White)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteConfirmDialog = false }) {
                                Text("Cancel", color = TextMuted)
                            }
                        },
                        containerColor = CardBg
                    )
                }

                if (showEditDialog) {
                    AlertDialog(
                        onDismissRequest = { showEditDialog = false },
                        title = { Text("Edit Schedule Alert", color = TextLight, fontWeight = FontWeight.Bold) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = editTitle,
                                    onValueChange = { editTitle = it },
                                    label = { Text("Title") },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = TextLight,
                                        unfocusedTextColor = TextLight,
                                        focusedBorderColor = Primary,
                                        unfocusedBorderColor = CardBorder
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = editTime,
                                    onValueChange = { editTime = it },
                                    label = { Text("Start Time") },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = TextLight,
                                        unfocusedTextColor = TextLight,
                                        focusedBorderColor = Primary,
                                        unfocusedBorderColor = CardBorder
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Surface(
                                    color = CardBorder,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (editAlarm) "⏰ Audible Alarm" else "🔔 Reminder Notification",
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (editAlarm) Warning else TextLight,
                                                fontSize = 13.sp
                                            )
                                        }
                                        Switch(
                                            checked = editAlarm,
                                            onCheckedChange = { editAlarm = it },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = Color.White,
                                                checkedTrackColor = Warning
                                            )
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showEditDialog = false
                                    onEdit(rem.schedule_id, editTitle, editTime.ifBlank { null }, editAlarm)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Primary)
                            ) {
                                Text("Save", color = Color.White)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showEditDialog = false }) {
                                Text("Cancel", color = TextMuted)
                            }
                        },
                        containerColor = CardBg
                    )
                }

                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value == SwipeToDismissBoxValue.EndToStart || value == SwipeToDismissBoxValue.StartToEnd) {
                            onDelete(rem.schedule_id)
                            true
                        } else {
                            false
                        }
                    }
                )

                SwipeToDismissBox(
                    state = dismissState,
                    backgroundContent = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Danger, RoundedCornerShape(12.dp))
                                .padding(horizontal = 20.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
                        }
                    }
                ) {
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
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (rem.type == "alarm") "⏰ ALARM (Ringing)" else "🔔 REMINDER (15m lead)",
                                        fontWeight = FontWeight.Bold,
                                        color = if (rem.type == "alarm") Warning else Accent,
                                        fontSize = 12.sp
                                    )
                                }
                                Text(rem.title, fontWeight = FontWeight.SemiBold, color = TextLight, fontSize = 16.sp)
                                Text("Trigger: ${formatFriendlyDateTime(rem.trigger_time)}", color = TextLight, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Badge(containerColor = if (rem.status.name == "FIRED") Success else Primary) {
                                    Text(rem.status.name, color = Color.White)
                                }
                                IconButton(onClick = { showEditDialog = true }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = Accent)
                                }
                                IconButton(onClick = { showDeleteConfirmDialog = true }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Danger)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Audit Log of Scheduling Attempts
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Scheduling Audit Log (${recentAudits.size} attempts)",
                        fontWeight = FontWeight.Bold,
                        color = TextLight,
                        fontSize = 15.sp
                    )
                    Text(
                        "Every AlarmClock.ACTION_SET_ALARM and setAlarmClock attempt is recorded here.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )

                    if (recentAudits.isEmpty()) {
                        Text("No scheduling attempts recorded yet.", color = TextMuted, fontSize = 13.sp)
                    } else {
                        recentAudits.forEach { audit ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(CardBorder, RoundedCornerShape(8.dp))
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${audit.api} • ${audit.entityId}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextLight
                                    )
                                    Text(
                                        text = "Trigger: ${audit.triggerTime}",
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                    if (!audit.errorMessage.isNullOrBlank()) {
                                        Text(
                                            text = "Error: ${audit.errorMessage}",
                                            fontSize = 11.sp,
                                            color = Danger
                                        )
                                    }
                                }
                                Badge(containerColor = if (audit.result == "SUCCESS") Success else Danger) {
                                    Text(audit.result, color = Color.White, fontSize = 10.sp)
                                }
                            }
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
    onRequestNotificationPermission: () -> Unit = {},
    onDeleteAllTestItems: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val lifecycleOwner = LocalLifecycleOwner.current

    // Requirement 2 & 4: Dynamically re-read live state on resume and periodically while visible
    var isIgnoringBatteryOptimizations by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }
    var refreshTick by remember { mutableStateOf(0) }

    // Requirement 4: Periodic live refresh while visible (every 1.5s)
    LaunchedEffect(Unit) {
        while (isActive) {
            kotlinx.coroutines.delay(1500)
            refreshTick++
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)
                refreshTick++
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
    val alarmTestsFlow = remember { db.alarmTestDao().getAllRecordsFlow() }
    val alarmTestRecords by alarmTestsFlow.collectAsState(initial = emptyList())
    val heartbeatsFlow = remember { db.heartbeatLogDao().getAllHeartbeatsFlow() }
    val heartbeats by heartbeatsFlow.collectAsState(initial = emptyList())
    val scheduler = remember { com.scheduleassistant.app.service.AlarmScheduler(context) }
    val canScheduleExactAlarms = remember(refreshTick) { scheduler.canScheduleExactAlarms() }
    val nextAlarmClockStr = remember(refreshTick) { scheduler.getNextAlarmClockFormatted() }
    val liveScheduledCount = remember(refreshTick) { scheduler.getActiveScheduledCount() }
    val canUseFullScreen = remember(refreshTick) { DiagnosticsManager.canUseFullScreenIntent(context) }
    var captureAllChats by remember(refreshTick) { mutableStateOf(DiagnosticsManager.isCaptureAllChats(context)) }
    val cutoff24h = remember(refreshTick) { System.currentTimeMillis() - 24 * 60 * 60 * 1000L }
    val decisionsFlow = remember(cutoff24h) { db.ingestionDecisionDao().getDecisionsSinceFlow(cutoff24h) }
    val decisions24h by decisionsFlow.collectAsState(initial = emptyList())
    var allowedChats by remember(refreshTick) { mutableStateOf(DiagnosticsManager.getAllowedChats(context)) }
    val lastErrorFlow = remember { db.alarmAuditDao().getLastErrorFlow() }
    val lastSchedulingError by lastErrorFlow.collectAsState(initial = null)
    var testAlarmMinutes by remember { mutableStateOf("1") }
    var testAlarmMessage by remember { mutableStateOf<String?>(null) }

    val lastNotificationTime = remember(refreshTick) { DiagnosticsManager.getLastNotificationReceivedTime(context) }
    val lastBootTime = remember(refreshTick) { DiagnosticsManager.getLastBootRecoveryTime(context) }

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

                    // Full-Screen Intent Allowed Row (Requirement 6 & Item 4)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Full-Screen Intent Allowed", color = TextLight, fontSize = 14.sp)
                            Text("Survives lockscreen & DND for alarm ringing UI. (needs owner verification)", color = TextMuted, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Badge(containerColor = if (canUseFullScreen) Success else Danger) {
                                Text(if (canUseFullScreen) "ALLOWED" else "BLOCKED", color = Color.White)
                            }
                            if (!canUseFullScreen) {
                                Spacer(Modifier.width(6.dp))
                                Button(
                                    onClick = {
                                        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            }
                                        } else {
                                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            }
                                        }
                                        context.startActivity(intent)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Danger),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("Fix", color = Color.White, fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    Divider(color = CardBorder)

                    // 1. canScheduleExactAlarms Row (Task E)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("canScheduleExactAlarms", color = TextLight, fontSize = 14.sp)
                            Text("SCHEDULE_EXACT_ALARM permission. (needs owner verification)", color = TextMuted, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Badge(containerColor = if (canScheduleExactAlarms) Success else Danger) {
                            Text(if (canScheduleExactAlarms) "GRANTED" else "DENIED", color = Color.White)
                        }
                    }

                    Divider(color = CardBorder)

                    // 2. getNextAlarmClock Row (Task E & Item 4: labelled reminder vs alarm)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("getNextAlarmClock", color = TextLight, fontSize = 14.sp)
                            Text("Next alarm clock (reminder vs alarm). (needs owner verification)", color = TextMuted, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = nextAlarmClockStr,
                            color = if (nextAlarmClockStr != "None") Primary else TextMuted,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    }

                    Divider(color = CardBorder)

                    // 3. Scheduled Alarm Count Row (Task E & Item 4)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Scheduled Alarm Count", color = TextLight, fontSize = 14.sp)
                            Text("Active alarms/reminders. (needs owner verification)", color = TextMuted, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "$liveScheduledCount active",
                            color = Primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }

                    Divider(color = CardBorder)

                    // 4. Last Scheduling Error Row (Task E)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Last Scheduling Error", color = TextLight, fontSize = 14.sp)
                            Text(
                                if (lastSchedulingError != null) "${lastSchedulingError?.api}: ${lastSchedulingError?.errorMessage}"
                                else "None recorded. (needs owner verification)",
                                color = if (lastSchedulingError != null) Danger else TextMuted,
                                fontSize = 12.sp
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Badge(containerColor = if (lastSchedulingError == null) Success else Danger) {
                            Text(if (lastSchedulingError == null) "NONE" else "ERROR", color = Color.White)
                        }
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

        // Chat Allowlist & Ingestion Decisions Card (Requirement 3)
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
                            Text("Chat Allowlist & Ingestion Decisions (24h)", fontWeight = FontWeight.Bold, color = TextLight, fontSize = 16.sp)
                            Text("Enforces allowlist on real WhatsApp ingestion. (needs owner verification)", color = TextMuted, fontSize = 12.sp)
                        }
                        Badge(containerColor = if (captureAllChats) Warning else Primary) {
                            Text(if (captureAllChats) "CAPTURE ALL" else "STRICT", color = Color.White)
                        }
                    }

                    // Test option: "capture all chats" (default OFF)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Capture All Chats (Test Option)", color = TextLight, fontSize = 14.sp)
                            Text(
                                if (captureAllChats) "Test mode ON: Accepts notifications from all chats. (needs owner verification)"
                                else "Default OFF: Only allowlisted chats are processed. (needs owner verification)",
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = captureAllChats,
                            onCheckedChange = { checked ->
                                captureAllChats = checked
                                DiagnosticsManager.setCaptureAllChats(context, checked)
                            }
                        )
                    }

                    Divider(color = CardBorder)

                    // 24h Ingestion Summary stats
                    val totalDecisions = decisions24h.size
                    val processedCount = decisions24h.count { it.disposition == "processed" }
                    val notAllowlistedCount = decisions24h.count { it.disposition.contains("not allowlisted") }
                    val summaryCount = decisions24h.count { it.disposition.contains("summary") }
                    val ownStatusCount = decisions24h.count { it.disposition.contains("own status") }
                    val dupCount = decisions24h.count { it.disposition.contains("duplicate") }

                    Text("Ingestion Stats (Last 24h): $totalDecisions notifications", fontWeight = FontWeight.SemiBold, color = Accent, fontSize = 13.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(color = Color(0x3310B981), shape = RoundedCornerShape(6.dp), modifier = Modifier.weight(1f)) {
                            Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$processedCount", fontWeight = FontWeight.Bold, color = Success, fontSize = 13.sp)
                                Text("Processed", color = TextMuted, fontSize = 10.sp)
                            }
                        }
                        Surface(color = Color(0x33EF4444), shape = RoundedCornerShape(6.dp), modifier = Modifier.weight(1f)) {
                            Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$notAllowlistedCount", fontWeight = FontWeight.Bold, color = Danger, fontSize = 13.sp)
                                Text("Blocked", color = TextMuted, fontSize = 10.sp)
                            }
                        }
                        Surface(color = Color(0x336B7280), shape = RoundedCornerShape(6.dp), modifier = Modifier.weight(1f)) {
                            Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${summaryCount + ownStatusCount + dupCount}", fontWeight = FontWeight.Bold, color = TextLight, fontSize = 13.sp)
                                Text("Skipped", color = TextMuted, fontSize = 10.sp)
                            }
                        }
                    }

                    Divider(color = CardBorder)

                    // Chats seen in the last 24h with allowed/blocked state
                    val seenChats = decisions24h
                        .filter { !it.chatTitle.isNullOrBlank() }
                        .groupBy { it.chatTitle!!.trim() }
                        .map { (title, decs) ->
                            val isGroup = decs.any { it.isGroup }
                            val count = decs.size
                            Triple(title, isGroup, count)
                        }

                    Text("Chats Seen in Last 24h (${seenChats.size}):", fontWeight = FontWeight.SemiBold, color = TextLight, fontSize = 13.sp)

                    if (seenChats.isEmpty()) {
                        Text(
                            "No WhatsApp chats observed in last 24h. Once WhatsApp messages arrive, chats will appear here with Allow/Block toggles. (needs owner verification)",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            seenChats.forEach { (title, isGroup, count) ->
                                val isAllowed = allowedChats.contains(title)
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
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Badge(containerColor = if (isGroup) Color(0xFF6366F1) else Color(0xFF3B82F6)) {
                                                    Text(if (isGroup) "GROUP" else "DM", color = Color.White, fontSize = 9.sp)
                                                }
                                                Text(title, fontWeight = FontWeight.SemiBold, color = TextLight, fontSize = 13.sp, maxLines = 1)
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Badge(containerColor = if (isAllowed) Success else Danger) {
                                                    Text(if (isAllowed) "✓ ALLOWED" else "⊘ BLOCKED", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                }
                                                Text("$count msgs in 24h", color = TextMuted, fontSize = 11.sp)
                                            }
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                if (isAllowed) "Allowed" else "Blocked",
                                                color = if (isAllowed) Success else Danger,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Switch(
                                                checked = isAllowed,
                                                onCheckedChange = { checked ->
                                                    DiagnosticsManager.setChatAllowed(context, title, checked)
                                                    allowedChats = DiagnosticsManager.getAllowedChats(context)
                                                },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = Color.White,
                                                    checkedTrackColor = Success,
                                                    uncheckedThumbColor = Color.White,
                                                    uncheckedTrackColor = Danger.copy(alpha = 0.5f)
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Recent Decision Log Entries (last 5)
                    if (decisions24h.isNotEmpty()) {
                        Divider(color = CardBorder)
                        Text("Recent Ingestion Decisions:", fontWeight = FontWeight.SemiBold, color = TextLight, fontSize = 13.sp)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            decisions24h.take(5).forEach { dec ->
                                Surface(
                                    color = Color(0x22FFFFFF),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(6.dp).fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${dec.disposition} • ${if (dec.isGroup) "Group" else "DM"}",
                                                fontWeight = FontWeight.Bold,
                                                color = if (dec.disposition == "processed") Success else if (dec.disposition.contains("not allowlisted")) Danger else TextMuted,
                                                fontSize = 11.sp
                                            )
                                            Text(
                                                text = "${dec.chatTitle?.take(20) ?: "Unknown"} • ${dec.timestamp.take(19).replace("T", " ")}",
                                                color = TextMuted,
                                                fontSize = 10.sp
                                            )
                                        }
                                        Badge(containerColor = if (dec.isAllowlisted) Success else Danger) {
                                            Text(if (dec.isAllowlisted) "ALLOWED" else "BLOCKED", color = Color.White, fontSize = 8.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Alert Lead Time Configuration Card (Requirement 4)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                var reminderLead by remember { mutableStateOf(DiagnosticsManager.getReminderLeadTimeMinutes(context)) }
                var alarmLead by remember { mutableStateOf(DiagnosticsManager.getAlarmLeadTimeMinutes(context)) }

                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Alert Lead Time Configuration", fontWeight = FontWeight.Bold, color = TextLight, fontSize = 16.sp)
                    Text("Configures how far in advance reminders and alarms trigger. (needs owner verification)", color = TextMuted, fontSize = 12.sp)

                    // Reminder Lead Time
                    Text("Reminder Lead Time: $reminderLead min before event (Default: 15m)", color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(5, 10, 15, 30, 60).forEach { mins ->
                            Button(
                                onClick = {
                                    reminderLead = mins
                                    DiagnosticsManager.setReminderLeadTimeMinutes(context, mins)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (reminderLead == mins) Primary else CardBorder
                                )
                            ) {
                                Text("${mins}m", fontSize = 12.sp, color = if (reminderLead == mins) Color.White else TextLight)
                            }
                        }
                    }

                    Divider(color = CardBorder)

                    // Alarm Lead Time
                    Text("Alarm Lead Time: ${if (alarmLead == 0) "At start (0 min / default)" else "$alarmLead min before event"}", color = Warning, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(0 to "Start", 5 to "5m", 10 to "10m", 15 to "15m").forEach { (mins, label) ->
                            Button(
                                onClick = {
                                    alarmLead = mins
                                    DiagnosticsManager.setAlarmLeadTimeMinutes(context, mins)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (alarmLead == mins) Warning else CardBorder
                                )
                            ) {
                                Text(label, fontSize = 12.sp, color = if (alarmLead == mins) Color.Black else TextLight)
                            }
                        }
                    }

                    Divider(color = CardBorder)

                    // Alarm Auto-Stop Timeout (Requirement 2)
                    var autoStopMin by remember { mutableStateOf(DiagnosticsManager.getAlarmAutoStopMinutes(context)) }
                    Text("Alarm Auto-Stop Timeout: $autoStopMin min (auto-stops & posts missed notification)", color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(1, 3, 5, 10).forEach { mins ->
                            Button(
                                onClick = {
                                    autoStopMin = mins
                                    DiagnosticsManager.setAlarmAutoStopMinutes(context, mins)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (autoStopMin == mins) Primary else CardBorder
                                )
                            ) {
                                Text("${mins}m", fontSize = 12.sp, color = if (autoStopMin == mins) Color.White else TextLight)
                            }
                        }
                    }

                    Divider(color = CardBorder)

                    // Emergency Stop All Alarms (Requirement 2)
                    Button(
                        onClick = {
                            com.scheduleassistant.app.service.AlarmRingingService.stopAll(context)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Danger),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🛑 Stop all alarms", color = Color.White, fontWeight = FontWeight.Bold)
                    }

                    Divider(color = CardBorder)

                    // Full-Screen Intent Status & Action (Requirement 3)
                    val canUseFsi = DiagnosticsManager.canUseFullScreenIntent(context)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Full-Screen Intent (Lockscreen UI)", color = TextLight, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Required to show full-screen when screen locked/off (needs owner verification)", color = TextMuted, fontSize = 11.sp)
                        }
                        Badge(containerColor = if (canUseFsi) Success else Danger) {
                            Text(if (canUseFsi) "ALLOWED" else "BLOCKED", color = Color.White)
                        }
                    }

                    if (!canUseFsi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        Button(
                            onClick = {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Warning),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open Full-Screen Intent Settings", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    Divider(color = CardBorder)

                    // Next Alarm Clock Info (Requirement 7)
                    val nextAlarmInfo = DiagnosticsManager.getNextAlarmClockInfo(context)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Next System Alarm Clock:", color = TextLight, fontSize = 13.sp)
                        Text(nextAlarmInfo, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // 2. Alarm Self-Test Card (Requirement 2: independent of log-only and events)
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
                            Text("Alarm Self-Test (Independent of Log-Only & Events)", fontWeight = FontWeight.Bold, color = TextLight, fontSize = 16.sp)
                            Text("Uses setAlarmClock and records scheduled vs actual fire time. Never touches parser/events. (needs owner verification)", color = TextMuted, fontSize = 12.sp)
                        }
                        Badge(containerColor = Primary) {
                            Text("EXACT ALARM", color = Color.White)
                        }
                    }

                    // Quick Schedule Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(1, 2, 5, 10).forEach { mins ->
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        val triggerMs = System.currentTimeMillis() + mins * 60 * 1000L
                                        val scheduler = com.scheduleassistant.app.service.AlarmScheduler(context)
                                        val record = scheduler.scheduleTestAlarm(triggerMs)
                                        val timeStr = SimpleDateFormat("h:mm:ss a", Locale.US).format(Date(triggerMs))
                                        testAlarmMessage = "Scheduled +${mins}m test alarm for $timeStr"
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CardBorder),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("+${mins}m", fontSize = 11.sp, color = TextLight)
                            }
                        }
                    }

                    if (testAlarmMessage != null) {
                        Text(testAlarmMessage!!, color = Success, fontSize = 12.sp)
                    }

                    if (alarmTestRecords.isEmpty()) {
                        Text("No test alarms executed yet. Tap a button above to schedule an exact AlarmClock self-test. (needs owner verification)", color = TextMuted, fontSize = 12.sp)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            alarmTestRecords.take(5).forEach { rec ->
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
                                            val timeFormat = SimpleDateFormat("h:mm:ss a", Locale.US)
                                            val schedStr = timeFormat.format(Date(rec.scheduledTimeMs))
                                            val fireStr = if (rec.firedTimeMs != null) timeFormat.format(Date(rec.firedTimeMs)) else "Pending"
                                            Text("Sched: $schedStr • Actual: $fireStr", fontWeight = FontWeight.Bold, color = TextLight, fontSize = 12.sp)
                                            Text(
                                                "Delay: ${rec.delayMs?.let { "${it}ms" } ?: "N/A"} • Fired after reboot: ${if (rec.firedAfterReboot) "YES" else "NO"} (needs owner verification)",
                                                color = if (rec.firedAfterReboot) Accent else TextMuted,
                                                fontSize = 11.sp
                                            )
                                        }
                                        Badge(containerColor = if (rec.status == "FIRED") Success else Warning) {
                                            Text(rec.status, color = if (rec.status == "FIRED") Color.White else Color.Black, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Automatic Heartbeat Log Card (Requirement 1: append-only, no user action)
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
                            Text("Automatic Heartbeat Log (Append-Only)", fontWeight = FontWeight.Bold, color = TextLight, fontSize = 16.sp)
                            Text("Automatic logging on process start, listener connect/disconnect, notifications (count only), service lifecycle, boot, & 15m WorkManager. (needs owner verification)", color = TextMuted, fontSize = 12.sp)
                        }
                        Badge(containerColor = Success) {
                            Text("${heartbeats.size} TICKS", color = Color.White)
                        }
                    }

                    if (heartbeats.isEmpty()) {
                        Text("No heartbeat entries recorded yet. (needs owner verification)", color = TextMuted, fontSize = 12.sp)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            heartbeats.take(6).forEach { hb ->
                                Surface(
                                    color = Color(0x33000000),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(8.dp).fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Text(hb.eventType, fontWeight = FontWeight.Bold, color = Primary, fontSize = 11.sp)
                                                Text(hb.timestamp.take(19).replace("T", " "), color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                            }
                                            if (hb.details.isNotBlank()) {
                                                Text(hb.details, color = TextLight, fontSize = 11.sp, maxLines = 2)
                                            }
                                        }
                                        Badge(containerColor = if (hb.listenerConnected) Success else Danger) {
                                            Text(if (hb.listenerConnected) "CONN" else "DOWN", color = Color.White, fontSize = 9.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Soak Testing Hourly Log Card (Requirement 4)
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
                            Text("48-Hour Soak Log & Checkpoints", fontWeight = FontWeight.Bold, color = TextLight, fontSize = 16.sp)
                            Text("Log-only applies solely to real listener ingestion. Debug injection + confirm + alarms remain fully functional. (needs owner verification)", color = TextMuted, fontSize = 12.sp)
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
                            Text("Applies solely to real WhatsApp listener ingestion. (needs owner verification)", color = TextMuted, fontSize = 12.sp)
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
                        Text("Checkpoints: ${soakCheckpoints.size}/48 (Optional)", color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    val cp = DiagnosticsManager.recordHourlyCheckpoint(context, "Optional Manual Soak Checkpoint")
                                    soakRecordMessage = "Hour #${cp.hourIndex} recorded (${if (cp.listenerConnected) "Connected" else "Down"})"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Primary)
                        ) {
                            Text("+ Record Checkpoint (Optional)", fontSize = 11.sp)
                        }
                    }

                    if (soakRecordMessage != null) {
                        Text(soakRecordMessage!!, color = Success, fontSize = 12.sp)
                    }

                    if (soakCheckpoints.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            soakCheckpoints.takeLast(4).reversed().forEach { cp ->
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

                    var showBulkDeleteConfirmDialog by remember { mutableStateOf(false) }
                    if (showBulkDeleteConfirmDialog) {
                        AlertDialog(
                            onDismissRequest = { showBulkDeleteConfirmDialog = false },
                            title = { Text("Delete All Test Items?", color = TextLight, fontWeight = FontWeight.Bold) },
                            text = {
                                Text(
                                    "This will tombstone all test items and cancel their alarms and reminders. This action appends delete events and cannot be undone.",
                                    color = TextMuted,
                                    fontSize = 14.sp
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        showBulkDeleteConfirmDialog = false
                                        onDeleteAllTestItems()
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

                    Button(
                        onClick = { showBulkDeleteConfirmDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Danger),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🗑️ Delete all test items (Tombstone Cleanup)", color = Color.White, fontWeight = FontWeight.Bold)
                    }

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
    isDeveloperModeActive: Boolean = false,
    onDeveloperModeChanged: (Boolean) -> Unit = {},
    onNavigateToDevConsole: () -> Unit = {},
    onRequestNotificationPermission: () -> Unit = {},
    onDeleteAllTestItems: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val lifecycleOwner = LocalLifecycleOwner.current

    var isIgnoringBatteryOptimizations by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }
    var refreshTick by remember { mutableStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)
                refreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isListenerConnected by DiagnosticsManager.isListenerConnected.collectAsState()
    var isLogOnlyMode by remember { mutableStateOf(DiagnosticsManager.isLogOnlyMode(context)) }
    var captureAllChats by remember(refreshTick) { mutableStateOf(DiagnosticsManager.isCaptureAllChats(context)) }
    val scheduler = remember { com.scheduleassistant.app.service.AlarmScheduler(context) }
    val canScheduleExactAlarms = remember(refreshTick) { scheduler.canScheduleExactAlarms() }
    val canUseFullScreen = remember(refreshTick) { DiagnosticsManager.canUseFullScreenIntent(context) }
    val isListenerAccessGranted = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    var isExporting by remember { mutableStateOf(false) }
    var showBulkDeleteConfirmDialog by remember { mutableStateOf(false) }

    if (showBulkDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirmDialog = false },
            title = { Text("Delete All Test Items?", color = TextLight, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This will tombstone all test items and cancel their alarms and reminders. This action appends delete events and cannot be undone.",
                    color = TextMuted,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBulkDeleteConfirmDialog = false
                        onDeleteAllTestItems()
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
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Settings & Preferences",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = TextLight,
                letterSpacing = (-0.5).sp
            )
            Text(
                text = "Autonomous secretary configuration & device permissions",
                fontSize = 13.sp,
                color = TextMuted
            )
        }

        // Section: Operating Mode
        item {
            SectionHeader(title = "Operating Mode")
            SleekCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Log-Only Mode", color = TextLight, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Evaluates and logs messages without firing audible alarms. Recommended during soak audit.",
                            color = TextMuted,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = isLogOnlyMode,
                        onCheckedChange = { checked ->
                            DiagnosticsManager.setLogOnlyMode(context, checked)
                            isLogOnlyMode = checked
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TextLight,
                            checkedTrackColor = Accent,
                            uncheckedTrackColor = CardElevated
                        )
                    )
                }

                HorizontalDivider(color = CardBorderSubtle, thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Capture All WhatsApp Chats", color = TextLight, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "When enabled, evaluates all WhatsApp chats instead of only allowlisted contacts.",
                            color = TextMuted,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = captureAllChats,
                        onCheckedChange = { checked ->
                            DiagnosticsManager.setCaptureAllChats(context, checked)
                            captureAllChats = checked
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TextLight,
                            checkedTrackColor = Primary,
                            uncheckedTrackColor = CardElevated
                        )
                    )
                }
            }
        }

        // Section: System Permissions & Services
        item {
            SectionHeader(title = "System Permissions & Background Engine")
            SleekCard {
                // 1. Notification Listener Access
                PermissionRow(
                    title = "Notification Listener Access",
                    subtitle = if (isListenerConnected) "Connected & listening to WhatsApp" else "Permission required to intercept schedules",
                    isGranted = isListenerAccessGranted && isListenerConnected,
                    grantedLabel = "CONNECTED",
                    deniedLabel = "SETUP",
                    onAction = {
                        context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                    }
                )

                HorizontalDivider(color = CardBorderSubtle, thickness = 1.dp, modifier = Modifier.padding(vertical = 10.dp))

                // 2. Exact Alarms
                PermissionRow(
                    title = "Exact Alarm Scheduling",
                    subtitle = "Required to ring alarms at the precise second (AlarmClock API)",
                    isGranted = canScheduleExactAlarms,
                    grantedLabel = "ALLOWED",
                    deniedLabel = "GRANT",
                    onAction = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        }
                    }
                )

                HorizontalDivider(color = CardBorderSubtle, thickness = 1.dp, modifier = Modifier.padding(vertical = 10.dp))

                // 3. Battery Optimization
                PermissionRow(
                    title = "Battery Optimization Exemption",
                    subtitle = if (isIgnoringBatteryOptimizations) "Exempted — background survival protected" else "System may terminate background listener",
                    isGranted = isIgnoringBatteryOptimizations,
                    grantedLabel = "EXEMPTED",
                    deniedLabel = "EXEMPT",
                    onAction = {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    }
                )

                HorizontalDivider(color = CardBorderSubtle, thickness = 1.dp, modifier = Modifier.padding(vertical = 10.dp))

                // 4. Full-Screen Alarms (FSI)
                PermissionRow(
                    title = "Full-Screen Alarms (Lock Screen)",
                    subtitle = "Wakes screen and presents ringing interface over lockscreen",
                    isGranted = canUseFullScreen,
                    grantedLabel = "ALLOWED",
                    deniedLabel = "SETUP",
                    onAction = {
                        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                        } else {
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                        }
                        context.startActivity(intent)
                    }
                )
            }
        }

        // Section: Developer Options (Revealed when unlocked via 5 taps)
        if (isDeveloperModeActive) {
            item {
                SectionHeader(title = "Developer Options")
                SleekCard(
                    borderColor = Accent.copy(alpha = 0.5f),
                    backgroundColor = CardBg
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Developer Console", color = TextLight, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                StatusPill(text = "UNLOCKED", color = Accent)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Deep hardware diagnostics, SQLite health, live searchable log viewer, and synthetic message injector.",
                                color = TextMuted,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SleekPrimaryButton(
                            text = "Open Console",
                            icon = Icons.Default.Terminal,
                            onClick = onNavigateToDevConsole,
                            containerColor = Accent,
                            contentColor = Color.Black,
                            modifier = Modifier.weight(1f)
                        )
                        SleekOutlinedButton(
                            text = "Deactivate",
                            onClick = {
                                DeveloperModeManager.setDeveloperModeEnabled(context, false)
                                onDeveloperModeChanged(false)
                                Toast.makeText(context, "Developer Mode deactivated", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }

        // Section: Diagnostic Data & Actions
        item {
            SectionHeader(title = "Diagnostic Actions")
            SleekCard {
                SleekOutlinedButton(
                    text = if (isExporting) "Generating Soak Log..." else "Export Anonymized Diagnostic Log",
                    icon = Icons.Default.Share,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        coroutineScope.launch {
                            isExporting = true
                            try {
                                val file = DiagnosticsManager.exportAnonymizedLogsToFile(context)
                                DiagnosticsManager.shareExportFile(context, file)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                isExporting = false
                            }
                        }
                    }
                )

                Spacer(Modifier.height(10.dp))

                SleekDangerButton(
                    text = "Delete All Test Items (Tombstone Cleanup)",
                    icon = Icons.Default.DeleteSweep,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showBulkDeleteConfirmDialog = true }
                )
            }
        }

        // Section: About Application (With 5-tap developer mode activation & user attribution)
        item {
            SectionHeader(title = "About Application")
            SleekCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(CardElevated)
                            .border(1.dp, CardBorder, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Column {
                        Text(
                            text = "Schedule Assistant",
                            color = TextLight,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Autonomous On-Device Secretary",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }
                }

                HorizontalDivider(color = CardBorderSubtle, thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))

                // App Version Row (5-Tap Activation Trigger)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            val result = DeveloperModeManager.onVersionTap(context)
                            when (result) {
                                is DeveloperTapResult.Hint -> {
                                    Toast.makeText(
                                        context,
                                        "You are ${result.stepsRemaining} steps away from Developer Mode.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                DeveloperTapResult.Activated -> {
                                    Toast.makeText(
                                        context,
                                        "🎉 Developer Mode enabled! Developer Options unlocked.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    onDeveloperModeChanged(true)
                                }
                                DeveloperTapResult.AlreadyActive -> {
                                    Toast.makeText(
                                        context,
                                        "Developer Mode is already active.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                DeveloperTapResult.Silent -> {}
                            }
                        }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = "App Version", color = TextLight, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(text = "Production Release", color = TextSubtle, fontSize = 11.sp)
                    }
                    Text(
                        text = "0.1.0 (Build 98e2945)",
                        color = PrimaryLight,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                HorizontalDivider(color = CardBorderSubtle, thickness = 1.dp, modifier = Modifier.padding(vertical = 8.dp))

                // Discreet, elegant attribution marking
                AttributionMark()
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    grantedLabel: String,
    deniedLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = TextLight, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(text = subtitle, color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(10.dp))
        if (isGranted) {
            StatusPill(text = grantedLabel, color = Success, backgroundColor = SuccessBg)
        } else {
            SleekOutlinedButton(
                text = deniedLabel,
                borderColor = Warning,
                contentColor = Warning,
                onClick = onAction
            )
        }
    }
}


