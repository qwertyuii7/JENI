package com.scheduleassistant.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.scheduleassistant.app.R
import com.scheduleassistant.app.data.db.AppDatabase
import com.scheduleassistant.app.data.model.ScheduleEntity
import com.scheduleassistant.app.data.model.ScheduleStatus
import com.scheduleassistant.app.data.projection.ProjectionEngine
import com.scheduleassistant.app.domain.TriageLane
import com.scheduleassistant.app.ui.MainActivity
import com.scheduleassistant.app.ui.toSyncEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DigestData(
    val todayEventCount: Int,
    val attentionItemCount: Int,
    val todayEvents: List<ScheduleEntity> = emptyList(),
    val attentionItems: List<ScheduleEntity> = emptyList()
) {
    val summaryText: String
        get() {
            return when {
                todayEventCount == 0 && attentionItemCount == 0 ->
                    "All caught up! No events scheduled for today and no items need attention."
                todayEventCount > 0 && attentionItemCount == 0 ->
                    "$todayEventCount event${if (todayEventCount > 1) "s" else ""} scheduled for today."
                todayEventCount == 0 && attentionItemCount > 0 ->
                    "$attentionItemCount item${if (attentionItemCount > 1) "s" else ""} need${if (attentionItemCount == 1) "s" else ""} your attention in Inbox."
                else ->
                    "$todayEventCount event${if (todayEventCount > 1) "s" else ""} today • $attentionItemCount item${if (attentionItemCount > 1) "s" else ""} need${if (attentionItemCount == 1) "s" else ""} attention"
            }
        }
}

object DailyDigestNotifier {

    const val CHANNEL_ID = "daily_digest"
    const val NOTIFICATION_ID = 4001

    fun buildDigestSummary(
        schedules: List<ScheduleEntity>,
        todayDateStr: String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    ): DigestData {
        val todayEvents = schedules.filter { sched ->
            sched.status == ScheduleStatus.CONFIRMED &&
                    !sched.deleted &&
                    (sched.start?.startsWith(todayDateStr) == true || sched.start_date == todayDateStr)
        }
        val attentionItems = schedules.filter { sched ->
            sched.status == ScheduleStatus.PENDING &&
                    sched.lane == TriageLane.NEEDS_ATTENTION &&
                    !sched.deleted
        }
        return DigestData(
            todayEventCount = todayEvents.size,
            attentionItemCount = attentionItems.size,
            todayEvents = todayEvents,
            attentionItems = attentionItems
        )
    }

    fun showDigestNotification(context: Context, digestData: DigestData): Boolean {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Daily Digest",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Daily morning summary of your schedule and items needing attention"
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("navigate_tab", "inbox")
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = if (digestData.todayEventCount == 0 && digestData.attentionItemCount == 0) {
                "Schedule Assistant: All Caught Up"
            } else {
                "Daily Digest: ${digestData.todayEventCount} Today • ${digestData.attentionItemCount} Need Attention"
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_logo_brand)
                .setContentTitle(title)
                .setContentText(digestData.summaryText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(digestData.summaryText))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(NOTIFICATION_ID, notification)
            return true
        } catch (_: Exception) {
            return false
        }
    }

    suspend fun checkAndDispatchDigest(context: Context): DigestData {
        val db = AppDatabase.getInstance(context)
        val eventEntities = db.eventDao().getAllEvents()
        val events = eventEntities.map { it.toSyncEvent() }
        val state = ProjectionEngine.projectState(events)
        val digest = buildDigestSummary(state.schedules.values.toList())
        showDigestNotification(context, digest)
        return digest
    }
}
