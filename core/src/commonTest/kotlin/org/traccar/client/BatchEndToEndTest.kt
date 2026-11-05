package org.traccar.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Real queue, real encoder, real gzip and real uploader against a stand-in server. */
class BatchEndToEndTest {

    private fun response(ids: List<String>, refused: Set<String>): String {
        val accepted = ids.filter { it !in refused }.joinToString(",") { "\"$it\"" }
        val rejected = ids.filter { it in refused }.joinToString(",") { """{"id":"$it","reason":"bad"}""" }
        return """{"accepted":[$accepted],"rejected":[$rejected]}"""
    }

    @Test
    fun aBacklogReachesTheServerInContractOrderAndLeavesOnlyWhatWasRefused() = runTest {
        val driver = testDriver()
        val queue = DatabaseQueue(driver)
        val received = MutableStateFlow(emptyList<String>())
        val client = HttpClient(MockEngine { request ->
            val ids = Json.parseToJsonElement(gunzip(request.body.toByteArray()).decodeToString())
                .jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
            received.value = received.value + ids
            respond(response(ids, refused = setOf("p2")), HttpStatusCode.OK)
        })
        val batch = BatchConfig(enabled = true, flushSeconds = 0, jitterSeconds = 0)
        val config = Config(serverUrl = "https://track.example.com", deviceId = "rider-1", batch = batch)

        (1L..4L).forEach { queue.enqueue(position("p$it", it)) }

        val scope = ComponentCoroutineScope()
        TrackerEngine(
            stateStore = StateStore.create(driver),
            queue = queue,
            network = FakeNetwork(),
            locationSource = FakeLocationSource(),
            signalSources = emptyList(),
            processors = emptyList(),
            uploader = RecordingUploader(),
            buffer = true,
            scope = scope,
            deviceId = "rider-1",
            batchUploader = HttpBatchUploader(config, client),
            batch = batch,
        )

        withTimeout(10.seconds) {
            received.first { it.size == 4 }
            while (queue.status().queued > 0) delay(10)
        }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

        assertEquals(listOf("p4", "p1", "p2", "p3"), received.value)
        assertEquals(QueueStatus(queued = 0, rejected = 1, queuedAlarms = 0, rejectedAlarms = 0), queue.status())
    }

    private fun runTest(block: suspend () -> Unit) = kotlinx.coroutines.test.runTest {
        withContext(Dispatchers.Default) { block() }
    }
}
