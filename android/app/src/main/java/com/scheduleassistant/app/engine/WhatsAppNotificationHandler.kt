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

    /**
     * Extracts WhatsApp messages while filtering summaries, parsing MessagingStyle,
     * stripping group sender prefixes, and deduping.
     */
    fun extractMessages(
        context: Context,
        sbn: StatusBarNotification
    ): List<WhatsAppExtractedMessage> {
        val allowedPackages = DiagnosticsManager.getAllowedPackages(context)
        if (!allowedPackages.contains(sbn.packageName)) {
            return emptyList()
        }

        val notification = sbn.notification ?: return emptyList()

        // 1. Skip summary notifications (NotificationCompat.isGroupSummary and FLAG_GROUP_SUMMARY)
        val isFlagSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        val isCompatSummary = NotificationCompat.isGroupSummary(notification)
        if (isFlagSummary || isCompatSummary) {
            return emptyList()
        }

        val results = mutableListOf<WhatsAppExtractedMessage>()
        val chatTitle = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

        // 2. Parse MessagingStyle if available
        val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val isGroup = messagingStyle?.isGroupConversation
            ?: (chatTitle != null && !chatTitle.matches(Regex("^\\+?\\d[\\d\\s-]{6,}$")))

        if (messagingStyle != null && messagingStyle.messages.isNotEmpty()) {
            // Process the latest messages in this MessagingStyle conversation
            for (msg in messagingStyle.messages) {
                val text = msg.text?.toString() ?: continue
                if (text.isBlank()) continue

                val msgSender = msg.person?.name?.toString()
                val (finalSender, cleanText) = splitSenderPrefix(msgSender, text)
                val msgTimestamp = if (msg.timestamp > 0L) msg.timestamp else sbn.postTime

                // 3. Deduplicate by (chat, text, post time)
                if (isDeduplicated(chatTitle, cleanText, msgTimestamp)) {
                    continue
                }

                val dedupeKey = "${chatTitle ?: "chat"}|$cleanText|$msgTimestamp"
                results.add(
                    WhatsAppExtractedMessage(
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
                )
            }
        } else {
            // Fallback for standard notifications / BigTextStyle
            val bigText = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: bigText ?: ""

            if (text.isNotBlank()) {
                val (finalSender, cleanText) = splitSenderPrefix(chatTitle, text)

                if (!isDeduplicated(chatTitle, cleanText, sbn.postTime)) {
                    val dedupeKey = "${chatTitle ?: "chat"}|$cleanText|${sbn.postTime}"
                    results.add(
                        WhatsAppExtractedMessage(
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
                    )
                }
            }
        }

        return results
    }
}
