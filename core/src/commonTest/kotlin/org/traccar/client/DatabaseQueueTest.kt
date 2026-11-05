package org.traccar.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class DatabaseQueueTest {

    private val queue = DatabaseQueue(testDriver())

    private suspend fun ids(limit: Int = 500) = queue.nextBatch(limit).map { it.id }

    @Test
    fun roundTripsEveryField() = runTest {
        val position = Position(
            latitude = -29.31,
            longitude = 27.48,
            accuracy = 5.0,
            time = 1_000,
            altitude = 2410.0,
            speed = 8.2,
            bearing = 140.0,
            battery = 82,
            charging = true,
            alarm = "sos",
            id = "a",
            deviceId = "rider-1",
            moving = false,
            extras = mapOf("sosId" to "s-1"),
        )
        queue.enqueue(position)
        assertEquals(position, queue.nextBatch(1).single())
        assertEquals(position, queue.peek())
    }

    @Test
    fun assignsAnIdWhenThePositionHasNone() = runTest {
        queue.enqueue(Position(time = 1))
        assertNotNull(queue.peek()?.id)
    }

    @Test
    fun newestFirstThenBacklogOldestToNewest() = runTest {
        listOf(100L to "b", 300L to "d", 200L to "c", 50L to "a", 400L to "newest")
            .forEach { (time, id) -> queue.enqueue(position(id, time)) }
        assertEquals(listOf("newest", "a", "b", "c", "d"), ids())
    }

    @Test
    fun aSinglePositionIsItsOwnBatch() = runTest {
        queue.enqueue(position("only"))
        assertEquals(listOf("only"), ids())
    }

    @Test
    fun thePositionWithTheLatestFixTimeIsNewestNotTheLastInserted() = runTest {
        queue.enqueue(position("later-fix", 500))
        queue.enqueue(position("earlier-fix", 100))
        assertEquals(listOf("later-fix", "earlier-fix"), ids())
    }

    @Test
    fun anAlarmInABacklogLeadsTheBatch() = runTest {
        (1L..5L).forEach { queue.enqueue(position("p$it", it * 100)) }
        queue.enqueue(position("sos-old", 150, alarm = "sos"))
        assertEquals(listOf("sos-old", "p5", "p1", "p2", "p3", "p4"), ids())
    }

    @Test
    fun anAlarmGetsIntoAFullBatch() = runTest {
        (1L..10L).forEach { queue.enqueue(position("p$it", it)) }
        queue.enqueue(position("sos", 0, alarm = "sos"))
        assertEquals(listOf("sos", "p10", "p1"), ids(limit = 3))
    }

    @Test
    fun newestAlarmLeadsWhenThereAreSeveral() = runTest {
        queue.enqueue(position("first", 100, alarm = "sos"))
        queue.enqueue(position("second", 200, alarm = "sos"))
        assertEquals(listOf("second", "first"), ids())
    }

    @Test
    fun removesOnlyTheNamedIds() = runTest {
        (1L..3L).forEach { queue.enqueue(position("p$it", it)) }
        queue.remove(listOf("p1", "p3", "unknown"))
        assertEquals(listOf("p2"), ids())
        queue.remove(emptyList())
        assertEquals(listOf("p2"), ids())
    }

    @Test
    fun aRejectedPositionIsKeptButNotSent() = runTest {
        queue.enqueue(position("a", 1))
        queue.enqueue(position("b", 2, alarm = "sos"))
        queue.enqueue(position("c", 3))
        queue.reject(mapOf("a" to "unknown_device", "b" to "bad"))

        assertEquals(listOf("c"), ids())
        assertEquals("c", queue.peek()?.id)
        assertEquals(QueueStatus(queued = 1, rejected = 2, queuedAlarms = 0, rejectedAlarms = 1), queue.status())

        queue.removeFirst()
        assertNull(queue.peek())
        assertEquals(QueueStatus(queued = 0, rejected = 2, queuedAlarms = 0, rejectedAlarms = 1), queue.status())
    }

    @Test
    fun aDeviceChangeDoesNotRelabelQueuedPositions() = runTest {
        queue.enqueue(position("a").copy(deviceId = "hostbrand-a"))
        queue.enqueue(position("b").copy(deviceId = "hostbrand-b"))
        assertEquals(setOf("hostbrand-a", "hostbrand-b"), queue.nextBatch(2).map { it.deviceId }.toSet())
    }

    @Test
    fun stockFirstInFirstOutStillWorks() = runTest {
        queue.enqueue(position("a", 2))
        queue.enqueue(position("b", 1))
        assertEquals("a", queue.peek()?.id)
        queue.removeFirst()
        assertEquals("b", queue.peek()?.id)
    }

    @Test
    fun countsAndEmptyQueue() = runTest {
        assertEquals(QueueStatus(0, 0, 0, 0), queue.status())
        assertEquals(emptyList(), queue.nextBatch(10))
        queue.enqueue(position("a"))
        queue.enqueue(position("s", alarm = "sos"))
        assertEquals(QueueStatus(queued = 2, rejected = 0, queuedAlarms = 1, rejectedAlarms = 0), queue.status())
    }
}
