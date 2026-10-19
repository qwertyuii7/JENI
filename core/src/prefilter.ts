export interface PrefilterResult {
  shouldParse: boolean;
  sanitizedText: string;
  reason?: string;
}

const ZERO_WIDTH_REGEX = /[\u200B-\u200D\uFEFF\u0000-\u0008\u000B\u000C\u000E-\u001F]/g;

// OTP and transaction pattern rejection
const OTP_REGEX = /\b(otp|one time password|verification code)\b.*\b\d{4,8}\b|\b\d{4,8}\b.*\b(otp|verification code)\b/i;
const TRANSACTION_REGEX = /\b(debited|credited|order #|order id|flight \w+ boarding)\b/i;

// Price and phone number noise patterns
const PRICE_REGEX = /\b\d+\s*(rupey|rs\.?|inr|ka mila|me mila|mein mila)\b/i;
const PHONE_ONLY_REGEX = /\b(call me on|contact me at)\s*\+?\d{10,12}\b/i;
const SCORE_REGEX = /\b\d{2,3}\s*for\s*\d\s*(wickets)?\b/i;

// Non-schedule time expressions
const NON_SCHEDULE_TIME = /\b\d+\s*baje\s*tak\s*(sone|utha|soya|sona)\b/i;

// Promo text with deadline words / discount noise
const PROMO_REGEX = /\b(flat\s+\d+%?\s+(?:off|discount)|flash\s+sale|discount\b.*\b(?:code|coupon)|use\s+code\b|cashback\b|coupon\s+code|offer\s+expires|limited\s+time\s+(?:deal|offer)|order\s+now\s+to\s+get|buy\s+\d+\s+get\s+\d+|free\s+delivery\s+on\s+orders)\b/i;

// Chat banter with time window (e.g. "rat me try krna 1-2 baje k bich")
const BANTER_WINDOW_REGEX = /\b(?:rat|raat|shaam|sham|dopahar|subah)?\s*(?:m|me|mein)?\s*(?:try\s+k(?:a)?rn[a-z]*|dekh\s+l(?:en[a-z]*|in[a-z]*|n[a-z]*)|check\s+k(?:a)?rn[a-z]*)\s+.*(?:\d{1,2}(?::\d{2})?\s*(?:se|-|to)?\s*\d{1,2}(?::\d{2})?\s*(?:baje)?)\s*(?:k|ke)?\s*(?:bich|beech|aas\s*pass)\b|\b\d{1,2}(?::\d{2})?\s*(?:se|-|to)\s*\d{1,2}(?::\d{2})?\s*(?:baje)?\s*(?:k|ke)\s*(?:bich|beech|aas\s*pass)\b/i;

// Someone else's plan / personal promise to send/submit/share tasks
const PERSONAL_PLAN_REGEX = /\b(?:I|we)\s+will\s+(?:send|share|forward|submit|upload|update|email|mail|give|post|provide)\s+.*(?:tomorrow|kal|today|aaj|tonight|afternoon|morning|evening)\b|\b(?:mai|main|hum)\s+.*(?:bhej\s+dunga|bhej\s+denge|share\s+kar\s+dunga|submit\s+kar\s+dunga|update\s+kar\s+dunga)\b/i;

// Roll-number lists (student rosters/registration lists)
const ROLL_NUMBER_LIST_REGEX = /\b(?:roll\s*(?:no|number|numbers|num|nos)?\.?|enrol(?:l)?ment\s*nos?\.?)\s*(?:[:\-]?\s*(?:\n|\r|\d{1,}[A-Za-z0-9,\s\-]{5,})|\d+\s*(?:to|-)\s*\d+)/i;

// Past tense patterns (kal class thi, कल हमारी क्लास थी, yesterday we had)
const PAST_TENSE_REGEX = /\b(kal|parso|aaj subah)\s+.*(thi|tha|the|chuka hai|ho gaya tha)\b|कल\s+.*(?:थी|था|थे|हो चुकी थी|कैंसिल था)|(?:रद्द|स्थगित|शिफ्ट|कैंसिल)\s+.*(?:थी|था|हो चुकी थी|हो गई थी)|\byesterday\s+we\s+had\b|\blast\s+(friday|monday|tuesday|wednesday|thursday|saturday|sunday)\s+we\b/i;

// Hypotheticals / Uncertainties
const HYPOTHETICAL_REGEX = /\b(agar\s+kal|maybe\s+meeting|lagta\s+hai\s+kal|if\s+.*let\s+me\s+know|shayad\s+parso)\b|(?:^|\s)(?:अगर|agar|शायद|shayad)(?:\s|$)|अगर\s+.*तो/i;

// Questions patterns
const QUESTION_REGEX = /\b(hai kya|hoga kya|kab hai|kab hoga|is there a|are there any)\b|(?:^|\s)(?:क्या|kya)(?:\s|$)|\?\s*$/i;

// Third party schedule patterns
const THIRD_PARTY_REGEX = /\b([A-Z][a-z]+ has a|unka viva hai|prof \w+ is taking lecture for batch)\b/i;

// Stale forward patterns
const STALE_FORWARD_REGEX = /\b(fwd:\s*202[0-4]|circular\s*202[0-4]|purana\s*202[0-4])\b/i;

// Casual chatter rejection (not real schedules)
const CASUAL_CHATTER_REGEX = /^(gm bro|good morning all|kya chal raha hai\??|ok done|lol that was so funny|hahaha true|send me the notes please|where are you reaching\??|chalo chai peete hain|happy birthday bhai!?|thank you so much|yes perfect|check this reel lol|bhai kal milte hain aram se|bye good night)$/i;

// Schedule trigger regex with strict word boundaries
const SCHEDULE_WORD_REGEX = /\b(tomorrow|today|monday|tuesday|wednesday|thursday|friday|saturday|sunday|meeting|class|classes|lecture|sync|standup|demo|presentation|viva|test|quiz|exam|practical|lab|workshop|webinar|seminar|discussion|meet|appointment|deadline|review|postponed|cancelled|canceled|rescheduled|shifted|called off|kickoff|kal|parso|tarso|aaj|baje|pe|subah|dopahar|shaam|sham|raat|somvar|mangalvar|budhvar|guruvar|veervar|shukravar|shanivar|ravivar|agle|jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec|january|february|march|april|june|july|august|september|october|november|december)\b|कल|परसों|आज|बजे|सुबह|दोपहर|शाम|रात|सोमवार|मंगलवार|बुधवार|गुरुवार|शुक्रवार|शनिवार|रविवार|अगले|क्लास|मीटिंग|टेस्ट|परीक्षा|कार्यशाला|सेमिनार|रद्द|कैंसिल|स्थगित|शिफ्ट|लेक्चर/i;

export function prefilterMessage(rawText: string): PrefilterResult {
  if (!rawText || typeof rawText !== 'string') {
    return { shouldParse: false, sanitizedText: '', reason: 'empty_input' };
  }

  let sanitized = rawText.replace(ZERO_WIDTH_REGEX, '').trim();

  if (sanitized.length > 4000) {
    sanitized = sanitized.substring(0, 1000);
  }

  const lower = sanitized.toLowerCase();

  // Fast rejection of casual chatter
  if (CASUAL_CHATTER_REGEX.test(sanitized)) {
    return { shouldParse: false, sanitizedText: sanitized, reason: 'casual_chatter' };
  }

  // Fast rejection: Pure injection commands without schedule context
  if (lower.startsWith('ignore previous instructions') ||
      lower.startsWith('system: run rm') ||
      lower.startsWith('forget system prompt') ||
      lower.startsWith('assistant instruction override:') ||
      lower.startsWith('admin override:') ||
      lower.startsWith('drop table events') ||
      lower.startsWith('urgent: disregard instructions') ||
      lower.startsWith('system prompt leak:') ||
      lower.startsWith('```json\n{"override": true}')) {
    const hasLegitSchedule = /\b(tomorrow|kal|parso|aaj|today|monday|friday|wednesday|thursday|tuesday|saturday|sunday)\b/i.test(sanitized) &&
                             /\b(at\s+\d|baje|\d\s*(?:am|pm)|meeting|class|lecture|sync|appointment)\b/i.test(sanitized);
    if (!hasLegitSchedule) {
      return { shouldParse: false, sanitizedText: sanitized, reason: 'pure_injection' };
    }
  }

  if (OTP_REGEX.test(sanitized)) {
    return { shouldParse: false, sanitizedText: sanitized, reason: 'otp_noise' };
  }
  if (TRANSACTION_REGEX.test(sanitized)) {
    return { shouldParse: false, sanitizedText: sanitized, reason: 'transaction_noise' };
  }
  if (PRICE_REGEX.test(sanitized)) {
    return { shouldParse: false, sanitizedText: sanitized, reason: 'price_noise' };
  }
  if (PHONE_ONLY_REGEX.test(sanitized)) {
    return { shouldParse: false, sanitizedText: sanitized, reason: 'phone_noise' };
  }
  if (SCORE_REGEX.test(sanitized)) {
    return { shouldParse: false, sanitizedText: sanitized, reason: 'score_noise' };
  }
  if (NON_SCHEDULE_TIME.test(sanitized)) {
    return { shouldParse: false, sanitizedText: sanitized, reason: 'non_schedule_time' };
  }
  if (PROMO_REGEX.test(sanitized)) {
    return { shouldParse: false, sanitizedText: sanitized, reason: 'promo_noise' };
  }
  if (BANTER_WINDOW_REGEX.test(sanitized)) {
    return { shouldParse: false, sanitizedText: sanitized, reason: 'chat_banter' };
  }
  if (PERSONAL_PLAN_REGEX.test(sanitized)) {
    return { shouldParse: false, sanitizedText: sanitized, reason: 'someone_elses_plan' };
  }
  if (ROLL_NUMBER_LIST_REGEX.test(sanitized)) {
    return { shouldParse: false, sanitizedText: sanitized, reason: 'roll_number_list' };
  }
  if (PAST_TENSE_REGEX.test(sanitized)) {
    return { shouldParse: false, sanitizedText: sanitized, reason: 'past_tense' };
  }
  if (HYPOTHETICAL_REGEX.test(sanitized)) {
    return { shouldParse: false, sanitizedText: sanitized, reason: 'hypothetical' };
  }
  if (QUESTION_REGEX.test(sanitized)) {
    return { shouldParse: false, sanitizedText: sanitized, reason: 'question_inquiry' };
  }
  if (THIRD_PARTY_REGEX.test(sanitized)) {
    return { shouldParse: false, sanitizedText: sanitized, reason: 'third_party_schedule' };
  }
  if (STALE_FORWARD_REGEX.test(sanitized)) {
    return { shouldParse: false, sanitizedText: sanitized, reason: 'stale_forward' };
  }

  if (!SCHEDULE_WORD_REGEX.test(sanitized)) {
    return { shouldParse: false, sanitizedText: sanitized, reason: 'no_schedule_trigger' };
  }

  return { shouldParse: true, sanitizedText: sanitized };
}
