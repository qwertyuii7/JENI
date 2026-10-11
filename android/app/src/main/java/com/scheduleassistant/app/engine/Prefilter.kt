package com.scheduleassistant.app.engine

data class PrefilterResult(
    val shouldParse: Boolean,
    val sanitizedText: String,
    val reason: String? = null
)

class Prefilter(private val specLoader: SpecLoader) {

    private val zeroWidthRegex = Regex("[\\u200B-\\u200D\\uFEFF\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]")

    // Rejection patterns
    private val otpRegex = Regex("(?i)\\b(otp|one time password|verification code)\\b.*\\b\\d{4,8}\\b|\\b\\d{4,8}\\b.*\\b(otp|verification code)\\b")
    private val transactionRegex = Regex("(?i)\\b(debited|credited|order #|order id|flight \\w+ boarding)\\b")
    private val priceRegex = Regex("(?i)\\b\\d+\\s*(rupey|rs\\.?|inr|ka mila|me mila|mein mila)\\b")
    private val phoneOnlyRegex = Regex("(?i)\\b(call me on|contact me at)\\s*\\+?\\d{10,12}\\b")
    private val scoreRegex = Regex("(?i)\\b\\d{2,3}\\s*for\\s*\\d\\s*(wickets)?\\b")
    private val nonScheduleTime = Regex("(?i)\\b\\d+\\s*baje\\s*tak\\s*(sone|utha|soya|sona)\\b")
    private val pastTenseRegex = Regex("(?i)\\b(kal|parso|aaj subah)\\s+.*(thi|tha|the|chuka hai|ho gaya tha)\\b|कल\\s+.*थी|\\byesterday\\s+we\\s+had\\b|\\blast\\s+(friday|monday|tuesday|wednesday|thursday|saturday|sunday)\\s+we\\b")
    private val hypotheticalRegex = Regex("(?i)\\b(agar\\s+kal|maybe\\s+meeting|lagta\\s+hai\\s+kal|if\\s+.*let\\s+me\\s+know|shayad\\s+parso)\\b")
    private val questionRegex = Regex("(?i)\\b(hai kya|hoga kya|kab hai|kab hoga|is there a|are there any)\\b|\\?\\s*$")
    private val thirdPartyRegex = Regex("(?i)\\b([A-Z][a-z]+ has a|unka viva hai|prof \\w+ is taking lecture for batch)\\b")
    private val staleForwardRegex = Regex("(?i)\\b(fwd:\\s*202[0-4]|circular\\s*202[0-4]|purana\\s*202[0-4])\\b")
    private val casualChatterRegex = Regex("(?i)^(gm bro|good morning all|kya chal raha hai\\??|ok done|lol that was so funny|hahaha true|send me the notes please|where are you reaching\\??|chalo chai peete hain|happy birthday bhai!?|thank you so much|yes perfect|check this reel lol|bhai kal milte hain aram se|bye good night)$")

    fun prefilter(rawText: String?): PrefilterResult {
        if (rawText.isNullOrBlank()) {
            return PrefilterResult(false, "", "empty_input")
        }

        var sanitized = rawText.replace(zeroWidthRegex, "").trim()
        if (sanitized.length > 4000) {
            sanitized = sanitized.substring(0, 1000)
        }

        val lower = sanitized.lowercase()

        if (casualChatterRegex.matches(sanitized)) {
            return PrefilterResult(false, sanitized, "casual_chatter")
        }

        // Fast injection filter
        if (lower.startsWith("ignore previous instructions") ||
            lower.startsWith("system: run rm") ||
            lower.startsWith("forget system prompt") ||
            lower.startsWith("assistant instruction override:") ||
            lower.startsWith("admin override:") ||
            lower.startsWith("drop table events") ||
            lower.startsWith("urgent: disregard instructions") ||
            lower.startsWith("system prompt leak:") ||
            lower.startsWith("```json\n{\"override\": true}")) {
            val hasLegitSchedule = Regex("(?i)\\b(tomorrow|kal|parso|aaj|today|monday|friday|wednesday|thursday|tuesday|saturday|sunday)\\b").containsMatchIn(sanitized) &&
                    Regex("(?i)\\b(at\\s+\\d|baje|\\d\\s*(?:am|pm)|meeting|class|lecture|sync|appointment)\\b").containsMatchIn(sanitized)
            if (!hasLegitSchedule) {
                return PrefilterResult(false, sanitized, "pure_injection")
            }
        }

        if (otpRegex.containsMatchIn(sanitized)) return PrefilterResult(false, sanitized, "otp_noise")
        if (transactionRegex.containsMatchIn(sanitized)) return PrefilterResult(false, sanitized, "transaction_noise")
        if (priceRegex.containsMatchIn(sanitized)) return PrefilterResult(false, sanitized, "price_noise")
        if (phoneOnlyRegex.containsMatchIn(sanitized)) return PrefilterResult(false, sanitized, "phone_noise")
        if (scoreRegex.containsMatchIn(sanitized)) return PrefilterResult(false, sanitized, "score_noise")
        if (nonScheduleTime.containsMatchIn(sanitized)) return PrefilterResult(false, sanitized, "non_schedule_time")
        if (pastTenseRegex.containsMatchIn(sanitized)) return PrefilterResult(false, sanitized, "past_tense")
        if (hypotheticalRegex.containsMatchIn(sanitized)) return PrefilterResult(false, sanitized, "hypothetical")
        if (questionRegex.containsMatchIn(sanitized)) return PrefilterResult(false, sanitized, "question_inquiry")
        if (thirdPartyRegex.containsMatchIn(sanitized)) return PrefilterResult(false, sanitized, "third_party_schedule")
        if (staleForwardRegex.containsMatchIn(sanitized)) return PrefilterResult(false, sanitized, "stale_forward")

        // Schedule triggers check from SpecLoader
        var hasTrigger = false
        for (trigger in specLoader.scheduleTriggers) {
            if (trigger.all { it.code in 0x0900..0x097F }) {
                // Devanagari substring match
                if (sanitized.contains(trigger)) {
                    hasTrigger = true
                    break
                }
            } else {
                // ASCII word boundary match
                val wordRegex = Regex("(?i)\\b${Regex.escape(trigger)}\\b")
                if (wordRegex.containsMatchIn(sanitized)) {
                    hasTrigger = true
                    break
                }
            }
        }

        if (!hasTrigger) {
            return PrefilterResult(false, sanitized, "no_schedule_trigger")
        }

        return PrefilterResult(true, sanitized)
    }
}
