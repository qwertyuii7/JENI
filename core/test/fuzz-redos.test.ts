import { describe, it, expect } from 'vitest';
import { parseScheduleMessage } from '../src/parser.js';
import { prefilterMessage } from '../src/prefilter.js';

describe('Fuzzing & ReDoS Security Suite', () => {
  it('enforces input length capping and prevents buffer or CPU exhaustion on oversized input', () => {
    const hugeInput = 'kal 4 baje meeting ' + 'A'.repeat(50000);
    const start = performance.now();
    const result = parseScheduleMessage(hugeInput);
    const duration = performance.now() - start;

    expect(duration).toBeLessThan(100); // Must process in under 100ms
    expect(result).toBeDefined();
    expect(result?.title).toBe('meeting');
  });

  it('resists ReDoS attacks with pathological repeating time and keyword patterns', () => {
    const pathologicalPatterns = [
      'kal '.repeat(2000) + '4 baje',
      'at '.repeat(1000) + '10:00:00:00',
      'aaj shaam '.repeat(1000) + '7 baje',
      '(((((((((kal 4 baje)))))))))'.repeat(200),
      '<script>'.repeat(500) + 'meeting' + '</script>'.repeat(500),
      'baje '.repeat(2000),
      '12:34:'.repeat(1000) + '56',
      'tomorrow '.repeat(1500) + 'at 5pm'
    ];

    for (const pattern of pathologicalPatterns) {
      const start = performance.now();
      prefilterMessage(pattern);
      parseScheduleMessage(pattern);
      const duration = performance.now() - start;
      expect(duration).toBeLessThan(150); // No exponential backtracking
    }
  });

  it('survives random fuzz strings without unhandled exceptions or crashes', () => {
    const fuzzSamples = [
      '\u200B\u200C\u200D'.repeat(1000),
      '🚀🎉🔥⏰'.repeat(500) + ' meeting tomorrow 4pm',
      '\x00\x01\x02\x03\x04\x05\x06\x07\x08\x0E\x0F'.repeat(200),
      'कल '.repeat(1000) + '४ बजे',
      'null',
      'undefined',
      'NaN',
      '{"type": "new"}',
      '--\r\n\t--\r\n\t'.repeat(200)
    ];

    for (const sample of fuzzSamples) {
      expect(() => {
        prefilterMessage(sample);
        parseScheduleMessage(sample);
      }).not.toThrow();
    }
  });
});
