package com.scheduleassistant.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import com.scheduleassistant.app.data.db.AppDatabase
import com.scheduleassistant.app.data.db.RawMessageEntity
import com.scheduleassistant.app.data.db.SyncEventEntity
import com.scheduleassistant.app.data.model.ExtractionOutput
import com.scheduleassistant.app.data.model.ScheduleStatus
import com.scheduleassistant.app.data.model.SyncEvent
import com.scheduleassistant.app.data.projection.ProjectionEngine
import com.scheduleassistant.app.engine.HlcClock
import com.scheduleassistant.app.engine.IngestionPipeline
import com.scheduleassistant.app.engine.Prefilter
import com.scheduleassistant.app.engine.ScheduleParser
import com.scheduleassistant.app.engine.SpecLoader
import com.scheduleassistant.app.service.AlarmScheduler
import com.scheduleassistant.app.ui.screens.*
import com.scheduleassistant.app.ui.theme.DarkBg
import com.scheduleassistant.app.ui.theme.Primary
import com.scheduleassistant.app.ui.theme.ScheduleAssistantTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

enum class AppTab(val title: String) {
    HOME("Home"),
    PENDING("Pending"),
    ALERTS("Alerts"),
    DEBUG("Debug"),
    SETTINGS("Settings")
}

class MainActivity : ComponentActivity() {

    private lateinit var db: AppDatabase
    private lateinit var specLoader: SpecLoader
    private lateinit var prefilter: Prefilter
    private lateinit var parser: ScheduleParser
    private lateinit var hlc: HlcClock
    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var pipeline: IngestionPipeline

    private val deviceId = "android-phone"

    private fun getIsoNow(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        db = AppDatabase.getInstance(applicationContext)
        alarmScheduler = AlarmScheduler(applicationContext)
        hlc = HlcClock(deviceId)

        specLoader = SpecLoader(
            keywordsStreamProvider = { assets.open("spec/keywords.json") },
            synonymsStreamProvider = { assets.open("spec/synonyms.json") },
            daypartsStreamProvider = { assets.open("spec/dayparts.json") }
        )
        prefilter = Prefilter(specLoader)
        parser = ScheduleParser(specLoader, prefilter)

        val initialTab = if (intent.getStringExtra("SCREEN_ROUTE") == "pending") {
            AppTab.PENDING
        } else {
            AppTab.HOME
        }

        setContent {
            ScheduleAssistantTheme {
                MainContent(initialTab = initialTab)
            }
        }
    }

    @Composable
    private fun MainContent(initialTab: AppTab) {
        var currentTab by remember { mutableStateOf(initialTab) }
        val eventsFlow = remember { db.eventDao().getAllEventsFlow() }
        val rawEntities by eventsFlow.collectAsState(initial = emptyList())

        // Map entities to domain SyncEvents
        val syncEvents = remember(rawEntities) {
            rawEntities.map { entity ->
                val payloadMap = mutableMapOf<String, Any?>()
                try {
                    val json = JSONObject(entity.payload)
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        payloadMap[key] = json.opt(key)
                    }
                } catch (_: Exception) {}

                SyncEvent(
                    id = entity.id,
                    timestamp = entity.timestamp,
                    device_id = entity.deviceId,
                    seq = entity.seq,
                    hlc = entity.hlc,
                    type = entity.type,
                    entity_id = entity.entityId,
                    payload = payloadMap
                )
            }
        }

        // Project deterministic state using HLC ordering
        val projectedState = remember(syncEvents) {
            ProjectionEngine.projectState(syncEvents)
        }

        val schedules = remember(projectedState) {
            projectedState.schedules.values.toList()
        }

        val reminders = remember(projectedState) {
            projectedState.reminders.values.toList()
        }

        var lastExtracted by remember { mutableStateOf<ExtractionOutput?>(null) }

        val pendingCount = schedules.count { it.status == ScheduleStatus.PENDING }

