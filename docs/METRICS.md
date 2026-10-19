# System Evaluation Metrics & Benchmark Records

This file records measured performance metrics, evaluation benchmarks, and soak records for the Schedule Assistant personal secretary across development milestones.

---

## 1. Triage Engine Benchmark (Day 2 Milestone Baseline)

Evaluated via `TriageEvaluationTest` ([HTML Report](file:///c:/prompts/ai_agent/android/app/build/reports/tests/testDebugUnitTest/classes/com.scheduleassistant.app.TriageEvaluationTest.html)) on pure Kotlin JVM execution using `spec/fixtures/triage.json` (35 labeled fixtures including real messages from the 2026-10-14 export, minimal pairs, Hindi notices, and adversarial injections).

| Metric | Target (PROJECT_SCOPE.md) | Day 2 Baseline Measured | Status |
|---|---|---|---|
| **Overall Classification Accuracy** | $\ge 90\%$ | **100.00%** (35/35) | **PASS** |
| **Schedule Recall** | $\ge 85\%$ | **100.00%** | **PASS** |
| **Pending Card Precision** | $\ge 90\%$ | **100.00%** | **PASS** |
| **Needs Attention Recall** | $\ge 80\%$ | **100.00%** | **PASS** |
| **Critical Ignore False Negative Rate** | $< 2.0\%$ | **0.00%** (0 false drops) | **PASS** |
| **Domain Evaluation Execution Time** | $< 2.0\text{ s}$ | **0.150 s** (Host JVM) | **PASS** |

### Per-Lane Confusion Matrix Breakdown

```
                  Predicted
                Sched   Attn   Ignore
Actual Sched     12       0       0
Actual Attn       0      11       0
Actual Ignore     0       0      12
```

- **True Positives (Schedule)**: 12 (Real class timing, Meet links, deadlines with clock time, roll number meetings)
- **True Positives (Needs Attention)**: 11 (Submission notices without time, Devanagari scholarship notice, roll number warning without time, Zoom link without time, form links, circular attachments)
- **True Positives (Ignore)**: 12 (Chitchat, peer homework requests, polls, phone number contacts, promo discounts, morning wishes, OTPs, prompt injections)
- **Critical Ignore False Negatives**: 0 (No academic/work event was ever dropped into IGNORE)

---

## 2. Binary & Test Suite Metrics

| Metric | Measured Value | Target / Limit |
|---|---|---|
| **Android JVM Unit Tests** | **59 passed**, 0 failures across 13 test classes | 100% pass |
| **Vitest Spec & Core Tests** | **69 passed**, 0 failures across 17 test files | 100% pass |
| **Debug APK Size** | **17.3 MB** (18,132,450 bytes) | Release target $\le 20\text{ MB}$ uncompressed |
| **Architecture Boundary** | 0 `android.*` imports in `domain/` | Hard Invariant (0 violations) |

---

## 3. Holdout Evaluation (Owner Managed)

*Note: The 40-message holdout dataset is maintained exclusively by the repository owner outside agent reach.*

- **Milestone 1 Run (Day 3)**: Pending owner run after triage wiring.
- **Milestone 2 Run (Day 6)**: Pending release candidate evaluation.

---

## 4. Soak Test History

- **Soak 1 (Days 1–3)**: In progress on Samsung Galaxy A15 5G (Log-only mode, allowlisted chats, WhatsApp Web closed).
- **Soak 2 (Days 6–7)**: Scheduled on Release Candidate.
