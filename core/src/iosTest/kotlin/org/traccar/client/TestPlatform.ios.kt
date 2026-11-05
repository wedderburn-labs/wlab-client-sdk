package org.traccar.client

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import org.traccar.client.db.Database
import platform.zlib.MAX_WBITS
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_FINISH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream

internal actual fun testDriver(): SqlDriver = inMemoryDriver(Database.Schema)

private const val GZIP_WRAPPER = 16
private const val OUTPUT_SIZE = 16 * 1024 * 1024

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal actual fun gunzip(data: ByteArray): ByteArray = memScoped {
    val stream = alloc<z_stream>()
    val init = inflateInit2_(stream.ptr, MAX_WBITS + GZIP_WRAPPER, ZLIB_VERSION, sizeOf<z_stream>().convert())
    check(init == Z_OK) { "inflateInit2 failed: $init" }
    try {
        val output = ByteArray(OUTPUT_SIZE)
        data.usePinned { input ->
            output.usePinned { target ->
                stream.next_in = input.addressOf(0).reinterpret()
                stream.avail_in = data.size.convert()
                stream.next_out = target.addressOf(0).reinterpret()
                stream.avail_out = output.size.convert()
                val result = inflate(stream.ptr, Z_FINISH)
                check(result == Z_STREAM_END) { "inflate failed: $result" }
            }
        }
        output.copyOf(stream.total_out.toInt())
    } finally {
        inflateEnd(stream.ptr)
    }
}
