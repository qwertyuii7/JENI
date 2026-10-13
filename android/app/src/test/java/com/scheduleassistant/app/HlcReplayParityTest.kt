package com.scheduleassistant.app

import com.scheduleassistant.app.data.model.ScheduleStatus
import com.scheduleassistant.app.data.model.SyncEvent
import com.scheduleassistant.app.data.projection.ProjectionEngine
import com.scheduleassistant.app.engine.HlcClock
import org.junit.Assert.*
import org.junit.Test

class HlcReplayParityTest {

    @Test
    fun testHlcMonotonicOrdering() {
        val hlc = HlcClock("device-a")
        val t1 = hlc.now()
        val t2 = hlc.now()
        val t3 = hlc.now()

        assertTrue(HlcClock.compare(t1, "device-a", t2, "device-a") < 0)
        assertTrue(HlcClock.compare(t2, "device-a", t3, "device-a") < 0)
    }

    @Test
    fun testShuffledAndDuplicatedEventsYieldIdenticalState() {
        val events = listOf(
            SyncEvent(
                id = "ev-1",
                timestamp = "2026-10-12T10:00:00Z",
                device_id = "device-1",
                seq = 1,
                hlc = "1000:0:device-1",
                type = "event_extracted",
                entity_id = "sched-100",
                payload = mapOf(
                    "title" to "Maths Class",
                    "start" to "2026-10-13T10:00:00+05:30",
                    "confidence" to 0.9
                )
            ),
            SyncEvent(
                id = "ev-2",
                timestamp = "2026-10-12T10:05:00Z",
                device_id = "device-1",
                seq = 2,
                hlc = "1005:0:device-1",
                type = "event_confirmed",
                entity_id = "sched-100",
                payload = emptyMap()
            ),
            SyncEvent(
                id = "ev-3",
                timestamp = "2026-10-12T10:10:00Z",
                device_id = "device-2",
                seq = 1,
                hlc = "1010:0:device-2",
                type = "event_extracted",
                entity_id = "sched-200",
                payload = mapOf(
                    "title" to "Physics Lab",
                    "start" to "2026-10-14T14:00:00+05:30",
                    "confidence" to 0.85
                )
            )
        )

        // 1. Order 1: Natural order
        val stateNatural = ProjectionEngine.projectState(events)

        // 2. Order 2: Shuffled order
        val shuffled = listOf(events[2], events[0], events[1])
        val stateShuffled = ProjectionEngine.projectState(shuffled)

        // 3. Order 3: Duplicated + shuffled
        val duplicated = listOf(events[1], events[0], events[2], events[0], events[1])
        val stateDuplicated = ProjectionEngine.projectState(duplicated)

        // Assert all 3 projections are identical
        assertEquals(stateNatural.schedules.size, stateShuffled.schedules.size)
        assertEquals(stateNatural.schedules.size, stateDuplicated.schedules.size)

        for (id in stateNatural.schedules.keys) {
            val schedNat = stateNatural.schedules[id]!!
            val schedShuf = stateShuffled.schedules[id]!!
            val schedDup = stateDuplicated.schedules[id]!!

            assertEquals(schedNat.id, schedShuf.id)
            assertEquals(schedNat.id, schedDup.id)
            assertEquals(schedNat.status, schedShuf.status)
            assertEquals(schedNat.status, schedDup.status)
            assertEquals(schedNat.title, schedShuf.title)
            assertEquals(schedNat.title, schedDup.title)
            assertEquals(schedNat.start, schedShuf.start)
            assertEquals(schedNat.start, schedDup.start)
        }
    }

