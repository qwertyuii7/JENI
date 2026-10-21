package com.scheduleassistant.app.engine

import com.scheduleassistant.app.data.model.ExtractionOutput
import com.scheduleassistant.app.data.model.ScheduleEntity
import com.scheduleassistant.app.data.model.ScheduleStatus
import com.scheduleassistant.app.data.model.SyncEvent
import com.scheduleassistant.app.data.projection.ProjectionEngine
import com.scheduleassistant.app.domain.DefaultLaneClassifier
import com.scheduleassistant.app.domain.DefaultSignalScorer
import com.scheduleassistant.app.domain.LaneClassifier
import com.scheduleassistant.app.domain.SignalScorer
import com.scheduleassistant.app.domain.TriageLane
import com.scheduleassistant.app.domain.UserProfile
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
    val status: String, // "PENDING", "NEEDS_ATTENTION", "IGNORED", "REJECTED", "PARSE_FAILED", "FLOOD_TRUNCATED"
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
    private val dispatcher: NotificationDispatcher? = null,
    private val signalScorer: SignalScorer = DefaultSignalScorer(),
    private val laneClassifier: LaneClassifier = DefaultLaneClassifier(),
    private val userProfile: UserProfile = UserProfile()
) {
    private var seqCounter: Long = 0

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Synchronized
    fun processMessage(
        rawText: String,
        referenceNow: String = "2026-10-12T10:00:00+05:30",
        sender: String? = null,
        chatTitle: String? = null
    ): PipelineResult {
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
                    "message" to "Message exceeded flood limit",
                    "chat_title" to chatTitle,
                    "sender" to sender
                )
            )
            eventStore.add(floodEvent)
            dispatcher?.onAlert("Schedule Alert: Message Truncated", "Notification contained high volume of events", "warning")
            return PipelineResult("FLOOD_TRUNCATED", message = "Message flood detected and truncated")
        }

        // 2. Prefilter & Injection guard
        val filterRes = prefilter.prefilter(rawText)
        if (!filterRes.shouldParse) {
            if (filterRes.reason == "empty_input") {
                return PipelineResult("REJECTED", message = "Ignored by prefilter: empty_input")
            }
            if (filterRes.reason == "pure_injection") {
                val ignoreId = "ignore-${UUID.randomUUID()}"
                val ignoreEvent = SyncEvent(
                    id = UUID.randomUUID().toString(),
                    timestamp = nowIso,
                    device_id = deviceId,
                    seq = ++seqCounter,
                    hlc = hlc.now(),
                    type = "event_ignored",
                    entity_id = ignoreId,
                    payload = mapOf(
                        "reasons" to listOf("prompt_injection", "security_blocked"),
                        "confidence" to 1.0,
                        "raw_text" to rawText,
                        "raw_message_id" to rawMessageId,
                        "chat_title" to chatTitle,
                        "sender" to sender
                    )
                )
                eventStore.add(ignoreEvent)
                return PipelineResult("IGNORED", message = "Blocked prompt injection")
            }
        }

        // 3. Triage Signal Scoring with Learned Priors
        val projectedState = ProjectionEngine.projectState(eventStore)
        val chatPriors = projectedState.chatPriors
        val senderPriors = projectedState.senderPriors

        val signalFeatures = signalScorer.score(
            normalizedText = rawText,
            sender = sender,
            chatTitle = chatTitle,
            userProfile = userProfile,
            chatPriors = chatPriors,
            senderPriors = senderPriors
        )
        val classification = laneClassifier.classify(signalFeatures)

        // 4. IGNORE Lane: Confident noise / chitchat / promo
        if (classification.lane == TriageLane.IGNORE) {
            val ignoreId = "ignore-${UUID.randomUUID()}"
            val ignoreEvent = SyncEvent(
                id = UUID.randomUUID().toString(),
                timestamp = nowIso,
                device_id = deviceId,
                seq = ++seqCounter,
                hlc = hlc.now(),
                type = "event_ignored",
                entity_id = ignoreId,
                payload = mapOf(
                    "reasons" to classification.reasons,
                    "confidence" to classification.confidence,
                    "raw_text" to rawText,
                    "raw_message_id" to rawMessageId,
                    "chat_title" to chatTitle,
                    "sender" to sender
                )
            )
            eventStore.add(ignoreEvent)
            return PipelineResult(
                status = "IGNORED",
                message = "Message routed to IGNORE lane: ${classification.reasons.joinToString()}",
                extraction = null
            )
        }

        // 5. Parse using ScheduleParser
        val extraction = parser.parse(rawText, referenceNow)

        // 6. NEEDS_ATTENTION Lane: Action items, notices, attachments, or time-unspecified items
        val isAttentionLane = classification.lane == TriageLane.NEEDS_ATTENTION ||
                extraction == null ||
                extraction.time_unspecified ||
                extraction.ambiguous

        if (isAttentionLane) {
            val effectiveReasons = classification.reasons.toMutableList()
            if (extraction == null && !effectiveReasons.contains("unresolved_rules")) {
                effectiveReasons.add("unresolved_rules")
            } else if (extraction?.time_unspecified == true && !effectiveReasons.contains("no_concrete_time")) {
                effectiveReasons.add("no_concrete_time")
            } else if (extraction?.ambiguous == true && !effectiveReasons.contains("ambiguous_time")) {
                effectiveReasons.add("ambiguous_time")
            }

            val effectiveExtraction = extraction ?: ExtractionOutput(
                type = "notice",
                title = extractNoticeTitle(rawText),
                start = null,
                start_date = extractNoticeDate(rawText),
                end = null,
                time_unspecified = true,
                ambiguous = false,
                source_quote = rawText.take(120),
                confidence = classification.confidence
            )
            if (extraction != null) {
                effectiveExtraction.time_unspecified = true
            }

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
                    "type" to effectiveExtraction.type,
                    "title" to effectiveExtraction.title,
                    "start" to effectiveExtraction.start,
                    "start_date" to effectiveExtraction.start_date,
                    "time_unspecified" to true,
                    "ambiguous" to effectiveExtraction.ambiguous,
                    "source_quote" to effectiveExtraction.source_quote,
                    "confidence" to effectiveExtraction.confidence,
                    "raw_text" to rawText,
                    "raw_message_id" to rawMessageId,
                    "lane" to "NEEDS_ATTENTION",
                    "reasons" to effectiveReasons,
                    "chat_title" to chatTitle,
                    "sender" to sender
                )
            )
            eventStore.add(extractEvent)

            return PipelineResult(
                status = "NEEDS_ATTENTION",
                entityId = entityId,
                extraction = effectiveExtraction,
                message = "Item routed to Needs Attention lane: ${effectiveReasons.joinToString()}"
            )
        }

        // 7. SCHEDULE Lane with concrete resolved time
        val concreteExtraction = extraction!!
        val candidates = mutableListOf<ScheduleEntity>()
        if (concreteExtraction.type == "cancel" || concreteExtraction.type == "reschedule") {
            val targetTitle = (concreteExtraction.target?.title ?: concreteExtraction.title).lowercase().trim()
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
                concreteExtraction.target?.matched_entity_id = matched.id
                if (concreteExtraction.type == "reschedule" && concreteExtraction.time_unspecified && matched.start != null) {
                    val origTime = matched.start!!.split("T")[1]
                    if (concreteExtraction.start_date != null) {
                        concreteExtraction.start = "${concreteExtraction.start_date}T$origTime"
                        concreteExtraction.time_unspecified = false
                    }
                }
            } else {
                concreteExtraction.ambiguous = true
            }
        }

        // Emit PENDING event_extracted for SCHEDULE lane
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
                "type" to concreteExtraction.type,
                "title" to concreteExtraction.title,
                "start" to concreteExtraction.start,
                "start_date" to concreteExtraction.start_date,
                "time_unspecified" to concreteExtraction.time_unspecified,
                "ambiguous" to concreteExtraction.ambiguous,
                "source_quote" to concreteExtraction.source_quote,
                "confidence" to concreteExtraction.confidence,
                "raw_text" to rawText,
                "raw_message_id" to rawMessageId,
                "lane" to "SCHEDULE",
                "reasons" to classification.reasons,
                "chat_title" to chatTitle,
                "sender" to sender,
                "candidate_matches" to candidates.map { mapOf("id" to it.id, "title" to it.title, "start" to it.start) }
            )
        )
        eventStore.add(extractEvent)

        return PipelineResult(
            status = "PENDING",
            entityId = entityId,
            extraction = concreteExtraction,
            candidates = candidates,
            message = "Event extracted and stored as PENDING suggestion"
        )
    }

    @Synchronized
    fun moveScheduleLane(entityId: String, targetLane: TriageLane, reason: String = "user_action"): Boolean {
        val schedules = getProjectedSchedules()
        val sched = schedules[entityId] ?: return false
        val fromLane = sched.lane.name
        val toLane = targetLane.name

        val moveEvent = SyncEvent(
            id = UUID.randomUUID().toString(),
            timestamp = isoFormat.format(Date()),
            device_id = deviceId,
            seq = ++seqCounter,
            hlc = hlc.now(),
            type = "moved_lane",
            entity_id = entityId,
            payload = mapOf(
                "from_lane" to fromLane,
                "to_lane" to toLane,
                "reason" to reason,
                "chat_title" to sched.chat_title,
                "sender" to sched.sender
            )
        )
        eventStore.add(moveEvent)
        return true
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
        payload["chat_title"] = sched.chat_title
        payload["sender"] = sched.sender

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
            payload = mapOf(
                "chat_title" to sched.chat_title,
                "sender" to sched.sender
            )
        )
        eventStore.add(dismissEvent)
        return true
    }

    private fun getProjectedSchedules(): Map<String, ScheduleEntity> {
        return ProjectionEngine.projectState(eventStore).schedules
    }

    private fun extractNoticeTitle(rawText: String): String {
        val firstLine = rawText.lines().firstOrNull { it.isNotBlank() } ?: "Notice"
        val clean = firstLine.replace(Regex("(?i)^(important notice|notice|circular|attention|urgent):?\\s*"), "").trim()
        return if (clean.length in 3..60) clean else if (clean.length > 60) clean.take(57) + "..." else "College Notice"
    }

    private fun extractNoticeDate(rawText: String): String? {
        val dateRegex = Regex("(?i)\\b(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}|\\d{1,2}(?:st|nd|rd|th)?\\s+(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*)\\b")
        return dateRegex.find(rawText)?.value
    }
}
