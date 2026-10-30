package com.scheduleassistant.app

import com.scheduleassistant.app.data.model.ScheduleEntity
import com.scheduleassistant.app.data.model.ScheduleStatus
import com.scheduleassistant.app.domain.TriageLane
import com.scheduleassistant.app.voice.VoiceAction
import com.scheduleassistant.app.voice.VoiceSecretaryManager
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.*

class VoiceSecretaryManagerTest {

    @Test
    fun testVoiceOptionsAndDefaults() {
        val voices = VoiceSecretaryManager.AVAILABLE_VOICES
        assertTrue("Must have at least 3 voice options", voices.size >= 4)

        // Verify Sarah is present
        val sarah = voices.firstOrNull { it.id == "EXAVITQu4vr4xnSDxMaL" }
        assertNotNull("Sarah voice must exist", sarah)
        assertEquals("Sarah", sarah?.name)

        // Verify Android offline TTS is present
        val offline = voices.firstOrNull { it.id == "ON_DEVICE" }
        assertNotNull("Offline TTS must exist", offline)
        assertTrue("Offline voice must be marked offline", offline?.isOffline == true)

        assertEquals("EXAVITQu4vr4xnSDxMaL", VoiceSecretaryManager.DEFAULT_VOICE_ID)
        assertTrue("Default API key must start with sk_", VoiceSecretaryManager.DEFAULT_API_KEY.startsWith("sk_"))
    }

    @Test
    fun testBuildScheduleAnswerEmptyDay() {
        val answer = VoiceSecretaryManager.buildScheduleAnswer(
            query = "What is my schedule for today?",
            schedules = emptyList()
        )
        assertTrue("Should indicate agenda is clear", answer.contains("completely clear", ignoreCase = true))
    }

    @Test
    fun testBuildScheduleAnswerPopulatedToday() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val schedules = listOf(
            ScheduleEntity(
                id = "s-1",
                title = "DBMS Lab Viva",
                start = "${todayStr}T16:00:00+05:30",
                end = "${todayStr}T17:00:00+05:30",
                status = ScheduleStatus.CONFIRMED,
                lane = TriageLane.SCHEDULE
            ),
            ScheduleEntity(
                id = "s-2",
                title = "Math Assignment Submission",
                start = "${todayStr}T23:59:00+05:30",
                end = null,
                status = ScheduleStatus.CONFIRMED,
                lane = TriageLane.SCHEDULE
            )
        )

        val answer = VoiceSecretaryManager.buildScheduleAnswer(
            query = "wts my schedule today",
            schedules = schedules
        )

