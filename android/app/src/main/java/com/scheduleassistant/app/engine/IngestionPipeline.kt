package com.scheduleassistant.app.engine

import com.scheduleassistant.app.data.model.ExtractionOutput
import com.scheduleassistant.app.data.model.ScheduleEntity
import com.scheduleassistant.app.data.model.ScheduleStatus
import com.scheduleassistant.app.data.model.SyncEvent
import com.scheduleassistant.app.data.projection.ProjectionEngine
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

interface NotificationDispatcher {
    fun onAlert(title: String, body: String, type: String)
}

data class PipelineResult(
    val status: String, // "PENDING", "REJECTED", "PARSE_FAILED", "FLOOD_TRUNCATED"
    val entityId: String? = null,
    val extraction: ExtractionOutput? = null,
    val message: String,
    val candidates: List<ScheduleEntity> = emptyList()
)

class IngestionPipeline(
    private val deviceId: String,
    private val specLoader: SpecLoader,
    private val prefilter: Prefilter,
    private val parser: ScheduleParser,
    private val hlc: HlcClock,
    private val eventStore: MutableList<SyncEvent>,
    private val rawMessageStore: MutableMap<String, String>,
    private val dispatcher: NotificationDispatcher? = null
) {
    private var seqCounter: Long = 0

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Synchronized
    fun processMessage(rawText: String, referenceNow: String = "2026-10-12T10:00:00+05:30"): PipelineResult {
        val nowIso = isoFormat.format(Date())
        val rawMessageId = "raw-${UUID.randomUUID()}"
        rawMessageStore[rawMessageId] = rawText

        // 1. Flooding Check (>3 schedule keywords)
        val matches = Regex("(?i)\\b(class|meeting|lecture|sync|viva|test|quiz|lab|demo)\\b").findAll(rawText).count()
        if (matches > 3) {
            val floodId = UUID.randomUUID().toString()
            val floodEvent = SyncEvent(
                id = floodId,
                timestamp = nowIso,
                device_id = deviceId,
                seq = ++seqCounter,
                hlc = hlc.now(),
                type = "event_dismissed",
                entity_id = "flood-$floodId",
                payload = mapOf(
                    "reason" to "flood_truncated",
                    "raw_text" to rawText,
                    "raw_message_id" to rawMessageId,
                    "message" to "Message exceeded flood limit"
                )
            )
            eventStore.add(floodEvent)
            dispatcher?.onAlert("Schedule Alert: Message Truncated", "Notification contained high volume of events", "warning")
            return PipelineResult("FLOOD_TRUNCATED", message = "Message flood detected and truncated")
        }

        // 2. Prefilter
        val filterRes = prefilter.prefilter(rawText)
        if (!filterRes.shouldParse) {
            if (filterRes.reason in listOf("empty_input", "casual_chatter", "otp_noise", "price_noise", "score_noise", "pure_injection")) {
                return PipelineResult("REJECTED", message = "Ignored by prefilter: ${filterRes.reason}")
            }

            // Visible parse failure event
            val failEvent = SyncEvent(
                id = UUID.randomUUID().toString(),
                timestamp = nowIso,
                device_id = deviceId,
                seq = ++seqCounter,
                hlc = hlc.now(),
                type = "parse_failed",
                entity_id = "fail-${UUID.randomUUID()}",
                payload = mapOf(
                    "raw_text" to rawText,
                    "raw_message_id" to rawMessageId,
                    "reason" to (filterRes.reason ?: "unresolved_prefilter")
                )
            )
            eventStore.add(failEvent)
            dispatcher?.onAlert("Schedule Assistant: Could Not Parse Message", "Received: \"${rawText.take(100)}\"", "info")
            return PipelineResult("PARSE_FAILED", message = "Parse failed: ${filterRes.reason}")
        }

        // 3. Parser
        val extraction = parser.parse(rawText, referenceNow)
        if (extraction == null) {
            val failEvent = SyncEvent(
                id = UUID.randomUUID().toString(),
                timestamp = nowIso,
                device_id = deviceId,
                seq = ++seqCounter,
                hlc = hlc.now(),
                type = "parse_failed",
                entity_id = "fail-${UUID.randomUUID()}",
                payload = mapOf(
                    "raw_text" to rawText,
                    "raw_message_id" to rawMessageId,
                    "reason" to "unresolved_rules"
                )
            )
            eventStore.add(failEvent)
            dispatcher?.onAlert("Schedule Assistant: Could Not Parse Message", "Received: \"${rawText.take(100)}\"", "info")
            return PipelineResult("PARSE_FAILED", message = "Extraction returned null")
        }

        // 4. Target Resolver
        val candidates = mutableListOf<ScheduleEntity>()
        if (extraction.type == "cancel" || extraction.type == "reschedule") {
            val targetTitle = (extraction.target?.title ?: extraction.title).lowercase().trim()
            val cleanTarget = specLoader.normalizeTitleForMatching(targetTitle)
            val existingSchedules = getProjectedSchedules()

            fun areTitlesSynonyms(t1: String, t2: String): Boolean {
                if (t1 == t2) return true
                val w1 = t1.split(Regex("\\s+"))
                val w2 = t2.split(Regex("\\s+"))
                if (w1.size != w2.size) return false
                for (i in w1.indices) {
                    if (w1[i] == w2[i]) continue
                    val syns = specLoader.intraTypeSynonyms[w1[i]] ?: emptyList()
                    if (!syns.contains(w2[i])) return false
                }
                return true
            }

            for (sched in existingSchedules.values) {
                if (sched.deleted || sched.status == ScheduleStatus.DELETED || sched.status == ScheduleStatus.DISMISSED || sched.status == ScheduleStatus.CANCELLED) continue
                val sTitle = sched.title.lowercase().trim()
                val cleanSched = specLoader.normalizeTitleForMatching(sTitle)
                val isSynonym = specLoader.intraTypeSynonyms[targetTitle]?.contains(sTitle) == true ||
                                specLoader.intraTypeSynonyms[cleanTarget]?.contains(cleanSched) == true ||
                                areTitlesSynonyms(cleanSched, cleanTarget) ||
                                areTitlesSynonyms(sTitle, targetTitle)
                if (sTitle == targetTitle || cleanSched == cleanTarget ||
                    sTitle.contains(targetTitle) || targetTitle.contains(sTitle) ||
                    cleanSched.contains(cleanTarget) || cleanTarget.contains(cleanSched) ||
                    isSynonym) {
                    candidates.add(sched)
                }
            }

            if (candidates.size == 1) {
                val matched = candidates[0]
                extraction.target?.matched_entity_id = matched.id
                // Time inheritance for time_unspecified reschedules
                if (extraction.type == "reschedule" && extraction.time_unspecified && matched.start != null) {
                    val origTime = matched.start!!.split("T")[1]
                    if (extraction.start_date != null) {
                        extraction.start = "${extraction.start_date}T$origTime"
                        extraction.time_unspecified = false
                    }
                }
            } else {
                extraction.ambiguous = true
            }
        }

        // 5. Emit PENDING event_extracted
        val entityId = "sched-${UUID.randomUUID()}"
        val extractEvent = SyncEvent(
            id = UUID.randomUUID().toString(),
            timestamp = nowIso,
            device_id = deviceId,
            seq = ++seqCounter,
            hlc = hlc.now(),
            type = "event_extracted",
            entity_id = entityId,
            payload = mapOf(
                "type" to extraction.type,
                "title" to extraction.title,
                "start" to extraction.start,
                "start_date" to extraction.start_date,
                "time_unspecified" to extraction.time_unspecified,
                "ambiguous" to extraction.ambiguous,
                "source_quote" to extraction.source_quote,
                "confidence" to extraction.confidence,
                "raw_text" to rawText,
                "raw_message_id" to rawMessageId,
                "candidate_matches" to candidates.map { mapOf("id" to it.id, "title" to it.title, "start" to it.start) }
            )
        )
        eventStore.add(extractEvent)

        return PipelineResult(
            status = "PENDING",
            entityId = entityId,
            extraction = extraction,
            candidates = candidates,
            message = "Event extracted and stored as PENDING"
        )
    }

    @Synchronized
    fun confirmSchedule(entityId: String, timeOverride: String? = null): Boolean {
        val schedules = getProjectedSchedules()
        val sched = schedules[entityId] ?: return false
        if (sched.status != ScheduleStatus.PENDING) return false

        val payload = mutableMapOf<String, Any?>()
        if (timeOverride != null) {
            payload["start"] = timeOverride
            payload["time_unspecified"] = false
        }

        val confirmEvent = SyncEvent(
            id = UUID.randomUUID().toString(),
            timestamp = isoFormat.format(Date()),
            device_id = deviceId,
            seq = ++seqCounter,
            hlc = hlc.now(),
            type = "event_confirmed",
            entity_id = entityId,
            payload = payload
        )
        eventStore.add(confirmEvent)

        val effectiveStart = timeOverride ?: sched.start
        val timeUnspecified = if (timeOverride != null) false else sched.time_unspecified

        if (!timeUnspecified && effectiveStart != null) {
            val startTimeMs = try {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(effectiveStart)?.time
            } catch (_: Exception) { null }

            if (startTimeMs != null) {
                val reminderTimeMs = startTimeMs - 15 * 60 * 1000
                val reminderIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date(reminderTimeMs))

                val reminderEvent = SyncEvent(
                    id = UUID.randomUUID().toString(),
                    timestamp = isoFormat.format(Date()),
                    device_id = deviceId,
                    seq = ++seqCounter,
                    hlc = hlc.now(),
                    type = "reminder_created",
                    entity_id = "rem-$entityId",
                    payload = mapOf(
                        "schedule_id" to entityId,
                        "title" to sched.title,
                        "trigger_time" to reminderIso,
                        "type" to "notification"
                    )
                )
                eventStore.add(reminderEvent)

                val alarmEvent = SyncEvent(
                    id = UUID.randomUUID().toString(),
                    timestamp = isoFormat.format(Date()),
                    device_id = deviceId,
                    seq = ++seqCounter,
                    hlc = hlc.now(),
                    type = "alarm_set",
                    entity_id = "alarm-$entityId",
                    payload = mapOf(
                        "schedule_id" to entityId,
                        "title" to sched.title,
                        "trigger_time" to effectiveStart,
                        "type" to "alarm"
                    )
                )
                eventStore.add(alarmEvent)
            }
        }
        return true
    }

    @Synchronized
    fun dismissSchedule(entityId: String): Boolean {
        val schedules = getProjectedSchedules()
        val sched = schedules[entityId] ?: return false
        if (sched.status != ScheduleStatus.PENDING) return false

        val dismissEvent = SyncEvent(
            id = UUID.randomUUID().toString(),
            timestamp = isoFormat.format(Date()),
            device_id = deviceId,
            seq = ++seqCounter,
            hlc = hlc.now(),
            type = "event_dismissed",
            entity_id = entityId,
            payload = emptyMap()
        )
        eventStore.add(dismissEvent)
        return true
    }

    private fun getProjectedSchedules(): Map<String, ScheduleEntity> {
        return ProjectionEngine.projectState(eventStore).schedules
    }
}
