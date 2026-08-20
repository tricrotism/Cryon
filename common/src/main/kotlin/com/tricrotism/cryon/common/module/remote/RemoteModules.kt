package com.tricrotism.cryon.common.module.remote

import com.tricrotism.cryon.common.concurrent.CryonIO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.*
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** What one artifact's poll did. Failures are per-artifact so one bad coordinate cannot stall the rest. */
sealed interface UpdateResult {

    val artifact: RemoteArtifact

    data class Installed(
        override val artifact: RemoteArtifact,
        val from: String?,
        val to: String,
    ) : UpdateResult

    data class UpToDate(override val artifact: RemoteArtifact, val revision: String) : UpdateResult

    data class Failed(override val artifact: RemoteArtifact, val reason: String) : UpdateResult
}

/**
 * Pulls feature jars from a remote Maven repository into `modules/`, and stops there.
 *
 * **It deliberately does not load anything.** Writing the jar is the whole job. When
 * `modules.auto-reload` is on, a filesystem watcher already turns a replaced jar into a hot-swap,
 * so a remote update behaves exactly like an admin dropping the file in by hand and needs no code
 * of its own. When auto-reload is off nothing watches, so the new build sits on disk until the next
 * restart or an explicit `/cryon load`. That one rule is why there is no second loading path here
 * and no separate switch deciding when a remote update may apply: the switch already exists.
 *
 * Every download is staged to a temporary file, verified against the `.sha1` the repository
 * publishes beside the jar, and only then moved into place, so a truncated transfer or a proxy
 * error page can never land somewhere the loader will try to open it. The move is atomic where the
 * filesystem supports it, which also keeps the watcher from ever seeing a half-written file.
 */
