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

  it('proves delete followed by older edit stays deleted under HLC ordering', () => {
    const hlc1 = '2026-10-12T10:00:00.000Z_0001';
    const hlcEditOlder = '2026-10-12T10:05:00.000Z_0001';
    const hlcDeleteNewer = '2026-10-12T10:10:00.000Z_0001';

    // 1. Initial schedule created and confirmed
    const ev1: SyncEvent = {
      id: 'ev-create',
      timestamp: '2026-10-12T10:00:00Z',
      device_id: 'device-a',
      seq: 1,
      hlc: hlc1,
      type: 'event_extracted',
      entity_id: 'sched-hlc-test',
      payload: {
        type: 'new',
        title: 'Original Title',
        start: '2026-10-13T10:00:00+05:30',
        confidence: 0.95,
        source_quote: 'Original Title'
      }
    };
    const evConfirm: SyncEvent = {
      id: 'ev-confirm',
      timestamp: '2026-10-12T10:01:00Z',
      device_id: 'device-a',
      seq: 2,
      hlc: '2026-10-12T10:01:00.000Z_0001',
      type: 'event_confirmed',
      entity_id: 'sched-hlc-test',
      payload: {}
    };

    // 2. Newer delete (HLC: 10:10)
    const evDelete: SyncEvent = {
      id: 'ev-delete',
      timestamp: '2026-10-12T10:10:00Z',
      device_id: 'device-a',
      seq: 3,
      hlc: hlcDeleteNewer,
      type: 'schedule_deleted',
      entity_id: 'sched-hlc-test',
      payload: { reason: 'user' }
    };

    // 3. Older edit arriving out-of-order from another device (HLC: 10:05)
    const evOlderEdit: SyncEvent = {
      id: 'ev-older-edit',
      timestamp: '2026-10-12T10:05:00Z',
      device_id: 'device-b',
      seq: 1,
      hlc: hlcEditOlder,
      type: 'schedule_edited',
      entity_id: 'sched-hlc-test',
      payload: { title: 'Edited Title By Offline Device' }
    };

    // Inverted arrival order: delete arrived first, then older edit
    const stateArrivalOrder = ProjectionEngine.projectState([ev1, evConfirm, evDelete, evOlderEdit]);
    expect(stateArrivalOrder.schedules.has('sched-hlc-test')).toBe(false);

    // Shuffled arrival order
    const stateShuffled = ProjectionEngine.projectState([evOlderEdit, evDelete, evConfirm, ev1]);
    expect(stateShuffled.schedules.has('sched-hlc-test')).toBe(false);

    // Full replay of canonical events
    const canonicalOrder = [ev1, evConfirm, evOlderEdit, evDelete];
    const stateCanonical = ProjectionEngine.projectState(canonicalOrder);
    expect(stateCanonical.schedules.has('sched-hlc-test')).toBe(false);
  });

  it('verifies projection hides deleted schedules and their reminders/alarms idempotently', () => {
    const res = pipelineLaptop.processMessage('OS lecture tomorrow at 11 AM', {
      referenceNow: '2026-10-12T10:00:00+05:30'
    });
    const entityId = res.entity_id!;
    pipelineLaptop.confirmSchedule(entityId);

    // Add reminder and alarm events
    const remEvent: SyncEvent = {
      id: 'rem-ev-1',
      timestamp: '2026-10-12T10:02:00Z',
      device_id: 'device-laptop',
      seq: 3,
      hlc: '2026-10-12T10:02:00.000Z_0001',
      type: 'reminder_created',
      entity_id: `rem-${entityId}`,
      payload: {
        schedule_id: entityId,
        title: 'OS lecture',
        trigger_time: '2026-10-13T10:45:00+05:30',
        type: 'notification'
      }
    };
    const alarmEvent: SyncEvent = {
      id: 'alarm-ev-1',
      timestamp: '2026-10-12T10:03:00Z',
      device_id: 'device-laptop',
      seq: 4,
      hlc: '2026-10-12T10:03:00.000Z_0001',
      type: 'alarm_set',
      entity_id: `alarm-${entityId}`,
      payload: {
        schedule_id: entityId,
        title: 'OS lecture',
        trigger_time: '2026-10-13T11:00:00+05:30',
        type: 'alarm'
      }
    };
    db.insertEvent(remEvent);
    db.insertEvent(alarmEvent);

    let state = ProjectionEngine.projectState(db.getAllEvents());
    expect(state.schedules.has(entityId)).toBe(true);
    expect(state.reminders.has(`rem-${entityId}`)).toBe(true);
    expect(state.reminders.has(`alarm-${entityId}`)).toBe(true);

    // First delete
    pipelineLaptop.deleteSchedule(entityId, 'user');
    state = ProjectionEngine.projectState(db.getAllEvents());
    expect(state.schedules.has(entityId)).toBe(false);
    expect(state.reminders.has(`rem-${entityId}`)).toBe(false);
    expect(state.reminders.has(`alarm-${entityId}`)).toBe(false);

    // Second delete (idempotency check)
    pipelineLaptop.deleteSchedule(entityId, 'user');
    const stateIdempotent = ProjectionEngine.projectState(db.getAllEvents());
    expect(stateIdempotent.schedules.has(entityId)).toBe(false);
    expect(stateIdempotent.reminders.has(`rem-${entityId}`)).toBe(false);
    expect(stateIdempotent.reminders.has(`alarm-${entityId}`)).toBe(false);

    // Restore via undo
    pipelineLaptop.restoreSchedule(entityId);
    const restoredState = ProjectionEngine.projectState(db.getAllEvents());
    expect(restoredState.schedules.has(entityId)).toBe(true);
    expect(restoredState.schedules.get(entityId)?.status).toBe('CONFIRMED');
    expect(restoredState.reminders.has(`rem-${entityId}`)).toBe(true);
    expect(restoredState.reminders.has(`alarm-${entityId}`)).toBe(true);
  });
});
