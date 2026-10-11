package com.scheduleassistant.app.data.projection

import com.scheduleassistant.app.data.model.ReminderEntity
import com.scheduleassistant.app.data.model.ReminderStatus
import com.scheduleassistant.app.data.model.ScheduleEntity
import com.scheduleassistant.app.data.model.ScheduleStatus
import com.scheduleassistant.app.data.model.SyncEvent
import com.scheduleassistant.app.engine.HlcClock
import org.json.JSONObject

data class ProjectedState(
    val schedules: Map<String, ScheduleEntity>,
    val reminders: Map<String, ReminderEntity>
)

object ProjectionEngine {

    fun projectState(events: List<SyncEvent>): ProjectedState {
        val schedules = mutableMapOf<String, ScheduleEntity>()
        val reminders = mutableMapOf<String, ReminderEntity>()

        // 1. Deduplicate by event id
        val seen = HashSet<String>()
        val unique = ArrayList<SyncEvent>()
        for (ev in events) {
            if (seen.add(ev.id)) {
                unique.add(ev)
            }
        }

        // 2. Sort deterministically by HLC then device_id
        unique.sortWith { a, b ->
            HlcClock.compare(a.hlc, a.device_id, b.hlc, b.device_id)
        }

        for (ev in unique) {
            when (ev.type) {
                "event_extracted" -> {
                    val title = ev.payload["title"] as? String ?: "event"
                    val start = ev.payload["start"] as? String
                    val startDate = ev.payload["start_date"] as? String
                    val timeUnspecified = ev.payload["time_unspecified"] as? Boolean ?: false
                    val ambiguous = ev.payload["ambiguous"] as? Boolean ?: false
                    val quote = ev.payload["source_quote"] as? String ?: ""
                    val rawText = ev.payload["raw_text"] as? String
                    val conf = (ev.payload["confidence"] as? Number)?.toDouble() ?: 0.85

                    schedules[ev.entity_id] = ScheduleEntity(
                        id = ev.entity_id,
                        title = title,
                        start = start,
                        start_date = startDate,
                        time_unspecified = timeUnspecified,
                        ambiguous = ambiguous,
                        status = ScheduleStatus.PENDING,
                        source_quote = quote,
                        raw_text = rawText,
                        confidence = conf,
                        created_at = ev.timestamp,
                        updated_at = ev.timestamp
                    )
                }

                "event_confirmed" -> {
                    val s = schedules[ev.entity_id]
                    if (s != null) {
                        s.status = ScheduleStatus.CONFIRMED
                        s.ambiguous = false
                        s.updated_at = ev.timestamp
                        val overrideStart = ev.payload["start"] as? String
                        if (overrideStart != null) {
                            s.start = overrideStart
                            s.time_unspecified = false
                        }
                    }
                }

                "event_dismissed" -> {
                    val s = schedules[ev.entity_id]
                    if (s != null) {
                        s.status = ScheduleStatus.DISMISSED
                        s.updated_at = ev.timestamp
                    }
                }

                "event_cancelled" -> {
                    val s = schedules[ev.entity_id]
                    if (s != null) {
                        s.status = ScheduleStatus.CANCELLED
                        s.updated_at = ev.timestamp
                    }
                }

                "event_rescheduled" -> {
                    val s = schedules[ev.entity_id]
                    if (s != null) {
                        val newStart = ev.payload["start"] as? String
                        if (newStart != null) {
                            s.start = newStart
                            s.time_unspecified = false
                        }
                        s.updated_at = ev.timestamp
                    }
                }

                "reminder_created", "alarm_set" -> {
                    val triggerTime = ev.payload["trigger_time"] as? String ?: ""
                    val title = ev.payload["title"] as? String ?: ""
                    val scheduleId = ev.payload["schedule_id"] as? String ?: ""
                    val type = if (ev.type == "alarm_set") "alarm" else "notification"

                    reminders[ev.entity_id] = ReminderEntity(
                        id = ev.entity_id,
                        schedule_id = scheduleId,
                        title = title,
                        trigger_time = triggerTime,
                        type = type,
                        status = ReminderStatus.SCHEDULED,
                        device_id = ev.device_id
                    )
                }

                "reminder_fired" -> {
                    val rem = reminders[ev.entity_id]
                    if (rem != null) {
                        rem.status = ReminderStatus.FIRED
                        rem.fired_at = ev.timestamp
                    }
                }
            }
        }

        return ProjectedState(schedules, reminders)
    }
}
