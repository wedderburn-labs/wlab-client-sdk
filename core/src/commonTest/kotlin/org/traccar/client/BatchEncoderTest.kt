package org.traccar.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class BatchEncoderTest {

    private val full = Position(
        latitude = -29.31,
        longitude = 27.48,
        accuracy = 5.0,
        time = 1_793_871_000_000, // 2026-11-05T09:30:00Z
        altitude = 2410.0,
        speed = 8.2,
        bearing = 140.0,
        battery = 82,
        charging = false,
        id = "abc",
        deviceId = "rider-1",
        moving = true,
    )

    private fun location(position: Position) =
        position.toBatchJson().getValue("location").jsonObject

    @Test
    fun matchesTheContractShape() {
        val json = full.toBatchJson()
        assertEquals("abc", json.getValue("id").jsonPrimitive.content)
        assertEquals("rider-1", json.getValue("device_id").jsonPrimitive.content)

        val location = json.getValue("location").jsonObject
        assertEquals("2026-11-05T09:30:00Z", location.getValue("timestamp").jsonPrimitive.content)
        assertTrue(location.getValue("is_moving").jsonPrimitive.boolean)

        val coords = location.getValue("coords").jsonObject
        assertEquals(-29.31, coords.getValue("latitude").jsonPrimitive.double)
        assertEquals(27.48, coords.getValue("longitude").jsonPrimitive.double)
        assertEquals(5.0, coords.getValue("accuracy").jsonPrimitive.double)
        assertEquals(8.2, coords.getValue("speed").jsonPrimitive.double, "metres per second")
        assertEquals(140.0, coords.getValue("heading").jsonPrimitive.double)
        assertEquals(2410.0, coords.getValue("altitude").jsonPrimitive.double)

        val battery = location.getValue("battery").jsonObject
        assertEquals(0.82, battery.getValue("level").jsonPrimitive.double)
        assertFalse(battery.getValue("is_charging").jsonPrimitive.boolean)
    }

    @Test
    fun keepsMillisecondsInTheTimestamp() {
        val timestamp = location(full.copy(time = full.time + 250)).getValue("timestamp").jsonPrimitive.content
        assertEquals("2026-11-05T09:30:00.250Z", timestamp)
    }

    @Test
    fun anAlarmIsSentAsEventAndAlarmWithItsExtras() {
        val location = location(full.copy(alarm = "sos", extras = mapOf("sosId" to "s-1")))
        assertEquals("sos", location.getValue("event").jsonPrimitive.content)
        assertEquals("sos", location.getValue("alarm").jsonPrimitive.content)
        assertEquals("s-1", location.getValue("extras").jsonObject.getValue("sosId").jsonPrimitive.content)
    }

    @Test
    fun omitsEventAndExtrasWhenThereAreNone() {
        // The osmand decoder reads `event` with getString, which fails on a JSON null.
        val location = location(full)
        assertFalse("event" in location)
        assertFalse("alarm" in location)
        assertFalse("extras" in location)
    }

    @Test
    fun unknownCoordinateValuesAreSentAsTheDecoderExpects() {
        // The osmand decoder reads all six keys without checking they exist.
        val coords = location(Position(latitude = 1.0, longitude = 2.0, time = 0, id = "a", deviceId = "rider-1"))
            .getValue("coords").jsonObject
        assertEquals(-1.0, coords.getValue("accuracy").jsonPrimitive.double)
        assertEquals(-1.0, coords.getValue("speed").jsonPrimitive.double)
        assertEquals(-1.0, coords.getValue("heading").jsonPrimitive.double)
        assertEquals(0.0, coords.getValue("altitude").jsonPrimitive.double)
    }

    @Test
    fun aPositionWithoutCoordinatesOmitsCoords() {
        assertFalse("coords" in location(Position(time = 0, id = "a", deviceId = "rider-1")))
    }

    @Test
    fun sendsTheDeviceStoredWithThePosition() {
        assertEquals("rider-1", full.toBatchJson().getValue("device_id").jsonPrimitive.content)
    }

    @Test
    fun refusesAPositionWithoutADevice() {
        assertFailsWith<IllegalStateException> { Position(time = 0, id = "a").toBatchJson() }
    }

    @Test
    fun refusesAPositionWithoutAnId() {
        assertFailsWith<IllegalStateException> { Position(time = 0).toBatchJson() }
    }

    private fun ids(encoded: EncodedBatch): List<String> =
        (Json.parseToJsonElement(encoded.json) as JsonArray)
            .map { it.jsonObject.getValue("id").jsonPrimitive.content }

    @Test
    fun keepsTheOrderItWasGiven() {
        val batch = encodeBatch(listOf(position("c"), position("a"), position("b")), 500, 1_000_000)
        assertEquals(listOf("c", "a", "b"), ids(batch))
    }

    @Test
    fun stopsAtMaxPositions() {
        val batch = encodeBatch((1..600).map { position("p$it") }, 500, 10_000_000)
        assertEquals(500, batch.positions.size)
        assertEquals(500, Json.parseToJsonElement(batch.json).jsonArray.size)
    }

    @Test
    fun stopsBeforeMaxBytes() {
        val positions = (1..50).map { position("p$it") }
        val one = encodeBatch(positions.take(1), 500, 1_000_000).json.encodeToByteArray().size
        val limit = one * 10
        val batch = encodeBatch(positions, 500, limit)
        assertTrue(batch.json.encodeToByteArray().size <= limit)
        assertTrue(batch.positions.size in 9..10, "got ${batch.positions.size}")
        assertEquals(batch.positions.size, Json.parseToJsonElement(batch.json).jsonArray.size)
    }

    @Test
    fun aRealisticFullBatchFitsTheByteLimit() {
        val batch = encodeBatch((1..500).map { full.copy(id = newPositionId()) }, 500, 1_000_000)
        assertEquals(500, batch.positions.size)
    }

    @Test
    fun alwaysSendsAtLeastOne() {
        val batch = encodeBatch(listOf(position("big").copy(extras = mapOf("k" to "v".repeat(5000)))), 500, 100)
        assertEquals(1, batch.positions.size)
    }
}
