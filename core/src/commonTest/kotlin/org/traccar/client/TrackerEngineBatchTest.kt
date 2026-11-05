package org.traccar.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class TrackerEngineBatchTest {

    private val queue = FakeQueue()
    private val network = FakeNetwork()
    private val location = FakeLocationSource()
    private val uploader = FakeBatchUploader()
    private val single = RecordingUploader()

    private suspend fun TestScope.engine(
        batch: BatchConfig = BatchConfig(enabled = true, flushSeconds = 10, jitterSeconds = 30),
        batched: Boolean = true,
        buffer: Boolean = true,
        deviceId: String = "rider-1",
    ) = TrackerEngine(
        stateStore = StateStore.create(testDriver()).also { store -> store.update { it.copy(enabled = true) } },
        queue = queue,
        network = network,
        locationSource = location,
        signalSources = emptyList(),
        processors = emptyList(),
        uploader = single,
        buffer = buffer,
        scope = ComponentCoroutineScope(backgroundScope.coroutineContext),
        deviceId = deviceId,
        batchUploader = uploader.takeIf { batched },
        batch = batch,
        random = MaxRandom,
    )

    private fun ids(call: Int) = uploader.calls[call].map { it.id }

    /** Gets past the startup jitter on an empty queue. */
    private fun TestScope.settle() = advanceTimeBy(1.seconds)

    @Test
    fun livePositionsGatherThenGoTogether() = runTest {
        val engine = engine()
        settle()
        engine.submit(position("a", 1))
        runCurrent()
        advanceTimeBy(5.seconds)
        engine.submit(position("b", 2))
        runCurrent()
        assertTrue(uploader.calls.isEmpty(), "held for the flush window")

        advanceTimeBy(6.seconds)
        runCurrent()
        assertEquals(1, uploader.calls.size)
        assertEquals(listOf("b", "a"), ids(0))
    }

    @Test
    fun aBacklogAtStartupWaitsForTheJitter() = runTest {
        queue.enqueue(position("old"))
        engine()
        advanceTimeBy(29.seconds)
        runCurrent()
        assertTrue(uploader.calls.isEmpty())
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(listOf("old"), ids(0))
    }

    @Test
    fun reconnectingWaitsForTheJitter() = runTest {
        network.isOnline.value = false
        val engine = engine()
        settle()
        engine.submit(position("a"))
        advanceTimeBy(600.seconds)
        runCurrent()
        assertTrue(uploader.calls.isEmpty(), "nothing sent offline")
        assertEquals(1, queue.status().queued)

        network.isOnline.value = true
        advanceTimeBy(29.seconds)
        runCurrent()
        assertTrue(uploader.calls.isEmpty(), "still inside the jitter")

        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(listOf("a"), ids(0))
        assertEquals(0, queue.status().queued)
    }

    @Test
    fun aBackedUpQueueDrainsInSuccessiveBatches() = runTest {
        (1L..1200L).forEach { queue.enqueue(position("p$it", it)) }
        engine()
        advanceTimeBy(31.seconds)
        runCurrent()
        assertEquals(listOf(500, 500, 200), uploader.calls.map { it.size })
        assertEquals("p1200", uploader.calls[0][0].id, "newest first")
        assertEquals("p1", uploader.calls[0][1].id, "then oldest")
        assertEquals(0, queue.status().queued)
    }

    @Test
    fun anAlarmSkipsFlushAndJitter() = runTest {
        val engine = engine()
        settle()
        engine.submit(position("sos", alarm = "sos"))
        runCurrent()
        assertEquals(listOf("sos"), ids(0))
    }

    @Test
    fun anAlarmCutsAnActiveFlushWindowShort() = runTest {
        val engine = engine()
        settle()
        engine.submit(position("a", 1))
        advanceTimeBy(2.seconds)
        engine.submit(position("sos", 2, alarm = "sos"))
        runCurrent()
        assertEquals(listOf("sos", "a"), ids(0))
    }

    @Test
    fun anAlarmQueuedOfflineGoesTheMomentTheNetworkReturns() = runTest {
        network.isOnline.value = false
        val engine = engine()
        settle()
        engine.submit(position("sos", alarm = "sos"))
        runCurrent()
        assertEquals(1, queue.status().queuedAlarms)
        assertTrue(uploader.calls.isEmpty())

        network.isOnline.value = true
        runCurrent()
        assertEquals(listOf("sos"), ids(0))
        assertEquals(0, queue.status().queuedAlarms)
    }

    @Test
    fun anAlarmFromBeforeARestartSkipsTheJitter() = runTest {
        queue.enqueue(position("sos", alarm = "sos"))
        engine()
        runCurrent()
        assertEquals(listOf("sos"), ids(0))
    }

    @Test
    fun deletesOnlyWhatTheServerAccepted() = runTest {
        uploader.respond = { BatchResult.Confirmed(setOf("a"), emptyMap()) }
        val engine = engine()
        settle()
        engine.submit(position("a", 1))
        engine.submit(position("b", 2))
        advanceTimeBy(11.seconds)
        runCurrent()
        assertEquals(setOf("b"), queue.rows.map { it.id }.toSet())
        assertEquals(2, uploader.calls.size, "b tried once more, then backing off")

        uploader.respond = { batch -> BatchResult.Confirmed(batch.mapNotNull { it.id }.toSet(), emptyMap()) }
        advanceTimeBy(11.seconds)
        runCurrent()
        assertEquals(listOf("b"), ids(2))
        assertTrue(queue.rows.isEmpty())
    }

    @Test
    fun aRefusedPositionIsKeptAndNeverResent() = runTest {
        uploader.respond = { BatchResult.Confirmed(setOf("a"), mapOf("b" to "unknown_device")) }
        val engine = engine()
        settle()
        engine.submit(position("a", 1))
        engine.submit(position("b", 2))
        advanceTimeBy(11.seconds)
        runCurrent()

        assertEquals(1, uploader.calls.size)
        assertEquals(mapOf("b" to "unknown_device"), queue.rejections)
        assertEquals(listOf("b"), queue.rows.map { it.id }, "not deleted")
        assertEquals(QueueStatus(queued = 0, rejected = 1, queuedAlarms = 0, rejectedAlarms = 0), queue.status())
    }

    @Test
    fun aRetryableRejectionStaysQueuedWithBackoff() = runTest {
        uploader.respond = { BatchResult.Confirmed(emptySet(), mapOf("a" to "retry_later")) }
        val engine = engine()
        settle()
        engine.submit(position("a"))
        advanceTimeBy(11.seconds)
        runCurrent()
        assertEquals(1, uploader.calls.size)
        assertEquals(1, queue.status().queued)
        assertTrue(queue.rejections.isEmpty())
    }

    @Test
    fun aFailureKeepsEverythingAndBacksOff() = runTest {
        uploader.respond = { BatchResult.Failed() }
        val engine = engine()
        settle()
        engine.submit(position("a"))
        advanceTimeBy(11.seconds)
        runCurrent()
        assertEquals(1, uploader.calls.size)

        // 5 s backoff plus up to 5 s jitter, then 10 s plus 10 s.
        advanceTimeBy(8.seconds)
        runCurrent()
        assertEquals(1, uploader.calls.size)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(2, uploader.calls.size)
        advanceTimeBy(18.seconds)
        runCurrent()
        assertEquals(2, uploader.calls.size)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(3, uploader.calls.size)
        assertEquals(1, queue.status().queued)
    }

    @Test
    fun aSuccessResetsTheBackoff() = runTest {
        uploader.respond = { BatchResult.Failed() }
        val engine = engine()
        settle()
        engine.submit(position("a"))
        advanceTimeBy(11.seconds)
        runCurrent()
        uploader.respond = { batch -> BatchResult.Confirmed(batch.mapNotNull { it.id }.toSet(), emptyMap()) }
        advanceTimeBy(11.seconds)
        runCurrent()
        assertEquals(0, queue.status().queued)

        uploader.respond = { BatchResult.Failed() }
        engine.submit(position("b"))
        advanceTimeBy(11.seconds)
        runCurrent()
        val calls = uploader.calls.size
        advanceTimeBy(11.seconds)
        runCurrent()
        assertEquals(calls + 1, uploader.calls.size, "back to the first backoff step")
    }

    @Test
    fun retryAfterIsObeyed() = runTest {
        uploader.respond = { BatchResult.Failed(120.seconds) }
        val engine = engine()
        settle()
        engine.submit(position("a"))
        advanceTimeBy(11.seconds)
        runCurrent()
        advanceTimeBy(118.seconds)
        runCurrent()
        assertEquals(1, uploader.calls.size)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(2, uploader.calls.size)
    }

    @Test
    fun anAlarmKeepsRetryingAtLeastEveryMinute() = runTest {
        uploader.respond = { BatchResult.Failed() }
        val engine = engine()
        settle()
        engine.submit(position("sos", alarm = "sos"))
        advanceTimeBy(1_800.seconds)
        runCurrent()
        assertTrue(uploader.calls.size >= 30, "got ${uploader.calls.size} tries in 30 minutes")
        assertEquals(1, queue.status().queuedAlarms)
    }

    @Test
    fun a413HalvesTheBatchThenGoesBackToFullSize() = runTest {
        uploader.respond = { batch ->
            if (batch.size > 2) BatchResult.TooLarge
            else BatchResult.Confirmed(batch.mapNotNull { it.id }.toSet(), emptyMap())
        }
        (1L..8L).forEach { queue.enqueue(position("p$it", it)) }
        engine(BatchConfig(enabled = true, maxPositions = 8, flushSeconds = 10, jitterSeconds = 30))
        advanceTimeBy(31.seconds)
        runCurrent()
        assertEquals(0, queue.status().queued)
        assertEquals(listOf(8, 4, 2), uploader.calls.take(3).map { it.size })
    }

    @Test
    fun aSinglePositionOver413IsSetAsideSoItCannotBlockTheQueue() = runTest {
        uploader.respond = { batch ->
            if (batch.any { it.id == "huge" }) BatchResult.TooLarge
            else BatchResult.Confirmed(batch.mapNotNull { it.id }.toSet(), emptyMap())
        }
        queue.enqueue(position("huge", 1))
        queue.enqueue(position("ok", 2))
        engine()
        advanceTimeBy(31.seconds)
        runCurrent()
        assertEquals(mapOf("huge" to "too_large"), queue.rejections)
        assertEquals(listOf("huge"), queue.rows.map { it.id })
    }

    @Test
    fun theDeviceIsStampedWhenThePositionIsQueued() = runTest {
        val engine = engine(deviceId = "hostbrand-a")
        settle()
        val id = engine.submit(Position(time = 1))
        assertEquals(id, queue.rows.single().id)
        assertEquals("hostbrand-a", queue.rows.single().deviceId)
    }

    @Test
    fun positionsFromThePipelineGoThroughTheQueueAndCarryMotion() = runTest {
        engine(buffer = false)
        settle()
        location.emitter.emit(Position(latitude = 1.0, longitude = 2.0, time = 5))
        runCurrent()
        assertEquals(1, queue.status().queued, "batching needs the queue even with buffer off")
        assertTrue(single.uploaded.isEmpty())
        assertEquals(true, queue.rows.single().moving)
    }

    @Test
    fun aHeartbeatIsNotMoving() = runTest {
        engine()
        settle()
        location.emitter.emit(Position(time = 5, heartbeat = true))
        runCurrent()
        assertEquals(false, queue.rows.single().moving)
    }

    @Test
    fun withBatchingOffTheStockPathIsUnchanged() = runTest {
        engine(batch = BatchConfig(), batched = false)
        settle()
        location.emitter.emit(Position(latitude = 1.0, longitude = 2.0, time = 5))
        location.emitter.emit(Position(latitude = 1.1, longitude = 2.1, time = 6))
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(listOf(5L, 6L), single.uploaded.map { it.time })
        assertTrue(uploader.calls.isEmpty())
        assertEquals(0, queue.status().queued)
    }

    @Test
    fun withBatchingOffAnOfflineAlarmStillGoesOutWithItsAlarm() = runTest {
        network.isOnline.value = false
        val engine = engine(batch = BatchConfig(), batched = false)
        settle()
        engine.submit(position("sos", alarm = "sos"))
        runCurrent()
        assertTrue(single.uploaded.isEmpty())
        network.isOnline.value = true
        runCurrent()
        assertEquals(listOf("sos"), single.uploaded.map { it.alarm })
    }
}
