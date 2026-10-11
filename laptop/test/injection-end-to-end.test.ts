import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { EventDatabase } from '../src/db/database.js';
import { IngestionPipeline } from '../src/pipeline.js';
import { ProjectionEngine } from '../src/db/projection.js';
import { SyncEvent } from '@schedule-assistant/core';

describe('End-to-End Injection Fixtures & Append-Only Log Integrity Suite', () => {
  let db: EventDatabase;
  let pipeline: IngestionPipeline;
  const fixturesPath = path.resolve(__dirname, '../../spec/fixtures/phrases.json');
  const allFixtures = JSON.parse(fs.readFileSync(fixturesPath, 'utf-8'));
  const injectionFixtures = allFixtures.filter(
    (f: any) => f.category === 'injection' || f.category === 'injection_mixed'
  );

  beforeEach(() => {
    db = new EventDatabase({ path: ':memory:' });
    pipeline = new IngestionPipeline(db, { deviceId: 'laptop-injection-test' });
  });

  afterEach(() => {
    db.close();
  });

  it('runs all 25+ injection fixtures through the real SQLite pipeline and guarantees zero event loss or mutation', () => {
    // 1. Seed existing legitimate confirmed events into the SQLite database
    const seedEvents: SyncEvent[] = [
      {
        id: 'seed-ev-001',
        timestamp: '2026-10-12T09:00:00+05:30',
        device_id: 'seed-device',
        type: 'event_extracted',
        entity_id: 'seed-sched-1',
        payload: {
          type: 'new',
          title: 'DBMS Lecture',
          start: '2026-10-13T10:00:00+05:30',
          confidence: 0.9,
          source_quote: 'DBMS Lecture at 10am'
        }
      },
      {
        id: 'seed-ev-002',
        timestamp: '2026-10-12T09:05:00+05:30',
        device_id: 'seed-device',
        type: 'event_confirmed',
        entity_id: 'seed-sched-1',
        payload: {}
      },
      {
        id: 'seed-ev-003',
        timestamp: '2026-10-12T09:10:00+05:30',
        device_id: 'seed-device',
        type: 'event_extracted',
        entity_id: 'seed-sched-2',
        payload: {
          type: 'new',
          title: 'Team Sync',
          start: '2026-10-14T15:00:00+05:30',
          confidence: 0.9,
          source_quote: 'Team Sync at 3pm'
        }
      }
    ];

    for (const ev of seedEvents) {
      db.insertEvent(ev);
    }

    const initialEvents = db.getAllEvents();
    expect(initialEvents.length).toBe(3);

    // 2. Feed every injection fixture through the real IngestionPipeline
    let processedCount = 0;
    for (const fixture of injectionFixtures) {
      const res = pipeline.processMessage(fixture.input, {
        referenceNow: fixture.reference_now || '2026-10-12T10:00:00+05:30'
      });

      // No crash; status is either PENDING, REJECTED, PARSE_FAILED, or FLOOD_TRUNCATED
      expect(['PENDING', 'REJECTED', 'PARSE_FAILED', 'FLOOD_TRUNCATED']).toContain(res.status);

      if (res.status === 'PENDING') {
        expect(res.extraction).toBeDefined();
        // Title length cap and sanitization
        expect(res.extraction!.title.length).toBeLessThanOrEqual(100);
        expect(res.extraction!.title.toLowerCase()).not.toContain('drop table');
        expect(res.extraction!.title.toLowerCase()).not.toContain('delete all');
        expect(res.extraction!.title.toLowerCase()).not.toContain('rm -rf');
      }

      processedCount++;
    }

    expect(processedCount).toBeGreaterThanOrEqual(25);

    // 3. Verify event log integrity: Seed events are strictly preserved in their original form
    const currentEvents = db.getAllEvents();
    expect(currentEvents.length).toBeGreaterThan(3);

    const preservedSeed1 = currentEvents.find((e) => e.id === 'seed-ev-001');
    const preservedSeed2 = currentEvents.find((e) => e.id === 'seed-ev-002');
    const preservedSeed3 = currentEvents.find((e) => e.id === 'seed-ev-003');

    expect(preservedSeed1).toBeDefined();
    expect(preservedSeed2).toBeDefined();
    expect(preservedSeed3).toBeDefined();
    expect(preservedSeed1?.payload.title).toBe('DBMS Lecture');
    expect(preservedSeed3?.payload.title).toBe('Team Sync');

    // 4. Invariant: Direct SQLite UPDATE or DELETE attempts are completely blocked by triggers
    expect(() => {
      db.executeRaw(`DELETE FROM events WHERE id = 'seed-ev-001';`);
    }).toThrow(/append-only/i);

    expect(() => {
      db.executeRaw(`UPDATE events SET type = 'corrupted' WHERE id = 'seed-ev-001';`);
    }).toThrow(/append-only/i);

    // 5. Verify projection state derives safely from entire log
    const projected = ProjectionEngine.projectState(currentEvents);
    expect(projected.schedules.get('seed-sched-1')?.status).toBe('CONFIRMED');
    expect(projected.schedules.get('seed-sched-1')?.title).toBe('DBMS Lecture');
  });
});
