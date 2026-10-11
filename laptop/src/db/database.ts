import { DatabaseSync } from 'node:sqlite';
import { SyncEvent } from '@schedule-assistant/core';

export interface SqliteDbOptions {
  path?: string;
}

export class EventDatabase {
  private db: DatabaseSync;

  constructor(options: SqliteDbOptions = {}) {
    const dbPath = options.path || ':memory:';
    this.db = new DatabaseSync(dbPath);
    this.initializeSchema();
  }

  private initializeSchema(): void {
    // 1. Create append-only events table
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS events (
        id TEXT PRIMARY KEY,
        timestamp TEXT NOT NULL,
        device_id TEXT NOT NULL,
        type TEXT NOT NULL,
        entity_id TEXT NOT NULL,
        payload TEXT NOT NULL,
        created_at DATETIME DEFAULT CURRENT_TIMESTAMP
      );

      CREATE INDEX IF NOT EXISTS idx_events_entity ON events(entity_id);
      CREATE INDEX IF NOT EXISTS idx_events_type ON events(type);
      CREATE INDEX IF NOT EXISTS idx_events_timestamp ON events(timestamp);
    `);

    // 2. Install triggers enforcing strict append-only immutability
    this.db.exec(`
      CREATE TRIGGER IF NOT EXISTS prevent_event_update
      BEFORE UPDATE ON events
      BEGIN
        SELECT RAISE(FAIL, 'Events table is append-only: updates are strictly prohibited');
      END;

      CREATE TRIGGER IF NOT EXISTS prevent_event_delete
      BEFORE DELETE ON events
      BEGIN
        SELECT RAISE(FAIL, 'Events table is append-only: deletes are strictly prohibited');
      END;
    `);
  }

  public insertEvent(event: SyncEvent): boolean {
    try {
      const stmt = this.db.prepare(`
        INSERT INTO events (id, timestamp, device_id, type, entity_id, payload)
        VALUES (?, ?, ?, ?, ?, ?)
      `);
      stmt.run(
        event.id,
        event.timestamp,
        event.device_id,
        event.type,
        event.entity_id,
        JSON.stringify(event.payload)
      );
      return true;
    } catch (err: any) {
      if (err.message && err.message.includes('UNIQUE constraint failed')) {
        // Event already exists by UUID, deduplicate cleanly
        return false;
      }
      throw err;
    }
  }

  public getAllEvents(): SyncEvent[] {
    const stmt = this.db.prepare(`
      SELECT id, timestamp, device_id, type, entity_id, payload
      FROM events
      ORDER BY created_at ASC
    `);
    const rows = stmt.all() as any[];
    return rows.map((r) => ({
      id: r.id,
      timestamp: r.timestamp,
      device_id: r.device_id,
      type: r.type,
      entity_id: r.entity_id,
      payload: JSON.parse(r.payload)
    }));
  }

  public getEventsByEntityId(entityId: string): SyncEvent[] {
    const stmt = this.db.prepare(`
      SELECT id, timestamp, device_id, type, entity_id, payload
      FROM events
      WHERE entity_id = ?
      ORDER BY created_at ASC
    `);
    const rows = stmt.all(entityId) as any[];
    return rows.map((r) => ({
      id: r.id,
      timestamp: r.timestamp,
      device_id: r.device_id,
      type: r.type,
      entity_id: r.entity_id,
      payload: JSON.parse(r.payload)
    }));
  }

  public close(): void {
    this.db.close();
  }

  // Exposed for testing trigger enforcement
  public executeRaw(sql: string): void {
    this.db.exec(sql);
  }
}
