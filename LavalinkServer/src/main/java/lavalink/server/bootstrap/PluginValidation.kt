package lavalink.server.bootstrap

import java.net.URI

/**
 * Validates plugin metadata before it is used to construct filesystem paths,
 * Maven coordinates, or classpath package filters.
 */
internal object PluginValidation {
    private val artifactName = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    private val version = Regex("[A-Za-z0-9][A-Za-z0-9._+-]*")
    private val packageSegment = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

    fun validateManifest(name: String, path: String, version: String) {
        require(artifactName.matches(name) && name != "." && name != "..") {
            "Invalid plugin name in manifest"
        }
        require(path.isNotBlank() && path.split('.').all(packageSegment::matches)) {
            "Invalid plugin package path in manifest"
        }
        require(this.version.matches(version) && version != "." && version != "..") {
            "Invalid plugin version in manifest"
        }
    }

    fun parseDependency(dependency: String): Triple<String, String, String> {
        val fragments = dependency.split(':')
        require(fragments.size == 3) { "Invalid plugin dependency coordinate" }

        val (group, name, version) = fragments
        require(group.isNotBlank() && group.split('.').all(artifactName::matches)) {
            "Invalid plugin dependency group"
        }
        require(artifactName.matches(name) && name != "." && name != "..") {
            "Invalid plugin dependency artifact"
        }
        require(this.version.matches(version) && version != "." && version != "..") {
            "Invalid plugin dependency version"
        }

        return Triple(group, name, version)
    }

    fun validateRepository(repository: String): String {
        val uri = try {
            URI(repository)
        } catch (ex: Exception) {
            throw IllegalArgumentException("Invalid plugin repository URL", ex)
        }

        require(uri.scheme.equals("https", ignoreCase = true) ||
            uri.scheme.equals("http", ignoreCase = true)) {
            "Plugin repository must use HTTP or HTTPS"
        }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.query == null && uri.fragment == null) {
            "Invalid plugin repository URL"
        }

        return repository.removeSuffix("/") + "/"
    }
}
