import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { EventStore } from '../src/state.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

interface MatchingFixture {
  id: string;
  description: string;
  existing_events: Array<{
    id: string;
    title: string;
    start?: string;
  }>;
  incoming: {
    type: 'cancel' | 'reschedule';
    title: string;
  };
  expected_matched_id: string | null;
}

describe('Matching Fixtures Parity Suite (spec/fixtures/matching.json)', () => {
  const matchingPath = path.resolve(__dirname, '../../spec/fixtures/matching.json');
  const fixtures: MatchingFixture[] = JSON.parse(fs.readFileSync(matchingPath, 'utf-8'));

  for (const fixture of fixtures) {
    it(`[${fixture.id}] ${fixture.description}`, () => {
      const store = new EventStore();
      for (const ev of fixture.existing_events) {
        store.addEvent({
          id: `ev-ext-${ev.id}`,
          timestamp: '2026-10-12T10:00:00+05:30',
          device_id: 'device',
          type: 'event_extracted',
          entity_id: ev.id,
          payload: {
            type: 'new',
            title: ev.title,
            start: ev.start || '2026-10-13T10:00:00+05:30',
            confidence: 0.9,
            source_quote: ev.title
          }
        });
        store.addEvent({
          id: `ev-conf-${ev.id}`,
          timestamp: '2026-10-12T10:01:00+05:30',
          device_id: 'device',
          type: 'event_confirmed',
          entity_id: ev.id,
          payload: {}
        });
      }

      const matched = store.matchTargetEntity(fixture.incoming.title);
      if (fixture.expected_matched_id === null) {
        expect(matched, `Expected null match for "${fixture.incoming.title}"`).toBeNull();
      } else {
        expect(matched, `Expected match for "${fixture.incoming.title}"`).toBeDefined();
        expect(matched!.id).toBe(fixture.expected_matched_id);
      }
    });
  }
});
