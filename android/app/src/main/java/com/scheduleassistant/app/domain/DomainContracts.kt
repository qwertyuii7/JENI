package com.scheduleassistant.app.domain

/**
 * Pure domain contracts for Schedule Assistant.
 *
 * ARCHITECTURAL INVARIANT:
 * Zero android.* imports allowed in the domain package.
 * All logic must be runnable on a host JVM in under 2 seconds without Robolectric or Android runtime.
 */

enum class TriageLane {
    SCHEDULE,        // Suggestion card awaiting confirmation
    NEEDS_ATTENTION, // Notice/action item without concrete time; lands in Inbox
    IGNORE           // Noise/chitchat/banter; discarded with reason
}

data class SignalFeatures(
    val hasTimeTokens: Boolean = false,
    val hasDateTokens: Boolean = false,
    val hasEventNouns: Boolean = false,
    val hasUrgencyTokens: Boolean = false,
    val hasLinks: Boolean = false,
    val mentionsUser: Boolean = false,
    val isClassGroup: Boolean = false,
    val priorScore: Double = 0.0,
    val rawTokens: List<String> = emptyList()
)

data class LaneClassification(
    val lane: TriageLane,
    val confidence: Double,
    val reasons: List<String>
)

data class PipelineTrace(
    val stage: String,
    val receivedAtMs: Long,
    val normalizedText: String,
    val dedupeKey: String,
    val allowlistPassed: Boolean,
    val signals: SignalFeatures,
    val lane: LaneClassification,
    val referenceTimeUsed: String,
    val validationReasons: List<String>
)

interface SignalScorer {
    fun score(normalizedText: String, sender: String?, chatTitle: String?): SignalFeatures
}

interface LaneClassifier {
    fun classify(features: SignalFeatures): LaneClassification
}

interface DateResolver {
    fun resolve(text: String, referenceIso: String, timeZoneId: String = "Asia/Kolkata"): ResolvedTimeRange?
}

data class ResolvedTimeRange(
    val startIso: String,
    val endIso: String? = null,
    val isAllDay: Boolean = false,
    val isAmbiguous: Boolean = false
)