        Scaffold(
            bottomBar = {
                NavigationBar(
                    containerColor = DarkBg,
                    contentColor = Color.White
                ) {
                    NavigationBarItem(
                        selected = currentTab == AppTab.HOME,
                        onClick = { currentTab = AppTab.HOME },
                        icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                        label = { Text("Home") },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Primary, selectedTextColor = Primary)
                    )
                    NavigationBarItem(
                        selected = currentTab == AppTab.PENDING,
                        onClick = { currentTab = AppTab.PENDING },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (pendingCount > 0) {
                                        Badge { Text("$pendingCount") }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.List, contentDescription = "Pending")
                            }
                        },
                        label = { Text("Pending") },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Primary, selectedTextColor = Primary)
                    )
                    NavigationBarItem(
                        selected = currentTab == AppTab.ALERTS,
                        onClick = { currentTab = AppTab.ALERTS },
                        icon = { Icon(Icons.Default.Notifications, contentDescription = "Alerts") },
                        label = { Text("Alerts") },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Primary, selectedTextColor = Primary)
                    )
                    NavigationBarItem(
                        selected = currentTab == AppTab.DEBUG,
                        onClick = { currentTab = AppTab.DEBUG },
                        icon = { Icon(Icons.Default.BugReport, contentDescription = "Debug") },
                        label = { Text("Debug") },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Primary, selectedTextColor = Primary)
                    )
                    NavigationBarItem(
                        selected = currentTab == AppTab.SETTINGS,
                        onClick = { currentTab = AppTab.SETTINGS },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Primary, selectedTextColor = Primary)
                    )
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                when (currentTab) {
                    AppTab.HOME -> HomeScreen(
                        schedules = schedules,
                        onNavigateToPending = { currentTab = AppTab.PENDING },
                        onNavigateToDebug = { currentTab = AppTab.DEBUG }
                    )
                    AppTab.PENDING -> PendingScreen(
                        schedules = schedules,
                        onConfirm = { id, customTime ->
                            handleConfirmSchedule(id, customTime, projectedState)
                        },
                        onDismiss = { id ->
                            handleDismissSchedule(id)
                        }
                    )
                    AppTab.ALERTS -> AlertsScreen(reminders = reminders)
                    AppTab.DEBUG -> DebugScreen(
                        onInjectMessage = { text ->
                            lifecycleScope.launch {
                                val result = handleInjectMessage(text)
                                lastExtracted = result
                            }
                        },
                        lastExtracted = lastExtracted,
                        events = syncEvents
                    )
                    AppTab.SETTINGS -> SettingsScreen()
                }
            }
        }
    }

    private fun handleConfirmSchedule(
        id: String,
        customTime: String?,
        projectedState: com.scheduleassistant.app.data.projection.ProjectedState
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val nextSeq = db.eventDao().getNextSeq(deviceId)
            val hlcNow = hlc.now()
            val nowIso = getIsoNow()

            val payloadJson = JSONObject()
            if (customTime != null) {
                payloadJson.put("start", customTime)
                payloadJson.put("time_unspecified", false)
            }

            val confirmEntity = SyncEventEntity(
                id = UUID.randomUUID().toString(),
                timestamp = nowIso,
                deviceId = deviceId,
                seq = nextSeq,
                hlc = hlcNow,
                type = "event_confirmed",
                entityId = id,
                payload = payloadJson.toString()
            )
            db.eventDao().insertEvent(confirmEntity)

            // Trigger alarm schedule for confirmed event
            val target = projectedState.schedules[id]
            if (target != null) {
                val updatedTarget = if (customTime != null) {
                    target.copy(status = ScheduleStatus.CONFIRMED, start = customTime, time_unspecified = false)
                } else {
                    target.copy(status = ScheduleStatus.CONFIRMED)
                }
                withContext(Dispatchers.Main) {
                    alarmScheduler.scheduleAlertsForSchedule(updatedTarget)
                }
            }
        }
    }

    private fun handleDismissSchedule(id: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val nextSeq = db.eventDao().getNextSeq(deviceId)
            val hlcNow = hlc.now()
            val nowIso = getIsoNow()

            val dismissEntity = SyncEventEntity(
                id = UUID.randomUUID().toString(),
                timestamp = nowIso,
                deviceId = deviceId,
                seq = nextSeq,
                hlc = hlcNow,
                type = "event_dismissed",
                entityId = id,
                payload = "{}"
            )
            db.eventDao().insertEvent(dismissEntity)

            withContext(Dispatchers.Main) {
                alarmScheduler.cancelAlertsForSchedule(id)
            }
        }
    }

    private suspend fun handleInjectMessage(rawText: String): ExtractionOutput? = withContext(Dispatchers.IO) {
        val nowIso = getIsoNow()
        val rawMsgId = "raw-${UUID.randomUUID()}"

        // 1. Store raw message in prunable table
        db.rawMessageDao().insertRawMessage(
            RawMessageEntity(id = rawMsgId, rawText = rawText, source = "debug_inject", createdAt = nowIso)
        )

        // 2. Prefilter & Parse
        val filterRes = prefilter.prefilter(rawText)
        if (!filterRes.shouldParse) {
            return@withContext null
        }

        val extraction = parser.parse(rawText) ?: return@withContext null

        val entityId = "sched-${UUID.randomUUID()}"
        val nextSeq = db.eventDao().getNextSeq(deviceId)
        val hlcNow = hlc.now()

        val payloadJson = JSONObject().apply {
            put("type", extraction.type)
            put("title", extraction.title)
            put("start", extraction.start)
            put("start_date", extraction.start_date)
            put("time_unspecified", extraction.time_unspecified)
            put("ambiguous", extraction.ambiguous)
            put("source_quote", extraction.source_quote)
            put("confidence", extraction.confidence)
            put("raw_text", rawText)
            put("raw_message_id", rawMsgId)
        }

        val eventEntity = SyncEventEntity(
            id = UUID.randomUUID().toString(),
            timestamp = nowIso,
            deviceId = deviceId,
            seq = nextSeq,
            hlc = hlcNow,
            type = "event_extracted",
            entityId = entityId,
            payload = payloadJson.toString()
        )
        db.eventDao().insertEvent(eventEntity)

        extraction
    }
}
