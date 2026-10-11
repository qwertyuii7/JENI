import { SyncEvent, ScheduleEntity, ExtractionOutput, ReminderEntity } from './types.js';

// Synonyms map strictly within categories (no cross-type mixing)
const INTRA_TYPE_SYNONYMS: Record<string, string[]> = {
  'class': ['lecture', 'period', 'session'],
  'lecture': ['class', 'period', 'session'],
  'meeting': ['sync', 'call', 'discussion', 'standup'],
  'sync': ['meeting', 'call', 'discussion', 'standup'],
  'call': ['meeting', 'sync', 'discussion'],
  'discussion': ['meeting', 'sync', 'call']
};

export class EventStore {
  private events: SyncEvent[] = [];
  private eventIds = new Set<string>();

  public addEvent(event: SyncEvent): boolean {
    if (this.eventIds.has(event.id)) {
      // Deduplicate: ignore already processed UUID
      return false;
    }
    this.eventIds.add(event.id);
    this.events.push(event);
    return true;
  }

  public getEvents(): SyncEvent[] {
    return [...this.events];
  }

  public getDerivedScheduleEntities(): Map<string, ScheduleEntity> {
    const schedules = new Map<string, ScheduleEntity>();

    for (const ev of this.events) {
      if (ev.type === 'event_extracted') {
        const payload = ev.payload as ExtractionOutput;
        schedules.set(ev.entity_id, {
          id: ev.entity_id,
          title: payload.title,
          start: payload.start,
          start_date: payload.start_date,
          time_unspecified: payload.time_unspecified,
          status: 'PENDING',
          source_quote: payload.source_quote,
          confidence: payload.confidence,
          created_at: ev.timestamp,
          updated_at: ev.timestamp,
          target_id: (payload.target as any)?.matched_entity_id
        });
      } else if (ev.type === 'event_confirmed') {
        const entity = schedules.get(ev.entity_id);
        if (entity) {
          entity.status = 'CONFIRMED';
          entity.updated_at = ev.timestamp;
          if (ev.payload.start) entity.start = ev.payload.start;
          if (ev.payload.time_unspecified !== undefined) {
            entity.time_unspecified = ev.payload.time_unspecified;
          }
        }
      } else if (ev.type === 'event_dismissed') {
        const entity = schedules.get(ev.entity_id);
        if (entity) {
          entity.status = 'DISMISSED';
          entity.updated_at = ev.timestamp;
        }
      }
    }

    return schedules;
  }

  public matchTargetEntity(
    targetTitle: string,
    originalStart?: string,
    originalDate?: string
  ): ScheduleEntity | null {
    const schedules = this.getDerivedScheduleEntities();
    const normalizedTarget = targetTitle.toLowerCase().trim();

    // 1. Check exact title and start time match
    for (const entity of schedules.values()) {
      if (entity.status !== 'CONFIRMED' && entity.status !== 'PENDING') continue;
      if (entity.title.toLowerCase().trim() === normalizedTarget) {
        if (originalStart && entity.start === originalStart) return entity;
        if (originalDate && (entity.start_date === originalDate || entity.start?.startsWith(originalDate))) return entity;
      }
    }

    // 2. Check synonym title match
    const targetSynonyms = INTRA_TYPE_SYNONYMS[normalizedTarget] || [];
    for (const entity of schedules.values()) {
      if (entity.status !== 'CONFIRMED' && entity.status !== 'PENDING') continue;
      const entityTitleNorm = entity.title.toLowerCase().trim();
      if (entityTitleNorm === normalizedTarget || targetSynonyms.includes(entityTitleNorm)) {
        return entity;
      }
    }

    // 3. Substring match
    for (const entity of schedules.values()) {
      if (entity.status !== 'CONFIRMED' && entity.status !== 'PENDING') continue;
      if (entity.title.toLowerCase().includes(normalizedTarget) || normalizedTarget.includes(entity.title.toLowerCase())) {
        return entity;
      }
    }

    return null;
  }
}
