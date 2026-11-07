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
        assertEquals(listOf("rider-1"), form.getAll("id"))
        assertEquals("x", form["note"])
    }

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
            )
            val tracker = Tracker(
                config = Config("https://track.example.com", "rider-1"),
                stateStore = store,
                locationSource = location,
                batteryProcessor = { it },
                uploader = single,
                queue = queue,
                engine = engine,
                componentScope = scope,
            )

            tracker.queuePosition(alarm = "sos", extras = mapOf("sosId" to "s-1"))
            assertEquals(1, queue.status().queuedAlarms)
            assertTrue(forms.value.isEmpty())

            network.isOnline.value = true
            val form = withTimeout(10.seconds) { forms.first { it.isNotEmpty() } }.single()
            scope.coroutineContext[Job]?.cancel()

            assertEquals("sos", form["alarm"])
            assertEquals("s-1", form["sosId"])
        }
    }
}