class RemoteModules(
    private val repositories: Map<String, MavenRepository>,
    val artifacts: List<RemoteArtifact>,
    private val modulesDir: File,
    private val stateFile: File,
    private val log: Logger,
) {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val lock = Mutex()

    private val state: Properties = Properties().also { properties ->
        if (stateFile.isFile) {
            runCatching { stateFile.inputStream().use(properties::load) }
                .onFailure { log.warn("Could not read remote module state from {}", stateFile.name, it) }
        }
    }

    /** The revision currently on disk for an artifact, or null if this server has never fetched it. */
    fun installedRevision(artifact: RemoteArtifact): String? = state.getProperty(artifact.id)

    /**
     * Check every configured artifact and install whatever moved.
     *
     * Serialized against itself so the poll timer and a manual `/cryon remote check` cannot race
     * onto the same file. Runs entirely off the server thread and touches no platform API.
     */
    suspend fun pollAll(): List<UpdateResult> = lock.withLock {
        val results = artifacts.map { artifact ->
            runCatching { update(artifact) }.getOrElse { error ->
                UpdateResult.Failed(artifact, error.message ?: error::class.simpleName.orEmpty())
            }
        }
        if (results.any { it is UpdateResult.Installed }) saveState()
        results
    }

    private suspend fun update(artifact: RemoteArtifact): UpdateResult {
        val repository = repositories[artifact.repository]
            ?: return UpdateResult.Failed(artifact, "no repository named ${artifact.repository}")

        val resolved = resolve(repository, artifact)
        val installed = installedRevision(artifact)
        val target = File(modulesDir, artifact.fileName)

        if (resolved.revision == installed && target.isFile) {
            return UpdateResult.UpToDate(artifact, resolved.revision)
        }

        val jar = get(repository, resolved.jarPath)
            ?: return UpdateResult.Failed(artifact, "jar missing at ${resolved.jarPath}")
        val expected = get(repository, resolved.jarPath + ".sha1")
            ?.toString(Charsets.UTF_8)
            ?.trim()
            ?.substringBefore(' ')

        when {
            expected == null ->
                log.warn("{} publishes no .sha1 beside its jar, installing unverified", artifact)

            !expected.equals(sha1(jar), ignoreCase = true) ->
                return UpdateResult.Failed(artifact, "checksum mismatch, refusing to install")
        }

        install(jar, target)
        state.setProperty(artifact.id, resolved.revision)
        return UpdateResult.Installed(artifact, installed, resolved.revision)
    }

    private fun install(jar: ByteArray, target: File) {
        val staging = File(modulesDir.parentFile, TMP_DIR).apply { mkdirs() }
        val part = File(staging, target.name + ".part")
        part.writeBytes(jar)
        runCatching {
            Files.move(
                part.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.recoverCatching {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.getOrThrow()
    }

    private class Resolved(val jarPath: String, val revision: String)

    /**
     * Work out which concrete build a coordinate currently points at.
     *
     * A pin resolves to itself and costs no round trip beyond the jar. A snapshot asks
     * `maven-metadata.xml`, preferring its `snapshotVersions` block, which is the only authoritative
     * source for the published filename: a repository may use either unique timestamped names or a
     * single overwritten `-SNAPSHOT` one, and guessing wrong is a 404 rather than a wrong answer.
     * Composing the timestamp and build number by hand is the fallback for metadata that omits it.
     *
     * The revision carries the resolved version as well as the build, so moving a server to a
     * different branch re-downloads even when that branch's newest build is older than the current
     * one.
     */
    private suspend fun resolve(repository: MavenRepository, artifact: RemoteArtifact): Resolved {
        if (!artifact.isSnapshot) {
            return Resolved(artifact.pinnedJarPath(), artifact.resolvedVersion)
        }

        val metadata = get(repository, artifact.metadataPath)
            ?: throw IOException("no maven-metadata.xml at " + artifact.metadataPath)
        val versioning = parse(metadata)

        val build = versioning.jarValue
            ?: composed(artifact, versioning)
            ?: artifact.resolvedVersion

        return Resolved(
            jarPath = artifact.jarPath(build),
            revision = artifact.resolvedVersion + "@" + build + "@" + versioning.lastUpdated.orEmpty(),
        )
    }

    private fun composed(artifact: RemoteArtifact, versioning: Versioning): String? {
        val stamp = versioning.timestamp ?: return null
        val number = versioning.buildNumber ?: return null
        return artifact.resolvedVersion.removeSuffix("-SNAPSHOT") + "-" + stamp + "-" + number
    }

    private class Versioning(
        val jarValue: String?,
        val timestamp: String?,
        val buildNumber: String?,
        val lastUpdated: String?,
    )

    private fun parse(xml: ByteArray): Versioning {
        val document = DOCUMENTS.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val entries = document.getElementsByTagName("snapshotVersion")
        val jarValue = (0 until entries.length)
            .map { entries.item(it) as Element }
            .firstOrNull { it.child("extension") == "jar" && it.child("classifier") == null }
            ?.child("value")
        return Versioning(
            jarValue = jarValue,
            timestamp = document.first("timestamp"),
            buildNumber = document.first("buildNumber"),
            lastUpdated = document.first("lastUpdated"),
        )
    }

    private fun Element.child(tag: String): String? =
        getElementsByTagName(tag).item(0)?.textContent?.takeIf { it.isNotBlank() }

    private fun org.w3c.dom.Document.first(tag: String): String? =
        getElementsByTagName(tag).item(0)?.textContent?.takeIf { it.isNotBlank() }

    /** Null on 404, which is a real answer here. Anything else is an error worth the caller's attention. */
    private suspend fun get(repository: MavenRepository, path: String): ByteArray? =
        withContext(CryonIO.dispatcher) {
            val request = repository.request(path).timeout(Duration.ofSeconds(60)).GET().build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
            when (val code = response.statusCode()) {
                in 200..299 -> response.body()
                404 -> null
                401, 403 -> throw IOException(
                    path + " rejected with " + code + " by " + repository + ", set " +
                            MavenRepository.envKey(repository.name, "USERNAME") + " and " +
                            MavenRepository.envKey(repository.name, "PASSWORD")
                )

                else -> throw IOException("$path returned $code from $repository")
            }
        }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun saveState() {
        runCatching {
            stateFile.parentFile?.mkdirs()
            stateFile.outputStream().use { state.store(it, "Cryon remote module revisions") }
        }.onFailure { log.warn("Could not write remote module state to {}", stateFile.name, it) }
    }

    private companion object {

        const val TMP_DIR = ".remote-tmp"

        /** Metadata arrives from a remote repository, so the parser is told to trust none of it. */
        val DOCUMENTS: DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
    }
}
