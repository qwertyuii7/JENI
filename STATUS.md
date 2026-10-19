# Project Status

## Day 1 | 2026-10-19 | branch: master (tag: day1-rc pending owner device checks)

### Done
- Analyzed and committed `WEEK_PLAN.md` and `PROJECT_SCOPE.md` into repository root.
- Phase 0 fixes implemented & tested (commit `629b03c`):
  - IST (`Asia/Kolkata`) reference time pinned from message timestamp (fallback to `sbn.postTime`, never wall clock).
  - 24-hour deduplication by `(chat, normalized text)`.
  - Edit timezone normalization bug (`+06:30` offset) fixed; trigger times stay identical across title-only edits.
  - Confirming past events blocked in UI with warning; confirmed schedules missing future alerts display red "no alert scheduled" badge.
  - Explicit date parsing added ("15 oct", "15th October", "15/10", "(15 oct)", "tomorrow (15 oct)").
  - Shared `/spec` test fixtures expanded to 252 items covering time ranges and multilingual cancel/reschedule.
  - Single monotonic `seq` per device for append-only sync events.
  - `source_quote` shortened to matched span with web URLs stripped.
- Android UI updated: unambiguous Allow/Block switches in Diagnostics with live status indicators.
- APK built and placed at `C:\Users\samsung\Downloads\ScheduleAssistant-debug.apk`.
- Pure `domain/` directory established and verified with JVM `ArchitectureBoundaryTest`.

### In Progress
- Owner verification of Day 1 device checklist (DC-02 through DC-09) on Samsung Galaxy A15 5G.
- Soak 1 launch by owner (log-only mode, 2-3 allowlisted chats, WhatsApp Web closed, 24-48h duration).

### Blocked
- None.

### Test Counts (HTML Reports)
- **Android JVM Unit Tests**: 58 passed, 0 failures across 12 test classes (`android/app/build/reports/tests/testDebugUnitTest/index.html`).
- **Vitest Suites**: 69 passed, 0 failures across 17 test files (`spec`: 14, `core`: 22, `laptop`: 33).
- **APK Size**: 17.3 MB (18,132,450 bytes) at `C:\Users\samsung\Downloads\ScheduleAssistant-debug.apk`.

### Device Checks Pending (Owner)
- [ ] **DC-02**: Real DM message (WhatsApp closed) creates suggestion with correct title and time.
- [ ] **DC-03**: Range message ("7:15 to 8:00pm") gives start 19:15, end 20:00.
- [ ] **DC-04**: Confirming a past event is blocked with clear message.
- [ ] **DC-05**: Two same-time alarms both listed; each dismisses independently; Dismiss all works.
- [ ] **DC-06**: Stop all alarms; 1-minute auto-stop produces a missed-alarm notification.
- [ ] **DC-07**: Alarm over lock screen with real PIN/fingerprint, Dismiss reachable.
- [ ] **DC-08**: Event 10 minutes away gives immediate reminder.
- [ ] **DC-09**: Edit (title only) keeps trigger identical; delete removes alarm; undo restores; reboot preserves state.

### Known Issues
- None blocking Day 1 completion.

### Cut Decisions
- Following Section 2 of `WEEK_PLAN.md`: Laptop work and M4 sync/relay are locked/cut. All focus is on the Android offline-first Secretary. No cuts to Day 1 stabilization scope.
