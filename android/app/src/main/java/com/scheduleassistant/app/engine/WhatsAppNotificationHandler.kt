package com.scheduleassistant.app.engine

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.scheduleassistant.app.data.diagnostics.DiagnosticsManager
import java.util.Collections
import java.util.LinkedHashMap

data class WhatsAppExtractedMessage(
    val packageName: String,
    val notificationKey: String,
    val chatTitle: String?,
    val sender: String?,
    val rawText: String,
    val cleanText: String, // stripped of "Sender: " prefix for parser
    val isGroup: Boolean,
    val postTime: Long,
    val dedupeKey: String
)

data class WhatsAppExtractionDecision(
    val chatTitle: String?,
    val isGroup: Boolean,
    val isAllowlisted: Boolean,
    val disposition: String, // "skipped: summary", "skipped: own status", "skipped: duplicate", "skipped: not allowlisted", "processed"
    val message: WhatsAppExtractedMessage? = null
)

class WhatsAppNotificationHandler(
    private val dedupeCacheSize: Int = 500
) {

    fun isWhatsAppPackage(packageName: String): Boolean {
        return packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b"
    }

    fun isWhatsAppPackage(context: Context, packageName: String): Boolean {
        return DiagnosticsManager.getAllowedPackages(context).contains(packageName)
    }

    // Thread-safe LRU cache for deduplication by (chat, text, postTime)
    private val dedupeCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(dedupeCacheSize, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > dedupeCacheSize
            }
        }
    )

    fun isDeduplicated(chatTitle: String?, cleanText: String, postTime: Long): Boolean {
        val key = "${chatTitle ?: "chat"}|${cleanText.trim()}|$postTime"
        synchronized(dedupeCache) {
            if (dedupeCache.containsKey(key)) {
                return true
            }
            dedupeCache[key] = System.currentTimeMillis()
            return false
        }
    }

    /**
     * Splits group "Sender: text" or "[Sender]: text" into sender and schedule body.
     */
    fun splitSenderPrefix(sender: String?, text: String): Pair<String?, String> {
        val trimmed = text.trim()
        val regex = Regex("^([^:\\n]{1,35}):\\s+(.+)$", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(trimmed)
        return if (match != null) {
            val extractedSender = match.groupValues[1].trim()
            val body = match.groupValues[2].trim()
            Pair(extractedSender, body)
        } else {
            Pair(sender, trimmed)
        }
    }

    fun isSystemNotification(text: String?, title: String? = null): Boolean {
        val combined = "${title ?: ""} ${text ?: ""}".lowercase()
        val systemPatterns = listOf(
            "checking for new messages",
            "searching for new messages",
            "backup in progress",
            "backing up messages",
            "chat backup",
            "whatsapp web is currently active",
            "whatsapp web",
            "whatsapp desktop is currently active",
            "whatsapp desktop",
            "waiting for this message"
        )
        return systemPatterns.any { combined.contains(it) }
    }

    /**
     * Processes WhatsApp notifications, enforcing allowlist, filtering system summaries/status,
     * deduping, and returning fine-grained ingestion decisions for each message/notification.
     */
    fun processNotification(
        context: Context,
        sbn: StatusBarNotification
    ): List<WhatsAppExtractionDecision> {
        val allowedPackages = DiagnosticsManager.getAllowedPackages(context)
        if (!allowedPackages.contains(sbn.packageName)) {
            return emptyList()
        }

        val notification = sbn.notification ?: return emptyList()

        val chatTitle = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val isGroup = messagingStyle?.isGroupConversation == true
        val isAllowlisted = DiagnosticsManager.isChatAllowlisted(context, chatTitle)

        // 1. Skip summary notifications (NotificationCompat.isGroupSummary and FLAG_GROUP_SUMMARY)
        val isFlagSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        val isCompatSummary = NotificationCompat.isGroupSummary(notification)
        if (isFlagSummary || isCompatSummary) {
            DiagnosticsManager.incrementSystemNotificationCount(context)
            return listOf(
                WhatsAppExtractionDecision(
                    chatTitle = chatTitle,
                    isGroup = isGroup,
                    isAllowlisted = isAllowlisted,
                    disposition = "skipped: summary"
                )
            )
        }

        val rawTextSample = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

        // 2. Filter WhatsApp's own internal system notifications
        if (isSystemNotification(rawTextSample, chatTitle)) {
            DiagnosticsManager.incrementSystemNotificationCount(context)
            return listOf(
                WhatsAppExtractionDecision(
                    chatTitle = chatTitle,
                    isGroup = isGroup,
                    isAllowlisted = isAllowlisted,
                    disposition = "skipped: own status"
                )
            )
        }

        // 3. Enforce chat allowlist
        if (!isAllowlisted) {
            return listOf(
                WhatsAppExtractionDecision(
                    chatTitle = chatTitle,
                    isGroup = isGroup,
                    isAllowlisted = false,
                    disposition = "skipped: not allowlisted"
                )
            )
        }

        val decisions = mutableListOf<WhatsAppExtractionDecision>()

        // 4. Parse MessagingStyle if available
        if (messagingStyle != null && messagingStyle.messages.isNotEmpty()) {
            for (msg in messagingStyle.messages) {
                val text = msg.text?.toString() ?: continue
                if (text.isBlank()) continue
                if (isSystemNotification(text, chatTitle)) {
                    DiagnosticsManager.incrementSystemNotificationCount(context)
                    decisions.add(
                        WhatsAppExtractionDecision(
                            chatTitle = chatTitle,
                            isGroup = isGroup,
                            isAllowlisted = true,
                            disposition = "skipped: own status"
                        )
                    )
                    continue
                }

                val msgSender = msg.person?.name?.toString()
                val (finalSender, cleanText) = splitSenderPrefix(msgSender, text)
                val msgTimestamp = if (msg.timestamp > 0L) msg.timestamp else sbn.postTime

                // Deduplicate by (chat, text, post time)
                if (isDeduplicated(chatTitle, cleanText, msgTimestamp)) {
                    decisions.add(
                        WhatsAppExtractionDecision(
                            chatTitle = chatTitle,
                            isGroup = isGroup,
                            isAllowlisted = true,
                            disposition = "skipped: duplicate"
                        )
                    )
                    continue
                }

                DiagnosticsManager.incrementChatMessageCount(context)
                val dedupeKey = "${chatTitle ?: "chat"}|$cleanText|$msgTimestamp"
                val extracted = WhatsAppExtractedMessage(
                    packageName = sbn.packageName,
                    notificationKey = sbn.key,
                    chatTitle = chatTitle,
                    sender = finalSender,
                    rawText = text,
                    cleanText = cleanText,
                    isGroup = isGroup,
                    postTime = msgTimestamp,
                    dedupeKey = dedupeKey
                )
                decisions.add(
                    WhatsAppExtractionDecision(
                        chatTitle = chatTitle,
                        isGroup = isGroup,
                        isAllowlisted = true,
                        disposition = "processed",
                        message = extracted
                    )
                )
            }
        } else {
            // Fallback for standard notifications / BigTextStyle
            val bigText = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: bigText ?: ""

            if (text.isNotBlank() && !isSystemNotification(text, chatTitle)) {
                val (finalSender, cleanText) = splitSenderPrefix(chatTitle, text)

                if (isDeduplicated(chatTitle, cleanText, sbn.postTime)) {
                    decisions.add(
                        WhatsAppExtractionDecision(
                            chatTitle = chatTitle,
                            isGroup = isGroup,
                            isAllowlisted = true,
                            disposition = "skipped: duplicate"
                        )
                    )
                } else {
                    DiagnosticsManager.incrementChatMessageCount(context)
                    val dedupeKey = "${chatTitle ?: "chat"}|$cleanText|${sbn.postTime}"
                    val extracted = WhatsAppExtractedMessage(
                        packageName = sbn.packageName,
                        notificationKey = sbn.key,
                        chatTitle = chatTitle,
                        sender = finalSender,
                        rawText = text,
                        cleanText = cleanText,
                        isGroup = isGroup,
                        postTime = sbn.postTime,
                        dedupeKey = dedupeKey
                    )
                    decisions.add(
                        WhatsAppExtractionDecision(
                            chatTitle = chatTitle,
                            isGroup = isGroup,
                            isAllowlisted = true,
                            disposition = "processed",
                            message = extracted
                        )
                    )
                }
            } else if (isSystemNotification(text, chatTitle)) {
                DiagnosticsManager.incrementSystemNotificationCount(context)
                decisions.add(
                    WhatsAppExtractionDecision(
                        chatTitle = chatTitle,
                        isGroup = isGroup,
                        isAllowlisted = true,
                        disposition = "skipped: own status"
                    )
                )
            }
        }

        return decisions
    }

    /**
     * Extracts WhatsApp messages while filtering summaries, parsing MessagingStyle,
     * stripping group sender prefixes, and deduping.
     */
    fun extractMessages(
        context: Context,
        sbn: StatusBarNotification
    ): List<WhatsAppExtractedMessage> {
        return processNotification(context, sbn).mapNotNull { it.message }
    }
}
