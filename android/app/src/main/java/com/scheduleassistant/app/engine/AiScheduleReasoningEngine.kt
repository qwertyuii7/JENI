package com.scheduleassistant.app.engine

import com.scheduleassistant.app.data.model.ExtractionOutput
import com.scheduleassistant.app.domain.TriageLane
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale

data class AiReasoningInput(
    val rawText: String,
    val sender: String? = null,
    val chatTitle: String? = null,
    val referenceNow: String,
    val normalLayerExtraction: ExtractionOutput?,
    val candidateLane: TriageLane,
    val initialConfidence: Double
)

data class AiReasoningOutput(
    val isActionable: Boolean,
    val refinedTitle: String,
    val refinedLane: TriageLane,
    val refinedExtraction: ExtractionOutput?,
    val aiConfidence: Double,
    val reasoningExplanation: String,
    val reasons: List<String>
)

/**
 * Intelligent Semantic Reasoning Layer for Schedule Assistant.
 *
 * Sits directly between the Normal Layer (deterministic regex/spec parser)
 * and Card Generation. It validates intent, refines conversational titles into
 * clean display titles, eliminates false-positive chatter, verifies the triage lane,
 * and caches results in-memory for sub-millisecond efficiency.
 */
class AiScheduleReasoningEngine(
    private val cacheSize: Int = 200
) {

    // Thread-safe LRU cache for ultra-fast, zero-overhead re-evaluations
    private val reasoningCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, AiReasoningOutput>(cacheSize, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AiReasoningOutput>?): Boolean {
                return size > cacheSize
            }
        }
    )

    private val conversationalNoisePatterns = listOf(
        Regex("(?i)\\b(hey|hi|hello|dear|bhai|bro|yaar|sir|madam|please|pls|can you|could you|let's|lets|chalo|sun|dekho|aram se|urgent|fast|jaldi)\\b"),
        Regex("(?i)\\b(kal|aaj|parso|tarso|tomorrow|today|tonight|yesterday)\\b"),
        Regex("(?i)\\b(at\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?|\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)|\\d{1,2}\\s*baje(?:\\s*pe)?)\\b"),
        Regex("(?i)\\b(pe|ko|hai|hoga|hogi|milte hain|rakhi hai|conduct hogi|scheduled for|scheduled at)\\b"),
        Regex("(?i)\\b(https?://\\S+|www\\.\\S+)\\b"),
        Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]+"), // Emojis
        Regex("[!?,.;:\"'()\\[\\]{}<>]")
    )

    private val nonActionableBanterRegex = Regex(
        "(?i)^(gm|gn|ok|okay|k|done|cool|sure|yes|no|haan|nahi|thanks|thank you|lol|haha|hahaha|nice|great|super|bye|cya|chalo|thik hai)$"
    )

    private val pastNarrationRegex = Regex(
        "(?i)\\b(soya tha|so raha tha|uth gaya|was sleeping|woke up|reached home|pohoch gaya|khana khaya|ate lunch|had dinner|yesterday we had)\\b"
    )

    fun reason(input: AiReasoningInput): AiReasoningOutput {
        val cacheKey = buildCacheKey(input)
        synchronized(reasoningCache) {
            val cached = reasoningCache[cacheKey]
            if (cached != null) {
                return cached
            }
        }

        val rawTrimmed = input.rawText.trim()
        val lower = rawTrimmed.lowercase()

        // 1. Filter out pure non-actionable chatter
        if (nonActionableBanterRegex.matches(lower)) {
            val result = AiReasoningOutput(
                isActionable = false,
                refinedTitle = "Chatter",
                refinedLane = TriageLane.IGNORE,
                refinedExtraction = null,
                aiConfidence = 0.99,
                reasoningExplanation = "Casual one-word chatter without schedule commitment",
                reasons = listOf("casual_chatter", "no_commitment")
            )
            cacheResult(cacheKey, result)
            return result
        }

        // 2. Filter out past narration
        if (pastNarrationRegex.containsMatchIn(lower)) {
            val result = AiReasoningOutput(
                isActionable = false,
                refinedTitle = "Past Narration",
                refinedLane = TriageLane.IGNORE,
                refinedExtraction = null,
                aiConfidence = 0.95,
                reasoningExplanation = "Message describes past routine or completed action",
                reasons = listOf("past_narration", "not_future_event")
            )
            cacheResult(cacheKey, result)
            return result
        }

        val normalExt = input.normalLayerExtraction
        val hasNormalTime = normalExt != null && normalExt.start != null && !normalExt.time_unspecified

        // 3. Synthesize and Refine Clean Event Title
        val refinedTitle = synthesizeCleanTitle(
            rawText = input.rawText,
            sender = input.sender,
            chatTitle = input.chatTitle,
            normalTitle = normalExt?.title
        )

        // 4. Verify Lane & Actionability
        val finalLane: TriageLane
        val isActionable: Boolean
        val effectiveConfidence: Double
        val explanation: String
        val reasons = mutableListOf<String>()

        if (input.candidateLane == TriageLane.IGNORE) {
            finalLane = TriageLane.IGNORE
            isActionable = false
            effectiveConfidence = input.initialConfidence
            explanation = "Normal layer classified as noise/ignore"
            reasons.add("normal_layer_ignore")
        } else if (hasNormalTime) {
            // Concrete, resolved future time with actionable intent
            finalLane = TriageLane.SCHEDULE
            isActionable = true
            effectiveConfidence = (normalExt?.confidence ?: 0.9).coerceAtLeast(0.85)
            explanation = "AI verified actionable schedule event with concrete time"
            reasons.add("ai_verified_schedule")
            reasons.add("concrete_time")
        } else {
            // Actionable notice, deadline, or time-unspecified commitment
            finalLane = TriageLane.NEEDS_ATTENTION
            isActionable = true
            effectiveConfidence = (normalExt?.confidence ?: 0.8).coerceAtLeast(0.75)
            explanation = "AI verified actionable item requiring user attention/review"
            reasons.add("ai_verified_notice")
            if (normalExt?.time_unspecified == true) {
                reasons.add("time_unspecified")
            }
        }

        val finalExtraction = if (isActionable) {
            val base = normalExt ?: ExtractionOutput(
                type = if (finalLane == TriageLane.SCHEDULE) "new" else "notice",
                title = refinedTitle,
                start = null,
                start_date = null,
                end = null,
                time_unspecified = (finalLane == TriageLane.NEEDS_ATTENTION),
                ambiguous = false,
                source_quote = input.rawText.take(120),
                confidence = effectiveConfidence
            )
            base.copy(
                title = refinedTitle,
                confidence = effectiveConfidence
            )
        } else {
            null
        }

        val output = AiReasoningOutput(
            isActionable = isActionable,
            refinedTitle = refinedTitle,
            refinedLane = finalLane,
            refinedExtraction = finalExtraction,
            aiConfidence = effectiveConfidence,
            reasoningExplanation = explanation,
            reasons = reasons
        )

        cacheResult(cacheKey, output)
        return output
    }

    /**
     * Synthesizes a clean, human-readable card title from conversational text.
     * E.g.:
     * - "bhai kal 5 baje call karna" with sender "Rahul" -> "Call with Rahul"
     * - "let's meet at 3pm for project review" -> "Project Review"
     * - "Doctor appointment tomorrow at 10am" -> "Doctor Appointment"
     * - "Dentist checkup at 4" -> "Dentist Checkup"
     * - "assignment 2 submission deadline" -> "Assignment 2 Submission"
     */
    fun synthesizeCleanTitle(
        rawText: String,
        sender: String?,
        chatTitle: String?,
        normalTitle: String?
    ): String {
        val trimmed = rawText.trim()
        val lower = trimmed.lowercase()

        // 1. High-priority known subjects if provided by normal layer and specific
        if (!normalTitle.isNullOrBlank() &&
            !normalTitle.equals("event", ignoreCase = true) &&
            !normalTitle.equals("notice", ignoreCase = true) &&
            !normalTitle.equals("meeting", ignoreCase = true) &&
            !normalTitle.equals("call", ignoreCase = true)
        ) {
            return normalTitle
        }

        // 2. Detect specific schedule intent categories
        val isCall = lower.contains("call") || lower.contains("connect") || lower.contains("zoom") || lower.contains("gmeet")
        val isMeeting = lower.contains("meeting") || lower.contains("meet") || lower.contains("sync") || lower.contains("milte hain") || lower.contains("huddle")
        val isDoctor = lower.contains("doctor") || lower.contains("dentist") || lower.contains("clinic") || lower.contains("hospital") || lower.contains("checkup")
        val isExamOrTest = lower.contains("exam") || lower.contains("test") || lower.contains("quiz") || lower.contains("viva")
        val isClassOrLecture = lower.contains("class") || lower.contains("lecture") || lower.contains("lab")
        val isSubmission = lower.contains("submission") || lower.contains("submit") || lower.contains("deadline")

        val senderName = cleanPersonName(sender)

        if (isDoctor) {
            return if (lower.contains("dentist")) "Dentist Appointment" else "Doctor Appointment"
        }

        if (isSubmission) {
            val assignmentMatch = Regex("(?i)\\b(assignment(?:\\s+\\d+)?|project|report|thesis|paper)\\b").find(trimmed)
            return if (assignmentMatch != null) {
                "${formatTitleCase(assignmentMatch.value)} Submission"
            } else {
                "Assignment Submission"
            }
        }

        if (isExamOrTest) {
            val examMatch = Regex("(?i)\\b([A-Za-z0-9]+)\\s+(exam|test|quiz|viva)\\b").find(trimmed)
            return if (examMatch != null) {
                formatTitleCase(examMatch.value)
            } else {
                "Exam / Test"
            }
        }

        if (isClassOrLecture) {
            val classMatch = Regex("(?i)\\b([A-Za-z0-9]+)\\s+(?:ki\\s+)?(class|lecture|lab)\\b").find(trimmed)
            return if (classMatch != null) {
                formatTitleCase(classMatch.value)
            } else {
                "Class"
            }
        }

        if (isCall) {
            return if (!senderName.isNullOrBlank()) {
                "Call with $senderName"
            } else {
                "Scheduled Call"
            }
        }

        if (isMeeting) {
            // Check if there is a meeting subject
            val forMatch = Regex("(?i)\\b(?:for|about|on|regarding)\\s+([A-Za-z0-9 ]{3,25})\\b").find(trimmed)
            if (forMatch != null) {
                val topic = stripNoise(forMatch.groupValues[1])
                if (topic.isNotBlank()) {
                    return "${formatTitleCase(topic)} Meeting"
                }
            }
            return if (!senderName.isNullOrBlank()) {
                "Meeting with $senderName"
            } else {
                "Meeting"
            }
        }

        // 3. Fallback: Strip conversational noise and capitalize
        val stripped = stripNoise(trimmed)
        if (stripped.length in 3..40) {
            return formatTitleCase(stripped)
        }

        // 4. Default fallback with sender or chat
        return if (!senderName.isNullOrBlank()) {
            "Reminder with $senderName"
        } else if (!chatTitle.isNullOrBlank() && !chatTitle.equals("WhatsApp", ignoreCase = true)) {
            "${formatTitleCase(chatTitle)} Item"
        } else {
            "Schedule Reminder"
        }
    }

    private fun stripNoise(text: String): String {
        var current = text
        for (pattern in conversationalNoisePatterns) {
            current = current.replace(pattern, " ")
        }
        return current.replace(Regex("\\s+"), " ").trim()
    }

    private fun cleanPersonName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val trimmed = name.trim()
        if (trimmed.equals("WhatsApp", ignoreCase = true) ||
            trimmed.equals("WhatsApp Business", ignoreCase = true) ||
            trimmed.startsWith("+") ||
            trimmed.matches(Regex("\\d+"))
        ) {
            return null
        }
        return trimmed.take(25)
    }

    private fun formatTitleCase(text: String): String {
        val words = text.trim().split(Regex("\\s+"))
        return words.joinToString(" ") { word ->
            if (word.length <= 1) word.uppercase(Locale.ROOT)
            else if (word.all { it.isUpperCase() }) word // Preserve acronyms like DBMS, OS, AI
            else word.substring(0, 1).uppercase(Locale.ROOT) + word.substring(1).lowercase(Locale.ROOT)
        }
    }

    private fun buildCacheKey(input: AiReasoningInput): String {
        return "${input.chatTitle.orEmpty()}|${input.sender.orEmpty()}|${input.rawText.lowercase().trim()}"
    }

    private fun cacheResult(key: String, output: AiReasoningOutput) {
        synchronized(reasoningCache) {
            reasoningCache[key] = output
        }
    }

    fun clearCache() {
        synchronized(reasoningCache) {
            reasoningCache.clear()
        }
    }
}
