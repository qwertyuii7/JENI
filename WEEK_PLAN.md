# WEEK_PLAN.md: 7-Day Build, Test and Demo Plan

> Companion to `PROJECT_SCOPE.md` (scope, principles, contracts). If the two conflict, stop and ask the owner. Assumes a 7-day window. **Hackathon date and team size are not yet known.** If the window is shorter, apply the cut order in Section 2.

## 0. How the agent must work (applies every day)

1. Work **only** on the current day's tasks. Do not start later days early.
2. One branch per task group (`day1-stabilize`, `day2-triage`, ...). Merge to `main` only when the day's gate passes. Tag the end of each day (`day1-rc`, ...).
3. **Failing test first** for every defect; no root-cause claims without a test.
4. Pure logic goes in `domain` (JVM-testable, no `android.*` imports). Android code stays at the edges.
5. Shared behavior goes in `/spec` fixtures run by Vitest and JUnit.
6. Report format at the end of each task: files changed, test counts from the **HTML reports**, commit hash, APK size, anything needing owner verification. Never claim device behavior is verified.
7. Never read or write the holdout file. Never change the build during a soak run unless told.
8. Maintain `STATUS.md` (done / in progress / blocked / known issues) after every task.
9. If behind schedule, apply the cut order; do not compress testing.

## 1. Definition of done (Day 7)

- Core app installs on a fresh phone and reaches all-green via the setup wizard in under 5 minutes.
- Real WhatsApp messages become: a Pending card (schedule), an Inbox item (needs attention) or nothing (ignore), with visible reasons.
- Reminders and alarms fire reliably, including after reboot, on lock screen, in DND, with cancel/reschedule/delete/undo working.
- Developer mode exposes a live decision trace, health dashboard, test lab, metrics and redacted export.
- Measured numbers recorded in `docs/METRICS.md`: recall, card precision, holdout accuracy, APK size, alarm punctuality, soak results.
- A scripted 3-minute demo runs three times in a row, with a recorded backup video.

## 2. Scope tiers and cut order

| Tier | Items |
|---|---|
| **Must** | Phase 0 fixes; three-lane triage (schedule / needs attention / ignore); customer UI redesign; setup wizard; notification actions; developer mode; evaluation + holdout; two soaks; demo mode; release build |
| **Should** | Today widget; conflict detection; daily digest; per-chat learning (priors); tap-to-talk voice with rule-based intents |
| **Stretch** | Smart Pack (on-device LLM) spike and integration; reply drafts; recurring timetable suggestions |
| **Cut** | Laptop work, sync/relay (M4), wake word, iOS, Play Store, cloud fallback |

**Cut order if behind (cut first to last):** reply drafts, recurring suggestions, Smart Pack, voice, widget, conflict detection, digest. Never cut: testing gates, setup wizard, developer mode trace, evaluation numbers, soaks.

## 3. Target architecture (build step by step, no big-bang refactor)

```
android/app/src/main/java/.../
  domain/      pure Kotlin: Parser, SignalScorer, LaneClassifier, DateResolver, Validator,
               Event models, ProjectionEngine, Matching. No android.* imports.
  data/        Room DB, event store (append-only), repositories, spec asset loader
  ingestion/   NotificationListenerService, WhatsAppNotificationHandler, dedupe, decision log
  alerts/      AlarmScheduler, AlarmRingingService, reminder notifications, notification actions
  ui/
    design/    tokens, typography, shared components (Card, StatusPill, Chip, EmptyState)
    customer/  Today, Inbox, Chats, Settings, Onboarding
    dev/       Pipeline trace, Health, Alarm audit, Test lab, Metrics, Export (hidden)
  voice/       (Should) speech capture, intent parser, query answering
  widgets/     (Should) Today widget, Quick Settings tile
```

**Dependency rule:** `ui -> domain, data, alerts`; `ingestion -> domain, data`; `alerts -> domain, data`; `domain -> nothing`. Enforce with a unit test that fails if any `domain` file imports `android.*` or any layer imports upward. Move to Gradle modules only if it costs under half a day.