    @Test
    fun testFullReplayEqualsIncremental() {
        val ev1 = SyncEvent(
            id = "ev-1",
            timestamp = "2026-10-12T10:00:00Z",
            device_id = "device-1",
            seq = 1,
            hlc = "1000:0:device-1",
            type = "event_extracted",
            entity_id = "sched-300",
            payload = mapOf("title" to "DBMS Viva", "start" to "2026-10-13T14:00:00+05:30")
        )
        val ev2 = SyncEvent(
            id = "ev-2",
            timestamp = "2026-10-12T10:05:00Z",
            device_id = "device-1",
            seq = 2,
            hlc = "1005:0:device-1",
            type = "event_confirmed",
            entity_id = "sched-300",
            payload = mapOf("start" to "2026-10-13T16:00:00+05:30")
        )

        // Incremental replay (step 1, then step 2)
        val step1 = ProjectionEngine.projectState(listOf(ev1))
        assertEquals(ScheduleStatus.PENDING, step1.schedules["sched-300"]?.status)

        val step2 = ProjectionEngine.projectState(listOf(ev1, ev2))
        assertEquals(ScheduleStatus.CONFIRMED, step2.schedules["sched-300"]?.status)
        assertEquals("2026-10-13T16:00:00+05:30", step2.schedules["sched-300"]?.start)

        // Full batch replay
        val full = ProjectionEngine.projectState(listOf(ev1, ev2))
        assertEquals(step2.schedules["sched-300"]?.status, full.schedules["sched-300"]?.status)
        assertEquals(step2.schedules["sched-300"]?.start, full.schedules["sched-300"]?.start)
    }

    @Test
    fun testDeleteFollowedByOlderEditStaysDeletedUnderHlcOrdering() {
        val hlcCreate = "1000:0:device-1"
        val hlcConfirm = "1001:0:device-1"
        val hlcOlderEdit = "1005:0:device-2"
        val hlcNewerDelete = "1010:0:device-1"

        val evCreate = SyncEvent(
            id = "ev-create",
            timestamp = "2026-10-12T10:00:00Z",
            device_id = "device-1",
            seq = 1,
            hlc = hlcCreate,
            type = "event_extracted",
            entity_id = "sched-hlc-del",
            payload = mapOf("title" to "Original Class", "start" to "2026-10-13T10:00:00+05:30")
        )

        val evConfirm = SyncEvent(
            id = "ev-confirm",
            timestamp = "2026-10-12T10:01:00Z",
            device_id = "device-1",
            seq = 2,
            hlc = hlcConfirm,
            type = "event_confirmed",
            entity_id = "sched-hlc-del",
            payload = emptyMap()
        )

        val evDelete = SyncEvent(
            id = "ev-delete",
            timestamp = "2026-10-12T10:10:00Z",
            device_id = "device-1",
            seq = 3,
            hlc = hlcNewerDelete,
            type = "schedule_deleted",
            entity_id = "sched-hlc-del",
            payload = mapOf("reason" to "user")
        )

        val evOlderEdit = SyncEvent(
            id = "ev-older-edit",
            timestamp = "2026-10-12T10:05:00Z",
            device_id = "device-2",
            seq = 1,
            hlc = hlcOlderEdit,
            type = "schedule_edited",
            entity_id = "sched-hlc-del",
            payload = mapOf("title" to "Class Edited on Device 2")
        )

        // 1. Natural arrival order: delete arrived first, then older edit arrived from sync
        val stateArrival = ProjectionEngine.projectState(listOf(evCreate, evConfirm, evDelete, evOlderEdit))
        assertFalse("Schedule must stay deleted when older edit arrives", stateArrival.schedules.containsKey("sched-hlc-del"))

        // 2. Shuffled arrival order
        val stateShuffled = ProjectionEngine.projectState(listOf(evOlderEdit, evDelete, evConfirm, evCreate))
        assertFalse("Schedule must stay deleted regardless of event ingestion order", stateShuffled.schedules.containsKey("sched-hlc-del"))

        // 3. Canonical HLC sorted order
        val canonical = listOf(evCreate, evConfirm, evOlderEdit, evDelete)
        val stateCanonical = ProjectionEngine.projectState(canonical)
        assertFalse("Schedule must stay deleted under canonical HLC order", stateCanonical.schedules.containsKey("sched-hlc-del"))
    }

