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

    fun normalizeTextForDedupe(text: String): String {
        return text.lowercase().replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Requirement 2: Dedupe by (chat, normalized text) within 24h, ignoring post time.
     */
    fun isDeduplicated(chatTitle: String?, cleanText: String, postTime: Long = System.currentTimeMillis()): Boolean {
        val norm = normalizeTextForDedupe(cleanText)
        val key = "${chatTitle ?: "chat"}|$norm"
        val now = if (postTime > 0L) postTime else System.currentTimeMillis()
        synchronized(dedupeCache) {
            val lastSeen = dedupeCache[key]
            if (lastSeen != null && kotlin.math.abs(now - lastSeen) < 24 * 60 * 60 * 1000L) {
                return true
            }
            dedupeCache[key] = now
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
        if (text.isNullOrBlank() && title.isNullOrBlank()) return true
        val cleanText = text?.trim() ?: ""
        val cleanTitle = title?.trim() ?: ""
        val combined = "$cleanTitle $cleanText".lowercase().trim()

        if (Regex("(?i)^(downloading|uploading|sending|calling|ringing|connecting)(\\.\\.\\.)?$").matches(cleanText)) {
            return true
        }

        val systemPatterns = listOf(
            "checking for new messages",
            "searching for new messages",
            "downloading content",
            "downloading media",
            "downloading document",
            "downloading file",
            "downloading video",
            "downloading audio",
            "downloading photo",
            "download in progress",
            "media download",
            "download paused",
            "uploading content",
            "uploading media",
            "uploading file",
            "upload in progress",
            "media upload",
            "sending file",
            "sending media",
            "backup in progress",
            "backing up messages",
            "chat backup",
            "restoring chat",
            "restoring messages",
            "whatsapp web is currently active",
            "whatsapp desktop is currently active",
            "waiting for this message",
            "this message was deleted",
            "you deleted this message",
            "ongoing video call",
            "ongoing voice call",
            "ongoing call",
            "call in progress",
            "incoming video call",
            "incoming voice call",
            "incoming call",
            "missed video call",
            "missed voice call",
            "missed group video call",
            "missed group voice call",
            "missed group call",
            "missed call",
            "waiting for wi-fi",
            "waiting for network",
            "deleting messages"
        )
        if (systemPatterns.any { combined.contains(it) }) return true

        val isWhatsAppTitle = cleanTitle.equals("WhatsApp", ignoreCase = true) || cleanTitle.equals("WhatsApp Business", ignoreCase = true)
        if (isWhatsAppTitle && (cleanText.contains("download") || cleanText.contains("backup") || cleanText.contains("call") || cleanText.contains("ring"))) {
            return true
        }

        return false
    }

    /**
     * Processes WhatsApp notifications, enforcing allowlist, filtering system summaries/status,
     * ongoing media transfers/calls, deduping, and returning fine-grained ingestion decisions.
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

        // 1. Skip ongoing notifications (e.g. file transfers, calls, audio playing, foreground sync)
        val isOngoing = sbn.isOngoing ||
                (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
                (notification.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
        if (isOngoing && (messagingStyle == null || messagingStyle.messages.isEmpty())) {
            DiagnosticsManager.incrementSystemNotificationCount(context)
            return listOf(
                WhatsAppExtractionDecision(
                    chatTitle = chatTitle,
                    isGroup = isGroup,
                    isAllowlisted = isAllowlisted,
                    disposition = "skipped: ongoing event"
                )
            )
        }

        // 2. Skip progress notifications (media downloading / uploading / backups)
        val hasProgress = notification.extras.containsKey(Notification.EXTRA_PROGRESS) ||
                notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0
        if (hasProgress) {
            DiagnosticsManager.incrementSystemNotificationCount(context)
            return listOf(
                WhatsAppExtractionDecision(
                    chatTitle = chatTitle,
                    isGroup = isGroup,
                    isAllowlisted = isAllowlisted,
                    disposition = "skipped: progress notification"
                )
            )
        }

        // 3. Skip non-message system categories (calls, progress, service, status, navigation)
        val category = notification.category
        if (category != null && category in setOf(
                NotificationCompat.CATEGORY_PROGRESS,
                NotificationCompat.CATEGORY_SERVICE,
                NotificationCompat.CATEGORY_CALL,
                NotificationCompat.CATEGORY_STATUS,
                NotificationCompat.CATEGORY_NAVIGATION,
                NotificationCompat.CATEGORY_SYSTEM
            )) {
            DiagnosticsManager.incrementSystemNotificationCount(context)
            return listOf(
                WhatsAppExtractionDecision(
                    chatTitle = chatTitle,
                    isGroup = isGroup,
                    isAllowlisted = isAllowlisted,
                    disposition = "skipped: system category ($category)"
                )
            )
        }

        // 4. Skip summary notifications (NotificationCompat.isGroupSummary and FLAG_GROUP_SUMMARY)
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

        // 5. Filter WhatsApp's own internal system notifications (downloads, backups, calls, connectivity)
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

        // 6. Generic WhatsApp title without messagingStyle is system status (e.g. app announcements or status)
        if (messagingStyle == null && (chatTitle.equals("WhatsApp", ignoreCase = true) || chatTitle.equals("WhatsApp Business", ignoreCase = true))) {
            DiagnosticsManager.incrementSystemNotificationCount(context)
            return listOf(
                WhatsAppExtractionDecision(
                    chatTitle = chatTitle,
                    isGroup = isGroup,
                    isAllowlisted = isAllowlisted,
                    disposition = "skipped: app status"
                )
            )
        }

        // 7. Enforce chat allowlist
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
                val nowMs = System.currentTimeMillis()

                // Requirement 1: Skip messages older than 24h
                if (nowMs - msgTimestamp > 24 * 60 * 60 * 1000L) {
                    decisions.add(
                        WhatsAppExtractionDecision(
                            chatTitle = chatTitle,
                            isGroup = isGroup,
                            isAllowlisted = true,
                            disposition = "skipped: older than 24h"
                        )
                    )
                    continue
                }

                // Requirement 2: Deduplicate by (chat, normalized text) ignoring post time
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
                val dedupeKey = "${chatTitle ?: "chat"}|${normalizeTextForDedupe(cleanText)}"
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
                val msgTimestamp = sbn.postTime
                val nowMs = System.currentTimeMillis()

                // Requirement 1: Skip messages older than 24h
                if (nowMs - msgTimestamp > 24 * 60 * 60 * 1000L) {
                    decisions.add(
                        WhatsAppExtractionDecision(
                            chatTitle = chatTitle,
                            isGroup = isGroup,
                            isAllowlisted = true,
                            disposition = "skipped: older than 24h"
                        )
                    )
                } else if (isDeduplicated(chatTitle, cleanText, msgTimestamp)) {
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
                    val dedupeKey = "${chatTitle ?: "chat"}|${normalizeTextForDedupe(cleanText)}"
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