**Pipeline stages (each emits a trace record, used by the dev-mode viewer):**
`received -> normalized -> deduped -> allowlist check -> signals/score -> lane -> parse -> resolve date -> validate -> card/inbox/ignore`

## 4. UI/UX plan

### 4.1 Principles
1. A student should understand every card in under 3 seconds.
2. Show state and action separately (a status pill says what *is*; a switch or button says what *happens*).
3. Never show raw ISO timestamps to customers; show "Today - 7:15 PM to 8:00 PM".
4. Nothing important is silent: skipped alerts, missing permissions and muted chats are visible warnings with a one-tap fix.
5. Every automated decision shows a reason.
6. Mistakes are cheap: Undo everywhere, Confirm/Dismiss from the notification.

### 4.2 Design system (Day 4)
Tokens: color roles (background, surface, primary, success, warning, danger, muted), light and dark themes, type scale, 8dp spacing, 16dp card radius, 48dp touch targets, font-scale safe. Components: `EventCard`, `StatusPill`, `ReasonChip`, `SourceQuote` (highlights the matched span, collapses long text, strips links), `EmptyState`, `WarningBanner`, `SectionHeader`, `ConfirmDialog`, `UndoSnackbar`.

### 4.3 Customer mode (bottom navigation, 4 tabs)

| Tab | Content |
|---|---|
| **Today** | Timeline of confirmed events (Today / Tomorrow / This week), next-alert chip, conflict markers, delete/edit/undo. Empty state explains what the app watches |
| **Inbox** | Segments: *Suggestions* (schedule cards awaiting Confirm) and *Needs attention* (important items without a time). Reason chips; Confirm / Edit / Dismiss; swipe actions |
| **Chats** | Seen chats with a StatusPill (Allowed / Blocked) plus a separate Switch; muted-chat warning; per-chat stats |
| **Settings** | Alert preferences (reminder lead time, alarm default, snooze), quiet hours, privacy (capture-all OFF), setup checklist, "Developer mode" entry (hidden unlock) |

**Suggestion card anatomy:** title; friendly time and range; source quote with highlighted span; reason chips ("time found", "class group", "mentions you"); confidence indicator; *Alarm* toggle (default off, reminder only); actions Confirm / Edit / Dismiss. Time-unspecified or ambiguous items never appear as normal suggestions; they appear in *Needs attention* with an "Add time" action.

**Onboarding wizard (first run):** one screen per step with live status and a button that opens the exact Settings page: notification permission; notification access (with the restricted-settings hint for sideloaded installs); battery Unrestricted and Never auto sleeping; full-screen intent; alarms and reminders; choose chats to watch; log-only vs live; finish with a built-in test ("send yourself a message") showing the pipeline working.

**Notification design:** reminder notification with Snooze / Done; suggestion notification with Confirm / Dismiss (no need to open the app); digest notification; "Missed alarm" notification; alarm full-screen UI lists all active alarms with per-alarm Dismiss and Dismiss all.

**Widget and tile (Should):** Today widget (next 3 events); Quick Settings tile for tap-to-talk (when voice exists).

### 4.4 Developer mode (hidden: tap the version 7 times)

Adds a **Dev** section with full diagnostic access:

| Screen | Purpose |
|---|---|
| **Pipeline trace** | Live list of notifications; open one to see every stage (normalized text, dedupe key, allowlist result, signal features and score, lane, parser output, date resolution with reference time used, validator reasons). Search and filter by lane/chat |
| **Replay lab** | Paste or pick a message, set reference time and flags, run through the real pipeline, see the full trace. Save as a fixture candidate |
| **Health** | Listener state and last notification, foreground service, battery whitelist, restricted settings, notification permission, full-screen intent, exact-alarm capability, next alarm clock (reminder vs alarm), scheduled count, last scheduling error, boot-recovery time, process exit reasons |
| **Alarm audit** | Every scheduling attempt (entity, trigger, API, result, error), fire-time metrics, missed alarms |
| **Event log viewer** | Append-only events with type, entity, HLC, seq; filter and tombstone view |
| **Metrics** | Counts per lane/chat, confirm vs dismiss rates, card precision from user actions, parse failures, latency per stage, battery estimate |
| **Test lab** | Inject fake notifications, schedule test alarms (+N min), simulate boot recovery, "delete all test items" |
| **Export** | Anonymized export with a **redaction preview** before sharing |

