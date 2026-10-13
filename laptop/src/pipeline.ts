import { v4 as uuidv4 } from 'uuid';
import {
  parseScheduleMessage,
  prefilterMessage,
  normalizeTitleForMatching,
  ExtractionOutput,
  SyncEvent,
  HybridLogicalClock
} from '@schedule-assistant/core';
import { EventDatabase } from './db/database.js';
import { ProjectionEngine } from './db/projection.js';

export interface NotificationCallback {
  (alert: { title: string; body: string; type: 'info' | 'warning' | 'error' | 'alert' }): void;
}

export interface IngestionOptions {
  deviceId?: string;
  referenceNow?: string;
  onNotification?: NotificationCallback;
}

export interface IngestionResult {
  status: 'PENDING' | 'REJECTED' | 'PARSE_FAILED' | 'FLOOD_TRUNCATED';
  entity_id?: string;
  extraction?: ExtractionOutput;
  message: string;
  candidates?: Array<{ id: string; title: string; start?: string }>;
}

export class IngestionPipeline {
  private db: EventDatabase;
  private defaultDeviceId: string;
  private hlc: HybridLogicalClock;
  private onNotification?: NotificationCallback;

  constructor(db: EventDatabase, options: IngestionOptions = {}) {
    this.db = db;
    this.defaultDeviceId = options.deviceId || 'laptop-local';
    this.hlc = new HybridLogicalClock(this.defaultDeviceId);
    this.onNotification = options.onNotification;
  }

  public setNotificationHandler(cb: NotificationCallback): void {
    this.onNotification = cb;
  }

  public getHlc(): HybridLogicalClock {
    return this.hlc;
  }

