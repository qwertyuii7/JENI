package com.scheduleassistant.app.ui

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scheduleassistant.app.service.ActiveRingingAlarm
import com.scheduleassistant.app.service.AlarmRingingService
import com.scheduleassistant.app.service.AlarmScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlarmRingingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        turnScreenOnAndShowWhenLocked()

        val initialTitle = intent.getStringExtra(AlarmRingingService.EXTRA_TITLE) ?: "Scheduled Alarm"
        val initialScheduleId = intent.getStringExtra(AlarmRingingService.EXTRA_SCHEDULE_ID) ?: ""
        val isTest = intent.getBooleanExtra(AlarmRingingService.EXTRA_IS_TEST, false)

        setContent {
            val activeAlarms by AlarmRingingService.activeAlarmsFlow.collectAsState()

            LaunchedEffect(activeAlarms) {
                // If service stopped or all alarms dismissed, close activity automatically (Requirement 1)
                if (activeAlarms.isEmpty() && initialScheduleId.isNotEmpty() && !AlarmRingingService.isRinging(initialScheduleId)) {
                    finish()
                }
            }

            // Fallback display if flow hasn't populated yet
            val displayAlarms = if (activeAlarms.isNotEmpty()) {
                activeAlarms
            } else if (initialScheduleId.isNotEmpty()) {
                listOf(ActiveRingingAlarm(initialScheduleId, initialTitle, isTest = isTest))
            } else {
                emptyList()
            }

            AlarmRingingContent(
                activeAlarms = displayAlarms,
                onDismissAlarm = { id ->
                    AlarmRingingService.dismissAlarm(this@AlarmRingingActivity, id)
                    if (displayAlarms.size <= 1) {
                        finish()
                    }
                },
                onDismissAll = {
                    AlarmRingingService.dismissAll(this@AlarmRingingActivity)
                    finish()
                },
                onSnoozeAll = {
                    for (alarm in displayAlarms) {
                        snoozeAlarm(alarm.title, alarm.entityId)
                    }
                    AlarmRingingService.dismissAll(this@AlarmRingingActivity)
                    finish()
                }
            )
        }
    }

    private fun turnScreenOnAndShowWhenLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            keyguardManager?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun snoozeAlarm(title: String, scheduleId: String) {
        val snoozeTimeMs = System.currentTimeMillis() + 5 * 60 * 1000L // +5 min snooze
        val scheduler = AlarmScheduler(this)
        scheduler.scheduleTestAlarmClock("snooze-$scheduleId", snoozeTimeMs, firedAfterReboot = false)
    }
}

@Composable
fun AlarmRingingContent(
    activeAlarms: List<ActiveRingingAlarm>,
    onDismissAlarm: (String) -> Unit,
    onDismissAll: () -> Unit,
    onSnoozeAll: () -> Unit
) {
    val currentTime = SimpleDateFormat("h:mm a", Locale.US).format(Date())

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0F172A)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 28.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(Color(0xFFDC2626).copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("⏰", fontSize = 40.sp)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = currentTime,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                if (activeAlarms.size > 1) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${activeAlarms.size} ALARMS ACTIVE",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFBBF24),
                        letterSpacing = 1.sp
                    )
                }
            }

            // List of every active ringing alarm (Requirement 1)
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(activeAlarms, key = { it.entityId }) { alarm ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                if (alarm.isTest) {
                                    Text(
                                        text = "🧪 ALARM SELF-TEST",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF38BDF8)
                                    )
                                }
                                Text(
                                    text = alarm.title,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Text(
                                    text = "ID: ${alarm.entityId.take(16)}...",
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Button(
                                onClick = { onDismissAlarm(alarm.entityId) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Dismiss", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Bottom Actions: Dismiss All + Snooze All
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onDismissAll,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text(
                        if (activeAlarms.size > 1) "DISMISS ALL ALARMS" else "DISMISS ALARM",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                OutlinedButton(
                    onClick = onSnoozeAll,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("Snooze (+5 min)", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
