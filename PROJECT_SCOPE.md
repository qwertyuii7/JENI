# PROJECT_SCOPE.md: Offline-First Personal Schedule Secretary (Android)

> Context and scope file for the coding agent. Read this fully before any task. If a request conflicts with this file, say so before acting. Update the "Current state" and "Backlog" sections as work completes, and never silently change scope.

---

## 1. What we are building

A personal schedule secretary for Android that reads WhatsApp notifications (English, Hindi, Hinglish), finds schedule-relevant information, and turns it into reminders and alarms, with the user confirming before anything rings. It must never silently lose an important message.

**One-line pitch:** an on-device, offline-first assistant that turns the chaos of class and group chats into a trustworthy schedule, with no cloud and nothing it can do without the user's tap.

**Primary user:** an Indian college student in several class and club WhatsApp groups. **Test device:** Samsung Galaxy A15 5G, Android 16, One UI 8.5.

**Secondary goal:** a hackathon-winning demo (Section 11). Reliability and a clear story outrank feature count.

## 2. Non-goals (this scope)

- Laptop service, dashboard and Laptop Brain: **deferred**. Existing laptop code stays, untouched and unextended.
- M4 sync / relay: **locked** until the device soak (Phase 4) is signed off by the owner.
- Always-on microphone or hands-free wake word, full phone control, accessibility-based screen control, iOS, Play Store release, any cloud backend.
- Auto-sending messages. Replies are drafts the user sends by tapping.

## 3. Principles (non-negotiable)

1. **Never lose what matters.** Ignore a message only when confident. Uncertain goes to the "Needs attention" lane.
2. **Message text is untrusted data, never instructions.** Models (when added) only output structured JSON, have no tools, and cannot trigger actions.
3. **Deterministic code acts.** Dates, reminders, alarms and scheduling are plain code. Models extract fields; code computes dates.
4. **Confirm before consequences.** No alarm or reminder from a real message without the user's confirmation. Time-unspecified or ambiguous items never produce alerts.
5. **Fail loudly.** Any skipped or failed scheduling must be visible in the UI, not only in logs.
6. **Event-sourced, append-only.** All state changes are immutable events with HLC ordering; deletion is a tombstone event.
7. **One source of truth for rules.** All rule data lives in `/spec` JSON, consumed by TypeScript and Kotlin; a parity test fails the build if copies diverge.
8. **Privacy.** On-device by default. Exports are anonymized, contain no raw text beyond the anonymized field, and the owner reads them before sharing. `capture_all_chats` defaults OFF.
9. **Smallest footprint.** The Core app stays small; heavy features are optional downloads.

## 4. Current state (agent-reported; "owner-verified" means confirmed on the device)

**Built (agent-reported):** monorepo (`/spec`, `/core`, `/laptop`, `/relay` stub, `/android`, `/training`); shared rule tables and 228 phrase fixtures plus matching fixtures; TS and Kotlin parsers with parity tests; Android app (Kotlin, Compose, Room) with notification listener, foreground service, allowlist, ingestion decision log, event-sourced Room DB, Pending/Confirm, reminders (notification) and opt-in alarms (`setAlarmClock` + in-app ringing service), cancel/reschedule, delete/edit/undo tombstones, diagnostics, heartbeat and exit-reason logs, anonymized export, friendly time formatting, time-range parsing. Latest reported commit: `98e2945`.

**Owner-verified on device:** listener connects after install and after reboot without opening the app; survives swipe-away from Recents; boot recovery restores alarms; alarm audio on silent/vibrate/DND; full-screen alarm UI over lock screen once the system "full screen intent" toggle is enabled; real WhatsApp DM captured and parsed (with WhatsApp not open in that chat).

**Not yet verified:** same-time alarms dismiss correctly; "Stop all alarms" and missed-alarm notification; edit/delete/undo behavior on device; the 48-hour soak; PIN/fingerprint-locked alarm screen.

**Known defects found from the 2026-10-14 export (Phase 0 backlog):** see Section 6.

