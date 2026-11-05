package org.traccar.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class QueuePositionTest {

    private val queue = FakeQueue()
    private val network = FakeNetwork(online = false)
    private val location = FakeLocationSource()
    private val single = RecordingUploader()

    private suspend fun TestScope.tracker(lastKnown: Position? = null): Tracker {
        val store = StateStore.create(testDriver())
        store.update { it.copy(lastAcceptedLocation = lastKnown) }
        val scope = ComponentCoroutineScope(backgroundScope.coroutineContext)
        val engine = TrackerEngine(
            stateStore = store,
            queue = queue,
            network = network,
            locationSource = location,
            signalSources = emptyList(),
            processors = emptyList(),
            uploader = single,
            buffer = true,
            scope = scope,
            deviceId = "rider-1",
            batchUploader = FakeBatchUploader(),
            batch = BatchConfig(enabled = true),
            random = MaxRandom,
        )
        return Tracker(
            config = Config("https://track.example.com", "rider-1"),
            stateStore = store,
            locationSource = location,
            batteryProcessor = { it.copy(battery = 70, charging = true) },
            uploader = single,
            queue = queue,
            engine = engine,
            componentScope = scope,
        )
    }

    private val fix = Position(latitude = -29.3, longitude = 27.4, accuracy = 4.0, time = 1_000, battery = 10)

    @Test
    fun anSosIsStoredWhileOfflineWithItsIdAndExtras() = runTest {
        location.fix = fix
        val id = tracker().queuePosition("sos", mapOf("sosId" to "s-1"))

        val row = queue.rows.single()
        assertEquals(id, row.id)
        assertEquals("sos", row.alarm)
        assertEquals("s-1", row.extras["sosId"])
        assertEquals("rider-1", row.deviceId)
        assertEquals(-29.3, row.latitude)
        assertEquals(QueueStatus(queued = 1, rejected = 0, queuedAlarms = 1, rejectedAlarms = 0), queue.status())
    }

    @Test
    fun theTimeIsNowEvenIfTheFixIsOld() = runTest {
        // A cached fix would otherwise share its timestamp with a position already stored,
        // and the server would drop the SOS as a duplicate.
        location.fix = fix
        val before = Clock.System.now().toEpochMilliseconds()
        tracker().queuePosition("sos")

        val row = queue.rows.single()
        assertTrue(row.time >= before)
        assertEquals(Instant.fromEpochMilliseconds(1_000).toString(), row.extras["fixTime"])
    }

    @Test
    fun batteryIsReadNowNotTakenFromTheFix() = runTest {
        location.fix = fix
        tracker().queuePosition("sos")
        assertEquals(70, queue.rows.single().battery)
    }

    @Test
    fun noFixFallsBackToTheLastKnownPlace() = runTest {
        tracker(lastKnown = fix).queuePosition("sos")
        val row = queue.rows.single()
        assertEquals(-29.3, row.latitude)
        assertEquals(27.4, row.longitude)
        assertEquals(Instant.fromEpochMilliseconds(1_000).toString(), row.extras["fixTime"])
    }

    @Test
    fun noFixAndNoLastPlaceStillQueues() = runTest {
        tracker().queuePosition("sos", mapOf("sosId" to "s-1"))
        val row = queue.rows.single()
        assertNull(row.latitude)
        assertEquals("sos", row.alarm)
        assertFalse("fixTime" in row.extras)
    }

    @Test
    fun aSlowFixGivesUpAfterTheTimeout() = runTest {
        location.hang = true
        val tracker = tracker(lastKnown = fix)
        val call = backgroundScope.async { tracker.queuePosition("sos") }
        advanceTimeBy(9.seconds)
        runCurrent()
        assertTrue(queue.rows.isEmpty())
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(1, queue.rows.size)
        assertEquals(queue.rows.single().id, call.await())
    }

    @Test
    fun twoPressesAreTwoPositions() = runTest {
        location.fix = fix
        val tracker = tracker()
        assertNotEquals(tracker.queuePosition("sos"), tracker.queuePosition("sos"))
        assertEquals(2, tracker.queueStatus().queuedAlarms)
    }
}
