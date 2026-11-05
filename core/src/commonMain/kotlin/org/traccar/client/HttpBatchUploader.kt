package org.traccar.client

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Posts a gzip JSON array to `Config.serverUrl`. Keep-alive comes from reusing the one
 * [HttpClient] and reading each response to the end. The `Connection` header is left to the
 * platform: URLSession reserves it and HTTP/2 forbids it.
 */
class HttpBatchUploader(
    private val config: Config,
    private val httpClient: HttpClient,
) : BatchUploader {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun upload(positions: List<Position>): BatchResult = try {
        val batch = encodeBatch(
            positions = positions,
            fallbackDeviceId = config.deviceId,
            maxPositions = config.batch.maxPositions,
            maxBytes = config.batch.maxBytes,
        )
        val response = httpClient.post(config.serverUrl.withTrailingSlash()) {
            contentType(ContentType.Application.Json)
            headers {
                config.headers.forEach { (name, value) -> append(name, value) }
                append(HttpHeaders.ContentEncoding, "gzip")
            }
            setBody(gzip(batch.json.encodeToByteArray()))
        }
        val body = response.bodyAsText()
        Log.log("Batch of ${batch.positions.size}: response ${response.status.value}")
        interpret(response, body, batch.positions.mapNotNull { it.id }.toSet())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.log("Batch upload error: ${e.message}")
        BatchResult.Failed()
    }

    private fun interpret(response: HttpResponse, body: String, sent: Set<String>): BatchResult = when {
        response.status == HttpStatusCode.PayloadTooLarge -> BatchResult.TooLarge
        response.status.isSuccess() -> parse(body, sent)
        else -> BatchResult.Failed(response.retryAfter())
    }

    private fun parse(body: String, sent: Set<String>): BatchResult = try {
        val confirmation = json.decodeFromString<Confirmation>(body)
        // Only what this request carried can be confirmed by its response.
        BatchResult.Confirmed(
            accepted = confirmation.accepted.filter { it in sent }.toSet(),
            rejected = confirmation.rejected.filter { it.id in sent }.associate { it.id to it.reason },
        )
    } catch (e: IllegalArgumentException) {
        Log.log("Batch response unreadable: ${e.message}")
        BatchResult.Failed()
    }

    private fun HttpResponse.retryAfter(): Duration? =
        headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.seconds

    private fun String.withTrailingSlash() = if (endsWith("/")) this else "$this/"

    @Serializable
    private data class Confirmation(
        val accepted: List<String> = emptyList(),
        val rejected: List<Rejection> = emptyList(),
    )

    @Serializable
    private data class Rejection(val id: String, val reason: String = "")
}
