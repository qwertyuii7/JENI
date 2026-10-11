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
}
