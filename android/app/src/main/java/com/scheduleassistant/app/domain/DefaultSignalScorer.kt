package com.scheduleassistant.app.domain

import java.util.Locale
import java.util.regex.Pattern

/**
 * Pure Kotlin signal scorer for multilingual academic and productivity messages.
 *
 * ARCHITECTURAL INVARIANT:
 * Zero android.* imports. Runs on pure JVM.
 */
class DefaultSignalScorer(
    private val config: SignalConfig
) : SignalScorer {

    private val timePattern12h = Pattern.compile(
        "\\b(?:1[0-2]|0?[1-9])(?::[0-5][0-9])?\\s*(?:am|pm|a\\.m\\.|p\\.m\\.)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val timePattern24h = Pattern.compile(
        "\\b(?:[01]?[0-9]|2[0-3]):[0-5][0-9]\\b"
    )

    private val timePatternRange = Pattern.compile(
        "\\b(?:1[0-2]|0?[1-9])(?::[0-5][0-9])?\\s*(?:to|-)\\s*(?:1[0-2]|0?[1-9])(?::[0-5][0-9])?\\s*(?:am|pm)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val timePatternBaje = Pattern.compile(
        "\\b(?:[01]?[0-9]|2[0-3])(?::[0-5][0-9])?\\s*(?:baje|बजे)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val timePatternNoonMidnight = Pattern.compile(
        "\\b(?:12\\s*noon|noon|midnight)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val timePatternDaypartWithHour = Pattern.compile(
        "(?:subah|sham|shaam|dopahar|raat|सुबह|दोपहर|शाम|रात)\\s*(?:ko\\s*)?(?:1[0-2]|0?[1-9])(?::[0-5][0-9])?\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val datePatternRelative = Pattern.compile(
        "\\b(?:today|tomorrow|tonight|aaj|kal|parso|tarso)\\b|[आजकलपरसों]",
        Pattern.CASE_INSENSITIVE
    )

    private val datePatternWeekdays = Pattern.compile(
        "\\b(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|" +
                "somvar|mangalvar|budhvar|guruvar|veervar|shukravar|shanivar|ravivar)\\b|" +
                "(?:सोमवार|मंगलवार|बुधवार|गुरुवार|शुक्रवार|शनिवार|रविवार)",
        Pattern.CASE_INSENSITIVE
    )

    private val datePatternExplicit = Pattern.compile(
        "\\b(?:\\d{1,2}(?:st|nd|rd|th)?\\s+(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)|" +
                "\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?|\\(\\d{1,2}\\s+(?:oct|nov|dec|jan|feb|mar|apr|may|jun|jul|aug|sep)\\))\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val cancelReschedulePattern = Pattern.compile(
        "\\b(?:cancel|cancelled|canceled|cancelling|rescheduled|reschedule|postponed|shifted|called off)\\b|" +
                "(?:रद्द|कैंसिल|स्थगित|शिफ्ट)",
        Pattern.CASE_INSENSITIVE
    )

    private val injectionPattern = Pattern.compile(
        "(?:ignore\\s+all\\s+previous\\s+instructions|system\\s+prompt|override\\s+instructions|jailbreak|disregard\\s+all\\s+prior)",
        Pattern.CASE_INSENSITIVE
    )

    private val pollPattern = Pattern.compile(
        "(?:📊|which\\s+role\\s+are\\s+you\\s+interested|poll:|vote:)",
        Pattern.CASE_INSENSITIVE
    )

    private val contactPattern = Pattern.compile(
        "(?:feel\\s+free\\s+to\\s+whatsapp|call:\\s*\\d{10}|contact\\s+us\\s+at\\s*\\d{10})",
        Pattern.CASE_INSENSITIVE
    )

    private val casualPeerPattern = Pattern.compile(
        "\\b(?:kya\\s+haal|kya\\s+chal\\s+rha|chai\\s+peene|bhai\\s+assignment|bhai\\s+notes|market\\s+me|homework|kya\\s+tha|bhai\\s+kal)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val rollPattern = Pattern.compile(
        "\\b(?:roll|roll\\s*no\\.?|roll\\s*numbers?)\\b[\\s\\d,]+",
        Pattern.CASE_INSENSITIVE
    )

    override fun score(
        normalizedText: String,
        sender: String?,
        chatTitle: String?,
        userProfile: UserProfile,
        chatPriors: Map<String, Double>,
        senderPriors: Map<String, Double>
    ): SignalFeatures {
        val lowerText = normalizedText.lowercase(Locale.ROOT)
        val matchedTokens = mutableListOf<String>()

        // 1. Adversarial prompt injection check
        val isInjection = injectionPattern.matcher(lowerText).find()
        if (isInjection) {
            matchedTokens.add("prompt_injection")
            return SignalFeatures(
                isInjection = true,
                score = -100.0,
                matchedTokens = matchedTokens
            )
        }

        // 2. Time tokens
        val hasTimeTokens = timePattern12h.matcher(lowerText).find() ||
                timePatternRange.matcher(lowerText).find() ||
                timePatternBaje.matcher(lowerText).find() ||
                timePatternNoonMidnight.matcher(lowerText).find() ||
                timePatternDaypartWithHour.matcher(lowerText).find() ||
                (timePattern24h.matcher(lowerText).find() && !lowerText.contains("http"))

        if (hasTimeTokens) matchedTokens.add("time_token")

        // 3. Date tokens
        val hasDateTokens = datePatternRelative.matcher(lowerText).find() ||
                datePatternWeekdays.matcher(lowerText).find() ||
                datePatternExplicit.matcher(lowerText).find()

        if (hasDateTokens) matchedTokens.add("date_token")

        // 4. Cancellation, postponement or reschedule
        val isCancellation = lowerText.contains("cancel") || lowerText.contains("रद्द") || lowerText.contains("कैंसिल") || lowerText.contains("called off")
        val isPostponed = lowerText.contains("postponed") || lowerText.contains("स्थगित")
        val isRescheduled = lowerText.contains("rescheduled") || lowerText.contains("reschedule") || lowerText.contains("shifted") || lowerText.contains("शिफ्ट")
        val isCancellationOrReschedule = isCancellation || isPostponed || isRescheduled
        if (isCancellation) matchedTokens.add("cancellation")
        if (isPostponed) matchedTokens.add("postponed")
        if (isRescheduled) matchedTokens.add("rescheduled")

        // 5. Event nouns
        var hasEventNouns = false
        for (noun in config.eventNouns) {
            if (containsWordOrPhrase(lowerText, noun.lowercase(Locale.ROOT))) {
                hasEventNouns = true
                matchedTokens.add("event:$noun")
                break
            }
        }

        // 6. Urgency tokens
        var hasUrgencyTokens = false
        for (urg in config.urgencyTokens) {
            if (containsWordOrPhrase(lowerText, urg.lowercase(Locale.ROOT))) {
                hasUrgencyTokens = true
                matchedTokens.add("urgency:$urg")
                break
            }
        }

        // 7. Action tokens
        var hasActionTokens = false
        for (act in config.actionTokens) {
            if (containsWordOrPhrase(lowerText, act.lowercase(Locale.ROOT))) {
                hasActionTokens = true
                matchedTokens.add("action:$act")
                break
            }
        }

        // 8. Links
        var hasLinks = false
        for (link in config.linkPatterns) {
            if (lowerText.contains(link.lowercase(Locale.ROOT))) {
                hasLinks = true
                matchedTokens.add("link:$link")
                break
            }
        }

        // 9. Attachments
        var hasAttachments = false
        for (att in config.attachmentIndicators) {
            if (containsWordOrPhrase(lowerText, att.lowercase(Locale.ROOT))) {
                hasAttachments = true
                matchedTokens.add("attachment:$att")
                break
            }
        }

        // 10. Hindi notice indicator
        val isHindiNotice = lowerText.contains("आवश्यक सूचना") ||
                lowerText.contains("सूचना") ||
                lowerText.contains("छात्रवृत्ति") ||
                lowerText.contains("समस्त छात्र")

        // 11. Noise indicators
        var isNoise = false
        for (noise in config.noiseIndicators) {
            if (containsWordOrPhrase(lowerText, noise.lowercase(Locale.ROOT))) {
                isNoise = true
                matchedTokens.add("noise:$noise")
                break
            }
        }

        if (casualPeerPattern.matcher(lowerText).find()) {
            isNoise = true
            matchedTokens.add("casual_peer")
        }

        val isPollOrContact = pollPattern.matcher(lowerText).find() || contactPattern.matcher(lowerText).find()
        if (isPollOrContact) {
            isNoise = true
            matchedTokens.add("poll_or_contact")
        }

        // 12. Mentions user / roll number
        var mentionsUser = false
        if (!userProfile.rollNumber.isNullOrBlank()) {
            if (lowerText.contains(userProfile.rollNumber.lowercase(Locale.ROOT))) {
                mentionsUser = true
                matchedTokens.add("user_roll_match")
            }
        }
        if (!userProfile.ownName.isNullOrBlank()) {
            if (containsWordOrPhrase(lowerText, userProfile.ownName.lowercase(Locale.ROOT))) {
                mentionsUser = true
                matchedTokens.add("user_name_match")
            }
        }
        if (rollPattern.matcher(lowerText).find()) {
            matchedTokens.add("roll_list_present")
            if (userProfile.rollNumber.isNullOrBlank()) {
                // If user hasn't set their roll number yet, treat roll call as relevant to student
                mentionsUser = true
            }
        }

        // 13. Priors
        var priorAdj = 0.0
        if (chatTitle != null && chatPriors.containsKey(chatTitle)) {
            priorAdj += (chatPriors[chatTitle] ?: 0.0)
        }
        if (sender != null && senderPriors.containsKey(sender)) {
            priorAdj += (senderPriors[sender] ?: 0.0)
        }

        // Compute baseline signal score
        var score = 0.0
        if (hasTimeTokens) score += 2.5
        if (hasDateTokens) score += 1.0
        if (hasEventNouns) score += 2.0
        if (hasUrgencyTokens) score += 1.5
        if (hasActionTokens) score += 1.0
        if (hasLinks) score += 1.5
        if (hasAttachments) score += 1.0
        if (isCancellationOrReschedule) score += 2.0
        if (isHindiNotice) score += 1.5
        if (mentionsUser) score += 2.0
        if (isNoise) score -= 3.0
        score += priorAdj

        return SignalFeatures(
            hasTimeTokens = hasTimeTokens,
            hasDateTokens = hasDateTokens,
            hasEventNouns = hasEventNouns,
            hasUrgencyTokens = hasUrgencyTokens,
            hasActionTokens = hasActionTokens,
            hasLinks = hasLinks,
            hasAttachments = hasAttachments,
            mentionsUser = mentionsUser,
            isCancellationOrReschedule = isCancellationOrReschedule,
            isCancellation = isCancellation,
            isPostponed = isPostponed,
            isNoise = isNoise,
            isInjection = false,
            isHindiNotice = isHindiNotice,
            isPollOrContact = isPollOrContact,
            score = score,
            priorAdjustment = priorAdj,
            matchedTokens = matchedTokens
        )
    }

    private fun containsWordOrPhrase(text: String, target: String): Boolean {
        if (!text.contains(target)) return false
        // For latin alphabetic words, ensure word boundary
        if (target.all { it in 'a'..'z' || it in 'A'..'Z' || it == ' ' }) {
            val regex = "\\b" + Pattern.quote(target) + "\\b"
            return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text).find()
        }
        return true
    }
}
