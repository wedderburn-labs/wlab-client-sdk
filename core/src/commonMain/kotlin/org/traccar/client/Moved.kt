package org.traccar.client

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS = 6_371_000.0

/**
 * Whether a coarse fix shows the device has left its stationary spot. Its accuracy is taken off the
 * distance first, so a cell-tower fix that is merely imprecise doesn't count as moving.
 */
internal fun movedBeyond(
    anchorLatitude: Double,
    anchorLongitude: Double,
    latitude: Double,
    longitude: Double,
    accuracyMeters: Double,
    radiusMeters: Int,
): Boolean {
    val lat1 = anchorLatitude * kotlin.math.PI / 180
    val lat2 = latitude * kotlin.math.PI / 180
    val dLat = lat2 - lat1
    val dLon = (longitude - anchorLongitude) * kotlin.math.PI / 180
    val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
    val distance = 2 * EARTH_RADIUS * asin(sqrt(h))
    return distance - accuracyMeters.coerceAtLeast(0.0) > radiusMeters
}

/** WLAB: fixes older than this when they arrive are iOS's cached location, not where the device is now. */
internal const val STALE_FIX_MS = 60_000L

/**
 * Whether a fix is too old to act on. iOS hands a new location manager its cached location first, and on
 * 10 Nov 2026 a fix from hours earlier, 300 m away, resumed tracking and was uploaded as a position.
 */
internal fun isStaleFix(fixTimeMs: Long, nowMs: Long): Boolean = nowMs - fixTimeMs > STALE_FIX_MS
