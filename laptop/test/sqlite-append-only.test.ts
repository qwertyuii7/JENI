import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { EventDatabase } from '../src/db/database.js';
import { SyncEvent } from '@schedule-assistant/core';

describe('SQLite Append-Only Immutability Tests', () => {
  let db: EventDatabase;

  beforeEach(() => {
    db = new EventDatabase({ path: ':memory:' });
  });

  afterEach(() => {
    db.close();
  });

  it('allows inserting valid immutable events', () => {
    const event: SyncEvent = {
      id: 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
      timestamp: '2026-10-12T10:00:00+05:30',
      device_id: 'laptop-1',
      type: 'event_extracted',
      entity_id: 'sched-1',
      payload: { title: 'DBMS class' }
    };

    expect(db.insertEvent(event)).toBe(true);
    const stored = db.getAllEvents();
    expect(stored.length).toBe(1);
    expect(stored[0].id).toBe(event.id);
  });

  it('deduplicates duplicate event UUIDs cleanly without error', () => {
    const event: SyncEvent = {
      id: 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
      timestamp: '2026-10-12T10:00:00+05:30',
      device_id: 'laptop-1',
      type: 'event_extracted',
      entity_id: 'sched-1',
      payload: { title: 'DBMS class' }
    };

    expect(db.insertEvent(event)).toBe(true);
    // Duplicate insertion must return false and not insert a duplicate row
    expect(db.insertEvent(event)).toBe(false);
    expect(db.getAllEvents().length).toBe(1);
  });

  it('strictly prohibits UPDATE operations on the events table via database trigger', () => {
    const event: SyncEvent = {
      id: 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
      timestamp: '2026-10-12T10:00:00+05:30',
      device_id: 'laptop-1',
      type: 'event_extracted',
      entity_id: 'sched-1',
      payload: { title: 'DBMS class' }
    };
    db.insertEvent(event);

    expect(() => {
      db.executeRaw(`UPDATE events SET type = 'corrupted' WHERE id = '${event.id}';`);
    }).toThrow(/Events table is append-only: updates are strictly prohibited/);

    // Verify row was NOT updated
    expect(db.getAllEvents()[0].type).toBe('event_extracted');
  });

  it('strictly prohibits DELETE operations on the events table via database trigger', () => {
    const event: SyncEvent = {
      id: 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
      timestamp: '2026-10-12T10:00:00+05:30',
      device_id: 'laptop-1',
      type: 'event_extracted',
      entity_id: 'sched-1',
      payload: { title: 'DBMS class' }
    };
    db.insertEvent(event);

    expect(() => {
      db.executeRaw(`DELETE FROM events WHERE id = '${event.id}';`);
    }).toThrow(/Events table is append-only: deletes are strictly prohibited/);

    // Verify row was NOT deleted
    expect(db.getAllEvents().length).toBe(1);
  });
});
