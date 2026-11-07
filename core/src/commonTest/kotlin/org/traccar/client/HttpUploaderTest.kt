package org.traccar.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class HttpUploaderTest {

    private val forms = MutableStateFlow(emptyList<Parameters>())

    private val client = HttpClient(MockEngine { request ->
        forms.value = forms.value + (request.body as FormDataContent).formData
        respond("", HttpStatusCode.OK)
    })

    private val uploader = HttpUploader(Config("https://track.example.com", "rider-1"), client)

    @Test
    fun sendsExtrasAsPlainFormParameters() = kotlinx.coroutines.test.runTest {
        uploader.upload(position("a").copy(alarm = "sos", extras = mapOf("sosId" to "s-1")))
        val form = forms.value.single()
        assertEquals("sos", form["alarm"])
        assertEquals("s-1", form["sosId"])
    }

    @Test
    fun noExtrasLeavesTheFormAsItWas() = kotlinx.coroutines.test.runTest {
        uploader.upload(position("a"))
        assertEquals(setOf("id", "lat", "lon", "timestamp"), forms.value.single().names())
    }

    @Test
    fun anExtraCannotReplaceAStandardParameter() = kotlinx.coroutines.test.runTest {
        uploader.upload(position("a").copy(extras = mapOf("id" to "other-rider", "note" to "x")))
        val form = forms.value.single()
        assertEquals(listOf("device"), form.getAll("id"))
        assertEquals("x", form["note"])
    }

    @Test
    fun sendsTheDeviceStoredWithThePosition() = kotlinx.coroutines.test.runTest {
        uploader.upload(position("a").copy(deviceId = "team-a"))
        assertEquals("team-a", forms.value.single()["id"])
    }

    @Test
    fun aLivePositionWithNoStoredDeviceUsesTheConfiguredOne() = kotlinx.coroutines.test.runTest {
        uploader.upload(position("a").copy(deviceId = null))
        assertEquals("rider-1", forms.value.single()["id"])
    }

    /** `setConfig` replaces the engine and uploader over the same database, as this does. */
    @Test
    fun positionsQueuedBeforeADeviceChangeKeepTheirDevice() = kotlinx.coroutines.test.runTest {
        withContext(Dispatchers.Default) {
            val driver = testDriver()
            val queue = DatabaseQueue(driver)
            val store = StateStore.create(driver)

            val before = ComponentCoroutineScope()
            val location = FakeLocationSource()
            val network = FakeNetwork(online = false)
            val single = HttpUploader(Config("https://track.example.com", "team-a"), client)
            val engine = engine(store, queue, network, location, single, before, "team-a")
            engine.submit(position("old").copy(deviceId = null))
            tracker(store, queue, location, single, engine, before, "team-a")
                .queuePosition(alarm = "sos", extras = mapOf("sosId" to "s-1"))
            before.coroutineContext[Job]?.cancel()

            val after = ComponentCoroutineScope()
            val next = engine(
                store, queue, FakeNetwork(online = true), location,
                HttpUploader(Config("https://track.example.com", "team-b"), client), after, "team-b",
            )
            withTimeout(10.seconds) { forms.first { it.size == 2 } }
            next.submit(position("new").copy(deviceId = null))
            withTimeout(10.seconds) { forms.first { it.size == 3 } }
            after.coroutineContext[Job]?.cancel()

            assertEquals(listOf("team-a", "team-a", "team-b"), forms.value.map { it["id"] })
            assertEquals("s-1", forms.value[1]["sosId"])
        }
    }

    /** A row an upgrade left with no device, ahead of one recorded normally. */
    private suspend fun queueWithAnOldRow(): DatabaseQueue = DatabaseQueue(testDriver()).also {
        it.enqueue(position("old").copy(deviceId = null, alarm = "sos"))
        it.enqueue(position("new").copy(deviceId = "rider-1"))
    }

    private suspend fun aRowWithNoDeviceIsKeptAndNeverSent(sent: () -> List<String>, queue: PositionQueue) =
        withContext(Dispatchers.Default) {
            withTimeout(10.seconds) { while (queue.status().queued > 0) delay(10) }
            assertEquals(listOf("rider-1"), sent())
            assertEquals(QueueStatus(queued = 0, rejected = 1, queuedAlarms = 0, rejectedAlarms = 1), queue.status())
        }

    @Test
    fun aRowWithNoDeviceIsNotGuessedOnSingleUploads() = kotlinx.coroutines.test.runTest {
        val queue = withContext(Dispatchers.Default) { queueWithAnOldRow() }
        val scope = ComponentCoroutineScope()
        engine(StateStore.create(testDriver()), queue, FakeNetwork(), FakeLocationSource(), uploader, scope, "rider-2")
        aRowWithNoDeviceIsKeptAndNeverSent({ forms.value.map { it["id"].orEmpty() } }, queue)
        scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun aRowWithNoDeviceIsNotGuessedOnBatchedUploads() = kotlinx.coroutines.test.runTest {
        val queue = withContext(Dispatchers.Default) { queueWithAnOldRow() }
        val sent = mutableListOf<String>()
        val scope = ComponentCoroutineScope()
        TrackerEngine(
            stateStore = StateStore.create(testDriver()),
            queue = queue,
            network = FakeNetwork(),
            locationSource = FakeLocationSource(),
            signalSources = emptyList(),
            processors = emptyList(),
            uploader = uploader,
            buffer = true,
            scope = scope,
            deviceId = "rider-2",
            batchUploader = BatchUploader { positions ->
                sent += positions.mapNotNull { it.deviceId }
                BatchResult.Confirmed(positions.mapNotNull { it.id }.toSet(), emptyMap())
            },
            batch = BatchConfig(enabled = true, flushSeconds = 0, jitterSeconds = 0),
        )
        aRowWithNoDeviceIsKeptAndNeverSent({ sent.toList() }, queue)
        scope.coroutineContext[Job]?.cancel()
    }

    private fun engine(
        store: StateStore,
        queue: PositionQueue,
        network: NetworkMonitor,
        location: LocationSource,
        single: Uploader,
        scope: ComponentCoroutineScope,
        device: String,
    ) = TrackerEngine(
        stateStore = store,
        queue = queue,
        network = network,
        locationSource = location,
        signalSources = emptyList(),
        processors = emptyList(),
        uploader = single,
        buffer = true,
        scope = scope,
        deviceId = device,
    )

    private fun tracker(
        store: StateStore,
        queue: PositionQueue,
        location: LocationSource,
        single: Uploader,
        engine: TrackerEngine,
        scope: ComponentCoroutineScope,
        device: String,
    ) = Tracker(
        config = Config("https://track.example.com", device),
        stateStore = store,
        locationSource = location,
        batteryProcessor = { it },
        uploader = single,
        queue = queue,
        engine = engine,
        componentScope = scope,
    )

    /** Real queue and uploader with batching off, as the app runs before the server has S6. */
    @Test
    fun aSosQueuedOfflineReachesTheServerWithItsId() = kotlinx.coroutines.test.runTest {
        withContext(Dispatchers.Default) {
            val driver = testDriver()
            val queue = DatabaseQueue(driver)
            val network = FakeNetwork(online = false)
            val location = FakeLocationSource()
            val store = StateStore.create(driver)
            val single = uploader
            val scope = ComponentCoroutineScope()
            val engine = engine(store, queue, network, location, single, scope, "rider-1")
            val tracker = tracker(store, queue, location, single, engine, scope, "rider-1")

            tracker.queuePosition(alarm = "sos", extras = mapOf("sosId" to "s-1"))
            assertEquals(1, queue.status().queuedAlarms)
            assertTrue(forms.value.isEmpty())

            network.isOnline.value = true
            val form = withTimeout(10.seconds) { forms.first { it.isNotEmpty() } }.single()
            scope.coroutineContext[Job]?.cancel()

            assertEquals("sos", form["alarm"])
            assertEquals("s-1", form["sosId"])
            assertEquals("rider-1", form["id"])
        }
    }
}
