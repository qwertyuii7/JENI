import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { EventDatabase } from '../src/db/database.js';
import { IngestionPipeline } from '../src/pipeline.js';
import { ProjectionEngine } from '../src/db/projection.js';
import { SyncEvent, HybridLogicalClock } from '@schedule-assistant/core';

describe('HLC Ordering, Full Replay & Shuffled/Duplicated Invariant Suite', () => {
  let db: EventDatabase;
  let pipelineLaptop: IngestionPipeline;

  beforeEach(() => {
    db = new EventDatabase({ path: ':memory:' });
    pipelineLaptop = new IngestionPipeline(db, { deviceId: 'device-laptop' });
  });

  afterEach(() => {
    db.close();
  });

  it('verifies event schema invariants: device_id, per-device seq, and HLC string format', () => {
    const res1 = pipelineLaptop.processMessage('kal 4 baje DBMS class hai', {
      referenceNow: '2026-10-12T10:00:00+05:30'
    });
    const res2 = pipelineLaptop.processMessage('Sprint planning on Friday at 11 AM', {
      referenceNow: '2026-10-12T10:00:00+05:30'
    });
    pipelineLaptop.confirmSchedule(res1.entity_id!);

    const events = db.getAllEvents();
    expect(events.length).toBe(3);

    for (const ev of events) {
      expect(ev.device_id).toBe('device-laptop');
      expect(ev.seq).toBeGreaterThanOrEqual(1);
      // HLC format: ISO timestamp string followed by counter
      expect(ev.hlc).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z_\d{4}$/);
    }

    // Monotonically increasing seq per device
    expect(events[0].seq).toBe(1);
    expect(events[1].seq).toBe(2);
    expect(events[2].seq).toBe(3);
  });

  it('guarantees full replay equals incremental state', () => {
    // 1. Incrementally process messages
    const r1 = pipelineLaptop.processMessage('DBMS lecture tomorrow at 2 PM', {
      referenceNow: '2026-10-12T10:00:00+05:30'
    });
    pipelineLaptop.confirmSchedule(r1.entity_id!);

    const r2 = pipelineLaptop.processMessage('viva on Friday at 10 AM', {
      referenceNow: '2026-10-12T10:00:00+05:30'
    });
    pipelineLaptop.confirmSchedule(r2.entity_id!, '2026-10-16T10:30:00+05:30');

    const r3 = pipelineLaptop.processMessage('client call tomorrow at 5 PM', {
      referenceNow: '2026-10-12T10:00:00+05:30'
    });
    pipelineLaptop.dismissSchedule(r3.entity_id!);

    // Incremental state
    const allEvents = db.getAllEvents();
    const incrementalState = ProjectionEngine.projectState(allEvents);

    // 2. Full replay from raw events log
    const replayedState = ProjectionEngine.projectState([...allEvents]);

    expect(replayedState.schedules.size).toBe(incrementalState.schedules.size);
    for (const [id, sched] of incrementalState.schedules.entries()) {
      const repSched = replayedState.schedules.get(id);
      expect(repSched).toBeDefined();
      expect(repSched?.title).toBe(sched.title);
      expect(repSched?.status).toBe(sched.status);
      expect(repSched?.start).toBe(sched.start);
      expect(repSched?.start_date).toBe(sched.start_date);
    }
  });

  it('guarantees shuffled (out-of-order) and duplicated delivery yields identical projected state', () => {
    // 1. Create multi-event workflow across two devices (laptop and phone)
    const hlcPhone = new HybridLogicalClock('device-phone');
    const phoneEvent1: SyncEvent = {
      id: 'phone-ev-1',
      timestamp: '2026-10-12T10:01:00+05:30',
      device_id: 'device-phone',
      seq: 1,
      hlc: hlcPhone.now(new Date('2026-10-12T10:01:00+05:30').getTime()),
      type: 'event_extracted',
      entity_id: 'sched-phone-1',
      payload: {
        type: 'new',
        title: 'Phone Created Meeting',
        start: '2026-10-13T12:00:00+05:30',
        confidence: 0.9,
        source_quote: 'Phone Created Meeting'
      }
    };
    db.insertEvent(phoneEvent1);

    const r1 = pipelineLaptop.processMessage('Laptop Sync tomorrow at 4 PM', {
      referenceNow: '2026-10-12T10:02:00+05:30'
    });
    pipelineLaptop.confirmSchedule(r1.entity_id!);

    const phoneEvent2: SyncEvent = {
      id: 'phone-ev-2',
      timestamp: '2026-10-12T10:03:00+05:30',
      device_id: 'device-phone',
      seq: 2,
      hlc: hlcPhone.now(new Date('2026-10-12T10:03:00+05:30').getTime()),
      type: 'event_confirmed',
      entity_id: 'sched-phone-1',
      payload: {}
    };
    db.insertEvent(phoneEvent2);

    const canonicalEvents = db.getAllEvents();
    const canonicalState = ProjectionEngine.projectState(canonicalEvents);

    // 2. Test 1: Completely reversed events
    const reversedEvents = [...canonicalEvents].reverse();
    const reversedState = ProjectionEngine.projectState(reversedEvents);

    expect(reversedState.schedules.size).toBe(canonicalState.schedules.size);
    for (const [id, s] of canonicalState.schedules) {
      expect(reversedState.schedules.get(id)?.status).toBe(s.status);
      expect(reversedState.schedules.get(id)?.title).toBe(s.title);
    }

    // 3. Test 2: Randomly shuffled events
    const shuffled = [...canonicalEvents].sort(() => Math.random() - 0.5);
    const shuffledState = ProjectionEngine.projectState(shuffled);
    expect(shuffledState.schedules.size).toBe(canonicalState.schedules.size);
    for (const [id, s] of canonicalState.schedules) {
      expect(shuffledState.schedules.get(id)?.status).toBe(s.status);
    }

    // 4. Test 3: Duplicated events (network retransmissions)
    const duplicatedEvents = [...canonicalEvents, ...canonicalEvents, canonicalEvents[0], canonicalEvents[1]];
    const duplicatedState = ProjectionEngine.projectState(duplicatedEvents);
    expect(duplicatedState.schedules.size).toBe(canonicalState.schedules.size);
    for (const [id, s] of canonicalState.schedules) {
      expect(duplicatedState.schedules.get(id)?.status).toBe(s.status);
      expect(duplicatedState.schedules.get(id)?.title).toBe(s.title);
    }
  });
});
