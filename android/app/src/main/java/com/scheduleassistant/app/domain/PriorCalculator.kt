package com.scheduleassistant.app.domain

/**
 * Pure Kotlin bounded, reversible prior calculator.
 *
 * Implements Section 5.3 Learning Loop of PROJECT_SCOPE.md:
 * - Confirm/Dismiss/Move-lane actions update per-chat and per-sender priors.
 * - Bounded: Strictly clamped within [-1.0, 1.0].
 * - Reversible: Derived deterministically from event-sourced event log.
 * - Zero android.* imports (pure JVM domain logic).
 */
object PriorCalculator {
    const val MAX_PRIOR = 1.0
    const val MIN_PRIOR = -1.0
    const val CONFIRM_DELTA = 0.20
    const val DISMISS_DELTA = -0.20
    const val MOVE_TO_SCHEDULE_DELTA = 0.20
    const val MOVE_TO_IGNORE_DELTA = -0.20
    const val MOVE_TO_ATTENTION_DELTA = 0.10

    data class LearnedPriors(
        val chatPriors: Map<String, Double> = emptyMap(),
        val senderPriors: Map<String, Double> = emptyMap()
    )

    fun computePriors(
        events: List<PriorUpdateEvent>,
        initialChatPriors: Map<String, Double> = emptyMap(),
        initialSenderPriors: Map<String, Double> = emptyMap()
    ): LearnedPriors {
        val chatPriors = initialChatPriors.toMutableMap()
        val senderPriors = initialSenderPriors.toMutableMap()

        for (event in events) {
            val delta = when (event.type) {
                "event_confirmed" -> CONFIRM_DELTA
                "event_dismissed" -> DISMISS_DELTA
                "moved_lane" -> {
                    when (event.toLane?.uppercase()) {
                        "SCHEDULE" -> MOVE_TO_SCHEDULE_DELTA
                        "IGNORE" -> MOVE_TO_IGNORE_DELTA
                        "NEEDS_ATTENTION", "ATTENTION" -> MOVE_TO_ATTENTION_DELTA
                        else -> 0.0
                    }
                }
                else -> 0.0
            }

            if (delta != 0.0) {
                val chat = event.chatTitle?.trim()
                if (!chat.isNullOrEmpty()) {
                    val current = chatPriors[chat] ?: 0.0
                    val updated = (current + delta).coerceIn(MIN_PRIOR, MAX_PRIOR)
                    chatPriors[chat] = Math.round(updated * 100.0) / 100.0
                }

                val sender = event.sender?.trim()
                if (!sender.isNullOrEmpty()) {
                    val current = senderPriors[sender] ?: 0.0
                    val updated = (current + delta).coerceIn(MIN_PRIOR, MAX_PRIOR)
                    senderPriors[sender] = Math.round(updated * 100.0) / 100.0
                }
            }
        }

        return LearnedPriors(chatPriors = chatPriors, senderPriors = senderPriors)
    }
}
