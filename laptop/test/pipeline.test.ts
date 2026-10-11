import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { EventDatabase } from '../src/db/database.js';
import { IngestionPipeline } from '../src/pipeline.js';
import { ProjectionEngine } from '../src/db/projection.js';

describe('Laptop Ingestion Pipeline Tests', () => {
  let db: EventDatabase;
  let pipeline: IngestionPipeline;
  let dispatchedNotifications: any[] = [];

  beforeEach(() => {
    db = new EventDatabase({ path: ':memory:' });
    dispatchedNotifications = [];
    pipeline = new IngestionPipeline(db, {
      deviceId: 'laptop-test',
      onNotification: (alert) => {
        dispatchedNotifications.push(alert);
      }
    });
  });

  afterEach(() => {
    db.close();
  });

  it('processes standard message into PENDING event in SQLite store', () => {
    const result = pipeline.processMessage('kal 4 baje DBMS class hai', {
      referenceNow: '2026-10-12T10:00:00+05:30'
    });

    expect(result.status).toBe('PENDING');
    expect(result.extraction?.title).toBe('DBMS class');
    expect(result.extraction?.start).toBe('2026-10-13T16:00:00+05:30');

    const events = db.getAllEvents();
    expect(events.length).toBe(1);
    expect(events[0].type).toBe('event_extracted');

    const { schedules } = ProjectionEngine.projectState(events);
    const schedule = schedules.get(result.entity_id!);
    expect(schedule).toBeDefined();
    expect(schedule?.status).toBe('PENDING');
  });

  it('enforces flood cap: emits flood_truncated event and notifies with original text', () => {
    const floodMessage = 'class at 9am, lab at 11am, sync at 1pm, viva at 3pm, demo at 5pm';
    const result = pipeline.processMessage(floodMessage);

    expect(result.status).toBe('FLOOD_TRUNCATED');

    const events = db.getAllEvents();
    expect(events.length).toBe(1);
    expect(events[0].payload.reason).toBe('flood_truncated');
    expect(events[0].payload.raw_text).toBe(floodMessage);

    expect(dispatchedNotifications.length).toBe(1);
    expect(dispatchedNotifications[0].title).toContain('Message Truncated');
    expect(dispatchedNotifications[0].body).toContain(floodMessage.substring(0, 50));
  });

  it('provides visible fallback notification on parse failure with original text', () => {
    const unparseableMessage = 'meeting shifted to somewhere sometime weird';
    const result = pipeline.processMessage(unparseableMessage);

    expect(result.status).toBe('PARSE_FAILED');

    const events = db.getAllEvents();
    expect(events.length).toBe(1);
    expect(events[0].type).toBe('parse_failed');
    expect(events[0].payload.raw_text).toBe(unparseableMessage);

    expect(dispatchedNotifications.length).toBe(1);
    expect(dispatchedNotifications[0].title).toContain('Could Not Parse Message');
    expect(dispatchedNotifications[0].body).toContain(unparseableMessage);
  });

  it('inherits original start clock time for time_unspecified reschedules', () => {
    // 1. Initial confirmed event at 14:00 (2 PM)
    const initialRes = pipeline.processMessage('Sprint planning today at 2 PM', {
      referenceNow: '2026-10-12T10:00:00+05:30'
    });
    pipeline.confirmSchedule(initialRes.entity_id!);

    // Verify initial event exists at 14:00
    const { schedules: s1 } = ProjectionEngine.projectState(db.getAllEvents());
    expect(s1.get(initialRes.entity_id!)?.start).toBe('2026-10-12T14:00:00+05:30');

    // 2. Incoming date-only reschedule: "Sprint planning postponed to Friday"
    const reschedRes = pipeline.processMessage('Sprint planning postponed to Friday', {
      referenceNow: '2026-10-12T10:00:00+05:30'
    });

    expect(reschedRes.status).toBe('PENDING');
    expect(reschedRes.extraction?.type).toBe('reschedule');
    // Time inheritance: inherited 14:00:00 into Friday date 2026-10-16
    expect(reschedRes.extraction?.start).toBe('2026-10-16T14:00:00+05:30');
    expect(reschedRes.extraction?.time_unspecified).toBe(false);
  });

  it('surfaces zero-match and multi-match as PENDING with candidates', () => {
    // 1. Create two separate meetings
    const m1 = pipeline.processMessage('team sync tomorrow at 10 AM');
    const m2 = pipeline.processMessage('team sync on Friday at 4 PM');
    pipeline.confirmSchedule(m1.entity_id!);
    pipeline.confirmSchedule(m2.entity_id!);

    // 2. Reschedule with ambiguous target (multi-match)
    const multiMatchRes = pipeline.processMessage('team sync postponed to Monday');
    expect(multiMatchRes.status).toBe('PENDING');
    expect(multiMatchRes.extraction?.ambiguous).toBe(true);
    expect(multiMatchRes.candidates?.length).toBe(2);

    // 3. Reschedule with non-existent target (zero-match)
    const zeroMatchRes = pipeline.processMessage('Quantum physics exam postponed to Monday');
    expect(zeroMatchRes.status).toBe('PENDING');
    expect(zeroMatchRes.extraction?.ambiguous).toBe(true);
    expect(zeroMatchRes.candidates?.length).toBe(0);
  });
});