        assertTrue("Must mention 2 events", answer.contains("2 events for today"))
        assertTrue("Must mention DBMS Lab Viva", answer.contains("DBMS Lab Viva"))
        assertTrue("Must mention 4:00 PM", answer.contains("4:00 PM"))
        assertTrue("Must mention Math Assignment", answer.contains("Math Assignment Submission"))
    }

    @Test
    fun testBuildScheduleAnswerTomorrowQuery() {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val tomorrowStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)

        val schedules = listOf(
            ScheduleEntity(
                id = "s-tomorrow",
                title = "Compiler Design Workshop",
                start = "${tomorrowStr}T10:00:00+05:30",
                end = "${tomorrowStr}T12:00:00+05:30",
                status = ScheduleStatus.CONFIRMED,
                lane = TriageLane.SCHEDULE
            )
        )

        // Query with 'tomorrow'
        val answerTomorrow = VoiceSecretaryManager.buildScheduleAnswer(
            query = "what is happening tomorrow",
            schedules = schedules
        )
        assertTrue("Must mention tomorrow", answerTomorrow.contains("tomorrow", ignoreCase = true))
        assertTrue("Must include workshop", answerTomorrow.contains("Compiler Design Workshop"))

        // Query with Hinglish 'kal'
        val answerKal = VoiceSecretaryManager.buildScheduleAnswer(
            query = "bhai kal kya schedule hai",
            schedules = schedules
        )
        assertTrue("Must detect tomorrow from 'kal'", answerKal.contains("tomorrow", ignoreCase = true))
        assertTrue("Must include workshop", answerKal.contains("Compiler Design Workshop"))
    }

    @Test
    fun testBuildMorningBriefing() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val schedules = listOf(
            ScheduleEntity(
                id = "s-1",
                title = "Team Standup",
                start = "${todayStr}T09:30:00+05:30",
                status = ScheduleStatus.CONFIRMED,
                lane = TriageLane.SCHEDULE
            ),
            ScheduleEntity(
                id = "p-1",
                title = "Unconfirmed Meeting Invite",
                start = "${todayStr}T14:00:00+05:30",
                status = ScheduleStatus.PENDING,
                lane = TriageLane.NEEDS_ATTENTION
            )
        )

        val briefing = VoiceSecretaryManager.buildMorningBriefing(schedules)
        assertTrue("Must start with Good morning", briefing.startsWith("Good morning!"))
        assertTrue("Must mention 1 event today", briefing.contains("1 event today"))
        assertTrue("Must mention Team Standup", briefing.contains("Team Standup"))
        assertTrue("Must mention pending review item", briefing.contains("1 new item to review"))
    }

    @Test
    fun testIgnoresDeletedEvents() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val deletedSchedule = ScheduleEntity(
            id = "s-del",
            title = "Cancelled Class",
            start = "${todayStr}T11:00:00+05:30",
            status = ScheduleStatus.CONFIRMED,
            lane = TriageLane.SCHEDULE,
            deleted = true
        )

        val answer = VoiceSecretaryManager.buildScheduleAnswer("today", listOf(deletedSchedule))
        assertTrue("Deleted schedule should not be spoken", answer.contains("completely clear"))
        assertFalse(answer.contains("Cancelled Class"))
    }

    @Test
    fun testConversationalGreetings() {
        val answerHello = VoiceSecretaryManager.buildScheduleAnswer("hello", emptyList())
        assertTrue("Must greet politely", answerHello.contains("schedule secretary", ignoreCase = true))

        val answerWho = VoiceSecretaryManager.buildScheduleAnswer("who are you", emptyList())
        assertTrue("Must explain identity", answerWho.contains("schedule secretary", ignoreCase = true))
    }

    @Test
    fun testGratitudeAndFarewell() {
        val answerThanks = VoiceSecretaryManager.buildScheduleAnswer("thank you so much", emptyList())
        assertTrue("Must say you're welcome", answerThanks.contains("welcome", ignoreCase = true))
    }

    @Test
    fun testPendingRemindersQuery() {
        val pending = ScheduleEntity(
            id = "p-9",
            title = "Physics Assignment Review",
            start = "2026-10-30T15:00:00+05:30",
            status = ScheduleStatus.PENDING,
            lane = TriageLane.NEEDS_ATTENTION
        )
        val answerPending = VoiceSecretaryManager.buildScheduleAnswer("any pending reminders", listOf(pending))
        assertTrue("Must report 1 pending reminder", answerPending.contains("1 pending reminder"))
        assertTrue("Must name the title", answerPending.contains("Physics Assignment Review"))

        val answerEmptyPending = VoiceSecretaryManager.buildScheduleAnswer("what is in my inbox", emptyList())
        assertTrue("Must report clear inbox", answerEmptyPending.contains("completely clear", ignoreCase = true))
    }

    @Test
    fun testFreeTimeQuery() {
        val answerFree = VoiceSecretaryManager.buildScheduleAnswer("am i free today", emptyList())
        assertTrue("Must state user is free", answerFree.contains("completely free", ignoreCase = true))
    }

    @Test
    fun testParseAlarmCommandMorningTomorrow() {
        val cmd = VoiceSecretaryManager.parseAlarmCommand("set an alarm for 7:00 AM tomorrow")
        assertNotNull("Command must be recognized as alarm", cmd)
        assertEquals("Alarm", cmd?.title)
        assertEquals("7:00 AM", cmd?.friendlyTime)
        assertEquals("tomorrow", cmd?.friendlyDay)
        assertEquals(7, cmd?.targetCalendar?.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cmd?.targetCalendar?.get(Calendar.MINUTE))
    }

    @Test
    fun testParseAlarmCommandWakeUp() {
        val cmd = VoiceSecretaryManager.parseAlarmCommand("wake me up at 6:30 tomorrow")
        assertNotNull("Wake up command must be recognized", cmd)
        assertEquals("Wake Up", cmd?.title)
        assertEquals("6:30 AM", cmd?.friendlyTime)
        assertEquals(6, cmd?.targetCalendar?.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cmd?.targetCalendar?.get(Calendar.MINUTE))
    }

    @Test
    fun testParseAlarmCommandSpecificTitle() {
        val cmd = VoiceSecretaryManager.parseAlarmCommand("set an alarm for 5 pm for Physics Viva")
        assertNotNull("Alarm with custom title must parse", cmd)
        assertEquals("Physics Viva", cmd?.title)
        assertEquals("5:00 PM", cmd?.friendlyTime)
        assertEquals(17, cmd?.targetCalendar?.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testVoiceAlarmResponse() {
        val answer = VoiceSecretaryManager.buildScheduleAnswer("set an alarm for 8:00 AM tomorrow for Math Exam", emptyList())
        assertTrue("Response must confirm alarm set", answer.startsWith("Done! I've set an alarm"))
        assertTrue("Response must state 8:00 AM", answer.contains("8:00 AM"))
        assertTrue("Response must state title", answer.contains("Math Exam"))
    }

    @Test
    fun testParseAlarmCommandRemindMePrefix() {
        val cmd = VoiceSecretaryManager.parseAlarmCommand("remind me to call Mom at 5 PM")
        assertNotNull("Must parse reminder command", cmd)
        assertEquals("Call Mom", cmd?.title)
        assertEquals("5:00 PM", cmd?.friendlyTime)
        assertEquals(17, cmd?.targetCalendar?.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testResolveVoiceActionDeleteAlarmByTime() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val alarm = ScheduleEntity(
            id = "alarm-7am",
            title = "Morning Run",
            start = "${todayStr}T07:00:00+05:30",
            status = ScheduleStatus.CONFIRMED,
            has_alarm = true,
            lane = TriageLane.SCHEDULE
        )

        val action = VoiceSecretaryManager.resolveVoiceAction("cancel my 7:00 AM alarm", listOf(alarm))
        assertTrue("Must resolve to DeleteAlarm", action is VoiceAction.DeleteAlarm)
        val delete = action as VoiceAction.DeleteAlarm
        assertEquals("alarm-7am", delete.targetSchedule.id)
        assertEquals("Morning Run", delete.targetSchedule.title)
    }

    @Test
    fun testResolveVoiceActionDeleteAlarmByTitle() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val alarm = ScheduleEntity(
            id = "alarm-phys",
            title = "Physics Viva",
            start = "${todayStr}T16:00:00+05:30",
            status = ScheduleStatus.CONFIRMED,
            has_alarm = true,
            lane = TriageLane.SCHEDULE
        )

        val action = VoiceSecretaryManager.resolveVoiceAction("turn off the Physics alarm", listOf(alarm))
        assertTrue("Must resolve to DeleteAlarm by title", action is VoiceAction.DeleteAlarm)
        val delete = action as VoiceAction.DeleteAlarm
        assertEquals("alarm-phys", delete.targetSchedule.id)
    }

    @Test
    fun testResolveVoiceActionDeleteAllAlarms() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val alarms = listOf(
            ScheduleEntity(id = "a-1", title = "Alarm 1", start = "${todayStr}T07:00:00+05:30", status = ScheduleStatus.CONFIRMED, has_alarm = true),
            ScheduleEntity(id = "a-2", title = "Alarm 2", start = "${todayStr}T08:00:00+05:30", status = ScheduleStatus.CONFIRMED, has_alarm = true)
        )

        val action = VoiceSecretaryManager.resolveVoiceAction("turn off all alarms", alarms)
        assertTrue("Must resolve to DeleteAllAlarms", action is VoiceAction.DeleteAllAlarms)
        val delAll = action as VoiceAction.DeleteAllAlarms
        assertEquals(2, delAll.targets.size)
    }

    @Test
    fun testResolveVoiceActionUpdateAlarm() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val alarm = ScheduleEntity(
            id = "alarm-7am",
            title = "Wake Up",
            start = "${todayStr}T07:00:00+05:30",
            status = ScheduleStatus.CONFIRMED,
            has_alarm = true,
            lane = TriageLane.SCHEDULE
        )

        val action = VoiceSecretaryManager.resolveVoiceAction("change my 7:00 AM alarm to 7:30 AM", listOf(alarm))
        assertTrue("Must resolve to UpdateAlarm", action is VoiceAction.UpdateAlarm)
        val update = action as VoiceAction.UpdateAlarm
        assertEquals("alarm-7am", update.targetSchedule.id)
        assertEquals("7:30 AM", update.newFriendlyTime)
        assertTrue(update.newIsoStart.contains("T07:30:00"))
    }

    @Test
    fun testResolveVoiceActionReadAlarms() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val alarm = ScheduleEntity(
            id = "alarm-7am",
            title = "Morning Alarm",
            start = "${todayStr}T07:00:00+05:30",
            status = ScheduleStatus.CONFIRMED,
            has_alarm = true
        )

        val action = VoiceSecretaryManager.resolveVoiceAction("what alarms are set for today", listOf(alarm))
        assertTrue("Must resolve to ReadAlarms", action is VoiceAction.ReadAlarms)
        val read = action as VoiceAction.ReadAlarms
        assertEquals("today", read.targetDay)
        assertEquals(1, read.alarms.size)
        assertEquals("alarm-7am", read.alarms[0].id)
    }

    @Test
    fun testResolveVoiceActionConfirmSchedule() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val pending = ScheduleEntity(
            id = "pending-math",
            title = "Math Group Study",
            start = "${todayStr}T18:00:00+05:30",
            status = ScheduleStatus.PENDING,
            lane = TriageLane.SCHEDULE
        )

        val action = VoiceSecretaryManager.resolveVoiceAction("confirm math group study", listOf(pending))
        assertTrue("Must resolve to ConfirmSchedule", action is VoiceAction.ConfirmSchedule)
        val confirm = action as VoiceAction.ConfirmSchedule
        assertEquals("pending-math", confirm.targetSchedule.id)
    }

    @Test
    fun testResolveVoiceActionConfirmAllPending() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val pendings = listOf(
            ScheduleEntity(id = "p-1", title = "Task 1", start = "${todayStr}T10:00:00+05:30", status = ScheduleStatus.PENDING),
            ScheduleEntity(id = "p-2", title = "Task 2", start = "${todayStr}T11:00:00+05:30", status = ScheduleStatus.PENDING)
        )

        val action = VoiceSecretaryManager.resolveVoiceAction("confirm all pending reminders", pendings)
        assertTrue("Must resolve to ConfirmAllPending", action is VoiceAction.ConfirmAllPending)
        val confirmAll = action as VoiceAction.ConfirmAllPending
        assertEquals(2, confirmAll.targets.size)
    }

    @Test
    fun testResolveVoiceActionReadInbox() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val pending = ScheduleEntity(
            id = "p-1",
            title = "Chemistry Viva",
            start = "${todayStr}T14:00:00+05:30",
            status = ScheduleStatus.PENDING
        )

        val action = VoiceSecretaryManager.resolveVoiceAction("what messages are in my inbox", listOf(pending))
        assertTrue("Must resolve to ReadInbox", action is VoiceAction.ReadInbox)
        val readInbox = action as VoiceAction.ReadInbox
        assertEquals(1, readInbox.pendingItems.size)
        assertEquals("Chemistry Viva", readInbox.pendingItems[0].title)
    }

    @Test
    fun testParseAlarmCommandRelativeMinutesAfter5Min() {
        val now = Calendar.getInstance()
        val cmd = VoiceSecretaryManager.parseAlarmCommand("set an alarm for me after 5 min")
        assertNotNull("Relative alarm command 'after 5 min' must be recognized", cmd)
        assertEquals("Alarm", cmd?.title)

        // Verify target calendar is within 4 to 6 minutes from now
        val diffMs = (cmd?.targetCalendar?.timeInMillis ?: 0L) - now.timeInMillis
        val diffMinutes = diffMs / (60 * 1000)
        assertTrue("Difference must be ~5 minutes, was $diffMinutes", diffMinutes in 4..6)
    }

    @Test
    fun testParseAlarmCommandRelativeHours() {
        val now = Calendar.getInstance()
        val cmd = VoiceSecretaryManager.parseAlarmCommand("remind me in 1 hour to submit assignment")
        assertNotNull("Relative alarm command 'in 1 hour' must be recognized", cmd)
        assertEquals("Submit Assignment", cmd?.title)

        val diffMs = (cmd?.targetCalendar?.timeInMillis ?: 0L) - now.timeInMillis
        val diffMinutes = diffMs / (60 * 1000)
        assertTrue("Difference must be ~60 minutes, was $diffMinutes", diffMinutes in 59..61)
    }

    @Test
    fun testResolveVoiceActionRelativeDelay() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val alarm = ScheduleEntity(
            id = "a-1",
            title = "Wake Up",
            start = "${todayStr}T07:00:00+05:30",
            status = ScheduleStatus.CONFIRMED,
            has_alarm = true
        )

        val action = VoiceSecretaryManager.resolveVoiceAction("delay my alarm by 15 minutes", listOf(alarm))
        assertTrue("Must resolve to UpdateAlarm", action is VoiceAction.UpdateAlarm)
        val update = action as VoiceAction.UpdateAlarm
        assertEquals("a-1", update.targetSchedule.id)
        assertTrue("Start must be moved to 07:15", update.newIsoStart.contains("07:15:00"))
    }

    @Test
    fun testResolveVoiceActionChangeAlarmSingleTime() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val alarm = ScheduleEntity(
            id = "a-1",
            title = "Standup",
            start = "${todayStr}T09:00:00+05:30",
            status = ScheduleStatus.CONFIRMED,
            has_alarm = true
        )

        val action = VoiceSecretaryManager.resolveVoiceAction("change alarm to 10:00 AM", listOf(alarm))
        assertTrue("Must resolve to UpdateAlarm", action is VoiceAction.UpdateAlarm)
        val update = action as VoiceAction.UpdateAlarm
        assertEquals("a-1", update.targetSchedule.id)
        assertTrue("Start must be moved to 10:00 AM", update.newIsoStart.contains("10:00:00"))
    }
}