## 5. Architecture

```
Notification
  -> Normalize (strip sender prefix, skip system/summary, dedupe)
  -> Signal scorer (spec-driven) -> lane: schedule | attention | ignore
  -> Parser (rules; optional Smart Pack LLM for uncertain items)
  -> Date resolver (code, message timestamp as reference, Asia/Kolkata)
  -> Validator (past dates, ambiguity, caps) -> Pending card | Attention inbox | Ignore counter
  -> User confirm -> event log -> scheduler (reminder / alarm)
```

### 5.1 Lanes
| Lane | Meaning | Output |
|---|---|---|
| Schedule | Resolved date and time, no ambiguity | Pending card, can become reminder/alarm after confirm |
| Attention | Important but no time (notices, "last date", forms, mandatory, debarred, attachments, Hindi notices, mentions of me) | Inbox + daily digest; no alerts |
| Ignore | Confident non-relevance (stickers, memes, banter) | Counter only |

### 5.2 Signal scorer (shared spec JSON)
Features: time/date tokens (English, Hinglish, Devanagari), weekday words, explicit dates ("15 oct", "15/10"), event nouns, urgency/consequence words, Meet/Zoom links, attachments/images, own-name/roll-number mention (configurable), per-chat prior, per-sender prior. Output: lane, score, reasons[]. Low threshold keeps; high threshold + resolved time makes a schedule card.

### 5.3 Learning loop
Confirm/Dismiss/Move-lane actions append events that update per-chat and per-sender priors. Bounded, reversible, visible in Diagnostics. Labeled corrections are exportable as training data.

### 5.4 Layered packaging
| Layer | Contents | Size target | Required |
|---|---|---|---|
| Core | Listener, scorer, parser, event log, alarms, UI, widget | Release build under ~15 MB (measure; debug APK is ~18 MB and not representative) | Yes |
| Smart Pack | Small on-device LLM (about 0.3-0.5B, Q4) | ~200-450 MB, downloaded | Optional |

