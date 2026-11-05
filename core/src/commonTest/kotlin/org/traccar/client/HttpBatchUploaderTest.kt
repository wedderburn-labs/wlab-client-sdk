package org.traccar.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class HttpBatchUploaderTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun uploader(
        url: String = "https://track.example.com",
        batch: BatchConfig = BatchConfig(enabled = true),
        headers: Map<String, String> = emptyMap(),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = HttpBatchUploader(
        Config(serverUrl = url, deviceId = "fallback", headers = headers, batch = batch),
        HttpClient(MockEngine { request ->
            requests += request
            handler(request)
        }),
    )

    private fun MockRequestHandleScope.ok(body: String) = respond(body, HttpStatusCode.OK)

    @Test
    fun postsGzipJsonToTheUploadUrl() = runTest {
        val result = uploader(headers = mapOf("X-Test" to "1")) {
            ok("""{"accepted":["a","b"],"rejected":[]}""")
        }.upload(listOf(position("a"), position("b")))

        assertEquals(BatchResult.Confirmed(setOf("a", "b"), emptyMap()), result)
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://track.example.com/", request.url.toString())
        assertEquals("gzip", request.headers[HttpHeaders.ContentEncoding])
        assertEquals("application/json", request.body.contentType.toString())
        assertEquals("1", request.headers["X-Test"])

        val array = Json.parseToJsonElement(gunzip(request.body.toByteArray()).decodeToString()).jsonArray
        assertEquals(listOf("a", "b"), array.map { it.jsonObject.getValue("id").jsonPrimitive.content })
    }

    @Test
    fun doesNotDoubleATrailingSlash() = runTest {
        uploader(url = "https://track.example.com/") { ok("""{"accepted":["a"]}""") }.upload(listOf(position("a")))
        assertEquals("https://track.example.com/", requests.single().url.toString())
    }

    @Test
    fun rejectedPositionsComeBackWithTheirReason() = runTest {
        val result = uploader {
            ok("""{"accepted":["a"],"rejected":[{"id":"b","reason":"unknown_device"}]}""")
        }.upload(listOf(position("a"), position("b")))
        assertEquals(BatchResult.Confirmed(setOf("a"), mapOf("b" to "unknown_device")), result)
    }

    @Test
    fun ignoresIdsThisRequestDidNotCarry() = runTest {
        val result = uploader {
            ok("""{"accepted":["a","ghost"],"rejected":[{"id":"other","reason":"x"}]}""")
        }.upload(listOf(position("a")))
        assertEquals(BatchResult.Confirmed(setOf("a"), emptyMap()), result)
    }

    @Test
    fun positionsCutByTheByteLimitCannotBeConfirmed() = runTest {
        val tiny = BatchConfig(enabled = true, maxBytes = 10)
        val result = uploader(batch = tiny) { ok("""{"accepted":["a","b"]}""") }
            .upload(listOf(position("a"), position("b")))
        assertEquals(BatchResult.Confirmed(setOf("a"), emptyMap()), result)
    }

    @Test
    fun aNewerResponseFieldIsIgnored() = runTest {
        val result = uploader { ok("""{"accepted":["a"],"rejected":[],"serverTime":"now"}""") }
            .upload(listOf(position("a")))
        assertEquals(BatchResult.Confirmed(setOf("a"), emptyMap()), result)
    }

    @Test
    fun aPlain200WithoutAConfirmationDeletesNothing() = runTest {
        // Today's single-object decoder answers 200 with an empty body.
        val result = uploader { ok("") }.upload(listOf(position("a")))
        assertEquals(BatchResult.Failed(), result)
    }

    @Test
    fun anUnreadableResponseDeletesNothing() = runTest {
        assertEquals(BatchResult.Failed(), uploader { ok("<html>bad gateway</html>") }.upload(listOf(position("a"))))
    }

    @Test
    fun status413IsTooLarge() = runTest {
        val result = uploader { respond("", HttpStatusCode.PayloadTooLarge) }.upload(listOf(position("a")))
        assertEquals(BatchResult.TooLarge, result)
    }

    @Test
    fun status429PassesOnRetryAfter() = runTest {
        val result = uploader {
            respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "45"))
        }.upload(listOf(position("a")))
        assertEquals(BatchResult.Failed(45.seconds), result)
    }

    @Test
    fun serverErrorsAreFailures() = runTest {
        for (status in listOf(HttpStatusCode.InternalServerError, HttpStatusCode.BadRequest, HttpStatusCode.NotFound)) {
            assertEquals(BatchResult.Failed(), uploader { respond("", status) }.upload(listOf(position("a"))))
        }
    }

    @Test
    fun aDroppedConnectionIsAFailure() = runTest {
        val result = uploader { throw RuntimeException("connection reset") }.upload(listOf(position("a")))
        assertEquals(BatchResult.Failed(), result)
    }

    @Test
    fun cancellationIsNotSwallowed() = runTest {
        assertFailsWith<CancellationException> {
            uploader { throw CancellationException("stopped") }.upload(listOf(position("a")))
        }
    }
}
