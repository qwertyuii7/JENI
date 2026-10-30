package com.scheduleassistant.app.domain

/**
 * Pure Kotlin three-lane triage classifier.
 *
 * Rules:
 * 1. SCHEDULE: Concrete time + event/link/urgency/action.
 * 2. NEEDS_ATTENTION: Action/urgency/event/link/attachment/Hindi notice, but missing concrete clock time.
 * 3. IGNORE: Noise, chitchat, promos, polls, prompt injections.
 *
 * HARD INVARIANT: "Ignore only when confident." If there is any doubt or academic urgency,
 * route to NEEDS_ATTENTION rather than silently dropping.
 *
 * ARCHITECTURAL INVARIANT:
 * Zero android.* imports. Runs on pure JVM.
 */
class DefaultLaneClassifier : LaneClassifier {

    override fun classify(features: SignalFeatures): LaneClassification {
        val reasons = mutableListOf<String>()

        // 1. Security / Injection: Block immediately and ignore
        if (features.isInjection) {
            reasons.add("prompt_injection")
            reasons.add("security_blocked")
            return LaneClassification(
                lane = TriageLane.IGNORE,
                confidence = 1.0,
                reasons = reasons
            )
        }

        // 2. Cancellation or reschedule of existing event
        if (features.isCancellationOrReschedule && (features.hasEventNouns || features.hasDateTokens)) {
            if (features.hasTimeTokens) {
                reasons.add("time_found")
                reasons.add("event_found")
                reasons.add("reschedule")
                return LaneClassification(
                    lane = TriageLane.SCHEDULE,
                    confidence = 0.95,
                    reasons = reasons
                )
            } else if (features.isCancellation) {
                reasons.add("event_found")
                reasons.add("cancellation")
                return LaneClassification(
                    lane = TriageLane.SCHEDULE,
                    confidence = 0.90,
                    reasons = reasons
                )
            }
            // Note: postponement without concrete time falls through to NEEDS_ATTENTION below
        }

        // 3. SCHEDULE lane: Concrete time + academic/work event or link or strong urgency
        // STRICT: We require at least ONE of: eventNoun, link, urgency+action combo.
        // Score alone (>= 1.5 without event context) is no longer sufficient.
        if (features.hasTimeTokens && !features.isNoise) {
            val hasStrongEventContext = features.hasEventNouns ||
                    features.hasLinks ||
                    (features.hasUrgencyTokens && features.hasActionTokens) || // both required together
                    features.mentionsUser

            if (hasStrongEventContext) {
                reasons.add("time_found")
                if (features.isHindiNotice) reasons.add("hindi_notice")
                if (features.hasEventNouns) reasons.add("event_found")
                if (features.hasLinks) reasons.add("link_found")
                if (features.hasUrgencyTokens) reasons.add("urgency_detected")
                if (features.hasActionTokens) reasons.add("action_required")

                // Stricter confidence tiers:
                // - event noun + time → 0.95 (highest confidence)
                // - link + time → 0.90
                // - mention + time → 0.88
                // - urgency+action + time (no noun/link) → 0.82 (lower; more ambiguous)
                val confidence = when {
                    features.hasEventNouns && features.hasTimeTokens -> 0.95
                    features.hasLinks -> 0.90
                    features.mentionsUser -> 0.88
                    else -> 0.82
                }
                return LaneClassification(
                    lane = TriageLane.SCHEDULE,
                    confidence = confidence,
                    reasons = reasons
                )
            }
            // Time + score alone (no event/link/urgency+action/mention) → NEEDS_ATTENTION, not SCHEDULE
            if (features.score >= 1.5) {
                reasons.add("time_found")
                reasons.add("weak_event_signal")
                return LaneClassification(
                    lane = TriageLane.NEEDS_ATTENTION,
                    confidence = 0.72,
                    reasons = reasons
                )
            }
        }

        // 4. NEEDS_ATTENTION lane: High importance signals, but no concrete clock time
        val hasAttentionSignal = features.hasUrgencyTokens ||
                features.hasActionTokens ||
                (features.hasEventNouns && (features.hasDateTokens || features.score >= 1.0)) ||
                features.hasLinks ||
                features.hasAttachments ||
                features.isHindiNotice ||
                features.mentionsUser ||
                (features.isCancellationOrReschedule && !features.hasTimeTokens)

        if (hasAttentionSignal) {
            if (features.isHindiNotice) reasons.add("hindi_notice")
            if (features.hasUrgencyTokens) reasons.add("urgency_detected")
            if (features.hasActionTokens) reasons.add("action_required")
            if (features.hasEventNouns) reasons.add("event_found")
            if (features.hasLinks) reasons.add("link_found")
            if (features.hasAttachments) reasons.add("attachment_found")
            reasons.add("no_concrete_time")

            return LaneClassification(
                lane = TriageLane.NEEDS_ATTENTION,
                confidence = 0.85,
                reasons = reasons
            )
        }

        // 5. IGNORE lane: Confident noise, poll, contact, or chitchat
        if (features.isPollOrContact) {
            val isPoll = features.matchedTokens.any { it.contains("poll") || it.contains("vote") || it.contains("📊") }
            if (isPoll) {
                reasons.add("poll_or_question")
            } else {
                reasons.add("contact_info")
            }
            reasons.add("no_schedule_intent")
            return LaneClassification(
                lane = TriageLane.IGNORE,
                confidence = 0.95,
                reasons = reasons
            )
        }

        if (features.isNoise) {
            val tokenStr = features.matchedTokens.joinToString(" ")
            when {
                tokenStr.contains("greeting") || tokenStr.contains("good morning") || tokenStr.contains("prabhat") -> {
                    if (tokenStr.contains("devanagari") || tokenStr.contains("प्रभात")) {
                        reasons.add("devanagari_greeting")
                    } else {
                        reasons.add("greeting_noise")
                    }
                }
                tokenStr.contains("discount") || tokenStr.contains("promo") || tokenStr.contains("code") -> {
                    reasons.add("promo_noise")
                }
                tokenStr.contains("otp") || tokenStr.contains("verification") -> {
                    reasons.add("otp_noise")
                }
                tokenStr.contains("congratulations") || tokenStr.contains("बधाई") -> {
                    reasons.add("congratulations")
                }
                tokenStr.contains("chai") || tokenStr.contains("market") -> {
                    reasons.add("casual_hangout")
                }
                else -> {
                    reasons.add("chitchat")
                }
            }
            reasons.add("no_schedule_intent")
            return LaneClassification(
                lane = TriageLane.IGNORE,
                confidence = 0.90,
                reasons = reasons
            )
        }

        // 6. Fail-safe: If date token exists without clock time or event, send to NEEDS_ATTENTION rather than dropping
        if (features.hasDateTokens) {
            reasons.add("date_without_concrete_time")
            reasons.add("no_concrete_time")
            return LaneClassification(
                lane = TriageLane.NEEDS_ATTENTION,
                confidence = 0.70,
                reasons = reasons
            )
        }

        // Default pure chitchat with zero triggers
        reasons.add("chitchat")
        reasons.add("no_schedule_intent")
        return LaneClassification(
            lane = TriageLane.IGNORE,
            confidence = 0.85,
            reasons = reasons
        )
    }
}
