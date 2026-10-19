package com.scheduleassistant.app.domain

/**
 * Data models for the live Decision Trace pipeline.
 *
 * Each notification flows through 10 deterministic stages:
 * received -> normalized -> deduped -> allowlist check -> signals/score -> lane -> parse -> resolve date -> validate -> card/inbox/ignore
 *
 * ARCHITECTURAL INVARIANT:
 * Zero android.* imports. Pure Kotlin domain model.
 */

enum class PipelineStage {
    RECEIVED,
    NORMALIZED,
    DEDUPED,
    ALLOWLIST_CHECK,
    SIGNALS_SCORED,
    LANE_CLASSIFIED,
    PARSED,
    DATE_RESOLVED,
    VALIDATED,
    DISPATCHED
}

data class StageTrace(
    val stage: PipelineStage,
    val timestampMs: Long,
    val durationMs: Long = 0,
    val description: String,
    val attributes: Map<String, String> = emptyMap()
)

data class FullDecisionTrace(
    val id: String,
    val receivedAtMs: Long,
    val chatName: String?,
    val senderName: String?,
    val rawText: String,
    val normalizedText: String,
    val dedupeKey: String,
    val isDuplicate: Boolean,
    val allowlistPassed: Boolean,
    val signals: SignalFeatures,
    val laneClassification: LaneClassification,
    val referenceTimeUsed: String,
    val parsedTitle: String? = null,
    val resolvedStartIso: String? = null,
    val resolvedEndIso: String? = null,
    val validationReasons: List<String> = emptyList(),
    val finalOutcome: TriageOutcome,
    val stages: List<StageTrace> = emptyList()
)

enum class TriageOutcome {
    SUGGESTION_CARD,
    INBOX_ATTENTION,
    IGNORED_NOISE,
    DEDUPE_DROPPED,
    BLOCKED_CHAT,
    PARSE_REJECTED
}
