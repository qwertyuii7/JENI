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
    const schedulesInternal = new Map<string, ProjectedSchedule>();
    const remindersInternal = new Map<string, ProjectedReminder>();
    const deletedAtHlc = new Map<string, string>();

    // Deduplicate by event.id
    const seenIds = new Set<string>();
    const deduplicated: SyncEvent[] = [];
    for (const ev of events) {
      if (!seenIds.has(ev.id)) {
        seenIds.add(ev.id);
        deduplicated.push(ev);
      }
    }

    // Sort deterministically: HLC ASC then device_id ASC
    deduplicated.sort((a, b) => {
      const hlcA = a.hlc || a.timestamp || '';
      const hlcB = b.hlc || b.timestamp || '';
      if (hlcA < hlcB) return -1;
      if (hlcA > hlcB) return 1;
      if (a.device_id < b.device_id) return -1;
      if (a.device_id > b.device_id) return 1;
      return 0;
    });

    for (const ev of deduplicated) {
      const hlc = ev.hlc || ev.timestamp || '';
      switch (ev.type) {
        case 'event_extracted': {
          const payload = ev.payload as ExtractionOutput & { raw_text?: string; candidate_matches?: any[] };
          schedulesInternal.set(ev.entity_id, {
            id: ev.entity_id,
            title: payload.title,
            start: payload.start,
            start_date: payload.start_date,
            time_unspecified: payload.time_unspecified,
            ambiguous: payload.ambiguous,
            status: 'PENDING',
            deleted: false,
            has_alarm: false,
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
          const schedule = schedulesInternal.get(ev.entity_id);
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
          const schedule = schedulesInternal.get(ev.entity_id);
          if (schedule) {
            schedule.status = 'DISMISSED';
            schedule.updated_at = ev.timestamp;
            schedule.history.push({ type: ev.type, timestamp: ev.timestamp, device_id: ev.device_id });
          }
          break;
        }

        case 'schedule_edited': {
          const schedule = schedulesInternal.get(ev.entity_id);
          if (schedule) {
            if (ev.payload.title) schedule.title = ev.payload.title;
            if (ev.payload.start) {
              schedule.start = ev.payload.start;
              schedule.time_unspecified = false;
            }
            if (ev.payload.has_alarm !== undefined) {
              schedule.has_alarm = ev.payload.has_alarm;
            }
            schedule.updated_at = ev.timestamp;
            schedule.history.push({ type: ev.type, timestamp: ev.timestamp, device_id: ev.device_id });
          }
          break;
        }

        case 'schedule_deleted': {
          const curDel = deletedAtHlc.get(ev.entity_id);
          if (!curDel || hlc >= curDel) {
            deletedAtHlc.set(ev.entity_id, hlc);
          }
          const schedule = schedulesInternal.get(ev.entity_id);
          if (schedule) {
            schedule.deleted = true;
            schedule.status = 'DELETED';
            schedule.updated_at = ev.timestamp;
            schedule.history.push({ type: ev.type, timestamp: ev.timestamp, device_id: ev.device_id });
          }
          break;
        }

        case 'schedule_restored': {
          const curDel = deletedAtHlc.get(ev.entity_id);
          if (!curDel || hlc >= curDel) {
            deletedAtHlc.delete(ev.entity_id);
          }
          const schedule = schedulesInternal.get(ev.entity_id);
          if (schedule) {
            schedule.deleted = false;
            schedule.status = 'CONFIRMED';
            schedule.updated_at = ev.timestamp;
            schedule.history.push({ type: ev.type, timestamp: ev.timestamp, device_id: ev.device_id });
          }
          break;
        }

        case 'reminder_created':
        case 'alarm_set': {
          const remId = ev.entity_id;
          remindersInternal.set(remId, {
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
          const rem = remindersInternal.get(ev.entity_id);
          if (rem) {
            rem.status = 'FIRED';
            rem.fired_at = ev.timestamp;
          }
          break;
        }
      }
    }

    // Projection hides deleted schedules and their reminders/alarms
    const schedules = new Map<string, ProjectedSchedule>();
    for (const [id, s] of schedulesInternal.entries()) {
      if (!deletedAtHlc.has(id) && !s.deleted) {
        schedules.set(id, s);
      }
    }

    const reminders = new Map<string, ProjectedReminder>();
    for (const [id, r] of remindersInternal.entries()) {
      if (!deletedAtHlc.has(r.schedule_id)) {
        reminders.set(id, r);
      }
    }

    return { schedules, reminders };
  }
}
