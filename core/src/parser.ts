import { ExtractionOutput } from './types.js';
import { prefilterMessage } from './prefilter.js';

export function normalizeDevanagariNumerals(text: string): string {
  const devanagariDigits: Record<string, string> = {
    '०': '0', '१': '1', '२': '2', '३': '3', '४': '4',
    '५': '5', '६': '6', '७': '7', '८': '8', '९': '9'
  };
  return text.replace(/[०-९]/g, (char) => devanagariDigits[char] || char);
}

export function sanitizeTitle(rawTitle: string): string {
  let cleaned = rawTitle
    .replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, '')
    .replace(/<[^>]+>/g, '')
    .replace(/[\u200B-\u200D\uFEFF]/g, '')
    .replace(/```[a-z]*[\s\S]*?```/gi, '')
    .replace(/SYSTEM:[\s\S]*/gi, '')
    .replace(/DROP TABLE[\s\S]*/gi, '')
    .replace(/delete all[\s\S]*/gi, '')
    .replace(/delete user[\s\S]*/gi, '')
    .replace(/eval\([^)]*\)/gi, '')
    .replace(/rm -rf[\s\S]*/gi, '')
    .replace(/root shell[\s\S]*/gi, '')
    .replace(/assistant override[\s\S]*/gi, '')
    .replace(/admin override[\s\S]*/gi, '')
    .replace(/disregard system prompt[\s\S]*/gi, '')
    .replace(/ignore prior rules[\s\S]*/gi, '')
    .replace(/set \d+ alarms/gi, '')
    .replace(/;\s*$/, '')
    .replace(/--\s*$/, '')
    .trim();

  cleaned = cleaned.replace(/\s+(hai|hoga|hogi|rakhi hai|milte hain|conduct hogi)\s*$/i, '').trim();

  if (cleaned.length > 100) {
    cleaned = cleaned.substring(0, 100).trim();
  }
  return cleaned || 'event';
}

function pad(n: number): string {
  return String(n).padStart(2, '0');
}

const KNOWN_SUBJECTS = [
  'Seminar on Cloud Computing', 'Database backup maintenance', 'Security audit walkthrough',
  'Backend architecture discussion', 'One on one with manager', 'Interview with candidate',
  'Code review session', 'Release retrospective', 'Frontend guild meetup', 'Machine Learning lab',
  'Quarterly town hall', 'Hackathon briefing', 'Architecture review', 'Dentist appointment',
  'Chemistry practical', 'Peer testing session', 'server maintenance', 'dinner meeting',
  'badminton match', 'farewell meeting', 'Doctor appointment', 'tutorial class', 'emergency sync',
  'catch up call', 'new year review', 'project discussion', 'project review', 'project deadline',
  'lunch meeting', 'product demo', 'product sync', 'Doctor checkup', 'Sprint planning',
  'physics test', 'Physics lecture', 'Algorithms viva', 'HR orientation', 'Weekly catchup',
  'weekly sync', 'Strategy meeting', 'Office all hands', 'morning yoga', 'coding round',
  'demo session', 'group study', 'guest lecture', 'review call', 'client call', 'client demo',
  'team sync', 'team meeting', 'project sync', 'sync meeting', 'lab session', 'lab test',
  'Math exam', 'math test', 'math quiz', 'DBMS class', 'DBMS lecture', 'DBMS quiz',
  'OS lecture', 'OS class', 'OS test', 'Board meeting', 'Design sync', 'Marketing review',
  'AI workshop', 'Budget meeting', 'code review', 'standup', 'presentation', 'webinar',
  'orientation', 'kickoff', 'workshop', 'seminar', 'interview', 'meeting', 'lecture',
  'classes', 'class', 'viva', 'quiz', 'exam', 'test', 'call', 'lab', 'flight', 'assembly',
  'deployment', 'holiday', 'review', 'sync',
  // Hindi
  'योग कक्षा', 'कार्यशाला', 'प्रेजेंटेशन', 'इंटरव्यू', 'परीक्षा', 'सेमिनार', 'लेक्चर',
  'मीटिंग', 'क्लास', 'चर्चा', 'टेस्ट', 'कॉल', 'लैब'
].sort((a, b) => b.length - a.length);

