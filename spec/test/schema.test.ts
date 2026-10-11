import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import Ajv2020 from 'ajv/dist/2020.js';

describe('Spec Schema & Fixtures Validation', () => {
  const eventSchema = JSON.parse(
    fs.readFileSync(path.resolve(__dirname, '../event.schema.json'), 'utf-8')
  );
  const extractionSchema = JSON.parse(
    fs.readFileSync(path.resolve(__dirname, '../extraction.schema.json'), 'utf-8')
  );

  const ajv = new Ajv2020({ allErrors: true });
  ajv.addFormat('uuid', /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i);
  ajv.addFormat('date-time', /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}/);

  const validateEvent = ajv.compile(eventSchema);
  const validateExtraction = ajv.compile(extractionSchema);

  it('should compile event and extraction schemas without error', () => {
    expect(validateEvent).toBeDefined();
    expect(validateExtraction).toBeDefined();
  });

  it('should validate sample event objects against event.schema.json', () => {
    const validEvent = {
      id: 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
      timestamp: '2026-10-12T10:00:00+05:30',
      device_id: 'phone-pixel8',
      type: 'event_extracted',
      entity_id: 'sched-123',
      payload: {
        title: 'DBMS class',
        start: '2026-10-13T16:00:00+05:30'
      }
    };
    expect(validateEvent(validEvent)).toBe(true);
  });
});
