package org.traccar.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class Position(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Double? = null,
    val time: Long,
    val altitude: Double? = null,
    val speed: Double? = null,
    val bearing: Double? = null,
    val battery: Int? = null,
    val charging: Boolean? = null,
    val alarm: String? = null,
    @Transient val heartbeat: Boolean = false,
    /** Client id, stable across retries. Assigned when the position is queued. */
    val id: String? = null,
    /** The device this position was recorded for, kept so a later config change can't relabel it. */
    val deviceId: String? = null,
    val moving: Boolean? = null,
    val extras: Map<String, String> = emptyMap(),
)
