import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

function loadSpecJson<T>(filename: string, fallback: T): T {
  try {
    const candidates = [
      path.resolve(__dirname, `../../spec/${filename}`),
      path.resolve(process.cwd(), `spec/${filename}`),
      path.resolve(__dirname, `../../../spec/${filename}`)
    ];
    for (const p of candidates) {
      if (fs.existsSync(p)) {
        return JSON.parse(fs.readFileSync(p, 'utf-8'));
      }
    }
  } catch (e) {
    // Return fallback
  }
  return fallback;
}

export interface KeywordsSpec {
  schedule_triggers: string[];
  known_subjects: string[];
}

export interface DaypartsSpec {
  morning: { tokens: string[]; start_hour: number; end_hour: number };
  afternoon: { tokens: string[]; start_hour: number; end_hour: number };
  evening: { tokens: string[]; start_hour: number; end_hour: number };
  night: { tokens: string[]; pm_hours: number[]; midnight_hour: number; early_am_hours: number[]; border_hours: number[] };
}

export type SynonymsSpec = Record<string, Record<string, string[]>>;

export const keywordsSpec = loadSpecJson<KeywordsSpec>('keywords.json', {
  schedule_triggers: [],
  known_subjects: []
});

export const daypartsSpec = loadSpecJson<DaypartsSpec>('dayparts.json', {
  morning: { tokens: ['subah', 'morning', 'am', 'सुबह'], start_hour: 0, end_hour: 11 },
  afternoon: { tokens: ['dopahar', 'afternoon', 'pm', 'दोपहर'], start_hour: 12, end_hour: 16 },
  evening: { tokens: ['shaam', 'sham', 'evening', 'शाम'], start_hour: 17, end_hour: 20 },
  night: { tokens: ['raat', 'night', 'रात'], pm_hours: [8, 9, 10, 11], midnight_hour: 12, early_am_hours: [1, 2, 3, 4], border_hours: [5, 6, 7] }
});

export const synonymsSpec = loadSpecJson<SynonymsSpec>('synonyms.json', {});
