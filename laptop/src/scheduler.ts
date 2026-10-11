import { v4 as uuidv4 } from 'uuid';
import {
  generateAlertsForSchedule,
  isReminderSuppressed,
  SyncEvent,
  HybridLogicalClock
} from '@schedule-assistant/core';
import { EventDatabase } from './db/database.js';
import { ProjectionEngine, ProjectedSchedule, ProjectedReminder } from './db/projection.js';

export interface AlertDispatcher {
  (alert: { title: string; message: string; type: 'notification' | 'alarm'; isMissed?: boolean }): void;
}

export interface NotificationHealth {
  status: 'HEALTHY' | 'FAILED';
  lastError?: string;
  lastCheckedAt?: string;
}

export class LaptopScheduler {
  private db: EventDatabase;
  private deviceId: string;
  private dispatcher: AlertDispatcher;
  private hlc: HybridLogicalClock;
  private intervalTimer: NodeJS.Timeout | null = null;
  private isRunning: boolean = false;
  public notificationHealth: NotificationHealth = { status: 'HEALTHY' };

  constructor(
    db: EventDatabase,
    deviceId: string = 'laptop-local',
    dispatcher?: AlertDispatcher
  ) {
    this.db = db;
    this.deviceId = deviceId;
    this.hlc = new HybridLogicalClock(this.deviceId);
    this.dispatcher = dispatcher || ((alert) => {
      console.log(`[DESKTOP ALERT] [${alert.type.toUpperCase()}] ${alert.title}: ${alert.message}`);
    });
  }

  public setDispatcher(dispatcher: AlertDispatcher): void {
    this.dispatcher = dispatcher;
  }

  public testNotification(): { success: boolean; error?: string } {
    try {
      this.dispatcher({
        title: 'Health Check: Schedule Assistant',
        message: 'Notification system is fully active and functional.',
        type: 'notification'
      });
      this.notificationHealth = {
        status: 'HEALTHY',
        lastCheckedAt: new Date().toISOString()
      };
      return { success: true };
    } catch (err: any) {
      this.notificationHealth = {
        status: 'FAILED',
        lastError: err.message || 'Notification dispatch failed',
        lastCheckedAt: new Date().toISOString()
      };
      return { success: false, error: this.notificationHealth.lastError };
    }
  }

  public checkAndTick(currentTimestampMs: number = Date.now()): {
    newRemindersCreated: number;
    remindersFired: number;
    missedFired: number;
  } {
    const allEvents = this.db.getAllEvents();
    const { schedules, reminders } = ProjectionEngine.projectState(allEvents);

    let newRemindersCreated = 0;
    let remindersFired = 0;
    let missedFired = 0;

    // 1. Generate Alerts for Confirmed Schedules
    for (const schedule of schedules.values()) {
      // HARD INVARIANT: time_unspecified or ambiguous unconfirmed schedules NEVER produce reminders/alarms
      if (schedule.time_unspecified || !schedule.start) {
        continue;
      }
      if (schedule.status !== 'CONFIRMED') {
        continue;
      }

      // Check if reminder already created for this schedule
      const hasReminder = Array.from(reminders.values()).some(
        (r) => r.schedule_id === schedule.id
      );

      if (!hasReminder) {
        const nextSeq = this.db.getNextSeq(this.deviceId);
        const alerts = generateAlertsForSchedule(schedule, this.deviceId, {
          seqStart: nextSeq,
          hlc: this.hlc.now(currentTimestampMs)
        });
        if (alerts.reminderEvent) {
          this.db.insertEvent(alerts.reminderEvent);
          newRemindersCreated++;
        }
        if (alerts.alarmEvent) {
          this.db.insertEvent(alerts.alarmEvent);
          newRemindersCreated++;
        }
      }
    }

    // 2. Evaluate and Fire Due Reminders & Alarms (with Startup Catch-up & Exactly-Once-ish Firing)
    const updatedEvents = this.db.getAllEvents();
    const { reminders: currentReminders } = ProjectionEngine.projectState(updatedEvents);

    for (const reminder of currentReminders.values()) {
      if (reminder.status !== 'SCHEDULED') continue;

      const triggerMs = new Date(reminder.trigger_time).getTime();
      if (triggerMs <= currentTimestampMs) {
        // Suppression check: verify reminder was not fired on any device
        if (isReminderSuppressed(reminder.id, updatedEvents)) {
          continue;
        }

        // Idempotency key per reminder
        const idempotencyKey = `fire-${reminder.id}-${reminder.trigger_time}`;

        // Startup catch-up check: was the trigger time missed during downtime? (> 60s ago)
        const isMissed = (currentTimestampMs - triggerMs) > 60_000;
        let alertTitle = '';

        if (isMissed) {
          const missedTimeFormatted = new Date(triggerMs).toLocaleTimeString('en-IN', {
            hour: 'numeric',
            minute: '2-digit',
            hour12: true,
            timeZone: 'Asia/Kolkata'
          });
          alertTitle = reminder.type === 'alarm'
            ? `⏰ ALARM (missed at ${missedTimeFormatted}): ${reminder.title}`
            : `🔔 Reminder (missed at ${missedTimeFormatted}): ${reminder.title}`;
          missedFired++;
        } else {
          alertTitle = reminder.type === 'alarm'
            ? `⏰ ALARM: ${reminder.title}`
            : `🔔 Reminder: ${reminder.title}`;
        }

        // Write reminder_fired event atomically with delivery intent BEFORE dispatch
        const firedEvent: SyncEvent = {
          id: uuidv4(),
          timestamp: new Date().toISOString(),
          device_id: this.deviceId,
          seq: this.db.getNextSeq(this.deviceId),
          hlc: this.hlc.now(currentTimestampMs),
          type: 'reminder_fired',
          entity_id: reminder.id,
          payload: {
            schedule_id: reminder.schedule_id,
            title: reminder.title,
            type: reminder.type,
            trigger_time: reminder.trigger_time,
            idempotency_key: idempotencyKey,
            missed: isMissed,
            fired_at: new Date().toISOString()
          }
        };

        const inserted = this.db.insertEvent(firedEvent);
        if (!inserted) {
          // Deduplicated by SQLite store
          continue;
        }

        remindersFired++;

        // Dispatch desktop notification
        try {
          this.dispatcher({
            title: alertTitle,
            message: isMissed ? `Missed scheduled alert for ${reminder.title}` : `Scheduled alert for ${reminder.title}`,
            type: reminder.type,
            isMissed
          });
          this.notificationHealth = {
            status: 'HEALTHY',
            lastCheckedAt: new Date().toISOString()
          };
        } catch (err: any) {
          this.notificationHealth = {
            status: 'FAILED',
            lastError: err.message || 'Notification dispatch failed',
            lastCheckedAt: new Date().toISOString()
          };
          console.error('[SCHEDULER] Dispatch failed:', err);
        }
      }
    }

    return {
      newRemindersCreated,
      remindersFired,
      missedFired
    };
  }

  public start(intervalMs: number = 5000): void {
    if (this.isRunning) return;
    this.isRunning = true;
    this.intervalTimer = setInterval(() => {
      try {
        this.checkAndTick();
      } catch (err) {
        console.error('Scheduler tick error:', err);
      }
    }, intervalMs);
  }

  public stop(): void {
    if (this.intervalTimer) {
      clearInterval(this.intervalTimer);
      this.intervalTimer = null;
    }
    this.isRunning = false;
  }
}
