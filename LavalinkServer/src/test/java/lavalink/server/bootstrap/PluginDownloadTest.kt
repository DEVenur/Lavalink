package lavalink.server.bootstrap

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI

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
    fun acceptsSafeRedirects() {
        val current = URI("https://repo.example.com/releases/plugin.jar")

        kotlin.test.assertEquals(
            URI("https://cdn.example.com/plugin.jar"),
            PluginDownload.resolveRedirect(current, "https://cdn.example.com/plugin.jar")
        )
        kotlin.test.assertEquals(
            URI("https://repo.example.com/releases/plugin-v2.jar"),
            PluginDownload.resolveRedirect(current, "plugin-v2.jar")
        )
    }

    @Test
    fun rejectsUnsafeRedirects() {
        val current = URI("https://repo.example.com/releases/plugin.jar")

        assertThrows(IllegalArgumentException::class.java) {
            PluginDownload.resolveRedirect(current, "http://repo.example.com/plugin.jar")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginDownload.resolveRedirect(current, "file:///etc/passwd")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginDownload.resolveRedirect(current, "https://user:password@repo.example.com/plugin.jar")
        }
    }

    @Test
    fun rejectsNonPositiveLimits() {
        assertThrows(IllegalArgumentException::class.java) {
            PluginDownload.copyWithLimit(ByteArrayInputStream(byteArrayOf(1)), ByteArrayOutputStream(), 0)
        }
    }
}
