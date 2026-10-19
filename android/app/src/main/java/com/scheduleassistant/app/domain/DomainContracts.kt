package com.scheduleassistant.app.domain

import org.json.JSONArray
import org.json.JSONObject

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

data class UserProfile(
    val ownName: String? = null,
    val rollNumber: String? = null
)

data class SignalConfig(
    val eventNouns: List<String> = emptyList(),
    val urgencyTokens: List<String> = emptyList(),
    val actionTokens: List<String> = emptyList(),
    val linkPatterns: List<String> = emptyList(),
    val attachmentIndicators: List<String> = emptyList(),
    val noiseIndicators: List<String> = emptyList()
) {
    companion object {
        fun fromJson(jsonStr: String): SignalConfig {
            val obj = JSONObject(jsonStr)
            fun parseList(key: String): List<String> {
                val arr = obj.optJSONArray(key) ?: JSONArray()
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    list.add(arr.getString(i))
                }
                return list
            }
            return SignalConfig(
                eventNouns = parseList("event_nouns"),
                urgencyTokens = parseList("urgency_tokens"),
                actionTokens = parseList("action_tokens"),
                linkPatterns = parseList("link_patterns"),
                attachmentIndicators = parseList("attachment_indicators"),
                noiseIndicators = parseList("noise_indicators")
            )
        }
    }
}

data class SignalFeatures(
    val hasTimeTokens: Boolean = false,
    val hasDateTokens: Boolean = false,
    val hasEventNouns: Boolean = false,
    val hasUrgencyTokens: Boolean = false,
    val hasActionTokens: Boolean = false,
    val hasLinks: Boolean = false,
    val hasAttachments: Boolean = false,
    val mentionsUser: Boolean = false,
    val isCancellationOrReschedule: Boolean = false,
    val isCancellation: Boolean = false,
    val isPostponed: Boolean = false,
    val isNoise: Boolean = false,
    val isInjection: Boolean = false,
    val isHindiNotice: Boolean = false,
    val isPollOrContact: Boolean = false,
    val score: Double = 0.0,
    val priorAdjustment: Double = 0.0,
    val matchedTokens: List<String> = emptyList()
)

data class LaneClassification(
    val lane: TriageLane,
    val confidence: Double,
    val reasons: List<String>
)

data class PipelineTrace(
    val id: String,
    val stage: String,
    val receivedAtMs: Long,
    val rawText: String,
    val normalizedText: String,
    val dedupeKey: String,
    val allowlistPassed: Boolean,
    val signals: SignalFeatures,
    val lane: LaneClassification,
    val referenceTimeUsed: String,
    val validationReasons: List<String>,
    val finalOutcome: String
)

interface SignalScorer {
    fun score(
        normalizedText: String,
        sender: String? = null,
        chatTitle: String? = null,
        userProfile: UserProfile = UserProfile(),
        chatPriors: Map<String, Double> = emptyMap(),
        senderPriors: Map<String, Double> = emptyMap()
    ): SignalFeatures
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