Dev mode must never change customer behavior; it only reads, replays and exports (except explicit test-lab actions).

## 5. Test strategy and gates

**Layers:** (1) JVM unit and parity tests (domain, fixtures, matching, spec-copy parity, injection tests, projection replay/shuffle, architecture-boundary test); (2) Compose UI tests for key components where cheap; (3) device checklist (owner); (4) two soaks; (5) evaluation on labeled data and the holdout.

**Rule:** a day is complete only when its JVM tests pass, the agent's report lists HTML-report counts, and the owner has run that day's device checks.

**Device checklist IDs (owner runs; agent marks "needs owner verification"):**

| ID | Check |
|---|---|
| DC-01 | Listener reconnects after reboot without opening the app |
| DC-02 | Real DM (WhatsApp closed) becomes a suggestion with correct title and time |
| DC-03 | Range message ("7:15 to 8:00pm") gives start 19:15, end 20:00 |
| DC-04 | Confirming a past event is blocked with a clear message |
| DC-05 | Two same-time alarms both listed; each dismisses; Dismiss all works |
| DC-06 | Stop all alarms; 1-minute auto-stop produces a missed-alarm notification |
| DC-07 | Alarm over lock screen with real PIN/fingerprint, Dismiss reachable |
| DC-08 | Event 10 minutes away gives an immediate reminder |
| DC-09 | Edit (title only) keeps trigger identical; delete removes alarm; undo restores; reboot keeps deleted items gone |
| DC-10 | Blocked chat creates nothing |
| DC-11 | A notice with no time lands in Needs attention, never as a suggestion |
| DC-12 | Notification actions Confirm / Snooze / Dismiss work without opening the app |
| DC-13 | Widget shows today's events and updates after changes |
| DC-14 | Fresh install (uninstall first) reaches all-green via the wizard |
| DC-15 | Tap-to-talk: "kal kya hai?" answers from the event log |
| DC-16 | Dev-mode trace shows every stage for a real message |
| DC-17 | Airplane-mode flow works end to end |
| DC-18 | Injection message ("ignore instructions...") has no effect and the UI explains why |

**Evaluation:** label 100+ real anonymized messages by Day 2 (lane + fields). Holdout: 40+ hand-labeled messages kept where the agent cannot read them (20 real, 10 minimal pairs, 5 odd formats, 5 Hindi-script). Run the holdout once per milestone (Day 3, Day 6); failures move into the training fixtures and a fresh holdout is written. Metrics definitions are in `PROJECT_SCOPE.md` Section 10.

## 6. Day-by-day plan

Owner time: about 2-3 hours per day for device testing and labeling.

### Day 1: Stabilize (correctness before features)
**Agent (in order):**
1. Create `STATUS.md`; add the architecture-boundary test (domain has no `android.*`); move obvious pure logic into `domain/` without behavior changes.
2. Phase 0 fixes from `PROJECT_SCOPE.md` Section 6: message-timestamp reference (Asia/Kolkata pinned), dedupe by (chat, normalized text), edit `+06:30` bug, block confirming past events plus a "no alert scheduled" badge, explicit dates ("15 oct", "15/10"), range fixtures in `/spec`, single monotonic `seq`, minimal `source_quote` with links stripped.
3. Verify with tests (report counts).

**Owner (evening):** install over the old build; run DC-02, 03, 04, 05, 06, 07, 08, 09; then **start Soak 1** (log-only ON, 2-3 chats allowlisted, WhatsApp Web closed, battery settings re-checked, note the start time; friend sends about 6 planned messages over the next 24 h, send times written down).

**Gate:** all JVM tests pass; DC-02..09 pass; soak started. **Cut if behind:** nothing; this day is the foundation.

