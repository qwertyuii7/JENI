import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { parseScheduleMessage } from '../src/parser.js';
import { EventStore } from '../src/state.js';
import { generateAlertsForSchedule, isReminderSuppressed } from '../src/reminders.js';
import { SyncEvent, ScheduleEntity } from '../src/types.js';

describe('Core Parser Fixtures Parity Test', () => {
  const fixturesPath = path.resolve(__dirname, '../../spec/fixtures/phrases.json');
  const fixtures = JSON.parse(fs.readFileSync(fixturesPath, 'utf-8'));

  it('should run and pass all fixtures from spec/fixtures/phrases.json', () => {
    let passed = 0;
    const failures: Array<{ id: string; input: string; expected: any; actual: any }> = [];

    for (const fixture of fixtures) {
      const actual = parseScheduleMessage(fixture.input, fixture.reference_now);

      if (!fixture.expected.is_schedule) {
        if (actual !== null) {
          failures.push({
            id: fixture.id,
            input: fixture.input,
            expected: null,
            actual
          });
        } else {
          expect(actual).toBeNull();
          passed++;
        }
      } else {
        const exp = fixture.expected.extraction;
        if (!actual) {
          failures.push({
            id: fixture.id,
            input: fixture.input,
            expected: exp,
            actual: null
          });
          continue;
        }

        try {
          expect(actual.type).toBe(exp.type);
          expect(actual.title.toLowerCase()).toBe(exp.title.toLowerCase());

          if (exp.start) {
            expect(actual.start).toBe(exp.start);
          }
          if (exp.start_date) {
            expect(actual.start_date).toBe(exp.start_date);
          }
          if (exp.time_unspecified) {
            expect(actual.time_unspecified).toBe(true);
            expect(actual.start).toBeUndefined();
          }
          if (exp.ambiguous) {
            expect(actual.ambiguous).toBe(true);
            expect(actual.confidence).toBeLessThanOrEqual(0.65);
          } else {
            expect(actual.confidence).toBeGreaterThanOrEqual(0.75);
          }

          passed++;
        } catch (err: any) {
          failures.push({
            id: fixture.id,
            input: fixture.input,
            expected: exp,
            actual
          });
        }
      }
    }

    if (failures.length > 0) {
      console.error(`Fixture failures (${failures.length}/${fixtures.length}):`, failures.slice(0, 5));
    }
    expect(failures.length).toBe(0);
    expect(passed).toBe(fixtures.length);
  });

  it('guarantees no reminders or alarms are generated from time_unspecified events', () => {
    const dateOnlySchedule: ScheduleEntity = {
      id: 'sched-date-only',
      title: 'DBMS quiz',
      start_date: '2026-10-13',
      time_unspecified: true,
      status: 'CONFIRMED',
      source_quote: 'kal DBMS quiz',
      confidence: 0.65,
      created_at: new Date().toISOString(),
      updated_at: new Date().toISOString()
    };

    const alerts = generateAlertsForSchedule(dateOnlySchedule, 'device-laptop');
    expect(alerts.reminderEvent).toBeUndefined();
    expect(alerts.alarmEvent).toBeUndefined();
  });

  it('generates reminder and alarm for confirmed schedule with start time', () => {
    const scheduledEvent: ScheduleEntity = {
      id: 'sched-101',
      title: 'DBMS class',
      start: '2026-10-13T16:00:00+05:30',
      status: 'CONFIRMED',
      source_quote: 'kal 4 baje DBMS class',
      confidence: 0.9,
      created_at: new Date().toISOString(),
      updated_at: new Date().toISOString()
    };

    const alerts = generateAlertsForSchedule(scheduledEvent, 'device-phone');
    expect(alerts.reminderEvent).toBeDefined();
    expect(alerts.alarmEvent).toBeDefined();

    // Trigger time should be 15 mins before for reminder
    const startMs = new Date('2026-10-13T16:00:00+05:30').getTime();
    const reminderTriggerMs = new Date(alerts.reminderEvent!.payload.trigger_time).getTime();
    expect(startMs - reminderTriggerMs).toBe(15 * 60 * 1000);

    // Trigger time should be 30 mins before for alarm
    const alarmTriggerMs = new Date(alerts.alarmEvent!.payload.trigger_time).getTime();
    expect(startMs - alarmTriggerMs).toBe(30 * 60 * 1000);
  });

  it('suppresses reminder firing if reminder_fired event exists in event log', () => {
    const reminderId = 'rem-sched-101';
    const eventLog: SyncEvent[] = [
      {
        id: 'ev-fired-1',
        timestamp: new Date().toISOString(),
        device_id: 'device-phone',
        type: 'reminder_fired',
        entity_id: reminderId,
        payload: { fired_at: new Date().toISOString() }
      }
    ];

    expect(isReminderSuppressed(reminderId, eventLog)).toBe(true);
    expect(isReminderSuppressed('rem-other-999', eventLog)).toBe(false);
  });

  it('correctly resolves reschedule target by inheriting original start time in state store', () => {
    const store = new EventStore();

    // 1. Initial confirmed event at 14:00
    store.addEvent({
      id: 'ev-initial-1',
      timestamp: '2026-10-12T10:00:00+05:30',
      device_id: 'phone',
      type: 'event_extracted',
      entity_id: 'sched-dbms',
      payload: {
        type: 'new',
        title: 'DBMS lecture',
        start: '2026-10-12T14:00:00+05:30',
        source_quote: 'today 2pm DBMS lecture',
        confidence: 0.95
      }
    });
    store.addEvent({
      id: 'ev-initial-2',
      timestamp: '2026-10-12T10:05:00+05:30',
      device_id: 'phone',
      type: 'event_confirmed',
      entity_id: 'sched-dbms',
      payload: {}
    });

    // 2. Incoming reschedule with date only (time_unspecified)
    const target = store.matchTargetEntity('DBMS lecture');
    expect(target).toBeDefined();
    expect(target!.start).toBe('2026-10-12T14:00:00+05:30');

    // Inherit original clock time (14:00) into new date (2026-10-19)
    const originalTime = target!.start!.split('T')[1];
    const newStart = `2026-10-19T${originalTime}`;

    store.addEvent({
      id: 'ev-resched-1',
      timestamp: '2026-10-12T11:00:00+05:30',
      device_id: 'laptop',
      type: 'event_confirmed',
      entity_id: 'sched-dbms',
      payload: {
        start: newStart,
        time_unspecified: false
      }
    });

    const updatedEntity = store.getDerivedScheduleEntities().get('sched-dbms');
    expect(updatedEntity!.start).toBe('2026-10-19T14:00:00+05:30');
    expect(updatedEntity!.status).toBe('CONFIRMED');
  });
});
