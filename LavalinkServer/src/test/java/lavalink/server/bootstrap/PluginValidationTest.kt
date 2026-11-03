package lavalink.server.bootstrap

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PluginValidationTest {
    @Test
    fun acceptsValidPluginMetadata() {
        assertDoesNotThrow {
            PluginValidation.validateManifest("youtube-plugin", "dev.lavalink.youtube", "1.2.3")
        }
        assertDoesNotThrow {
            PluginValidation.parseDependency("dev.lavalink:youtube-plugin:1.2.3-SNAPSHOT")
        }
    }

    @Test
    fun rejectsUnsafeManifestValues() {
        assertThrows(IllegalArgumentException::class.java) {
            PluginValidation.validateManifest("../outside", "dev.example", "1.0.0")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginValidation.validateManifest("plugin", "dev.example/../../outside", "1.0.0")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginValidation.validateManifest("plugin", "dev.example", "../../outside")
        }
    }

    @Test
    fun rejectsUnsafeMavenCoordinates() {
        assertThrows(IllegalArgumentException::class.java) {
            PluginValidation.parseDependency("dev.example:../../outside:1.0.0")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginValidation.parseDependency("dev.example:plugin:../1.0.0")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginValidation.parseDependency("dev..example:plugin:1.0.0")
        }
    }

    @Test
    fun onlyAcceptsHttpRepositoriesWithoutCredentialsOrQuery() {
        assertDoesNotThrow {
            PluginValidation.validateRepository("https://repo.example.com/releases")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginValidation.validateRepository("file:///tmp/repository")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginValidation.validateRepository("https://user:password@repo.example.com/releases")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginValidation.validateRepository("https://repo.example.com/releases?token=secret")
        }
    }
}
