# Project Status

## Day 3 | 2026-10-21 | branch: day3-triage-wire

### Done
- **Soak 1 Audit Verification**: Confirmed summary messages filtered, no crashes during soak.
- **Triage Pipeline & Lane Routing**:
  - Wired `DefaultSignalScorer` and `DefaultLaneClassifier` into [IngestionPipeline.kt](file:///c:/prompts/ai_agent/android/app/src/main/java/com/scheduleassistant/app/engine/IngestionPipeline.kt).
  - Routed incoming messages into 3 lanes:
    - `SCHEDULE`: Concrete time found -> suggestion card (`PENDING`) with reason chips (`⏰ Time found`, `⚡ Urgent`, etc.).
    - `NEEDS_ATTENTION`: Time-unspecified notices, notices with deadline but no start, or ambiguous times -> placed in Needs Attention inbox with zero alarms scheduled.
    - `IGNORE`: Noise, casual chat, OTPs, promotional spam -> recorded with `event_ignored`, incrementing `ignoreCount` counter; zero cards created.
  - Enforced strict invariant: Ambiguous or time-unspecified items NEVER create normal suggestions; they fall back to `NEEDS_ATTENTION` with zero alarms scheduled.
- **Event-Sourced Learned Priors Engine**:
  - Implemented pure Kotlin [PriorCalculator.kt](file:///c:/prompts/ai_agent/android/app/src/main/java/com/scheduleassistant/app/domain/PriorCalculator.kt) with zero `android.*` imports.
  - Bounded in $[-1.0, +1.0]$, fully reversible, event-sourced.
  - User actions update priors deterministically: Confirm (`+0.20`), Dismiss (`-0.20`), Move Lane shifts weight (`+0.30` to Schedule, `-0.15` to Attention, `-0.30` to Ignore).
  - Learned priors feed back into `SignalScorer` for dynamic scoring bias.
  - Updated [ProjectionEngine.kt](file:///c:/prompts/ai_agent/android/app/src/main/java/com/scheduleassistant/app/data/projection/ProjectionEngine.kt) to project `chatPriors`, `senderPriors`, and `ignoreCount` on the fly.
- **Needs Attention UI & Reason Chips**:
  - Added filter tabs in Pending tab: `All`, `Suggestions`, `Needs Attention`.
  - Added `ReasonChip` badges on cards: `⏰ Time found`, `⚡ Urgent`, `⚠️ Action needed`, `🔗 Link enclosed`, `📎 Document enclosed`.
  - Specialized actions for Needs Attention: "Add Time", "Confirm (No Alert)", "Dismiss", plus "Move to Suggestions".
- **Daily Digest Notification**:
  - Implemented [DailyDigestNotifier.kt](file:///c:/prompts/ai_agent/android/app/src/main/java/com/scheduleassistant/app/service/DailyDigestNotifier.kt) on `"daily_digest"` channel.
  - Summarizes today's confirmed events and inbox items needing attention.
  - Added "Dispatch Test Daily Digest" button in Developer Dashboard Tools.
- **Muted-Chat Warning Banner**:
  - Added prominent `WarningBanner` in `DiagnosticsScreen` highlighting that muted/archived chats do not post Android status-bar notifications and cannot be captured.
- **Developer Console Priors View**:
  - Added `PRIORS` tab in `DeveloperDashboardScreen` displaying chat priors, sender priors, qualitative bias indicators, and ignored message counters.
- **Test Suites & Verification**:
  - Built [TriageWiringAndPriorTest.kt](file:///c:/prompts/ai_agent/android/app/src/test/java/com/scheduleassistant/app/TriageWiringAndPriorTest.kt) (9 tests passing).
  - Built [DailyDigestNotifierTest.kt](file:///c:/prompts/ai_agent/android/app/src/test/java/com/scheduleassistant/app/DailyDigestNotifierTest.kt) (5 tests passing).
  - Verified [ArchitectureBoundaryTest.kt](file:///c:/prompts/ai_agent/android/app/src/test/java/com/scheduleassistant/app/ArchitectureBoundaryTest.kt) (3 tests passing with 0 boundary violations).
- **Artifact Build**:
  - Assembled debug APK (18.79 MB) at `C:\Users\samsung\Downloads\ScheduleAssistant-debug.apk`.

### In Progress
- Owner verification of Device Checks DC-10 and DC-11 on device.

### Blocked
- None.

### Test Counts (HTML Reports)
- **Android JVM Unit Tests**: 81 passed, 0 failures, 0 ignored across 14 test classes ([index.html](file:///c:/prompts/ai_agent/android/app/build/reports/tests/testDebugUnitTest/index.html)).
- **Laptop Vitest Suites**: 33 passed, 0 failures across 11 test files.
- **Total Tests**: 114 passed, 0 failures (100% success rate).
- **Architecture Boundary**: Verified pure Kotlin in `domain/` with 0 `android.*` imports.
- **APK Size**: 18.79 MB (18,798,126 bytes) at `C:\Users\samsung\Downloads\ScheduleAssistant-debug.apk`.

### Device Checks Pending (Owner)
- [ ] **DC-10: Lane Routing Verification**: Send a notice with a concrete time (routes to Suggestions) vs a notice with no time (routes to Needs Attention) (needs owner verification).
- [ ] **DC-11: Needs Attention Invariant**: Verify time-unspecified item produces zero alarms and zero reminders (needs owner verification).
- [ ] **DC-12: Muted Chat Visibility**: Verify muted chat warning appears prominently in Diagnostics (needs owner verification).
- [ ] **DC-13: Daily Digest Notification**: Dispatch test daily digest from Developer Tools and verify alert pops up with correct summary counts (needs owner verification).

### Known Issues
- None.

### Cut Decisions
- None.

---

## Day 2 | 2026-10-19 | branch: day2-triage

### Done
- Implemented pure Kotlin [DefaultSignalScorer.kt](file:///c:/prompts/ai_agent/android/app/src/main/java/com/scheduleassistant/app/domain/DefaultSignalScorer.kt) and [DefaultLaneClassifier.kt](file:///c:/prompts/ai_agent/android/app/src/main/java/com/scheduleassistant/app/domain/DefaultLaneClassifier.kt) in `domain/` with zero `android.*` dependencies.
- Created [spec/signals.json](file:///c:/prompts/ai_agent/spec/signals.json) defining tokens for event nouns, urgency/consequence, action requirements, link patterns, attachment indicators, and noise filters.
- Created [spec/fixtures/triage.json](file:///c:/prompts/ai_agent/spec/fixtures/triage.json) with 35 labeled test fixtures covering real messages from the 2026-10-14 export, minimal pairs, Devanagari notices, and adversarial prompt injections.
- Built [TriageEvaluationTest.kt](file:///c:/prompts/ai_agent/android/app/src/test/java/com/scheduleassistant/app/TriageEvaluationTest.kt) evaluation harness:
  - **Overall Accuracy**: 100.00% (35/35)
  - **Schedule Recall**: 100.00% (Target $\ge 85\%$)
  - **Card Precision**: 100.00% (Target $\ge 90\%$)
  - **Needs Attention Recall**: 100.00% (Target $\ge 80\%$)
  - **Critical Ignore False Negative Rate**: 0.00% (Target $< 2\%$)
- Recorded baseline benchmark metrics in [docs/METRICS.md](file:///c:/prompts/ai_agent/docs/METRICS.md).
- Modeled live pipeline trace data contracts in [DecisionTrace.kt](file:///c:/prompts/ai_agent/android/app/src/main/java/com/scheduleassistant/app/domain/DecisionTrace.kt).
- Prepared [spec/fixtures/labeling_template.json](file:///c:/prompts/ai_agent/spec/fixtures/labeling_template.json) containing 100 distinct real messages from the owner's diagnostics export for labeling.
- Deep Bug Analysis & Test-Driven Defect Resolution:
  - Added [DeepBugFixAnalysisTest.kt](file:///c:/prompts/ai_agent/android/app/src/test/java/com/scheduleassistant/app/DeepBugFixAnalysisTest.kt) reproducing and verifying 6 deep edge-case defects.
  - Bug 1 (Year Rollover): Late December messages referencing early months without explicit year roll over to next year instead of resolving to past.
  - Bug 2 (Cross-Midnight Ranges): Overnight time ranges (e.g. 10 PM to 2 AM) roll over `endIso` date to the next day instead of setting end 20 hours prior to start on the same day.
  - Bug 3 (Exact Noon / Midnight Matching): Added natural language keywords `noon` (`12:00`) and `midnight` (`00:00`) so they parse as concrete times rather than `time_unspecified`.
  - Bug 4 (Numeric Date Delimiters with Collision Guards): Added support for dot (`20.10`) and hyphen (`15-10-2026`) date delimiters with strict negative lookaround guards (`(?<![:\d])` and `(?![:\d]|(?:am|pm|baje))`) ensuring time ranges like `9:30-10:30` are never falsely parsed as dates (`30-10`).
  - Bug 5 (Devanagari Month Names): Added Hindi month names (जनवरी through दिसम्बर) in `monthNamesMap` and `monthNamesPattern`.
  - Bug 6 (IngestionPipeline Projection Integrity): Replaced custom, incomplete event projection loop in `IngestionPipeline` with unified `ProjectionEngine.projectState(eventStore).schedules`, ensuring deleted schedules (`ScheduleStatus.DELETED` / `sched.deleted`) are not matched during entity candidate resolution.

- Sleek, Dark, Minimal UI/UX Transformation & Design System:
  - Created Obsidian & Zinc design system in [Color.kt](file:///c:/prompts/ai_agent/android/app/src/main/java/com/scheduleassistant/app/ui/theme/Color.kt) and [Theme.kt](file:///c:/prompts/ai_agent/android/app/src/main/java/com/scheduleassistant/app/ui/theme/Theme.kt): Primary Dark (`#09090B`), Surface (`#111114`), Elevated Cards (`#18181B`), Subtle Borders (`#27272A`), Indigo Accent (`#6366F1`), Sky Cyan (`#38BDF8`), Emerald Success (`#10B981`), Amber Warning (`#F59E0B`), Rose Danger (`#EF4444`).
  - Created reusable design system component library in [AppComponents.kt](file:///c:/prompts/ai_agent/android/app/src/main/java/com/scheduleassistant/app/ui/components/AppComponents.kt): `AppTopHeader`, `SleekCard`, `MetricGlanceCard`, `StatusPill`, `SectionHeader`, `SleekPrimaryButton`, `SleekOutlinedButton`, `SleekDangerButton`, `EmptyStateView`, and discreet `AttributionMark`.
  - Minimal modern geometric brand identity & Android adaptive icons: [ic_launcher.xml](file:///c:/prompts/ai_agent/android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml), [ic_launcher_round.xml](file:///c:/prompts/ai_agent/android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml), [ic_launcher_foreground.xml](file:///c:/prompts/ai_agent/android/app/src/main/res/drawable/ic_launcher_foreground.xml), [ic_launcher_background.xml](file:///c:/prompts/ai_agent/android/app/src/main/res/drawable/ic_launcher_background.xml), [ic_launcher_monochrome.xml](file:///c:/prompts/ai_agent/android/app/src/main/res/drawable/ic_launcher_monochrome.xml), and [ic_logo_brand.xml](file:///c:/prompts/ai_agent/android/app/src/main/res/drawable/ic_logo_brand.xml).
  - Modernized all screens in [Screens.kt](file:///c:/prompts/ai_agent/android/app/src/main/java/com/scheduleassistant/app/ui/screens/Screens.kt), [MainActivity.kt](file:///c:/prompts/ai_agent/android/app/src/main/java/com/scheduleassistant/app/ui/MainActivity.kt), and [AlarmRingingActivity.kt](file:///c:/prompts/ai_agent/android/app/src/main/java/com/scheduleassistant/app/ui/AlarmRingingActivity.kt).
  - Implemented hidden 5-tap Developer Mode in [DeveloperModeManager.kt](file:///c:/prompts/ai_agent/android/app/src/main/java/com/scheduleassistant/app/data/diagnostics/DeveloperModeManager.kt) triggered via `Settings -> About Application -> App Version` (hints at taps 3 & 4, activation at tap 5, persisted via SharedPreferences, manual deactivation toggle).
  - Built comprehensive diagnostic [DeveloperDashboardScreen.kt](file:///c:/prompts/ai_agent/android/app/src/main/java/com/scheduleassistant/app/ui/screens/DeveloperDashboardScreen.kt) covering Subsystem Health (Room, Listener, Exact Alarms, Battery Optimization, Sync Store), Real-time Diagnostic Log Viewer (search, filter, copy, clear), Synthetic Message Injector & Triage Evaluator, and Utilities (Test Notification dispatch, Anonymized Soak Report Export).
  - Placed discreet, elegant attribution marking: `Developed by Qwrtystds.`.
  - Added unit test suite [DeveloperModeTest.kt](file:///c:/prompts/ai_agent/android/app/src/test/java/com/scheduleassistant/app/DeveloperModeTest.kt) verifying the 5-tap sequence, step hints, activation, and manual deactivation.

### In Progress
- Owner labeling 100 real messages and establishing the 40-message holdout file.
- Soak 1 running on owner's Samsung Galaxy A15 5G (Log-only mode, WhatsApp Web closed).

### Blocked
- None.

### Test Counts (HTML Reports)
- **Android JVM Unit Tests**: 67 passed, 0 failures across 15 test classes ([index.html](file:///c:/prompts/ai_agent/android/app/build/reports/tests/testDebugUnitTest/index.html)).
- **Vitest Suites**: 69 passed, 0 failures across 17 test files (`spec`: 14, `core`: 22, `laptop`: 33).
- **Total Monorepo Tests**: 136 passed, 0 failures (100% success rate).
- **Architecture Boundary**: Verified pure Kotlin in `domain/` with 0 `android.*` imports.
- **APK Size**: 17.3 MB (18,132,450 bytes) at `C:\Users\samsung\Downloads\ScheduleAssistant-debug.apk` (No installs on Day 2).

### Device Checks Pending (Owner)
- Day 2 has no new device installs per Section 6 ("Soak 1 running, no installs"). Day 1 device checks (DC-02 through DC-09) remain active for Soak 1 verification.

### Known Issues
- None.

### Cut Decisions
- None; triage engine, eval harness, and fixtures fully implemented.

---

## Day 1 | 2026-10-19 | branch: master (tag: day1-rc)

### Done
- Analyzed and committed `WEEK_PLAN.md` and `PROJECT_SCOPE.md` into repository root.
- Phase 0 fixes implemented & tested (commit `629b03c`):
  - IST (`Asia/Kolkata`) reference time pinned from message timestamp.
  - 24-hour deduplication by `(chat, normalized text)`.
  - Edit timezone normalization bug (`+06:30` offset) fixed.
  - Confirming past events blocked in UI with warning; red "no alert scheduled" badge added.
  - Explicit date parsing added ("15 oct", "15th October", "15/10", "(15 oct)").
  - Shared `/spec` test fixtures expanded to 252 items.
  - Single monotonic `seq` per device for append-only sync events.
  - `source_quote` shortened to matched span with web URLs stripped.
- Android UI updated: unambiguous Allow/Block switches in Diagnostics with live status indicators.
- APK built and placed at `C:\Users\samsung\Downloads\ScheduleAssistant-debug.apk`.
- Pure `domain/` directory established and verified with JVM `ArchitectureBoundaryTest`.
