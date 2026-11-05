package org.traccar.client

import kotlinx.serialization.Serializable

@Serializable
data class Config(
    val serverUrl: String,
    val deviceId: String,
    val location: LocationConfig = LocationConfig(),
    val wakeLock: Boolean = false,
    val buffer: Boolean = true,
    val preferPlatformProviders: Boolean = false,
    val notification: NotificationConfig = NotificationConfig(),
    val headers: Map<String, String> = emptyMap(),
    val batch: BatchConfig = BatchConfig(),
) {
    constructor(serverUrl: String, deviceId: String) : this(serverUrl, deviceId, LocationConfig())
}

/**
 * Batched upload. Off by default: each position then goes as one form post, as before.
 * When on, positions always go through the local queue, whatever `buffer` says.
 */
@Serializable
data class BatchConfig(
    val enabled: Boolean = false,
    /** Most positions in one request. */
    val maxPositions: Int = 500,
    /** Most uncompressed JSON bytes in one request. */
    val maxBytes: Int = 1_000_000,
    /** How long live positions gather before a send. */
    val flushSeconds: Int = 10,
    /** Upper bound of the random delay before sending after a gap in coverage. */
    val jitterSeconds: Int = 30,
)

@Serializable
data class NotificationConfig(
    val text: String = "Location tracking",
)

@Serializable
data class LocationConfig(
    val accuracy: Accuracy = Accuracy.MEDIUM,
    val distanceMeters: Int = 75,
    val intervalSeconds: Int = 300,
    val angleDegrees: Int = 0,
    val stopDetection: Boolean = true,
    val stopTimeoutSeconds: Int = 60,
    val stationaryRadiusMeters: Int = 100,
    val heartbeatIntervalSeconds: Int = 0,
)

@Serializable
enum class Accuracy {
    HIGHEST,
    HIGH,
    MEDIUM,
    LOW,
}

val LocationConfig.effective: LocationConfig
    get() = when {
        accuracy == Accuracy.HIGHEST -> copy(distanceMeters = 0, intervalSeconds = 0)
        distanceMeters > 0 -> copy(intervalSeconds = 0)
        else -> this
    }
