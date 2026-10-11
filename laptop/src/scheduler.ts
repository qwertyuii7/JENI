import { v4 as uuidv4 } from 'uuid';
import {
  generateAlertsForSchedule,
  isReminderSuppressed,
  SyncEvent
} from '@schedule-assistant/core';
import { EventDatabase } from './db/database.js';
import { ProjectionEngine, ProjectedSchedule, ProjectedReminder } from './db/projection.js';

export interface AlertDispatcher {
  (alert: { title: string; message: string; type: 'notification' | 'alarm' }): void;
}

export class LaptopScheduler {
  private db: EventDatabase;
  private deviceId: string;
  private dispatcher: AlertDispatcher;
  private intervalTimer: NodeJS.Timeout | null = null;
  private isRunning: boolean = false;

  constructor(
    db: EventDatabase,
    deviceId: string = 'laptop-local',
    dispatcher?: AlertDispatcher
  ) {
    this.db = db;
    this.deviceId = deviceId;
    this.dispatcher = dispatcher || ((alert) => {
      console.log(`[DESKTOP ALERT] [${alert.type.toUpperCase()}] ${alert.title}: ${alert.message}`);
    });
  }

  public setDispatcher(dispatcher: AlertDispatcher): void {
    this.dispatcher = dispatcher;
  }

  public checkAndTick(currentTimestampMs: number = Date.now()): {
    newRemindersCreated: number;
    remindersFired: number;
  } {
    const allEvents = this.db.getAllEvents();
    const { schedules, reminders } = ProjectionEngine.projectState(allEvents);

    let newRemindersCreated = 0;
    let remindersFired = 0;

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
        const alerts = generateAlertsForSchedule(schedule, this.deviceId);
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

    // 2. Evaluate and Fire Due Reminders & Alarms
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

        // Fire reminder: insert immutable reminder_fired event
        const firedEvent: SyncEvent = {
          id: uuidv4(),
          timestamp: new Date().toISOString(),
          device_id: this.deviceId,
          type: 'reminder_fired',
          entity_id: reminder.id,
          payload: {
            schedule_id: reminder.schedule_id,
            title: reminder.title,
            type: reminder.type,
            trigger_time: reminder.trigger_time,
            fired_at: new Date().toISOString()
          }
        };

        this.db.insertEvent(firedEvent);
        remindersFired++;

        // Dispatch desktop notification
        this.dispatcher({
          title: reminder.type === 'alarm' ? `⏰ ALARM: ${reminder.title}` : `🔔 Reminder: ${reminder.title}`,
          message: `Scheduled alert for ${reminder.title}`,
          type: reminder.type
        });
      }
    }

    return {
      newRemindersCreated,
      remindersFired
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
