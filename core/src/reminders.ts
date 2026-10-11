import { ScheduleEntity, SyncEvent } from './types.js';
import { v4 as uuidv4 } from 'uuid';

export interface GeneratedAlerts {
  reminderEvent?: SyncEvent;
  alarmEvent?: SyncEvent;
}

export function generateAlertsForSchedule(
  schedule: ScheduleEntity,
  deviceId: string,
  options: { seqStart?: number; hlc?: string } = {}
): GeneratedAlerts {
  // HARD INVARIANT: No reminder or alarm is created from a time_unspecified event
  // until an explicit time is confirmed
  if (schedule.time_unspecified || !schedule.start) {
    return {};
  }

  // Must be confirmed by user before creating alerts
  if (schedule.status !== 'CONFIRMED') {
    return {};
  }

  const startTimeMs = new Date(schedule.start).getTime();
  if (isNaN(startTimeMs)) {
    return {};
  }

  const nowIso = new Date().toISOString();
  const baseSeq = options.seqStart || 1;
  const baseHlc = options.hlc || `${nowIso}_0001`;

  // Notification reminder 15 minutes before
  const reminderTimeMs = startTimeMs - 15 * 60 * 1000;
  const reminderIso = new Date(reminderTimeMs).toISOString();

  // Real alarm 30 minutes before
  const alarmTimeMs = startTimeMs - 30 * 60 * 1000;
  const alarmIso = new Date(alarmTimeMs).toISOString();

  const reminderEvent: SyncEvent = {
    id: uuidv4(),
    timestamp: nowIso,
    device_id: deviceId,
    seq: baseSeq,
    hlc: baseHlc,
    type: 'reminder_created',
    entity_id: `rem-${schedule.id}`,
    payload: {
      schedule_id: schedule.id,
      title: schedule.title,
      trigger_time: reminderIso,
      type: 'notification'
    }
  };

  const alarmEvent: SyncEvent = {
    id: uuidv4(),
    timestamp: nowIso,
    device_id: deviceId,
    seq: baseSeq + 1,
    hlc: baseHlc,
    type: 'alarm_set',
    entity_id: `alarm-${schedule.id}`,
    payload: {
      schedule_id: schedule.id,
      title: schedule.title,
      trigger_time: alarmIso,
      type: 'alarm'
    }
  };

  return {
    reminderEvent,
    alarmEvent
  };
}

export function isReminderSuppressed(
  reminderId: string,
  events: SyncEvent[]
): boolean {
  return events.some(
    (ev) => ev.type === 'reminder_fired' && ev.entity_id === reminderId
  );
}
