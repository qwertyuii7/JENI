import { z } from 'zod';

export const EventTypeSchema = z.enum([
  'message_received',
  'event_extracted',
  'event_confirmed',
  'event_dismissed',
  'reminder_created',
  'reminder_fired',
  'alarm_set',
  'draft_created',
  'draft_sent',
  'parse_failed',
  'correction_made'
]);

export type EventType = z.infer<typeof EventTypeSchema>;

export const TargetSchema = z.object({
  title: z.string().min(1).max(100),
  original_start: z.string().optional(),
  original_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional()
});

export type ExtractionTarget = z.infer<typeof TargetSchema>;

export const ExtractionOutputSchema = z.object({
  type: z.enum(['new', 'cancel', 'reschedule']),
  title: z.string().min(1).max(100),
  start: z.string().optional(),
  start_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
  end: z.string().optional(),
  time_unspecified: z.boolean().optional(),
  ambiguous: z.boolean().optional(),
  location: z.string().max(100).optional(),
  target: TargetSchema.optional(),
  source_quote: z.string().max(200),
  confidence: z.number().min(0).max(1)
});

export type ExtractionOutput = z.infer<typeof ExtractionOutputSchema>;

export const SyncEventSchema = z.object({
  id: z.string().uuid(),
  timestamp: z.string(),
  device_id: z.string().min(1),
  seq: z.number().int().positive().default(1),
  hlc: z.string().min(1),
  type: EventTypeSchema,
  entity_id: z.string(),
  payload: z.record(z.any())
});

export type SyncEvent = z.infer<typeof SyncEventSchema>;

export interface ScheduleEntity {
  id: string;
  title: string;
  start?: string;
  start_date?: string;
  time_unspecified?: boolean;
  ambiguous?: boolean;
  status: 'PENDING' | 'CONFIRMED' | 'DISMISSED' | 'CANCELLED';
  source_quote: string;
  raw_text?: string;
  confidence: number;
  created_at: string;
  updated_at: string;
  target_id?: string;
}

export interface ReminderEntity {
  id: string;
  schedule_id: string;
  trigger_time: string;
  type: 'notification' | 'alarm';
  status: 'SCHEDULED' | 'FIRED' | 'CANCELLED';
}