  public processMessage(rawText: string, options: IngestionOptions = {}): IngestionResult {
    const deviceId = options.deviceId || this.defaultDeviceId;
    const refNow = options.referenceNow || new Date().toISOString();
    const nowIso = new Date().toISOString();

    // Store in prunable raw_messages table
    const rawMessageId = `raw-${uuidv4()}`;
    this.db.storeRawMessage(rawMessageId, rawText, nowIso);

    // 1. Flooding Check: Detect message flooding (> 3 schedule declarations)
    const eventCount = (rawText.match(/\b(class|meeting|lecture|sync|viva|test|quiz|lab|demo)\b/gi) || []).length;
    if (eventCount > 3) {
      const floodEventId = uuidv4();
      const floodEvent: SyncEvent = {
        id: floodEventId,
        timestamp: nowIso,
        device_id: deviceId,
        seq: this.db.getNextSeq(deviceId),
        hlc: this.hlc.now(),
        type: 'event_dismissed',
        entity_id: `flood-${floodEventId}`,
        payload: {
          reason: 'flood_truncated',
          raw_text: rawText,
          raw_message_id: rawMessageId,
          message: 'Message exceeded flood limit (capped at 3 events per notification)'
        }
      };
      this.db.insertEvent(floodEvent);

      if (this.onNotification) {
        this.onNotification({
          title: 'Schedule Alert: Message Truncated',
          body: `Notification contained high volume of events. Original message: "${rawText.substring(0, 120)}..."`,
          type: 'warning'
        });
      }

      return {
        status: 'FLOOD_TRUNCATED',
        message: 'Message flood detected and truncated'
      };
    }

    // 2. Prefilter & Extraction
    const prefilter = prefilterMessage(rawText);
    if (!prefilter.shouldParse) {
      if (['empty_input', 'casual_chatter', 'otp_noise', 'price_noise', 'score_noise', 'pure_injection'].includes(prefilter.reason || '')) {
        return {
          status: 'REJECTED',
          message: `Ignored by prefilter: ${prefilter.reason}`
        };
      }

      // If text had potential schedule intent but failed prefilter, emit parse_failed
      const failEvent: SyncEvent = {
        id: uuidv4(),
        timestamp: nowIso,
        device_id: deviceId,
        seq: this.db.getNextSeq(deviceId),
        hlc: this.hlc.now(),
        type: 'parse_failed',
        entity_id: `fail-${uuidv4()}`,
        payload: {
          raw_text: rawText,
          raw_message_id: rawMessageId,
          reason: prefilter.reason || 'unresolved_prefilter'
        }
      };
      this.db.insertEvent(failEvent);

      if (this.onNotification) {
        this.onNotification({
          title: 'Schedule Assistant: Could Not Parse Message',
          body: `Received message: "${rawText.substring(0, 100)}"`,
          type: 'info'
        });
      }

      return {
        status: 'PARSE_FAILED',
        message: `Parse failed: ${prefilter.reason}`
      };
    }

    // 3. Parser
    const extraction = parseScheduleMessage(rawText, refNow);
    if (!extraction) {
      const failEvent: SyncEvent = {
        id: uuidv4(),
        timestamp: nowIso,
        device_id: deviceId,
        seq: this.db.getNextSeq(deviceId),
        hlc: this.hlc.now(),
        type: 'parse_failed',
        entity_id: `fail-${uuidv4()}`,
        payload: {
          raw_text: rawText,
          raw_message_id: rawMessageId,
          reason: 'unresolved_rules'
        }
      };
      this.db.insertEvent(failEvent);

      if (this.onNotification) {
        this.onNotification({
          title: 'Schedule Assistant: Could Not Parse Message',
          body: `Received: "${rawText.substring(0, 100)}"`,
          type: 'info'
        });
      }

      return {
        status: 'PARSE_FAILED',
        message: 'Extraction returned null'
      };
    }

    // 4. Target Resolver (RULES.md section 5)
    let candidates: Array<{ id: string; title: string; start?: string }> = [];
    if (extraction.type === 'cancel' || extraction.type === 'reschedule') {
      const allEvents = this.db.getAllEvents();
      const { schedules } = ProjectionEngine.projectState(allEvents);
      const targetTitle = (extraction.target?.title || extraction.title).toLowerCase().trim();
      const cleanTarget = normalizeTitleForMatching(targetTitle);

      for (const sched of schedules.values()) {
        if (sched.status === 'DISMISSED' || sched.status === 'CANCELLED') continue;
        const schedTitle = sched.title.toLowerCase().trim();
        const cleanSched = normalizeTitleForMatching(sched.title);
        if (
          schedTitle === targetTitle || schedTitle.includes(targetTitle) || targetTitle.includes(schedTitle) ||
          cleanSched === cleanTarget || cleanSched.includes(cleanTarget) || cleanTarget.includes(cleanSched)
        ) {
          candidates.push({
            id: sched.id,
            title: sched.title,
            start: sched.start
          });
        }
      }

      if (candidates.length === 1) {
        const matched = candidates[0];
        if (extraction.target) {
          (extraction.target as any).matched_entity_id = matched.id;
        }

        // Time inheritance for time_unspecified reschedules
        if (extraction.type === 'reschedule' && extraction.time_unspecified && matched.start) {
          const originalTime = matched.start.split('T')[1];
          if (extraction.start_date) {
            extraction.start = `${extraction.start_date}T${originalTime}`;
            extraction.time_unspecified = false;
          }
        }
      } else {
        extraction.ambiguous = true;
      }
    }

    // 5. Emit PENDING event_extracted
    const entityId = `sched-${uuidv4()}`;
    const extractEvent: SyncEvent = {
      id: uuidv4(),
      timestamp: nowIso,
      device_id: deviceId,
      seq: this.db.getNextSeq(deviceId),
      hlc: this.hlc.now(),
      type: 'event_extracted',
      entity_id: entityId,
      payload: {
        ...extraction,
        raw_text: rawText,
        raw_message_id: rawMessageId,
        candidate_matches: candidates
      }
    };
    this.db.insertEvent(extractEvent);

    return {
      status: 'PENDING',
      entity_id: entityId,
      extraction,
      candidates,
      message: 'Event extracted and stored as PENDING'
    };
  }

  public confirmSchedule(entityId: string, timeOverride?: string): boolean {
    const allEvents = this.db.getAllEvents();
    const { schedules } = ProjectionEngine.projectState(allEvents);
    const existing = schedules.get(entityId);
    if (!existing || existing.status !== 'PENDING') {
      return false;
    }

    const payload: Record<string, any> = {};
    if (timeOverride) {
      payload.start = timeOverride;
      payload.time_unspecified = false;
    }

    const confirmEvent: SyncEvent = {
      id: uuidv4(),
      timestamp: new Date().toISOString(),
      device_id: this.defaultDeviceId,
      seq: this.db.getNextSeq(this.defaultDeviceId),
      hlc: this.hlc.now(),
      type: 'event_confirmed',
      entity_id: entityId,
      payload
    };

    return this.db.insertEvent(confirmEvent);
  }

  public dismissSchedule(entityId: string): boolean {
    const allEvents = this.db.getAllEvents();
    const { schedules } = ProjectionEngine.projectState(allEvents);
    const existing = schedules.get(entityId);
    if (!existing || existing.status !== 'PENDING') {
      return false;
    }

    const dismissEvent: SyncEvent = {
      id: uuidv4(),
      timestamp: new Date().toISOString(),
      device_id: this.defaultDeviceId,
      seq: this.db.getNextSeq(this.defaultDeviceId),
      hlc: this.hlc.now(),
      type: 'event_dismissed',
      entity_id: entityId,
      payload: {}
    };

    return this.db.insertEvent(dismissEvent);
  }
}
