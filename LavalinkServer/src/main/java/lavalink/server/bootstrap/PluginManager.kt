package lavalink.server.bootstrap

import dev.arbjerg.lavalink.protocol.v4.Version
import nl.adaptivity.xmlutil.core.KtXmlReader
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLClassLoader
import java.util.*
import java.util.jar.JarFile

@SpringBootApplication
class PluginManager(val config: PluginsConfig) {
    companion object {
        private val log: Logger = LoggerFactory.getLogger(PluginManager::class.java)
    }

    final val pluginManifests: MutableList<PluginManifest> = mutableListOf()

    var classLoader = javaClass.classLoader

    init {
        manageDownloads()

        pluginManifests.apply {
            addAll(readClasspathManifests())
            addAll(loadJars())
        }
    }

    private fun manageDownloads() {
        if (config.plugins.isEmpty()) return

        val directory = File(config.pluginsDir)
        if (!directory.exists() && !directory.mkdirs()) {
            throw RuntimeException("Failed to create plugin directory: ${directory.path}")
        }
        if (!directory.isDirectory) {
            throw RuntimeException("Plugin path is not a directory: ${directory.path}")
        }

        val pluginFiles = directory.listFiles()
            ?: throw RuntimeException("Failed to list plugin directory: ${directory.path}")

        val pluginJars = pluginFiles.filter { it.isFile && it.extension == "jar" }
            .flatMap { file ->
                JarFile(file).use { jar ->
                    loadPluginManifests(jar).map { manifest -> PluginJar(manifest, file) }
                }
            }
            .onEach { log.info("Found plugin '${it.manifest.name}' version '${it.manifest.version}'") }

        val declarations = config.plugins.map { declaration ->
            if (declaration.dependency == null) throw RuntimeException("Illegal dependency declaration: null")
            val (group, name, version) = try {
                PluginValidation.parseDependency(declaration.dependency!!)
            } catch (ex: IllegalArgumentException) {
                throw RuntimeException("Invalid dependency \"${declaration.dependency}\"", ex)
            }

            val repository = declaration.repository
                ?: config.defaultPluginSnapshotRepository.takeIf { declaration.snapshot }
                ?: config.defaultPluginRepository

            val validatedRepository = try {
                PluginValidation.validateRepository(repository)
            } catch (ex: IllegalArgumentException) {
                throw RuntimeException("Invalid repository for plugin '$name'", ex)
            }

            Declaration(group, name, version, validatedRepository)
        }.distinctBy { "${it.group}:${it.name}" }

        for (declaration in declarations) {
            val jars = pluginJars.filter { it.manifest.name == declaration.name }.takeIf { it.isNotEmpty() }
                ?: pluginJars.filter { matchName(it, declaration.name) }

            val currentVersionFiles = jars
                .filter { it.manifest.version == declaration.version }
                .map { it.file.canonicalFile }
                .toSet()
            val targetFile = File(directory, declaration.canonicalJarName).canonicalFile

            if (currentVersionFiles.isEmpty()) {
                // Keep the previously installed version until the replacement is downloaded and validated.
                downloadJar(targetFile, declaration.url)
            }

            val outdatedFiles = jars
                .map { it.file.canonicalFile }
                .distinct()
                .filter { it !in currentVersionFiles && it != targetFile }

            // Remove outdated versions only after the requested version is available.
            for (file in outdatedFiles) {
                if (!file.delete()) throw RuntimeException("Failed to delete ${file.path}")
                log.info("Deleted ${file.path} (new version: ${declaration.version})")
            }

            checkPluginForUpdates(declaration)
        }
    }

