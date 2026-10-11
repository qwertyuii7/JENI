import { describe, it, expect, afterEach } from 'vitest';
import { EventDatabase } from '../src/db/database.js';
import { IngestionPipeline } from '../src/pipeline.js';
import { LaptopScheduler } from '../src/scheduler.js';

describe('Timezone Independence & Asia/Kolkata Correctness Suite', () => {
  const originalTZ = process.env.TZ;

  afterEach(() => {
    if (originalTZ) {
      process.env.TZ = originalTZ;
    } else {
      delete process.env.TZ;
    }
  });

  const runTimezoneAssertion = (tzName: string) => {
    process.env.TZ = tzName;

    const db = new EventDatabase({ path: ':memory:' });
    const pipeline = new IngestionPipeline(db, { deviceId: `laptop-${tzName}` });

    // Reference now: Monday Oct 12, 2026 10:00 AM IST (Asia/Kolkata +05:30)
    const refNowIST = '2026-10-12T10:00:00+05:30';

    // 1. "kal 4 baje DBMS class hai" must give Tuesday Oct 13, 2026 16:00:00+05:30
    const res = pipeline.processMessage('kal 4 baje DBMS class hai', {
      referenceNow: refNowIST
    });

    expect(res.status).toBe('PENDING');
    expect(res.extraction?.start).toBe('2026-10-13T16:00:00+05:30');
    expect(res.extraction?.title).toBe('DBMS class');

    // Confirm schedule
    pipeline.confirmSchedule(res.entity_id!);

    let alertFired = false;
    const scheduler = new LaptopScheduler(db, `laptop-${tzName}`, (alert) => {
      alertFired = true;
    });

    // Generate alerts
    scheduler.checkAndTick(new Date('2026-10-12T10:00:00+05:30').getTime());

    // In Asia/Kolkata, 16:00:00+05:30 corresponds to 10:30:00.000Z UTC
    // Alarm is set at T - 30m = 15:30:00+05:30 = 10:00:00.000Z UTC
    const dueAlarmTimeEpoch = new Date('2026-10-13T15:30:00+05:30').getTime();

    // Tick at 15:29 IST -> alert must NOT fire yet
    scheduler.checkAndTick(dueAlarmTimeEpoch - 60000);
    expect(alertFired).toBe(false);

    // Tick at 15:30 IST -> alarm MUST fire
    const tickRes = scheduler.checkAndTick(dueAlarmTimeEpoch);
    expect(tickRes.remindersFired).toBe(1);
    expect(alertFired).toBe(true);

    db.close();
  };

  it('preserves 100% Asia/Kolkata correctness when process TZ is set to UTC', () => {
    runTimezoneAssertion('UTC');
  });

  it('preserves 100% Asia/Kolkata correctness when process TZ is set to America/New_York', () => {
    runTimezoneAssertion('America/New_York');
  });
});