    @Test
    fun testIdempotentDeletionHidesSchedulesAndReminders() {
        val evCreate = SyncEvent(
            id = "ev-c",
            timestamp = "2026-10-12T10:00:00Z",
            device_id = "device-1",
            seq = 1,
            hlc = "1000:0:device-1",
            type = "event_extracted",
            entity_id = "sched-rem-test",
            payload = mapOf("title" to "OS Lecture", "start" to "2026-10-13T11:00:00+05:30")
        )

        val evConfirm = SyncEvent(
            id = "ev-conf",
            timestamp = "2026-10-12T10:01:00Z",
            device_id = "device-1",
            seq = 2,
            hlc = "1001:0:device-1",
            type = "event_confirmed",
            entity_id = "sched-rem-test",
            payload = emptyMap()
        )

        val evRem = SyncEvent(
            id = "rem-ev",
            timestamp = "2026-10-12T10:02:00Z",
            device_id = "device-1",
            seq = 3,
            hlc = "1002:0:device-1",
            type = "reminder_created",
            entity_id = "rem-sched-rem-test",
            payload = mapOf(
                "schedule_id" to "sched-rem-test",
                "title" to "OS Lecture",
                "trigger_time" to "2026-10-13T10:45:00+05:30"
            )
        )

        val evAlarm = SyncEvent(
            id = "alarm-ev",
            timestamp = "2026-10-12T10:03:00Z",
            device_id = "device-1",
            seq = 4,
            hlc = "1003:0:device-1",
            type = "alarm_set",
            entity_id = "alarm-sched-rem-test",
            payload = mapOf(
                "schedule_id" to "sched-rem-test",
                "title" to "OS Lecture",
                "trigger_time" to "2026-10-13T11:00:00+05:30"
            )
        )

        val activeState = ProjectionEngine.projectState(listOf(evCreate, evConfirm, evRem, evAlarm))
        assertTrue(activeState.schedules.containsKey("sched-rem-test"))
        assertTrue(activeState.reminders.containsKey("rem-sched-rem-test"))
        assertTrue(activeState.reminders.containsKey("alarm-sched-rem-test"))

        // First deletion tombstone
        val evDel1 = SyncEvent(
            id = "del-1",
            timestamp = "2026-10-12T10:04:00Z",
            device_id = "device-1",
            seq = 5,
            hlc = "1004:0:device-1",
            type = "schedule_deleted",
            entity_id = "sched-rem-test",
            payload = mapOf("reason" to "user")
        )

        val deletedState = ProjectionEngine.projectState(listOf(evCreate, evConfirm, evRem, evAlarm, evDel1))
        assertFalse("Deleted schedule must be hidden from schedules", deletedState.schedules.containsKey("sched-rem-test"))
        assertFalse("Reminder of deleted schedule must be hidden", deletedState.reminders.containsKey("rem-sched-rem-test"))
        assertFalse("Alarm of deleted schedule must be hidden", deletedState.reminders.containsKey("alarm-sched-rem-test"))

        // Second deletion tombstone (idempotency)
        val evDel2 = SyncEvent(
            id = "del-2",
            timestamp = "2026-10-12T10:05:00Z",
            device_id = "device-1",
            seq = 6,
            hlc = "1005:0:device-1",
            type = "schedule_deleted",
            entity_id = "sched-rem-test",
            payload = mapOf("reason" to "user")
        )

        val idempotentState = ProjectionEngine.projectState(listOf(evCreate, evConfirm, evRem, evAlarm, evDel1, evDel2))
        assertFalse(idempotentState.schedules.containsKey("sched-rem-test"))
        assertFalse(idempotentState.reminders.containsKey("rem-sched-rem-test"))
        assertFalse(idempotentState.reminders.containsKey("alarm-sched-rem-test"))

        // Restore via undo
        val evRestore = SyncEvent(
            id = "restore-1",
            timestamp = "2026-10-12T10:06:00Z",
            device_id = "device-1",
            seq = 7,
            hlc = "1006:0:device-1",
            type = "schedule_restored",
            entity_id = "sched-rem-test",
            payload = mapOf("reason" to "user_undo")
        )

        val restoredState = ProjectionEngine.projectState(listOf(evCreate, evConfirm, evRem, evAlarm, evDel1, evDel2, evRestore))
        assertTrue("Restored schedule must be visible", restoredState.schedules.containsKey("sched-rem-test"))
        assertEquals(ScheduleStatus.CONFIRMED, restoredState.schedules["sched-rem-test"]?.status)
        assertTrue("Restored schedule reminder must be visible", restoredState.reminders.containsKey("rem-sched-rem-test"))
        assertTrue("Restored schedule alarm must be visible", restoredState.reminders.containsKey("alarm-sched-rem-test"))
    }
}
