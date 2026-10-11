import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { EventDatabase } from '../src/db/database.js';
import { LaptopScheduler, AlertDispatcher } from '../src/scheduler.js';
import { IngestionPipeline } from '../src/pipeline.js';
import { ProjectionEngine } from '../src/db/projection.js';
import { SyncEvent } from '@schedule-assistant/core';

describe('Laptop Scheduler & Hard Invariant Tests', () => {
  let db: EventDatabase;
  let scheduler: LaptopScheduler;
  let pipeline: IngestionPipeline;
  let dispatchedAlerts: any[] = [];

  beforeEach(() => {
    db = new EventDatabase({ path: ':memory:' });
    dispatchedAlerts = [];
    const dispatcher: AlertDispatcher = (alert) => {
      dispatchedAlerts.push(alert);
    };
    scheduler = new LaptopScheduler(db, 'laptop-test-device', dispatcher);
    pipeline = new IngestionPipeline(db, { deviceId: 'laptop-test-device' });
  });

  afterEach(() => {
    scheduler.stop();
    db.close();
  });

  it('HARD INVARIANT: time_unspecified schedule NEVER generates reminders or alarms at the scheduler layer', () => {
    // Ingest date-only schedule ("kal DBMS quiz hai" -> time_unspecified: true)
    const res = pipeline.processMessage('kal DBMS quiz hai', {
      referenceNow: '2026-10-12T10:00:00+05:30'
    });
    expect(res.status).toBe('PENDING');
    expect(res.extraction?.time_unspecified).toBe(true);
    expect(res.extraction?.start).toBeUndefined();

    // Even if confirmed without specifying a time:
    pipeline.confirmSchedule(res.entity_id!);

    const { schedules: s1 } = ProjectionEngine.projectState(db.getAllEvents());
    const sched = s1.get(res.entity_id!);
    expect(sched?.status).toBe('CONFIRMED');
    expect(sched?.time_unspecified).toBe(true);

    // Run scheduler check
    const tickResult = scheduler.checkAndTick(new Date('2026-10-13T10:00:00+05:30').getTime());
    expect(tickResult.newRemindersCreated).toBe(0);
    expect(tickResult.remindersFired).toBe(0);

    const { reminders } = ProjectionEngine.projectState(db.getAllEvents());
    expect(reminders.size).toBe(0);
    expect(dispatchedAlerts.length).toBe(0);
  });

  it('HARD INVARIANT: ambiguous or unconfirmed schedules NEVER produce reminders or alarms until confirmed', () => {
    // Ingest ambiguous time schedule
    const res = pipeline.processMessage('meeting at 6 oclock tomorrow', {
      referenceNow: '2026-10-12T10:00:00+05:30'
    });
    expect(res.status).toBe('PENDING');

    // While PENDING, tick should create nothing
    const tickPending = scheduler.checkAndTick();
    expect(tickPending.newRemindersCreated).toBe(0);

    const { reminders: rem1 } = ProjectionEngine.projectState(db.getAllEvents());
    expect(rem1.size).toBe(0);

    // Confirm schedule with explicit time override
    pipeline.confirmSchedule(res.entity_id!, '2026-10-13T18:00:00+05:30');

    // Now tick creates reminder + alarm
    const tickConfirmed = scheduler.checkAndTick();
    expect(tickConfirmed.newRemindersCreated).toBe(2); // 1 reminder (T-15m) + 1 alarm (T-0)

    const { reminders: rem2 } = ProjectionEngine.projectState(db.getAllEvents());
    expect(rem2.size).toBe(2);
  });

  it('fires reminder and alarm when due, and records immutable reminder_fired event', () => {
    // Schedule confirmed meeting at 2026-10-13T14:00:00+05:30
    const res = pipeline.processMessage('Sprint sync tomorrow at 2 PM', {
      referenceNow: '2026-10-12T10:00:00+05:30'
    });
    pipeline.confirmSchedule(res.entity_id!);

    // Tick to create reminders
    scheduler.checkAndTick(new Date('2026-10-12T10:00:00+05:30').getTime());

    // 1. Tick before reminder time (e.g. 13:00) -> 0 fired
    const beforeTick = scheduler.checkAndTick(new Date('2026-10-13T13:00:00+05:30').getTime());
    expect(beforeTick.remindersFired).toBe(0);
    expect(dispatchedAlerts.length).toBe(0);

    // 2. Tick at alarm time (13:30 = T - 30m) -> alarm fires
    const alarmTick = scheduler.checkAndTick(new Date('2026-10-13T13:30:00+05:30').getTime());
    expect(alarmTick.remindersFired).toBe(1);
    expect(dispatchedAlerts.length).toBe(1);
    expect(dispatchedAlerts[0].title).toContain('ALARM: sync');

    // Verify reminder_fired event was inserted into SQLite
    const events = db.getAllEvents();
    const firedEvents = events.filter((e) => e.type === 'reminder_fired');
    expect(firedEvents.length).toBe(1);

    // 3. Tick at reminder time (13:45 = T - 15m) -> reminder fires
    const reminderTick = scheduler.checkAndTick(new Date('2026-10-13T13:45:00+05:30').getTime());
    expect(reminderTick.remindersFired).toBe(1);
    expect(dispatchedAlerts.length).toBe(2);
    expect(dispatchedAlerts[1].title).toContain('Reminder: sync');
  });

  it('guarantees suppression: already fired reminder never fires a second time', () => {
    const res = pipeline.processMessage('Project demo tomorrow at 11 AM', {
      referenceNow: '2026-10-12T10:00:00+05:30'
    });
    pipeline.confirmSchedule(res.entity_id!);
    scheduler.checkAndTick(new Date('2026-10-12T10:00:00+05:30').getTime());

    // Fire at 11:00 AM
    scheduler.checkAndTick(new Date('2026-10-13T11:00:00+05:30').getTime());
    expect(dispatchedAlerts.length).toBe(2); // reminder (10:45) + alarm (11:00)

    // Tick again 5 minutes later at 11:05 AM -> must NOT fire again
    const repeatedTick = scheduler.checkAndTick(new Date('2026-10-13T11:05:00+05:30').getTime());
    expect(repeatedTick.remindersFired).toBe(0);
    expect(dispatchedAlerts.length).toBe(2);
  });
});
