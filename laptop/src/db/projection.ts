import { SyncEvent, ScheduleEntity, ExtractionOutput } from '@schedule-assistant/core';

export interface ProjectedSchedule extends ScheduleEntity {
  ambiguous?: boolean;
  history: Array<{ type: string; timestamp: string; device_id: string }>;
  candidate_matches?: Array<{ id: string; title: string; start?: string }>;
}

export interface ProjectedReminder {
  id: string;
  schedule_id: string;
  title: string;
  trigger_time: string;
  type: 'notification' | 'alarm';
  status: 'SCHEDULED' | 'FIRED' | 'CANCELLED';
  fired_at?: string;
  device_id: string;
}

export class ProjectionEngine {
  public static projectState(events: SyncEvent[]) {
    const schedules = new Map<string, ProjectedSchedule>();
    const reminders = new Map<string, ProjectedReminder>();

    for (const ev of events) {
      switch (ev.type) {
        case 'event_extracted': {
          const payload = ev.payload as ExtractionOutput & { raw_text?: string; candidate_matches?: any[] };
          schedules.set(ev.entity_id, {
            id: ev.entity_id,
            title: payload.title,
            start: payload.start,
            start_date: payload.start_date,
            time_unspecified: payload.time_unspecified,
            ambiguous: payload.ambiguous,
            status: 'PENDING',
            source_quote: payload.source_quote,
            raw_text: payload.raw_text,
            confidence: payload.confidence,
            created_at: ev.timestamp,
            updated_at: ev.timestamp,
            target_id: (payload.target as any)?.matched_entity_id,
            candidate_matches: payload.candidate_matches || [],
            history: [{ type: ev.type, timestamp: ev.timestamp, device_id: ev.device_id }]
          });
          break;
        }

        case 'event_confirmed': {
          const schedule = schedules.get(ev.entity_id);
          if (schedule) {
            schedule.status = 'CONFIRMED';
            schedule.ambiguous = false;
            schedule.updated_at = ev.timestamp;
            if (ev.payload.start) schedule.start = ev.payload.start;
            if (ev.payload.time_unspecified !== undefined) {
              schedule.time_unspecified = ev.payload.time_unspecified;
            }
            schedule.history.push({ type: ev.type, timestamp: ev.timestamp, device_id: ev.device_id });
          }
          break;
        }

        case 'event_dismissed': {
          const schedule = schedules.get(ev.entity_id);
          if (schedule) {
            schedule.status = 'DISMISSED';
            schedule.updated_at = ev.timestamp;
            schedule.history.push({ type: ev.type, timestamp: ev.timestamp, device_id: ev.device_id });
          }
          break;
        }

        case 'reminder_created':
        case 'alarm_set': {
          const remId = ev.entity_id;
          reminders.set(remId, {
            id: remId,
            schedule_id: ev.payload.schedule_id,
            title: ev.payload.title,
            trigger_time: ev.payload.trigger_time,
            type: ev.payload.type || (ev.type === 'alarm_set' ? 'alarm' : 'notification'),
            status: 'SCHEDULED',
            device_id: ev.device_id
          });
          break;
        }

        case 'reminder_fired': {
          const rem = reminders.get(ev.entity_id);
          if (rem) {
            rem.status = 'FIRED';
            rem.fired_at = ev.timestamp;
          }
          break;
        }
      }
    }

    return { schedules, reminders };
  }
}
