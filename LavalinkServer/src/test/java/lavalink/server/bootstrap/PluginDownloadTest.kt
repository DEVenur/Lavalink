package lavalink.server.bootstrap

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class PluginDownloadTest {
    @Test
    fun copiesArtifactsWithinLimit() {
        val content = ByteArray(1024) { it.toByte() }
        val output = ByteArrayOutputStream()

        PluginDownload.copyWithLimit(ByteArrayInputStream(content), output, content.size.toLong())

        assertArrayEquals(content, output.toByteArray())
    }

    @Test
    fun rejectsArtifactsExceedingLimit() {
        val output = ByteArrayOutputStream()

        assertThrows(IOException::class.java) {
            PluginDownload.copyWithLimit(ByteArrayInputStream(ByteArray(1025)), output, 1024)
        }
    }

    @Test
    fun rejectsNonPositiveLimits() {
        assertThrows(IllegalArgumentException::class.java) {
            PluginDownload.copyWithLimit(ByteArrayInputStream(byteArrayOf(1)), ByteArrayOutputStream(), 0)
        }
    }
}