### Day 2: Triage engine (Soak 1 running, no installs)
**Agent:**
1. `domain`: `SignalScorer` (spec JSON: time/date tokens in English/Hinglish/Devanagari, weekday and explicit dates, event nouns, urgency/consequence words, Meet/Zoom links, attachments, own name/roll number, per-chat and per-sender prior hooks) and `LaneClassifier` (schedule / attention / ignore, with reasons[]). Ignore only when confident.
2. Fixtures: label the real messages from the 2026-10-14 export (scholarship notice, roll-number meeting, Hindi notice, promos, banter, class timings) with expected lanes; add minimal pairs and Hindi hard negatives.
3. Eval harness (JVM test or CLI): per-lane precision/recall, Pending-card precision, failure diff; write the baseline to `docs/METRICS.md`.
4. Trace record emitted at each pipeline stage (data model only; UI comes later).

**Owner:** label 100 real messages (use the export + a small labeling file the agent provides) and write the 40-message holdout; do **not** install anything.

**Gate:** all tests pass; baseline numbers recorded; holdout file exists outside the agent's reach.

### Day 3: Read Soak 1, install triage, Needs-attention lane
**Owner (morning):** after the 24 h, export; check listener uptime, captured vs planned messages, exit reasons, day-2/3 self-test alarm. Share results with Claude/the agent for fixes.
**Agent:**
1. Fix anything the soak found.
2. Wire lanes into ingestion: schedule -> suggestion; attention -> Inbox item; ignore -> counter. Time-unspecified/ambiguous items never create normal suggestions.
3. Events for lane actions: `confirmed`, `dismissed`, `moved_lane`; **priors** updated from Confirm/Dismiss (bounded, reversible, visible in Dev mode).
4. Daily digest notification (count of attention items and today's events).
5. Muted-chat warning in Diagnostics.

**Owner (afternoon):** install; run DC-10, DC-11; run the holdout once and record results in `docs/METRICS.md` (agent never sees the file).
**Gate:** DC-10/11 pass; recall and precision reported; fixes from holdout failures moved into fixtures.
**Cut if behind:** digest.

### Day 4: UX foundation (customer mode)
**Agent (three commits):**
1. Design system (Section 4.2) plus theme and component previews.
2. Customer shell: Today, Inbox (Suggestions / Needs attention), Chats (StatusPill + Switch), Settings; new event and suggestion cards (Section 4.3); friendly time everywhere; empty states; Undo patterns.
3. Onboarding wizard with live status checks and deep links to each Settings page; built-in "send yourself a test" step.

**Owner:** uninstall, fresh install, follow the wizard only (DC-14); use the app for a few hours as a real user and write down every confusing moment.
**Gate:** DC-14 passes; no raw ISO timestamps visible; Allow/Block state unambiguous.
**Cut if behind:** Quick Settings tile, dark/light polish.

### Day 5: Interaction layer and Developer mode
**Agent:**
1. Notification actions: Confirm / Dismiss on suggestions, Snooze / Done on reminders, handled in a receiver without opening the app (DC-12).
2. Developer mode (Section 4.4): hidden unlock; Pipeline trace; Replay lab; Health; Alarm audit; Event log viewer; Metrics; Test lab; Export with redaction preview.
3. Conflict detection ("overlaps with your 4 PM lab") on suggestion cards.
4. Today widget (Should).

**Owner:** run DC-12, 13, 16; test the Replay lab with real messages.
**Gate:** DC-12/16 pass; every pipeline stage visible for a real message.
**Cut if behind:** widget, conflict detection.

### Day 6: Voice, demo mode, release candidate
**Agent:**
1. **Voice (Should):** tap-to-talk button (and tile) using Android's speech recognizer, offline packs where available (**test Hindi quality first**, report accuracy on 10 phrases). Rule-based intents: create reminder, create alarm, "kal / aaj kya hai?", cancel event, snooze. Answers come from the event log, not a model. Spoken or tapped confirmation before any change.
2. **Demo mode:** a scripted scenario in Test lab (seeded chats and messages, one-tap reset), a "decision trace" presentation screen, and a metrics screen with the headline numbers.
3. **Release build:** R8 shrinking, arm64-only; record APK size in `docs/METRICS.md`.
4. **Smart Pack spike (Stretch, only if Day 5's gate passed before noon):** benchmark Qwen2.5-0.5B Q4 (llama.cpp JNI or MediaPipe LLM Inference) on the A15: load time, peak RAM, tokens/s, latency, valid-JSON rate on 50 anonymized messages; no production changes; branch only. If it doesn't clearly beat Needs-attention triage on the holdout, keep it as roadmap with the measured numbers.
5. Final holdout run (owner) and metrics update.

**Owner (evening):** DC-15, 17, 18; **install the release candidate and start Soak 2** (same protocol as Soak 1; this is also the demo rehearsal phone setup).
**Gate:** RC built; Soak 2 started; metrics file complete.
**Cut if behind:** voice (keep demo mode, release build and the soak).

### Day 7: Verify, freeze, rehearse
**Morning:** read Soak 2 results; fix only blocking bugs. **Code freeze at noon** (no new features, no refactors).
**Afternoon:** run the demo script three times end to end; record a backup video of a clean run; finalize `README` (problem, architecture diagram, metrics, limitations, how to install), the pitch outline and the submission.
**Gate:** three clean demo runs; backup video exists; submission files ready.

## 7. Agent prompt templates

**Preamble (paste at the start of every day):**
```
Read PROJECT_SCOPE.md and WEEK_PLAN.md and STATUS.md. Work only on Day <N>. Follow Section 0 of WEEK_PLAN.md. For each task: failing test first, keep domain free of android.* imports, shared behavior in /spec fixtures. Finish with: files changed, test counts from the HTML reports, commit hash, APK size, and a list of items needing owner verification (device checks DC-xx). Update STATUS.md. Do not start the next day.
```

**Day-specific:** paste the Agent list for that day from Section 6 below the preamble.

**Stop rule:** if a task takes more than double its estimate, report and apply the cut order instead of continuing silently.

## 8. Soak protocol (used on Days 1-3 and 6-7)

- Settings: log-only ON, 2-3 allowlisted chats, `capture_all_chats` OFF, WhatsApp Web closed, battery settings verified, start time written down, a morning self-test alarm scheduled for the last day.
- Planned messages: about 6 over 24-48 h from a friend, each with a recorded send time, read only via notification (do not open the chat).
- Do not reinstall, clear data or force-stop during a soak.
- Pass: every planned message has a decision record with correct output; any listener disconnect recovers; no unexplained process kills; the self-test alarm rings.
- Export and redact before sharing.

## 9. Demo plan (3 minutes)

1. Story (20 s): a student in five class groups missing deadlines.
2. Airplane mode on; a friend sends "kal 4 se 5 baje DBMS lab hai"; a suggestion appears with the highlighted source span and reason chips (40 s).
3. Confirm from the notification; show the alarm in Today and in Health (20 s).
4. "DBMS lab cancelled": the event is matched and the alarm disappears (20 s).
5. An important notice with no time lands in Needs attention; show the digest (20 s).
6. Safety: send an injection message; show the decision trace proving nothing executed (20 s).
7. Numbers slide: recall, card precision, holdout accuracy, APK size, soak result (20 s).
8. Optional: tap-to-talk "kal kya hai?" (20 s).

Prepare: a dedicated demo chat, WhatsApp Web closed, the chat unopened, demo-mode seed data, the backup video. State the limits openly: chats that are open, muted or archived are invisible to a notification listener.

## 10. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Live demo fails (notification timing, OEM kill) | Demo mode with seeded data and a one-tap reset; backup video; rehearse three times; soak 2 on the demo phone |
| Agent overclaims | HTML-report counts, "needs owner verification", owner device gates |
| Scope creep | Tiers and cut order in Section 2; stop rule |
| Messy architecture | Day 1 boundary test, domain purity, per-day branches and tags |
| Hindi speech recognition is poor | Test first on Day 6; keep voice optional; text query fallback in the same screen |
| LLM too slow on the device | Spike is gated and optional; Needs-attention triage already covers uncertain items |
| Overfitting to fixtures | Holdout protocol, run once per milestone |
| Privacy leak in exports | `capture_all_chats` OFF, redaction preview, owner reads before sharing |

## 11. Daily status template (agent fills in `STATUS.md`)

```
Day N | date | branch/tag
Done: ...
In progress: ...
Blocked: ...
Test counts (HTML reports): android X, vitest Y
Device checks pending: DC-..
Known issues: ...
Cut decisions: ...
```
