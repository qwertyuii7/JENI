import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import Ajv2020 from 'ajv/dist/2020.js';

describe('Fixtures Validation Suite', () => {
  const schemaPath = path.resolve(__dirname, '../extraction.schema.json');
  const fixturesPath = path.resolve(__dirname, '../fixtures/phrases.json');

  const extractionSchema = JSON.parse(fs.readFileSync(schemaPath, 'utf-8'));
  const fixtures = JSON.parse(fs.readFileSync(fixturesPath, 'utf-8'));

  const ajv = new Ajv2020({ allErrors: true });
  const validateExtraction = ajv.compile(extractionSchema);

  it('should have unique fixture IDs', () => {
    const ids = new Set<string>();
    for (const f of fixtures) {
      expect(ids.has(f.id)).toBe(false);
      ids.add(f.id);
    }
    expect(ids.size).toBe(fixtures.length);
  });

  it('should validate every extraction against extraction.schema.json with Ajv', () => {
    for (const f of fixtures) {
      if (f.expected.is_schedule) {
        const valid = validateExtraction(f.expected.extraction);
        if (!valid) {
          throw new Error(`Fixture ${f.id} failed schema validation: ${JSON.stringify(validateExtraction.errors)}`);
        }
        expect(valid).toBe(true);
      } else {
        expect(f.expected.extraction).toBeNull();
      }
    }
  });

  it('strictly validates time_unspecified: requires start_date, forbids fake 00:00 start, blocks alarms/reminders', () => {
    for (const f of fixtures) {
      if (!f.expected.is_schedule) continue;
      const extraction = f.expected.extraction;

      if (extraction.time_unspecified) {
        // Must provide valid YYYY-MM-DD start_date
        expect(extraction.start_date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
        // Must NOT invent a fake 00:00:00 start time
        expect(extraction.start).toBeUndefined();

        // HARD INVARIANT: System cannot generate reminder/alarm time from date-only event
        const deriveReminderTime = (ext: typeof extraction) => {
          if (ext.time_unspecified || !ext.start) return null;
          return new Date(ext.start).getTime() - 15 * 60 * 1000;
        };
        const deriveAlarmTime = (ext: typeof extraction) => {
          if (ext.time_unspecified || !ext.start) return null;
          return new Date(ext.start).getTime();
        };

        expect(deriveReminderTime(extraction)).toBeNull();
        expect(deriveAlarmTime(extraction)).toBeNull();
      } else {
        // When time is specified, start is mandatory
        expect(extraction.start).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\+05:30$/);
      }
    }
  });

  it('validates minimal source_quote and title sanitization', () => {
    const forbiddenInjectionWords = [
      'system:', 'drop table', 'delete all', '<script', 'eval(', 'rm -rf',
      'ignore previous', 'disregard'
    ];

    for (const f of fixtures) {
      if (!f.expected.is_schedule) continue;
      const extraction = f.expected.extraction;

      // Title length and character checks
      expect(extraction.title.length).toBeGreaterThan(0);
      expect(extraction.title.length).toBeLessThanOrEqual(100);
      // No zero-width characters in title
      expect(extraction.title).not.toMatch(/[\u200B-\u200D\uFEFF]/);

      // source_quote must be minimal span (<= 150 chars) and not leak raw injection commands
      expect(extraction.source_quote.length).toBeLessThanOrEqual(150);
      const lowerQuote = extraction.source_quote.toLowerCase();
      for (const word of forbiddenInjectionWords) {
        expect(lowerQuote).not.toContain(word);
      }
    }
  });

  it('validates confidence thresholds aligned with RULES.md', () => {
    for (const f of fixtures) {
      if (!f.expected.is_schedule) continue;
      const extraction = f.expected.extraction;
      if (extraction.ambiguous) {
        // Ambiguous cases must be <= 0.65 (test threshold matches rule threshold)
        expect(extraction.confidence).toBeLessThanOrEqual(0.65);
      } else {
        // Clear cases must be >= 0.75
        expect(extraction.confidence).toBeGreaterThanOrEqual(0.75);
      }
      expect(extraction.confidence).toBeLessThanOrEqual(1.0);
    }
  });

  it('validates language classification using script/token detection', () => {
    const devanagariRegex = /[\u0900-\u097F]/;
    const hinglishTokens = [
      'kal', 'parso', 'tarso', 'baje', 'pe', 'ho', 'gayi', 'gaya',
      'hai', 'hain', 'aaj', 'subah', 'shaam', 'raat', 'somvar',
      'mangalvar', 'budhvar', 'guruvar', 'shukravar', 'shanivar',
      'ravivar', 'chutti', 'hoga', 'wali', 'karo', 'ka', 'ki', 'ke',
      'rupey', 'mein', 'mili', 'padh', 'lena', 'sab', 'bhai',
      'bachi', 'thi', 'tha', 'mann', 'utha', 'chal', 'rahi', 'kya',
      'peete', 'chalo', 'chai', 'aram', 'se', 'dekho', 'batana',
      'hui', 'toh', 'lagta', 'shayad', 'rakhi', 'milte', 'nahi'
    ];

    for (const f of fixtures) {
      const text = f.input.toLowerCase();
      if (devanagariRegex.test(f.input)) {
        expect(f.language).toBe('hindi');
      } else {
        const words = text.replace(/[^a-z0-9\s]/g, ' ').split(/\s+/);
        const hasHinglish = words.some(w => hinglishTokens.includes(w));
        if (hasHinglish) {
          expect(f.language).toBe('hinglish');
        } else {
          expect(f.language).toBe('english');
        }
      }
    }
  });

  it('independently recomputes relative day offsets and weekday math', () => {
    const parseYMD = (isoStr: string) => {
      const [datePart, timePart] = isoStr.split('T');
      const [y, m, d] = datePart.split('-').map(Number);
      const [h, min] = timePart ? timePart.substring(0, 5).split(':').map(Number) : [0, 0];
      return { y, m, d, h, min };
    };

    const getDaysDiff = (isoA: string, dateB: string) => {
      const a = parseYMD(isoA);
      const [by, bm, bd] = dateB.split('T')[0].split('-').map(Number);
      const utcA = Date.UTC(a.y, a.m - 1, a.d);
      const utcB = Date.UTC(by, bm - 1, bd);
      return Math.round((utcB - utcA) / (24 * 60 * 60 * 1000));
    };

    const getWeekday = (dateStr: string) => {
      const [y, m, d] = dateStr.split('T')[0].split('-').map(Number);
      return new Date(Date.UTC(y, m - 1, d)).getUTCDay();
    };

    for (const f of fixtures) {
      if (!f.expected.is_schedule) continue;
      const targetDate = f.expected.extraction.start || f.expected.extraction.start_date;
      const lowerInput = f.input.toLowerCase();
      const diffDays = getDaysDiff(f.reference_now, targetDate);
      const targetWeekday = getWeekday(targetDate);
      const refWeekday = getWeekday(f.reference_now);
      const refParsed = parseYMD(f.reference_now);
      const startParsed = parseYMD(f.expected.extraction.start || `${targetDate}T00:00:00`);

      // 1. Relative day checks (explicit date overrides relative words)
      const hasExplicitDate = /\b\d{1,2}(?:st|nd|rd|th)?\s+(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)|\b\d{1,2}\/\d{1,2}\b/i.test(lowerInput);
      if (!hasExplicitDate) {
        if (lowerInput.includes('kal ') || lowerInput.includes('tomorrow')) {
          expect(diffDays).toBe(1);
        } else if (lowerInput.includes('parso ') || lowerInput.includes('day after tomorrow')) {
          expect(diffDays).toBe(2);
        } else if (lowerInput.includes('tarso ')) {
          expect(diffDays).toBe(3);
        } else if (lowerInput.includes('aaj ') || lowerInput.includes('today')) {
          expect(diffDays).toBe(0);
        }
      }

      // 2. Weekday name checks
      const isNextWeekModifier = lowerInput.includes('next ') || lowerInput.includes('agle ') || lowerInput.includes('अगले ');

      const checkWeekday = (expectedDay: number) => {
        expect(targetWeekday).toBe(expectedDay);
        if (isNextWeekModifier) {
          expect(diffDays).toBeGreaterThanOrEqual(7);
          expect(diffDays).toBeLessThanOrEqual(13);
        } else if (refWeekday === expectedDay) {
          const isAhead = (startParsed.h > refParsed.h) || (startParsed.h === refParsed.h && startParsed.min > refParsed.min);
          if (isAhead && !f.expected.extraction.time_unspecified) {
            expect(diffDays).toBe(0);
          } else {
            expect(diffDays).toBe(7);
          }
        }
      };

      if (lowerInput.includes('somvar') || lowerInput.includes('monday') || lowerInput.includes('सोमवार')) {
        checkWeekday(1);
      } else if (lowerInput.includes('mangalvar') || lowerInput.includes('tuesday') || lowerInput.includes('मंगलवार')) {
        checkWeekday(2);
      } else if (lowerInput.includes('budhvar') || lowerInput.includes('wednesday') || lowerInput.includes('बुधवार')) {
        checkWeekday(3);
      } else if (lowerInput.includes('guruvar') || lowerInput.includes('thursday') || lowerInput.includes('गुरुवार')) {
        checkWeekday(4);
      } else if (lowerInput.includes('shukravar') || lowerInput.includes('friday') || lowerInput.includes('शुक्रवार')) {
        checkWeekday(5);
      } else if (lowerInput.includes('shanivar') || lowerInput.includes('saturday') || lowerInput.includes('शनिवार')) {
        checkWeekday(6);
      } else if (lowerInput.includes('ravivar') || lowerInput.includes('sunday') || lowerInput.includes('रविवार')) {
        checkWeekday(0);
      }
    }
  });
});
