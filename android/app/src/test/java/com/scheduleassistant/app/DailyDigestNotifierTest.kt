package com.scheduleassistant.app

import com.scheduleassistant.app.data.model.ScheduleEntity
import com.scheduleassistant.app.data.model.ScheduleStatus
import com.scheduleassistant.app.domain.TriageLane
import com.scheduleassistant.app.service.DailyDigestNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyDigestNotifierTest {

    @Test
    fun testAllCaughtUpWhenNoEventsOrAttentionItems() {
        val digest = DailyDigestNotifier.buildDigestSummary(
            schedules = emptyList(),
            todayDateStr = "2026-10-21"
        )
        assertEquals(0, digest.todayEventCount)
        assertEquals(0, digest.attentionItemCount)
        assertTrue(digest.summaryText.contains("All caught up"))
    }

    @Test
    fun testDigestWithOnlyTodayEvents() {
        val schedules = listOf(
            ScheduleEntity(
                id = "s-1",
                title = "Team Standup",
                start = "2026-10-21T10:00:00",
                status = ScheduleStatus.CONFIRMED,
                lane = TriageLane.SCHEDULE
            ),
            ScheduleEntity(
                id = "s-2",
                title = "Design Sync",
                start = "2026-10-21T14:30:00",
                status = ScheduleStatus.CONFIRMED,
                lane = TriageLane.SCHEDULE
            ),
            ScheduleEntity(
                id = "s-3",
                title = "Tomorrow Workshop",
                start = "2026-10-22T09:00:00",
                status = ScheduleStatus.CONFIRMED,
                lane = TriageLane.SCHEDULE
            )
        )

        val digest = DailyDigestNotifier.buildDigestSummary(schedules, todayDateStr = "2026-10-21")
        assertEquals(2, digest.todayEventCount)
        assertEquals(0, digest.attentionItemCount)
        assertEquals("2 events scheduled for today.", digest.summaryText)
    }

    @Test
    fun testDigestWithOnlyAttentionItems() {
        val schedules = listOf(
            ScheduleEntity(
                id = "att-1",
                title = "Scholarship notice pending review",
                status = ScheduleStatus.PENDING,
                lane = TriageLane.NEEDS_ATTENTION
            ),
            ScheduleEntity(
                id = "att-2",
                title = "Project submission meeting without time",
                status = ScheduleStatus.PENDING,
                lane = TriageLane.NEEDS_ATTENTION
            )
        )

        val digest = DailyDigestNotifier.buildDigestSummary(schedules, todayDateStr = "2026-10-21")
        assertEquals(0, digest.todayEventCount)
        assertEquals(2, digest.attentionItemCount)
        assertEquals("2 items need your attention in Inbox.", digest.summaryText)
    }

    @Test
    fun testDigestWithBothEventsAndAttentionItems() {
        val schedules = listOf(
            ScheduleEntity(
                id = "s-1",
                title = "Lab Exam",
                start = "2026-10-21T11:00:00",
                status = ScheduleStatus.CONFIRMED,
                lane = TriageLane.SCHEDULE
            ),
            ScheduleEntity(
                id = "att-1",
                title = "Roll number call",
                status = ScheduleStatus.PENDING,
                lane = TriageLane.NEEDS_ATTENTION
            )
        )

        val digest = DailyDigestNotifier.buildDigestSummary(schedules, todayDateStr = "2026-10-21")
        assertEquals(1, digest.todayEventCount)
        assertEquals(1, digest.attentionItemCount)
        assertEquals("1 event today • 1 item needs attention", digest.summaryText)
    }

    @Test
    fun testTombstonedItemsAreExcludedFromDigest() {
        val schedules = listOf(
            ScheduleEntity(
                id = "s-del",
                title = "Deleted Event",
                start = "2026-10-21T15:00:00",
                status = ScheduleStatus.CONFIRMED,
                lane = TriageLane.SCHEDULE,
                deleted = true
            ),
            ScheduleEntity(
                id = "att-del",
                title = "Deleted Attention Item",
                status = ScheduleStatus.PENDING,
                lane = TriageLane.NEEDS_ATTENTION,
                deleted = true
            )
        )

        val digest = DailyDigestNotifier.buildDigestSummary(schedules, todayDateStr = "2026-10-21")
        assertEquals(0, digest.todayEventCount)
        assertEquals(0, digest.attentionItemCount)
    }
}