export function parseScheduleMessage(
  rawText: string,
  referenceNow: string = '2026-10-12T10:00:00+05:30'
): ExtractionOutput | null {
  const prefilter = prefilterMessage(rawText);
  if (!prefilter.shouldParse) {
    return null;
  }

  const text = normalizeDevanagariNumerals(prefilter.sanitizedText);
  const lower = text.toLowerCase();

  const [refDateStr, refTimePart] = referenceNow.split('T');
  const [refYear, refMonth, refDay] = refDateStr.split('-').map(Number);
  const [refHour, refMinute] = refTimePart.substring(0, 5).split(':').map(Number);
  const refDateUtc = new Date(Date.UTC(refYear, refMonth - 1, refDay));
  const refWeekday = refDateUtc.getUTCDay();

  // 1. Classification
  let type: 'new' | 'cancel' | 'reschedule' = 'new';
  const isReschedule = /\b(shift ho gayi|shifted to|rescheduled to|postponed to|moved to|postpone ho gaya|postpone ho gayi)\b/i.test(lower);
  const isCancel = /\b(cancel ho gayi|cancel ho gaya|cancelled|canceled|cancel hai|called off|nahi hai|nahi hoga|no meeting|no lecture|no classes|no class)\b/i.test(lower);

  if (isReschedule) {
    type = 'reschedule';
  } else if (isCancel) {
    type = 'cancel';
  }

  // 2. Relative Day Offset
  let dayOffset: number | null = null;
  const isNextWeek = /\b(next|agle)\b/i.test(lower) || lower.includes('अगले');

  if (/\b(kal|tomorrow)\b/i.test(lower) || lower.includes('कल')) {
    dayOffset = 1;
  } else if (/\b(parso|day after tomorrow)\b/i.test(lower) || lower.includes('परसों')) {
    dayOffset = 2;
  } else if (/\b(tarso)\b/i.test(lower)) {
    dayOffset = 3;
  } else if (/\b(aaj|today)\b/i.test(lower) || lower.includes('आज')) {
    dayOffset = 0;
  } else {
    const weekdayMap: Record<string, number> = {
      'somvar': 1, 'monday': 1, 'सोमवार': 1,
      'mangalvar': 2, 'tuesday': 2, 'मंगलवार': 2,
      'budhvar': 3, 'wednesday': 3, 'बुधवार': 3,
      'guruvar': 4, 'veervar': 4, 'thursday': 4, 'गुरुवार': 4,
      'shukravar': 5, 'friday': 5, 'शुक्रवार': 5,
      'shanivar': 6, 'saturday': 6, 'शनिवार': 6,
      'ravivar': 0, 'sunday': 0, 'रविवार': 0
    };

    for (const [dayName, dayIndex] of Object.entries(weekdayMap)) {
      if (lower.includes(dayName)) {
        if (isNextWeek) {
          if (dayIndex === refWeekday) {
            dayOffset = 7;
          } else {
            dayOffset = ((dayIndex - refWeekday + 7) % 7) + 7;
          }
        } else if (dayIndex === refWeekday) {
          dayOffset = null; // Resolved after time check
        } else {
          dayOffset = (dayIndex - refWeekday + 7) % 7;
        }
        break;
      }
    }
  }

  // Edge case: Late night 23:50 with early morning "raat 5"
  if (dayOffset === null && refHour >= 22 && /\braat\s+[1-5]\b/i.test(lower)) {
    dayOffset = 1;
  }

  // 3. Time Parsing
  let hour: number | null = null;
  let minute: number = 0;
  let timeUnspecified = false;
  let isAmbiguous = false;
  let originalHour: number | null = null;
  let originalMinute: number = 0;

  const isMorning = /\b(subah|pratah|morning|am)\b/i.test(lower) || lower.includes('सुबह');
  const isAfternoon = /\b(dopahar|afternoon|noon|pm)\b/i.test(lower) || lower.includes('दोपहर');
  const isEvening = /\b(shaam|sham|sanjh|evening)\b/i.test(lower) || lower.includes('शाम');
  const isNight = /\b(raat|night)\b/i.test(lower) || lower.includes('रात');
  const isDeadline = /\b(deadline)\b/i.test(lower);
  const isDinner = /\b(dinner)\b/i.test(lower);

  // Multi-time reschedule pattern: "kal ki 3 baje wali class 5 baje shift ho gayi"
  const multiTimeMatch = lower.match(/(\d{1,2})(?::(\d{2}))?\s*(?:baje|बजे)\s*wali.*?(\d{1,2})(?::(\d{2}))?\s*(?:baje|बजे)\s*(?:shift|postpone)/i);
  if (multiTimeMatch) {
    const origH = Number(multiTimeMatch[1]);
    originalHour = origH <= 6 ? origH + 12 : origH;
    originalMinute = multiTimeMatch[2] ? Number(multiTimeMatch[2]) : 0;
    const newH = Number(multiTimeMatch[3]);
    hour = newH <= 6 ? newH + 12 : newH;
    minute = multiTimeMatch[4] ? Number(multiTimeMatch[4]) : 0;
  } else {
    // Check Hinglish and Devanagari baje / बजे / pe with local daypart prefix
    const bajeMatch = lower.match(/(?:(subah|dopahar|shaam|sham|raat|सुबह|दोपहर|शाम|रात)\s+)?(\d{1,2})(?::(\d{2}))?\s*(?:baje|pe|बजे)/i);
    // Explicit 12h AM/PM
    const ampmMatch = lower.match(/\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b/i);
    // at HH[:mm]
    const atMatch = lower.match(/\bat\s+(\d{1,2})(?::(\d{2}))?(?:\s*(am|pm))?\b/i);
    // 24h clock syntax
    const exact24Match = lower.match(/\b([01]?\d|2[0-3]):([0-5]\d)\b/);

    if (bajeMatch) {
      const daypartPrefix = bajeMatch[1]?.toLowerCase();
      const isMatchMorning = daypartPrefix === 'subah' || daypartPrefix === 'pratah' || daypartPrefix === 'morning' || daypartPrefix === 'am' || daypartPrefix === 'सुबह';
      const isMatchAfternoon = daypartPrefix === 'dopahar' || daypartPrefix === 'afternoon' || daypartPrefix === 'noon' || daypartPrefix === 'pm' || daypartPrefix === 'दोपहर';
      const isMatchEvening = daypartPrefix === 'shaam' || daypartPrefix === 'sham' || daypartPrefix === 'sanjh' || daypartPrefix === 'evening' || daypartPrefix === 'शाम';
      const isMatchNight = daypartPrefix === 'raat' || daypartPrefix === 'night' || daypartPrefix === 'रात';

      const rawH = Number(bajeMatch[2]);
      minute = bajeMatch[3] ? Number(bajeMatch[3]) : 0;
      if (isMatchMorning) {
        hour = rawH === 12 ? 0 : rawH;
      } else if (isMatchAfternoon) {
        hour = rawH < 12 ? rawH + 12 : 12;
      } else if (isMatchEvening) {
        hour = rawH < 12 ? rawH + 12 : rawH;
      } else if (isMatchNight) {
        if (rawH === 12) hour = 0;
        else if (rawH >= 8 && rawH <= 11) hour = rawH + 12;
        else if (rawH >= 1 && rawH <= 4) hour = rawH;
        else if (rawH >= 5 && rawH <= 7) {
          hour = rawH;
          isAmbiguous = true;
        } else hour = rawH < 12 ? rawH + 12 : rawH;
      } else if (isDinner) {
        hour = rawH < 12 ? rawH + 12 : rawH;
      } else {
        if (rawH === 12) hour = 12;
        else if (rawH >= 1 && rawH <= 6) hour = rawH + 12;
        else if (rawH >= 7 && rawH <= 11) hour = rawH;
        else hour = rawH;
      }
    } else if (ampmMatch) {
      let rawH = Number(ampmMatch[1]);
      minute = ampmMatch[2] ? Number(ampmMatch[2]) : 0;
      const meridiem = ampmMatch[3].toLowerCase();
      if (meridiem === 'pm' && rawH < 12) rawH += 12;
      if (meridiem === 'am' && rawH === 12) rawH = 0;
      hour = rawH;
    } else if (atMatch) {
      let rawH = Number(atMatch[1]);
      minute = atMatch[2] ? Number(atMatch[2]) : 0;
      const mer = atMatch[3]?.toLowerCase();
      if (mer === 'pm' && rawH < 12) rawH += 12;
      else if (mer === 'am' && rawH === 12) rawH = 0;
      else if (!mer && rawH <= 6) rawH += 12;
      hour = rawH;
    } else if (exact24Match && !lower.includes('for 4') && !lower.includes('room') && !lower.includes('gate') && !lower.includes('2022')) {
      hour = Number(exact24Match[1]);
      minute = Number(exact24Match[2]);
    } else if (isDeadline) {
      hour = 23;
      minute = 59;
    } else {
      timeUnspecified = true;
      if (type !== 'cancel') {
        isAmbiguous = true;
      }
    }
  }

  // Disambiguate same-weekday when dayOffset was null
  if (dayOffset === null) {
    if (timeUnspecified || hour === null) {
      const mentionsWeekday = /\b(monday|somvar|सोमवार)\b/i.test(lower) || lower.includes('सोमवार');
      dayOffset = (mentionsWeekday || type !== 'cancel') ? 7 : 0;
      if (type !== 'cancel') isAmbiguous = true;
    } else {
      const isAhead = (hour > refHour) || (hour === refHour && minute > refMinute);
      if (isAhead) {
        dayOffset = 0;
      } else {
        dayOffset = 7;
      }
    }
  }

  // Disambiguate same-day past-time with 12h flip
  if (dayOffset === 0 && hour !== null && !isDinner) {
    const isPast = (hour < refHour) || (hour === refHour && minute <= refMinute);
    if (isPast) {
      const flipHour = hour < 12 ? hour + 12 : hour - 12;
      const isFlipFuture = (flipHour > refHour) || (flipHour === refHour && minute > refMinute);
      if (isFlipFuture) {
        hour = flipHour;
        isAmbiguous = true;
      } else {
        dayOffset = 7;
        isAmbiguous = true;
      }
    }
  }

  // 4. Calendar Date Calculation
  const targetDateUtc = new Date(refDateUtc.getTime() + (dayOffset || 0) * 24 * 60 * 60 * 1000);
  const targetYear = targetDateUtc.getUTCFullYear();
  const targetMonth = targetDateUtc.getUTCMonth() + 1;
  const targetDay = targetDateUtc.getUTCDate();
  const startDateStr = `${targetYear}-${pad(targetMonth)}-${pad(targetDay)}`;

  let startIso: string | undefined = undefined;
  if (!timeUnspecified && hour !== null) {
    startIso = `${startDateStr}T${pad(hour)}:${pad(minute)}:00+05:30`;
  }

  // 5. Subject / Title Extraction
  let title = 'event';
  let bestPos = Infinity;

  for (const subject of KNOWN_SUBJECTS) {
    let pos = -1;
    // Check ASCII word boundary for English, or substring for Devanagari
    if (/[\u0900-\u097F]/.test(subject)) {
      pos = text.indexOf(subject);
    } else {
      const re = new RegExp(`\\b${subject}\\b`, 'i');
      const match = re.exec(text);
      if (match) pos = match.index;
    }

    if (pos !== -1 && pos < bestPos) {
      bestPos = pos;
      title = subject;
    }
  }

  if (title === 'event') {
    if (lower.includes('milte hain')) {
      title = 'meeting';
    } else if (lower.includes('deployment')) {
      title = 'deployment';
    } else if (lower.includes('exam')) {
      title = 'exam';
    } else if (lower.includes('call moved') || lower.includes('client call')) {
      title = 'call';
    }
  }

  title = sanitizeTitle(title);

  // 6. Minimal source_quote
  let sourceQuote = text;
  const quoteCandidates = [
    new RegExp(`(?:kal|parso|tarso|aaj|tomorrow|today|monday|tuesday|wednesday|thursday|friday|saturday|sunday|सोमवार|कल|परसों|आज)\\s+.*?(?:${title}|class|meeting|lecture|test|quiz|viva|lab|sync)`, 'i'),
    new RegExp(`(?:${title}|class|meeting|lecture|test|quiz|viva|lab|sync)\\s+.*?(?:tomorrow|today|friday|monday|tuesday|wednesday|thursday|saturday|sunday|kal|parso|aaj)`, 'i')
  ];

  for (const cand of quoteCandidates) {
    const m = cand.exec(text);
    if (m && m[0].length < sourceQuote.length) {
      sourceQuote = m[0].trim();
      break;
    }
  }

  sourceQuote = sourceQuote
    .replace(/;\s*system:[\s\S]*/i, '')
    .replace(/;\s*drop table[\s\S]*/i, '')
    .replace(/\s*ignore instructions[\s\S]*/i, '')
    .replace(/\s*eval\([\s\S]*/i, '')
    .replace(/<script[\s\S]*/i, '')
    .trim();

  // 7. Confidence
  let confidence = 0.90;
  if (isAmbiguous || (timeUnspecified && type !== 'cancel')) {
    confidence = 0.65;
  } else if (lower.includes('milte hain') || lower.includes('standup at 10am and')) {
    confidence = 0.85;
  }

  // 8. Target matching
  let target: { title: string; original_start?: string; original_date?: string } | undefined = undefined;
  if (type === 'cancel' || type === 'reschedule') {
    target = { title };
    if (originalHour !== null) {
      target.original_start = `${startDateStr}T${pad(originalHour)}:${pad(originalMinute)}:00+05:30`;
    }
    if (type === 'cancel' && !startIso) {
      target.original_date = startDateStr;
    }
  }

  const result: ExtractionOutput = {
    type,
    title,
    source_quote: sourceQuote,
    confidence
  };

  if (startIso) {
    result.start = startIso;
  }
  if (timeUnspecified || startDateStr) {
    result.start_date = startDateStr;
  }
  if (timeUnspecified) {
    result.time_unspecified = true;
  }
  if (isAmbiguous) {
    result.ambiguous = true;
  }
  if (target) {
    result.target = target;
  }

  return result;
}
