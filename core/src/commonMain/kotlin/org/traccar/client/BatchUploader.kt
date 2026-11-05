package org.traccar.client

import kotlin.time.Duration

/** Sends many positions in one request. Only used when `Config.batch.enabled` is true. */
fun interface BatchUploader {
    suspend fun upload(positions: List<Position>): BatchResult
}

sealed interface BatchResult {
    /** The server answered per position. Ids it named in neither list stay queued. */
    data class Confirmed(
        val accepted: Set<String>,
        val rejected: Map<String, String>,
    ) : BatchResult

    /** The request was over the server's size limit (413). */
    data object TooLarge : BatchResult

    /** Nothing was confirmed. Everything stays queued. */
    data class Failed(val retryAfter: Duration? = null) : BatchResult
}
