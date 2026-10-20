# Project Status

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

### In Progress
- Owner labeling 100 real messages and establishing the 40-message holdout file.
- Soak 1 running on owner's Samsung Galaxy A15 5G (Log-only mode, WhatsApp Web closed).

### Blocked
- None.

### Test Counts (HTML Reports)
- **Android JVM Unit Tests**: 65 passed, 0 failures across 14 test classes ([index.html](file:///c:/prompts/ai_agent/android/app/build/reports/tests/testDebugUnitTest/index.html)).
- **Vitest Suites**: 69 passed, 0 failures across 17 test files (`spec`: 14, `core`: 22, `laptop`: 33).
- **Total Monorepo Tests**: 134 passed, 0 failures (100% success rate).
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
