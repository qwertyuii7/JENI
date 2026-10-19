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

### In Progress
- Owner labeling 100 real messages and establishing the 40-message holdout file.
- Soak 1 running on owner's Samsung Galaxy A15 5G (Log-only mode, WhatsApp Web closed).

### Blocked
- None.

### Test Counts (HTML Reports)
- **Android JVM Unit Tests**: 59 passed, 0 failures across 13 test classes ([index.html](file:///c:/prompts/ai_agent/android/app/build/reports/tests/testDebugUnitTest/index.html)).
- **Vitest Suites**: 69 passed, 0 failures across 17 test files (`spec`: 14, `core`: 22, `laptop`: 33).
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
