import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { EventDatabase } from '../src/db/database.js';
import { IngestionPipeline } from '../src/pipeline.js';

describe('Prunable Raw Messages & Append-Only Event Log Suite', () => {
  let db: EventDatabase;
  let pipeline: IngestionPipeline;

  beforeEach(() => {
    db = new EventDatabase({ path: ':memory:' });
    pipeline = new IngestionPipeline(db, { deviceId: 'test-device' });
  });

  afterEach(() => {
    db.close();
  });

  it('prunes expired raw messages while keeping events table strictly immutable and intact', () => {
    const nowMs = new Date('2026-10-12T10:00:00+05:30').getTime();

    // 1. Ingest an old message (45 days old)
    const oldTimestamp = new Date(nowMs - 45 * 24 * 60 * 60 * 1000).toISOString();
    db.storeRawMessage('msg-old-1', 'sensitive private chat text from 45 days ago', oldTimestamp);

    // 2. Ingest a recent message (5 days old)
    const recentTimestamp = new Date(nowMs - 5 * 24 * 60 * 60 * 1000).toISOString();
    db.storeRawMessage('msg-recent-1', 'recent team update from 5 days ago', recentTimestamp);

    // 3. Process a current message through the pipeline
    pipeline.processMessage('kal 4 baje DBMS class hai', {
      referenceNow: '2026-10-12T10:00:00+05:30'
    });

    // Check pre-prune state
    expect(db.getRawMessage('msg-old-1')).toBe('sensitive private chat text from 45 days ago');
    expect(db.getRawMessage('msg-recent-1')).toBe('recent team update from 5 days ago');

    const initialEventCount = db.getAllEvents().length;
    expect(initialEventCount).toBeGreaterThanOrEqual(1);

    // 4. Run prune operation with 30-day retention
    const deletedCount = db.pruneRawMessages(30, nowMs);
    expect(deletedCount).toBe(1);

    // 5. Assert: Expired raw message was deleted, recent raw message remains
    expect(db.getRawMessage('msg-old-1')).toBeNull();
    expect(db.getRawMessage('msg-recent-1')).toBe('recent team update from 5 days ago');

    // 6. Assert: Events table is untouched (zero deletions)
    const postPruneEvents = db.getAllEvents();
    expect(postPruneEvents.length).toBe(initialEventCount);

    // 7. Assert: Attempting to delete from events table is strictly blocked
    expect(() => {
      db.executeRaw(`DELETE FROM events;`);
    }).toThrow(/append-only/i);
  });
});