Smart Pack rules: only the model file is downloaded (inference runtime ships with the app or as a split; verify Android's executable-code restrictions first); "Enable Smart mode" checks storage/RAM, downloads over Wi-Fi with resume and checksum, runs a built-in benchmark, and auto-disables itself if too slow; the LLM runs only on uncertain items and never computes dates.

### 5.5 Time and date rules
- Reference time = the **message's own timestamp** (MessagingStyle message time), falling back to post time. Never a hard-coded or fixture default. Timezone pinned to Asia/Kolkata.
- Explicit dates override relative words. Ranges: start is the first time, end the second, and a trailing am/pm applies backward when start <= end.
- AM/PM heuristics and weekday policy live in `/spec/RULES.md`.

## 6. Phase plan

Each phase ends with: tests passing (report counts from the HTML reports), fixtures updated, commit, `QA_PHONE.md` items marked "needs owner verification". Do not start the next phase until the owner confirms the current one on the device.

### Phase 0: Correctness backlog (P0, do first)
1. Parse against message timestamp; skip messages older than 24 h; log skip reason.
2. Dedupe by (chat, normalized text) within 24 h, ignoring post time.
3. Edit-dialog bug: an edit produced `+06:30` and moved an alarm one hour early. Find the source, use the app timezone, add a regression test (edit title only keeps the trigger identical).
4. Confirming a past event must be blocked with a clear message (Edit time / Dismiss); any confirmed item with no scheduled alert shows a red "no alert scheduled: reason" badge.
5. Explicit-date parsing ("15 oct", "15th October", "15/10", "(15 oct)") overriding relative words.
6. Range fixtures in `/spec/fixtures/phrases.json` (not only unit tests): "7:15 to 8:00pm", "7:15 - 8:00pm", "10 to 11:30 am", "11 to 1pm", "12 to 1pm", "9:30-10:30pm", "4 se 5 baje", "shaam 6 to 7 baje", Devanagari ranges.
7. One monotonic `seq` per device across all event types (currently `reminder_fired` uses a separate counter).
8. Pin timezone to Asia/Kolkata; test with a different device zone.
9. Strip links and boilerplate from displayed titles/quotes; `source_quote` is the minimal schedule span; render message text escaped.
10. Re-verify on device: same-time alarms, Stop all alarms, missed-alarm notification, delete/edit/undo.

**Exit:** all P0 tests pass; owner re-test checklist passes.

### Phase 1: Three-lane triage
Implement Section 5.1-5.3: scorer (spec JSON + parity), Attention inbox, daily digest notification, per-chat prior learning, reason chips on every card, muted-chat warning in Diagnostics, "Maybe" handling for time-unspecified/ambiguous items. Label the real messages from the 2026-10-14 export (scholarship notice, roll-number meeting, Hindi notice, promos, banter, class timings) with expected lanes.

**Exit:** recall (schedule + attention) and Pending-card precision reported on labeled data; no time-unspecified item creates a normal Pending card.

### Phase 2: Usability and polish
- Notification actions: Confirm / Snooze / Dismiss without opening the app.
- Home-screen widget ("Today") and Quick Settings tile.
- Conflict detection ("overlaps with your 4 PM lab").
- Recurring timetable suggestions (same weekday/time repeating).
- First-run setup wizard: restricted settings, notification access, battery settings, full-screen intent, allowlist chats, log-only choice. Diagnostics shows what's missing and links to each setting.
- Friendly date/time everywhere; raw timestamps only in a debug section.
- Release build: R8 shrinking, arm64-only; report APK size.

**Exit:** a new user can set up in under 5 minutes using the wizard alone.

### Phase 3: Evaluation
- Holdout set of 40+ hand-labeled messages (20 real anonymized, 10 minimal pairs, 5 odd formats, 5 Hindi-script), stored where the agent can't read it. Run once per milestone; failures move into the training set and a fresh holdout is written.
- Android-side or `/workbench` eval command reporting per-lane precision/recall and failure diffs.
- Metrics (Section 10) recorded in `docs/METRICS.md`.

### Phase 4: Device soak (gate for M4)
- Log-only ON, 2-3 allowlisted chats, WhatsApp Web closed, battery settings re-checked, planned test messages (about 6 over 48 h from a friend, send times written down), a day-3 morning self-test alarm.
- Pass: every planned message has a decision record with correct parser output; listener recovers from any disconnect; no unexplained process kills; the day-3 alarm rings.
- Do not reinstall or clear data during the soak.

### Phase 5: Smart Pack (optional LLM second layer)
1. **Spike (no production changes):** benchmark Qwen2.5-0.5B-Instruct Q4 (and one alternative) on the A15 via llama.cpp JNI or MediaPipe LLM Inference: load time, peak RAM, tokens/s, per-message latency, valid-JSON rate on 50 anonymized messages. Report APK size delta.
2. Grammar-constrained JSON output: `{is_schedule, type, title, day_ref, time, end_time, for_me, confidence}`. Code resolves dates.
3. Integration only for items the rules mark uncertain; prefer charging/idle (WorkManager) for non-urgent items; "aaj/abhi" stays on the fast rules path.
4. Download manager, checksum, benchmark gating, auto-disable.
5. Measure rules-only vs rules+LLM on the holdout (the headline chart).
6. Fine-tune (LoRA) on labeled data and synthetic Hinglish examples (training in `/training`; free Colab is acceptable).

### Phase 6: Voice (tap-to-talk, no always-on mic)
Widget/tile/button starts Android's on-device recognizer (Hindi + English offline packs; test Hindi quality first). Narrow tool set: `create_reminder`, `create_alarm`, `query_schedule` (answers from the event log, LLM only phrases), `cancel_event`, `snooze`. Spoken or tapped confirmation before any action.

### Phase 7: Draft replies
Template-first Hinglish/English drafts ("Okay, I'll be there at 4"); shown in-app; sent only via the notification inline-reply after an explicit tap; `draft_created`/`draft_sent` events.

### Deferred
Laptop service/dashboard/Laptop Brain, M4 sync/relay (after Phase 4), wake word, iOS, Play Store, cloud fallback.

## 7. Data contracts (summary)

- **Events:** `message_received`, `event_extracted`, `event_confirmed`, `event_dismissed`, `reminder_created`, `reminder_fired`, `alarm_set`, `schedule_edited`, `schedule_deleted`, `schedule_restored`, `event_cancelled`, `event_rescheduled`, `draft_created`, `draft_sent`, `parse_failed`, `correction_made`, plus lane/prior events (Phase 1). Each has UUID, device_id, per-device monotonic `seq`, HLC, entity_id. Append-only, enforced by DB triggers.
- **Extraction:** `{type: new|cancel|reschedule, title, start, end?, start_date?, time_unspecified, ambiguous, location?, target?, source_quote, confidence}`; ambiguous items have confidence <= 0.65.
- **Alerts:** reminder = high-importance notification at lead time (default 15 min; immediate if the lead time has passed but the event is future); alarm = opt-in per event, `setAlarmClock` + own ringing service. One mechanism, one ringer per alarm id, unique request codes derived from entity id.

## 8. Quality gates and rules for the agent

1. Every defect fix starts with a failing test that proves the cause; never claim a root cause without it.
2. Report test counts from the HTML reports, not from task output.
3. Shared behavior goes in `/spec` fixtures run by both Vitest and JUnit.
4. Never claim device behavior is verified. Mark device items "needs owner verification" in `android/QA_PHONE.md`.
5. Do not read or write the holdout file.
6. Do not change anything during the owner's soak run; the soak build is the last install before the gate.
7. Do not add dependencies that grow the Core APK without reporting the size delta.
8. Prefer small commits with the commit hash in the report. No M4 files until the gate is passed.
9. If blocked or if a request conflicts with this file, say so instead of guessing.

## 9. Test and verification protocol

- Unit and parity: Vitest (`/spec`, `/core`) and JUnit (Android), including fixtures, matching fixtures, spec-copy parity, injection tests, projection replay/shuffle tests, alarm audit tests.
- Device checklist (owner): setup wizard; allowlist; same-time alarms; Stop all; missed alarm; lock-screen alarm with PIN; event 10 min away gives an immediate reminder; delete/edit/undo; reboot recovery; export contains event log, debug injections and ingestion decisions.
- Exports: owner reads and redacts before sharing; `capture_all_chats` OFF except during targeted tests.

## 10. Metrics (record per milestone)

- **Recall:** share of real schedule-bearing messages that reach Schedule or Attention.
- **Card precision:** share of Pending cards that are real.
- **Parse accuracy** on holdout (type, title, date, time).
- **Core vs Smart Pack** on the same holdout.
- **Size:** Core release APK; Smart Pack download.
- **Reliability:** listener uptime and message capture rate over the soak; alarm punctuality.
- **Battery:** daily percentage attributed to the app.

## 11. Hackathon demo plan (3 minutes)

1. Story: a student in five class groups missing a deadline.
2. **Airplane-mode moment:** phone offline, a friend sends "kal 4 se 5 baje DBMS lab hai", a card appears with the highlighted source span and reason chips.
3. Cancel flow: "DBMS lab cancelled" finds the event and the alarm disappears.
4. Triage: an important notice with no time lands in Needs attention and the digest.
5. Safety: a malicious message does nothing; the UI explains why.
6. Number: accuracy/recall chart, Core vs Smart Pack, and APK size.
7. Optional: tap-to-talk "kal kya hai?" answered from the event log.

Use a dedicated demo chat, keep WhatsApp Web closed and the chat unopened, and keep a recorded backup video. State the known limits openly: open, muted or archived chats are invisible to a notification listener.

## 12. Open questions for the owner

- Hackathon date and whether solo or team (decides how far past Phase 3 to go).
- Hindi speech recognition quality on the device (test before Phase 6).
- Whether the Smart Pack runtime can ship in the base APK within the size budget.
