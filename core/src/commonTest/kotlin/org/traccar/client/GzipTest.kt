package org.traccar.client

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GzipTest {

    @Test
    fun roundTrips() {
        val data = "[" + (1..500).joinToString(",") { "{\"id\":\"$it\",\"lat\":-29.31}" } + "]"
        val compressed = gzip(data.encodeToByteArray())
        assertContentEquals(data.encodeToByteArray(), gunzip(compressed))
        assertTrue(compressed.size < data.length / 3, "compressed to ${compressed.size} of ${data.length}")
    }

    @Test
    fun writesAGzipStream() {
        val compressed = gzip("hello".encodeToByteArray())
        assertEquals(0x1f.toByte(), compressed[0])
        assertEquals(0x8b.toByte(), compressed[1])
    }

    @Test
    fun handlesDataThatWillNotCompress() {
        val data = kotlin.random.Random(1).nextBytes(100_000)
        assertContentEquals(data, gunzip(gzip(data)))
    }
}