    private fun checkPluginForUpdates(declaration: Declaration) {
        val baseSplitPath = declaration.url.split('/').dropLast(2)
        val metadataUrl = baseSplitPath.joinToString("/") + "/maven-metadata.xml"

        val conn = try {
            (URL(metadataUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 30_000
            }
        } catch (ex: Exception) {
            log.warn("Failed to check for updates for ${declaration.name}: ${ex.message}")
            return
        }

        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                log.warn("Failed to check for updates for ${declaration.name}: HTTP ${conn.responseCode} ${conn.responseMessage}")
                return
            }

            val metadata = try {
                conn.inputStream.use {
                    xml.decodeFromReader(Metadata.serializer(), KtXmlReader(it))
                }
            } catch (ex: Exception) {
                log.warn("Failed to parse update metadata for ${declaration.name}: ${ex.message}")
                return
            }

            val current = Version.fromSemver(declaration.version)
            var latest = metadata.versioning.latest
            if (latest.isEmpty()) {
                latest = metadata.versioning.release
            }
            if (latest.isEmpty()) {
                latest = metadata.versioning.versions.lastOrNull() ?: ""
            }

            if (latest.isEmpty()) return

            val latestVersion = Version.fromSemver(latest)
            if (latestVersion > current) {
                log.warn("A newer version of '${declaration.name}' was found: '$latestVersion', The current version is '$current' please update the version in your configuration.")
            }
        } catch (ex: Exception) {
            log.warn("Failed to check for updates for ${declaration.name}: ${ex.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadJar(output: File, url: String) {
        log.info("Downloading $url")

        val parent = output.absoluteFile.parentFile
        if (!parent.exists() && !parent.mkdirs()) {
            throw RuntimeException("Failed to create plugin directory: ${parent.path}")
        }

        val temporaryFile = File.createTempFile("${output.name}.", ".part", parent)
        var connection: java.net.URLConnection? = null

        try {
            connection = URL(url).openConnection().apply {
                connectTimeout = 10_000
                readTimeout = 30_000
            }

            if (connection is HttpURLConnection) {
                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw RuntimeException("Failed to download plugin from $url: HTTP $responseCode")
                }
            }

            connection.getInputStream().use { input ->
                temporaryFile.outputStream().use { outputStream ->
                    input.copyTo(outputStream)
                }
            }

            if (temporaryFile.length() == 0L) {
                throw RuntimeException("Downloaded plugin is empty: $url")
            }

            JarFile(temporaryFile).use { jar ->
                if (loadPluginManifests(jar).isEmpty()) {
                    throw RuntimeException("Downloaded jar does not contain a Lavalink plugin manifest: $url")
                }
            }

            try {
                java.nio.file.Files.move(
                    temporaryFile.toPath(),
                    output.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    temporaryFile.toPath(),
                    output.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            if (temporaryFile.exists() && !temporaryFile.delete()) {
                log.warn("Could not delete temporary plugin file: ${temporaryFile.path}")
            }
            (connection as? HttpURLConnection)?.disconnect()
        }
    }

    private fun readClasspathManifests(): List<PluginManifest> {
        return PathMatchingResourcePatternResolver()
            .getResources("classpath*:lavalink-plugins/*.properties")
            .map { parsePluginManifest(it.inputStream) }
            .onEach { log.info("Found plugin '${it.name}' version '${it.version}'") }
    }

    private fun loadJars(): List<PluginManifest> {
        val directory = File(config.pluginsDir).takeIf { it.isDirectory }
            ?: return emptyList()

        val jarsToLoad = directory.listFiles()?.filter { it.isFile && it.extension == "jar" }
            ?.filter {
                if (it.length() == 0L) {
                    log.warn("Plugin jar is empty, deleting...: ${it.path}")
                    if (!it.delete()) {
                        log.error("Failed to delete empty plugin jar: ${it.path}")
                    }
                    false
                } else {
                    true
                }
            }
            ?.takeIf { it.isNotEmpty() }
            ?: return emptyList()

        classLoader = URLClassLoader.newInstance(
            jarsToLoad.map { URL("jar:file:${it.absolutePath}!/") }.toTypedArray(),
            javaClass.classLoader
        )

        return jarsToLoad.flatMap { loadJar(it, classLoader) }
    }

    private fun loadJar(file: File, cl: ClassLoader): List<PluginManifest> {
        val jar = JarFile(file)
        val manifests = loadPluginManifests(jar)
        var classCount = 0

        jar.use {
            if (manifests.isEmpty()) {
                throw RuntimeException("No plugin manifest found in ${file.path}")
            }

            val allowedPaths = manifests.map { manifest -> "${manifest.path.replace(".", "/")}/" }

            for (entry in it.entries()) {
                if (entry.isDirectory ||
                    !entry.name.endsWith(".class") ||
                    allowedPaths.none(entry.name::startsWith)
                ) continue

                cl.loadClass(entry.name.dropLast(6).replace("/", "."))
                classCount++
            }
        }

        log.info("Loaded '${file.name}' ($classCount classes)")
        return manifests
    }

    private fun loadPluginManifests(jar: JarFile): List<PluginManifest> {
        return jar.entries().asSequence()
            .filter { !it.isDirectory && it.name.startsWith("lavalink-plugins/") && it.name.endsWith(".properties") }
            .map { parsePluginManifest(jar.getInputStream(it)) }
            .toList()
    }

    private fun parsePluginManifest(stream: InputStream): PluginManifest {
        val props = stream.use {
            Properties().apply { load(it) }
        }

        val name = props.getProperty("name") ?: throw RuntimeException("Manifest is missing 'name'")
        val path = props.getProperty("path") ?: throw RuntimeException("Manifest is missing 'path'")
        val version = props.getProperty("version") ?: throw RuntimeException("Manifest is missing 'version'")
        try {
            PluginValidation.validateManifest(name, path, version)
        } catch (ex: IllegalArgumentException) {
            throw RuntimeException("Invalid Lavalink plugin manifest", ex)
        }
        return PluginManifest(name, path, version)
    }

    private fun matchName(jar: PluginJar, name: String): Boolean {
        // removeSuffix removes names ending with "-v", such as -v1.0.0
        // and then the subsequent removeSuffix call removes trailing "-", which
        // usually precedes a version number, such as my-plugin-1.0.0.
        // We strip these to produce the name of the jar's file.
        val jarName = jar.file.nameWithoutExtension.takeWhile { !it.isDigit() }
            .removeSuffix("-v")
            .removeSuffix("-")

        return name == jarName
    }

    private data class PluginJar(val manifest: PluginManifest, val file: File)
    private data class Declaration(val group: String, val name: String, val version: String, val repository: String) {
        val canonicalJarName = "$name-$version.jar"
        val url = "$repository${group.replace(".", "/")}/$name/$version/$name-$version.jar"
    }
}
