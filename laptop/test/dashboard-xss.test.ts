import { describe, it, expect } from 'vitest';
import { escapeHtml, createDashboardHtml } from '../src/server.js';

describe('Laptop Dashboard Security & XSS Suite', () => {
  it('correctly escapes dangerous HTML special characters', () => {
    expect(escapeHtml('<script>alert("xss")</script>')).toBe(
      '&lt;script&gt;alert(&quot;xss&quot;)&lt;/script&gt;'
    );
    expect(escapeHtml(`' onfocus='alert(1)`)).toBe(
      '&#039; onfocus=&#039;alert(1)'
    );
    expect(escapeHtml('Tom & Jerry <test>')).toBe('Tom &amp; Jerry &lt;test&gt;');
  });

  it('prevents XSS injection across all schedule attributes in rendered dashboard HTML', () => {
    const maliciousSchedules = [
      {
        id: 'sched-xss-1',
        title: '<script>alert("pwned title")</script>',
        start: '2026-10-13T17:00:00+05:30',
        start_date: '2026-10-13',
        status: 'PENDING',
        confidence: 0.65,
        ambiguous: true,
        source_quote: '"><svg onload=alert(document.cookie)>',
        raw_text: '<img src=x onerror=evil()>'
      }
    ];

    const maliciousReminders = [
      {
        id: 'rem-xss-1',
        schedule_id: 'sched-xss-1',
        title: '<iframe src="javascript:alert(1)"></iframe>',
        trigger_time: '2026-10-13T16:45:00+05:30',
        type: 'notification',
        status: 'SCHEDULED'
      }
    ];

    const maliciousEvents = [
      {
        id: 'ev-1"><script>alert(1)</script>',
        type: 'event_extracted',
        entity_id: 'sched-xss-1',
        timestamp: '2026-10-12T10:00:00+05:30',
        payload: {
          raw_text: '"><script>alert(1)</script>'
        }
      }
    ];

    const html = createDashboardHtml({
      schedules: maliciousSchedules,
      reminders: maliciousReminders,
      events: maliciousEvents
    });

    // 1. Assert unescaped script tags DO NOT appear anywhere outside the fixed script blocks
    expect(html).not.toContain('<script>alert("pwned title")</script>');
    expect(html).not.toContain('<svg onload=alert(document.cookie)>');
    expect(html).not.toContain('<img src=x onerror=evil()>');
    expect(html).not.toContain('<iframe src="javascript:alert(1)"></iframe>');

    // 2. Assert properly escaped versions DO exist in the HTML
    expect(html).toContain('&lt;script&gt;alert(&quot;pwned title&quot;)&lt;/script&gt;');
    expect(html).toContain('&quot;&gt;&lt;svg onload=alert(document.cookie)&gt;');
    expect(html).toContain('&lt;iframe src=&quot;javascript:alert(1)&quot;&gt;&lt;/iframe&gt;');
  });

  it('renders all required dashboard sections and features', () => {
    const schedules = [
      {
        id: 'sched-1',
        title: 'Project Review',
        start: '2026-10-13T17:00:00+05:30',
        status: 'PENDING',
        confidence: 0.65,
        ambiguous: true,
        source_quote: 'review kal 5 baje'
      }
    ];

    const html = createDashboardHtml({
      schedules,
      reminders: [],
      events: []
    });

    // Navigation tabs
    expect(html).toContain('tab-home');
    expect(html).toContain('tab-pending');
    expect(html).toContain('tab-alerts');
    expect(html).toContain('tab-settings');

    // Pending details
    expect(html).toContain('Source Quote:');
    expect(html).toContain('65% Confidence');
    expect(html).toContain('Ambiguous AM/PM');
    // Dual readings for 17:00 (5:00 PM vs 5:00 AM)
    expect(html).toContain('Confirm Reading A: 5:00 PM');
    expect(html).toContain('Confirm Reading B: 5:00 AM');
    // Edit time and buttons
    expect(html).toContain('Confirm with Custom Time');
    expect(html).toContain('Dismiss');
  });
});
