package com.tricrotism.cryon.common.deploy

import com.tricrotism.cryon.common.deploy.GitArchive.Companion.REF_LINE
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.*
import java.util.zip.ZipInputStream

/**
 * Reads a git repository over plain HTTP.
 *
 * No git client and no JGit, for the same reason `RemoteModules` talks to Maven over `HttpClient`
 * rather than embedding a resolver: the two things needed are a ref lookup and a download, both one
 * request. It also means this works from a scratch container.
 *
 * The ref lookup is git's smart-HTTP advertisement, which every forge serves and which returns a few
 * kilobytes, so polling costs almost nothing. The tree comes from the forge's zip archive rather than
 * a tarball, because the JDK reads zip and has no tar reader.
 */
class GitArchive(
    private val refsUrl: String,
    private val archiveUrlTemplate: String,
    private val username: String?,
    private val password: String?,
    private val timeout: Duration,
) : RepositorySource {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /**
     * The commit [ref] currently points at, or null when the ref is not advertised.
     *
     * @throws java.io.IOException when the repository cannot be reached, which the caller treats as
     *   "try again next poll" rather than as a reason to change anything locally.
     */
    override fun advertise(): Advertisement {
        val response = send(refsUrl, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw java.io.IOException("ref lookup returned HTTP ${response.statusCode()} for $refsUrl")
        }
        return parseAdvertisement(response.body())
    }

    /**
     * Extract the tree at [commit] into a fresh directory under [workDirectory], and return it.
     *
     * The forge's archive wraps everything in one top-level directory named after the repository and
     * commit, which is stripped, so callers address paths as they appear in the repository.
     */
    override fun fetch(commit: String, workDirectory: Path): Path {
        val destination = workDirectory.resolve("tree-$commit")
        if (Files.isDirectory(destination)) deleteRecursively(destination)
        Files.createDirectories(destination)

        val url = archiveUrlTemplate.replace(REF_PLACEHOLDER, commit)
        val response = send(url, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            runCatching { response.body().close() }
            throw java.io.IOException("archive download returned HTTP ${response.statusCode()} for $url")
        }
        response.body().use { body -> unzip(body, destination) }
        return destination
    }

    /**
     * Unpack [input] into [target], dropping the archive's single top-level directory.
     *
     * Every entry's resolved path is checked to still be under [target] before anything is written.
     * An archive entry named `../../plugins/evil.jar` is a well-known way to write outside the
     * extraction root, and the repository this reads is exactly the kind of thing that gets a pull
     * request from somebody who is not an operator.
     */
    private fun unzip(input: InputStream, target: Path) {
        val root = target.toAbsolutePath().normalize()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val relative = entry.name.substringAfter('/', "")
                if (relative.isNotEmpty()) {
                    val path = root.resolve(relative).normalize()
                    if (!path.startsWith(root)) {
                        throw java.io.IOException("archive entry '${entry.name}' escapes the extraction directory")
                    }
                    if (entry.isDirectory) {
                        Files.createDirectories(path)
                    } else {
                        Files.createDirectories(path.parent)
                        Files.copy(zip, path, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun <T> send(url: String, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET()
        // No header at all when nothing resolved, so a public repository works and a private one
        // answers 401 honestly instead of falling back to whatever credentials happen to be around.
        if (!username.isNullOrEmpty()) {
            val token = Base64.getEncoder()
                .encodeToString("$username:${password.orEmpty()}".toByteArray(Charsets.UTF_8))
            request.header("Authorization", "Basic $token")
        }
        return client.send(request.build(), handler)
    }

    companion object {

        /**
         * Read a smart-HTTP ref advertisement.
         *
         * Split out from the request so it can be exercised. The advertisement is pkt-line framed
         * and its first entry separates the ref name from its capability list with a **NUL**, a byte
         * no reviewer would think to check for by eye, and the whole thing is a text format with no
         * library behind it. Both of those are why the parse lives here and is tested, rather than
         * sitting inside a method that needs a network to reach.
         *
         * Refs are matched rather than frames parsed, which is enough because a ref name can contain
         * neither whitespace nor NUL, so [REF_LINE]'s character class ends the name exactly where it
         * really ends. A body that parses to nothing yields an empty advertisement rather than
         * throwing: a proxy's HTML error page is a repository that answered badly, not a bug here.
         */
        internal fun parseAdvertisement(body: String): Advertisement {
            val commits = LinkedHashMap<String, String>()
            for (match in REF_LINE.findAll(body)) {
                // A ref advertised twice keeps the first, which is how git reads the listing.
                commits.putIfAbsent(match.groupValues[2], match.groupValues[1])
            }
            return Advertisement(commits, SYMREF.find(body)?.groupValues?.get(1))
        }

        /**
         * `<sha> <ref>`, the name running up to whatever ends it.
         *
         * The NUL is written as an escape, never as a raw byte: one typed into source is invisible
         * to a reader and turns the whole file binary for diff and grep.
         */
        private val REF_LINE = Regex("([0-9a-f]{40})\\s+(refs/[^\\s\\u0000]+)")

        /** `symref=HEAD:refs/heads/<name>`, the only statement of the repository's default branch. */
        private val SYMREF = Regex("symref=HEAD:(refs/[^\\s\\u0000]+)")

        /** Replaced with the commit in `archive-url`. */
        const val REF_PLACEHOLDER = "{ref}"

        fun deleteRecursively(directory: Path) {
            if (!Files.exists(directory)) return
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
            }
        }
    }
}
