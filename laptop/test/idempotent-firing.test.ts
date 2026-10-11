import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { EventDatabase } from '../src/db/database.js';
import { IngestionPipeline } from '../src/pipeline.js';
import { LaptopScheduler } from '../src/scheduler.js';
import { ProjectionEngine } from '../src/db/projection.js';

describe('Exactly-Once-ish Firing & Crash Simulation Suite', () => {
  let db: EventDatabase;
  let pipeline: IngestionPipeline;

  beforeEach(() => {
    db = new EventDatabase({ path: ':memory:' });
    pipeline = new IngestionPipeline(db, { deviceId: 'test-device' });
  });

  afterEach(() => {
    db.close();
  });

  it('records idempotency key and persists reminder_fired atomically with delivery intent', () => {
    const res = pipeline.processMessage('Project demo tomorrow at 4 PM', {
      referenceNow: '2026-10-12T10:00:00+05:30'
    });
    pipeline.confirmSchedule(res.entity_id!);

    let dispatchedCount = 0;
    const scheduler = new LaptopScheduler(db, 'test-device', () => {
      dispatchedCount++;
    });

    // Create reminders
    scheduler.checkAndTick(new Date('2026-10-12T10:00:00+05:30').getTime());

    // Fire alarm at 15:30 (T - 30m)
    scheduler.checkAndTick(new Date('2026-10-13T15:30:00+05:30').getTime());
    expect(dispatchedCount).toBe(1);

    const events = db.getAllEvents();
    const firedEvent = events.find((e) => e.type === 'reminder_fired');
    expect(firedEvent).toBeDefined();

    // Idempotency key verification
    expect(firedEvent?.payload.idempotency_key).toBeDefined();
    expect(firedEvent?.payload.idempotency_key).toMatch(/^fire-alarm-sched-.*-2026-10-13/);
  });

  it('guarantees crash tolerance: if notification dispatch fails or crashes after event write, duplicate delivery is prevented', () => {
    const res = pipeline.processMessage('All hands meeting tomorrow at 3 PM', {
      referenceNow: '2026-10-12T10:00:00+05:30'
    });
    pipeline.confirmSchedule(res.entity_id!);

    let deliveryAttempts = 0;
    // Simulate crash or failure on initial delivery attempt
    const faultyDispatcher = () => {
      deliveryAttempts++;
      throw new Error('SIMULATED_SYSTEM_CRASH_DURING_DELIVERY');
    };

    const scheduler = new LaptopScheduler(db, 'test-device', faultyDispatcher);
    scheduler.checkAndTick(new Date('2026-10-12T10:00:00+05:30').getTime());

    // Tick at 14:30 (T - 30m): atomic write succeeds, dispatcher throws
    expect(() => {
      scheduler.checkAndTick(new Date('2026-10-13T14:30:00+05:30').getTime());
    }).not.toThrow(); // Scheduler catches and flags health error without crashing host

    expect(deliveryAttempts).toBe(1);
    expect(scheduler.notificationHealth.status).toBe('FAILED');
    expect(scheduler.notificationHealth.lastError).toContain('SIMULATED_SYSTEM_CRASH');

    // Verify reminder_fired was written atomically to SQLite
    const firedEvents = db.getAllEvents().filter((e) => e.type === 'reminder_fired');
    expect(firedEvents.length).toBe(1);

    // Simulate recovery / next tick with restored healthy dispatcher:
    let recoveredDispatches = 0;
    scheduler.setDispatcher(() => {
      recoveredDispatches++;
    });

    // Run tick again
    const recoveryTick = scheduler.checkAndTick(new Date('2026-10-13T14:31:00+05:30').getTime());
    expect(recoveryTick.remindersFired).toBe(0);
    // Invariant: no duplicate delivery attempts because reminder_fired was already committed
    expect(recoveredDispatches).toBe(0);
  });
});
