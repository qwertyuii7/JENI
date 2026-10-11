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
    // 1. Create append-only events table with seq and hlc
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS events (
        id TEXT PRIMARY KEY,
        timestamp TEXT NOT NULL,
        device_id TEXT NOT NULL,
        seq INTEGER NOT NULL,
        hlc TEXT NOT NULL,
        type TEXT NOT NULL,
        entity_id TEXT NOT NULL,
        payload TEXT NOT NULL,
        created_at DATETIME DEFAULT CURRENT_TIMESTAMP
      );

      CREATE INDEX IF NOT EXISTS idx_events_hlc ON events(hlc, device_id);
      CREATE INDEX IF NOT EXISTS idx_events_device_seq ON events(device_id, seq);
      CREATE INDEX IF NOT EXISTS idx_events_entity ON events(entity_id);
      CREATE INDEX IF NOT EXISTS idx_events_type ON events(type);

      -- Prunable raw messages table with configurable retention
      CREATE TABLE IF NOT EXISTS raw_messages (
        id TEXT PRIMARY KEY,
        raw_text TEXT NOT NULL,
        source TEXT DEFAULT 'whatsapp',
        created_at DATETIME NOT NULL
      );

      CREATE INDEX IF NOT EXISTS idx_raw_messages_created ON raw_messages(created_at);
    `);

    // 2. Install triggers enforcing strict append-only immutability on events table
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

  public getNextSeq(deviceId: string): number {
    const stmt = this.db.prepare(`
      SELECT MAX(seq) AS max_seq FROM events WHERE device_id = ?
    `);
    const row = stmt.get(deviceId) as { max_seq: number | null } | undefined;
    return (row?.max_seq || 0) + 1;
  }

  public storeRawMessage(id: string, rawText: string, createdAtIso: string, source: string = 'whatsapp'): void {
    const stmt = this.db.prepare(`
      INSERT OR REPLACE INTO raw_messages (id, raw_text, created_at, source)
      VALUES (?, ?, ?, ?)
    `);
    stmt.run(id, rawText, createdAtIso, source);
  }

  public getRawMessage(id: string): string | null {
    const stmt = this.db.prepare(`
      SELECT raw_text FROM raw_messages WHERE id = ?
    `);
    const row = stmt.get(id) as { raw_text: string } | undefined;
    return row ? row.raw_text : null;
  }

  public pruneRawMessages(retentionDays: number = 30, referenceDateMs: number = Date.now()): number {
    const cutoffDate = new Date(referenceDateMs - retentionDays * 24 * 60 * 60 * 1000).toISOString();
    const countStmt = this.db.prepare(`
      SELECT COUNT(*) AS cnt FROM raw_messages WHERE created_at < ?
    `);
    const row = countStmt.get(cutoffDate) as { cnt: number };
    const toDelete = row.cnt;

    const delStmt = this.db.prepare(`
      DELETE FROM raw_messages WHERE created_at < ?
    `);
    delStmt.run(cutoffDate);
    return toDelete;
  }

  public insertEvent(event: SyncEvent): boolean {
    try {
      const stmt = this.db.prepare(`
        INSERT INTO events (id, timestamp, device_id, seq, hlc, type, entity_id, payload)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      `);
      const hlc = event.hlc || `${event.timestamp || new Date().toISOString()}_0001`;
      const seq = event.seq || 1;
      stmt.run(
        event.id,
        event.timestamp,
        event.device_id,
        seq,
        hlc,
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
      SELECT id, timestamp, device_id, seq, hlc, type, entity_id, payload
      FROM events
      ORDER BY hlc ASC, device_id ASC
    `);
    const rows = stmt.all() as any[];
    return rows.map((r) => ({
      id: r.id,
      timestamp: r.timestamp,
      device_id: r.device_id,
      seq: r.seq,
      hlc: r.hlc,
      type: r.type,
      entity_id: r.entity_id,
      payload: JSON.parse(r.payload)
    }));
  }

  public getEventsByEntityId(entityId: string): SyncEvent[] {
    const stmt = this.db.prepare(`
      SELECT id, timestamp, device_id, seq, hlc, type, entity_id, payload
      FROM events
      WHERE entity_id = ?
      ORDER BY hlc ASC, device_id ASC
    `);
    const rows = stmt.all(entityId) as any[];
    return rows.map((r) => ({
      id: r.id,
      timestamp: r.timestamp,
      device_id: r.device_id,
      seq: r.seq,
      hlc: r.hlc,
      type: r.type,
      entity_id: r.entity_id,
      payload: JSON.parse(r.payload)
    }));
  }

  public close(): void {
    this.db.close();
  }

  public getRawDb(): DatabaseSync {
    return this.db;
  }

  public executeRaw(sql: string): void {
    this.db.exec(sql);
  }
}
