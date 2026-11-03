package lavalink.server.bootstrap

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal object PluginDownload {
    fun copyWithLimit(input: InputStream, output: OutputStream, maxBytes: Long) {
        require(maxBytes > 0) { "Maximum plugin size must be positive" }

        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var totalBytes = 0L
        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead < 0) break
            if (bytesRead == 0) continue

            if (bytesRead.toLong() > maxBytes - totalBytes) {
                throw IOException("Plugin artifact exceeds the configured size limit of $maxBytes bytes")
            }

            output.write(buffer, 0, bytesRead)
            totalBytes += bytesRead
        }
    }
}
