package org.traccar.client

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
import platform.zlib.MAX_WBITS
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.deflate
import platform.zlib.deflateBound
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.z_stream

private const val GZIP_WRAPPER = 16
private const val MEMORY_LEVEL = 8

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal actual fun gzip(data: ByteArray): ByteArray = memScoped {
    val stream = alloc<z_stream>()
    val init = deflateInit2_(
        strm = stream.ptr,
        level = Z_DEFAULT_COMPRESSION,
        method = Z_DEFLATED,
        windowBits = MAX_WBITS + GZIP_WRAPPER,
        memLevel = MEMORY_LEVEL,
        strategy = Z_DEFAULT_STRATEGY,
        version = ZLIB_VERSION,
        stream_size = sizeOf<z_stream>().convert(),
    )
    check(init == Z_OK) { "deflateInit2 failed: $init" }
    try {
        val output = ByteArray(deflateBound(stream.ptr, data.size.convert()).toInt())
        data.usePinned { input ->
            output.usePinned { target ->
                stream.next_in = input.addressOf(0).reinterpret()
                stream.avail_in = data.size.convert()
                stream.next_out = target.addressOf(0).reinterpret()
                stream.avail_out = output.size.convert()
                val result = deflate(stream.ptr, Z_FINISH)
                check(result == Z_STREAM_END) { "deflate failed: $result" }
            }
        }
        output.copyOf(stream.total_out.toInt())
    } finally {
        deflateEnd(stream.ptr)
    }
}
