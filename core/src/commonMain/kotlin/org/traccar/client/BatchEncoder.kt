package org.traccar.client

import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Fits the osmand JSON decoder, which needs every `coords` key: -1 means "unknown" there. */
private const val UNKNOWN = -1.0

internal class EncodedBatch(val json: String, val positions: List<Position>)

/**
 * Encodes positions in order until [maxPositions] or [maxBytes] of uncompressed JSON is reached.
 * The first position always goes, so an oversized one reaches the server and gets a real answer.
 */
internal fun encodeBatch(
    positions: List<Position>,
    fallbackDeviceId: String,
    maxPositions: Int,
    maxBytes: Int,
): EncodedBatch {
    val items = mutableListOf<String>()
    val included = mutableListOf<Position>()
    var bytes = 2 // the array's brackets
    for (position in positions.take(maxPositions)) {
        val item = position.toBatchJson(fallbackDeviceId).toString()
        val size = item.encodeToByteArray().size + if (items.isEmpty()) 0 else 1
        if (items.isNotEmpty() && bytes + size > maxBytes) break
        items += item
        included += position
        bytes += size
    }
    return EncodedBatch(items.joinToString(",", "[", "]"), included)
}

internal fun Position.toBatchJson(fallbackDeviceId: String): JsonObject = buildJsonObject {
    put("id", checkNotNull(id) { "Queued position has no id" })
    put("device_id", deviceId ?: fallbackDeviceId)
    putJsonObject("location") {
        put("timestamp", Instant.fromEpochMilliseconds(time).toString())
        if (latitude != null && longitude != null) {
            putJsonObject("coords") {
                put("latitude", latitude)
                put("longitude", longitude)
                put("accuracy", accuracy ?: UNKNOWN)
                put("speed", speed ?: UNKNOWN)
                put("heading", bearing ?: UNKNOWN)
                put("altitude", altitude ?: 0.0)
            }
        }
        moving?.let { put("is_moving", it) }
        battery?.let { level ->
            putJsonObject("battery") {
                put("level", level / 100.0)
                put("is_charging", charging ?: false)
            }
        }
        alarm?.let {
            put("event", it)
            put("alarm", it)
        }
        if (extras.isNotEmpty()) {
            putJsonObject("extras") {
                extras.forEach { (key, value) -> put(key, value) }
            }
        }
    }
}
