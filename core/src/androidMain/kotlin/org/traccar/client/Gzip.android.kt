package org.traccar.client

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

internal actual fun gzip(data: ByteArray): ByteArray {
    val output = ByteArrayOutputStream()
    GZIPOutputStream(output).use { it.write(data) }
    return output.toByteArray()
}
