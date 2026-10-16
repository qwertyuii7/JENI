package com.scheduleassistant.app.data.model

data class ExtractionTarget(
    val title: String,
    val original_start: String? = null,
    val original_date: String? = null,
    var matched_entity_id: String? = null
)

data class ExtractionOutput(
    val type: String, // "new", "cancel", "reschedule"
    var title: String,
    var start: String? = null,
    val start_date: String? = null,
    val end: String? = null,
    var time_unspecified: Boolean = false,
    var ambiguous: Boolean = false,
    val location: String? = null,
    var target: ExtractionTarget? = null,
    val source_quote: String,
    var confidence: Double
)

data class SyncEvent(
    val id: String,
    val timestamp: String,
    val device_id: String,
    val seq: Long,
    val hlc: String,
    val type: String,
    val entity_id: String,
    val payload: Map<String, Any?>
)

enum class ScheduleStatus {
    PENDING,
    CONFIRMED,
    DISMISSED,
    CANCELLED,
    DELETED
}

data class ScheduleEntity(
    val id: String,
    var title: String,
    var start: String? = null,
    var start_date: String? = null,
    var end: String? = null,
    var time_unspecified: Boolean = false,
    var ambiguous: Boolean = false,
    var status: ScheduleStatus = ScheduleStatus.PENDING,
    var deleted: Boolean = false,
    var has_alarm: Boolean = false,
    val source_quote: String,
    val raw_text: String? = null,
    var confidence: Double,
    val created_at: String,
    var updated_at: String,
    var target_id: String? = null,
    val candidate_matches: List<Map<String, Any?>> = emptyList()
)

enum class ReminderStatus {
    SCHEDULED,
    FIRED,
    CANCELLED
}

data class ReminderEntity(
    val id: String,
    val schedule_id: String,
    val title: String,
    val trigger_time: String,
    val type: String, // "notification" or "alarm"
    var status: ReminderStatus = ReminderStatus.SCHEDULED,
    var fired_at: String? = null,
    val device_id: String
)
