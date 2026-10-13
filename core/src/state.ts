import { SyncEvent, ScheduleEntity, ExtractionOutput, ReminderEntity } from './types.js';
import { synonymsSpec, titleFillers } from './spec_loader.js';

// Flatten intra-type synonyms from spec
const INTRA_TYPE_SYNONYMS: Record<string, string[]> = {};
for (const group of Object.values(synonymsSpec)) {
  for (const [key, list] of Object.entries(group)) {
    INTRA_TYPE_SYNONYMS[key.toLowerCase()] = list.map(s => s.toLowerCase());
  }
}

export function normalizeTitleForMatching(title: string, fillers: string[] = titleFillers): string {
  let normalized = title.toLowerCase();

  const asciiFillers = fillers.filter(f => /^[\x00-\x7F]+$/.test(f));
  const unicodeFillers = fillers.filter(f => !/^[\x00-\x7F]+$/.test(f));

  if (asciiFillers.length > 0) {
    const asciiRegex = new RegExp(`\\b(?:${asciiFillers.join('|')})\\b`, 'gi');
    normalized = normalized.replace(asciiRegex, ' ');
  }

  if (unicodeFillers.length > 0) {
    const unicodeRegex = new RegExp(`(?:(?<=^|[\\s\\p{P}])(?:${unicodeFillers.join('|')})(?=$|[\\s\\p{P}]))`, 'gui');
    normalized = normalized.replace(unicodeRegex, ' ');
  }

  return normalized.replace(/\s+/g, ' ').trim();
}

function areTitlesSynonyms(t1: string, t2: string): boolean {
  if (t1 === t2) return true;
  const w1 = t1.split(/\s+/);
  const w2 = t2.split(/\s+/);
  if (w1.length !== w2.length) return false;
  for (let i = 0; i < w1.length; i++) {
    if (w1[i] === w2[i]) continue;
    const syns = INTRA_TYPE_SYNONYMS[w1[i]] || [];
    if (!syns.includes(w2[i])) return false;
  }
  return true;
}

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
    const cleanTarget = normalizeTitleForMatching(targetTitle);

    // 1. Check exact title and start time match
    for (const entity of schedules.values()) {
      if (entity.status !== 'CONFIRMED' && entity.status !== 'PENDING') continue;
      const entityTitleNorm = entity.title.toLowerCase().trim();
      const cleanEntity = normalizeTitleForMatching(entity.title);
      if (entityTitleNorm === normalizedTarget || cleanEntity === cleanTarget) {
        if (originalStart && entity.start === originalStart) return entity;
        if (originalDate && (entity.start_date === originalDate || entity.start?.startsWith(originalDate))) return entity;
      }
    }

    // 2. Check synonym title match
    const targetSynonyms = INTRA_TYPE_SYNONYMS[normalizedTarget] || INTRA_TYPE_SYNONYMS[cleanTarget] || [];
    for (const entity of schedules.values()) {
      if (entity.status !== 'CONFIRMED' && entity.status !== 'PENDING') continue;
      const entityTitleNorm = entity.title.toLowerCase().trim();
      const cleanEntity = normalizeTitleForMatching(entity.title);
      if (
        entityTitleNorm === normalizedTarget ||
        cleanEntity === cleanTarget ||
        targetSynonyms.includes(entityTitleNorm) ||
        targetSynonyms.includes(cleanEntity) ||
        areTitlesSynonyms(cleanEntity, cleanTarget) ||
        areTitlesSynonyms(entityTitleNorm, normalizedTarget)
      ) {
        return entity;
      }
    }

    // 3. Substring match
    for (const entity of schedules.values()) {
      if (entity.status !== 'CONFIRMED' && entity.status !== 'PENDING') continue;
      const entityTitleNorm = entity.title.toLowerCase().trim();
      const cleanEntity = normalizeTitleForMatching(entity.title);
      if (
        entityTitleNorm.includes(normalizedTarget) ||
        normalizedTarget.includes(entityTitleNorm) ||
        cleanEntity.includes(cleanTarget) ||
        cleanTarget.includes(cleanEntity)
      ) {
        return entity;
      }
    }

    return null;
  }
}
