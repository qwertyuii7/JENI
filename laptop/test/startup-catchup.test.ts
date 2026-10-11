import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { EventDatabase } from '../src/db/database.js';
import { IngestionPipeline } from '../src/pipeline.js';
import { LaptopScheduler, AlertDispatcher } from '../src/scheduler.js';
import { ProjectionEngine } from '../src/db/projection.js';

describe('Startup Catch-up & Downtime Recovery Tests', () => {
  let db: EventDatabase;
  let pipeline: IngestionPipeline;
  let dispatchedAlerts: any[] = [];

  beforeEach(() => {
    db = new EventDatabase({ path: ':memory:' });
    dispatchedAlerts = [];
    pipeline = new IngestionPipeline(db, { deviceId: 'laptop-test' });
  });

  afterEach(() => {
    db.close();
  });

  it('fires missed reminders upon startup with "missed at <time>" label and prevents duplicate firings', () => {
    // 1. Initial schedule created and confirmed when laptop was active at 09:00
    // Meeting at 10:00 AM on 2026-10-13 (Alarm at 09:30, Reminder at 09:45)
    const ingestRes = pipeline.processMessage('Sprint retro tomorrow at 10 AM', {
      referenceNow: '2026-10-12T09:00:00+05:30'
    });
    pipeline.confirmSchedule(ingestRes.entity_id!);

    // Pre-create reminders before downtime
    const schedulerBeforeSleep = new LaptopScheduler(db, 'laptop-test', (alert) => {
      dispatchedAlerts.push(alert);
    });
    schedulerBeforeSleep.checkAndTick(new Date('2026-10-12T09:00:00+05:30').getTime());
    schedulerBeforeSleep.stop();

    const { reminders: preReminders } = ProjectionEngine.projectState(db.getAllEvents());
    expect(preReminders.size).toBe(2);
    expect(dispatchedAlerts.length).toBe(0);

    // 2. SIMULATE DOWNTIME:
    // Laptop is powered off throughout Oct 13 09:00 - 14:00 (completely missed 09:30 and 09:45)

    // 3. STARTUP AT 14:00 PM:
    // User boots up laptop at 14:00 PM (4+ hours after scheduled alerts)
    const schedulerAfterBoot = new LaptopScheduler(db, 'laptop-test', (alert) => {
      dispatchedAlerts.push(alert);
    });

    const bootTickTime = new Date('2026-10-13T14:00:00+05:30').getTime();
    const catchupResult = schedulerAfterBoot.checkAndTick(bootTickTime);

    // Both overdue alerts fire during startup catch-up
    expect(catchupResult.remindersFired).toBe(2);
    expect(catchupResult.missedFired).toBe(2);
    expect(dispatchedAlerts.length).toBe(2);

    // Verify "missed at" label is explicitly included in alert titles
    const alarmAlert = dispatchedAlerts.find((a) => a.type === 'alarm');
    const reminderAlert = dispatchedAlerts.find((a) => a.type === 'notification');

    expect(alarmAlert).toBeDefined();
    expect(alarmAlert.title.toLowerCase()).toContain('missed at');
    expect(alarmAlert.isMissed).toBe(true);

    expect(reminderAlert).toBeDefined();
    expect(reminderAlert.title.toLowerCase()).toContain('missed at');
    expect(reminderAlert.isMissed).toBe(true);

    // 4. SUBSEQUENT TICKS: NO DUPLICATES
    const nextTickResult = schedulerAfterBoot.checkAndTick(new Date('2026-10-13T14:05:00+05:30').getTime());
    expect(nextTickResult.remindersFired).toBe(0);
    expect(nextTickResult.missedFired).toBe(0);
    expect(dispatchedAlerts.length).toBe(2); // Still exactly 2

    // 5. RESTART SCHEDULER SIMULATION: NO DUPLICATES
    schedulerAfterBoot.stop();
    const restartedScheduler = new LaptopScheduler(db, 'laptop-test', (alert) => {
      dispatchedAlerts.push(alert);
    });
    const restartTickResult = restartedScheduler.checkAndTick(new Date('2026-10-13T14:10:00+05:30').getTime());
    expect(restartTickResult.remindersFired).toBe(0);
    expect(dispatchedAlerts.length).toBe(2);
    restartedScheduler.stop();
  });
});
