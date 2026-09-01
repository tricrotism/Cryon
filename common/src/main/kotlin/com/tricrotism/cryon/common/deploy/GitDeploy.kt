package com.tricrotism.cryon.common.deploy

import com.tricrotism.cryon.common.config.Config
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*
import java.util.concurrent.atomic.AtomicReference

/**
 * Pull a git repository on an interval and lay its contents where the loaders already look.
 *
 * This is the delivery half only. A jar written into `modules/` hot-swaps when `modules.auto-reload`
 * is on and waits for a restart when it is not, exactly as if an admin had dropped it in, and a lang
 * file is re-read by `MessageService.reload`. A second switch letting a commit apply where a local
 * change could not is the surprise this refuses, the same rule `remote.enabled` follows. `config.yml`
 * is the exception and is re-read immediately, because nothing was watching it before.
 *
 * Nothing is ever deleted, so a bad merge cannot empty a server. A target is written only where the
 * bytes differ, so an unrelated commit does not fire the config reload hook.
 *
 * The commit is recorded after the writes land and before the hooks run, so a process killed
 * mid-apply re-applies the same commit rather than skipping it. Applying twice is harmless: the
 * writes are a copy of the same bytes.
 */
class GitDeploy(
    private val archive: RepositorySource,
    private val ref: String,
    private val serverId: String,
    // the directory in the repository this server's files live under, `{server}` already resolved
    private val folder: String,
    private val targets: List<DeployTarget>,
    private val stateFile: Path,
    private val workDirectory: Path,
    private val logger: Logger,
) {

    // held in memory rather than re-read, so a command can ask on a region thread without disk I/O
    @Volatile
    private var appliedCommit: String? = readState().getProperty(COMMIT_KEY)
    private val following = AtomicReference<String>()

    /**
     * @return the commit last applied here, or null when nothing has been. Safe on any thread
     */
    fun applied(): String? = appliedCommit

    /**
     * @return the ref actually being followed, which is [ref] unless it fell back, or null before the
     *   first poll
     */
    fun following(): String? = following.get()

    /**
     * @return the repository folder this server takes its files from
     */
    fun folder(): String = folder

    /**
     * Look for a new commit and, if there is one, write it out.
     *
     * Blocking, and deliberately not suspending: it is driven by a timer that already runs off the
     * server threads, and every step of it is a blocking JDK call, so a suspending wrapper would
     * only hide that it is I/O all the way down. Never call it from a tick or event thread.
     */
    fun poll(): DeployResult {
        val advertisement = try {
            archive.advertise()
        } catch (e: Exception) {
            return DeployResult.Failed("could not reach the deploy repository: ${e.message}")
        }

        val resolved = resolve(advertisement)
            ?: return DeployResult.Failed(
                "the deploy repository advertises neither $ref nor any fallback " +
                        "(it has: ${advertisement.refs.take(10).joinToString(", ").ifEmpty { "nothing" }})"
            )
        announce(resolved.ref)
        val head = resolved.commit

        if (head == applied()) return DeployResult.UpToDate(head)

        val tree = try {
            archive.fetch(head, workDirectory)
        } catch (e: Exception) {
            return DeployResult.Failed("could not read the tree at ${head.take(8)}: ${e.message}")
        }

        return try {
            // A folder that is not there means every target below finds nothing, which without this
            // is indistinguishable from a commit that changed none of them. A typo in deploy.folder
            // would otherwise look exactly like a working deployment.
            if (folder.isNotEmpty() && !Files.isDirectory(tree.resolve(folder))) {
                logger.warn(
                    "The deploy repository has no '{}' folder at {}; it holds: {}",
                    folder, head.take(8), folders(tree).ifEmpty { listOf("nothing") }.joinToString(", "),
                )
            }

            val changed = ArrayList<String>()
            val applied = ArrayList<DeployTarget>()
            for (target in targets) {
                val written = syncTarget(target, tree)
                if (written.isNotEmpty()) {
                    changed += written
                    applied += target
                }
            }
            // Recorded before the hooks run: the bytes are already down, so a hook that throws must
            // not make the next poll rewrite everything and throw again.
            writeState(head)
            for (target in applied) {
                runCatching { target.apply() }.onFailure {
                    logger.error("Deployed {} but its reload hook failed", target.name, it)
                }
            }
            if (changed.isEmpty()) DeployResult.UpToDate(head) else DeployResult.Applied(head, changed)
        } catch (e: Exception) {
            DeployResult.Failed("could not apply ${head.take(8)}: ${e.message}")
        } finally {
            GitArchive.deleteRecursively(tree)
        }
    }

    private class Resolved(val ref: String, val commit: String)

    /**
     * A branch that is not there falls back rather than failing. The configured branch is what the
     * operator meant; the repository's own default is what they would pick knowing it was gone, since
     * a deleted feature branch should leave a server on the trunk rather than frozen on its last
     * commit. `main` and `master` come last because a server that omits `symref` still has one.
     *
     * Ordered and de-duplicated, so a repository whose default is the configured branch costs one
     * lookup rather than four.
     *
     * @return the first ref in the chain the repository has, or null when it has none of them
     */
    private fun resolve(advertisement: Advertisement): Resolved? {
        val candidates = LinkedHashSet<String>()

        candidates += ref
        advertisement.defaultRef?.let { candidates += it }
        candidates += FALLBACK_REFS

        for (candidate in candidates) {
            advertisement.commitOf(candidate)?.let { return Resolved(candidate, it) }
        }

        return null
    }

    /**
     * Say which ref is being followed, but only when it changes.
     *
     * Logging it every poll would put a line in the console every minute for as long as the branch is
     * missing, which is how a real warning becomes something operators filter out.
     */
    private fun announce(resolved: String) {
        if (following.getAndSet(resolved) == resolved) return

        if (resolved == ref) logger.info("Deploying from {}", resolved)
        else logger.warn("The deploy repository has no {}, so falling back to {}", ref, resolved)
    }

    /** Copy [target]'s source into place, returning the repository-relative paths actually written. */
    /**
     * @return the top-level directory names in [tree], so a missing folder can say what is there
     */
    private fun folders(tree: Path): List<String> = runCatching {
        Files.list(tree).use { paths ->
            paths.filter { Files.isDirectory(it) }.map { it.fileName.toString() }.sorted().toList()
        }
    }.getOrDefault(emptyList())

    private fun syncTarget(target: DeployTarget, tree: Path): List<String> {
        val within = target.repositoryPath.replace(SERVER_PLACEHOLDER, serverId)
        // every path is relative to this server's folder, so one repository holds many servers and a
        // file only ever reaches the server whose folder it sits in
        val relative = if (folder.isEmpty()) within else "$folder/$within"
        val source = tree.resolve(relative)
        if (!Files.exists(source)) return emptyList()

        val written = ArrayList<String>()
        if (Files.isDirectory(source)) {
            Files.walk(source).use { paths ->
                paths.filter { Files.isRegularFile(it) }.forEach { file ->
                    val within = source.relativize(file).toString().replace('\\', '/')
                    if (copyIfChanged(file, target.destination.resolve(within))) {
                        written += "$relative/$within"
                    }
                }
            }
        } else if (copyIfChanged(source, target.destination)) {
            written += relative
        }
        return written
    }

    /**
     * Copy [from] over [to] when the bytes differ, through a temporary file and an atomic move.
     *
     * Comparing first is what keeps a poll from re-announcing the whole tree every time an unrelated
     * file in the repository changes, and it keeps the config reload hook from firing on a commit
     * that did not touch the config.
     */
    private fun copyIfChanged(from: Path, to: Path): Boolean {
        if (Files.exists(to) && Files.size(from) == Files.size(to) &&
            Files.readAllBytes(from).contentEquals(Files.readAllBytes(to))
        ) {
            return false
        }
        Files.createDirectories(to.parent)
        val temp = to.resolveSibling(to.fileName.toString() + ".deploy-tmp")
        Files.copy(from, temp, StandardCopyOption.REPLACE_EXISTING)
        Files.move(temp, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return true
    }

    private fun readState(): Properties {
        val properties = Properties()
        if (Files.isRegularFile(stateFile)) {
            runCatching { Files.newInputStream(stateFile).use { properties.load(it) } }
                .onFailure { logger.warn("Could not read {}, treating the deploy state as empty", stateFile, it) }
        }
        return properties
    }

    private fun writeState(commit: String) {
        val properties = readState()
        properties.setProperty(COMMIT_KEY, commit)
        properties.setProperty(REF_KEY, ref)
        Files.createDirectories(stateFile.parent)
        Files.newOutputStream(stateFile).use { properties.store(it, "Cryon git deploy state") }
        appliedCommit = commit
    }

    companion object {

        // replaced with this node's serverId in a target's repository path
        const val SERVER_PLACEHOLDER = "{server}"

        // tried after the configured branch and the repository's own default; a server that omits
        // symref gives no other way to find the trunk, and which name it uses is not knowable
        private val FALLBACK_REFS = listOf("refs/heads/main", "refs/heads/master")

        private const val COMMIT_KEY = "commit"
        private const val REF_KEY = "ref"

        /**
         * Build the poller from [config].
         *
         * Here rather than in each loader because all three wire the identical block. The only things
         * that differ are the directories and the two reload hooks, so those are the parameters.
         *
         * A misconfiguration logs and returns null rather than throwing: `deploy.enabled` on with a
         * blank URL is an operator halfway through setting it up, and refusing to boot over that
         * would make the feature more dangerous than not having it.
         *
         * @return the poller, or null when deploy is off or misconfigured
         */
        fun from(
            config: Config,
            serverId: String,
            dataFolder: Path,
            configFile: Path,
            langDirectory: Path,
            modulesDirectory: Path,
            onConfigChanged: () -> Unit,
            onLangChanged: () -> Unit,
            logger: Logger,
        ): GitDeploy? {
            if (!config[DeployKeys.ENABLED]) return null

            val refsUrl = config[DeployKeys.REFS_URL]
            val archiveUrl = config[DeployKeys.ARCHIVE_URL]

            if (refsUrl.isBlank() || archiveUrl.isBlank()) {
                logger.error("deploy.enabled is on but deploy.refs-url or deploy.archive-url is empty; not polling")
                return null
            }

            if (!archiveUrl.contains(GitArchive.REF_PLACEHOLDER)) {
                logger.error(
                    "deploy.archive-url must contain {} where the commit goes; not polling",
                    GitArchive.REF_PLACEHOLDER,
                )
                return null
            }

            val targets = ArrayList<DeployTarget>()
            config[DeployKeys.PATH_CONFIG].takeIf { it.isNotBlank() }?.let { path ->
                targets += DeployTarget("config.yml", path, configFile, onConfigChanged)
            }
            config[DeployKeys.PATH_LANG].takeIf { it.isNotBlank() }?.let { path ->
                targets += DeployTarget("lang", path, langDirectory, onLangChanged)
            }
            config[DeployKeys.PATH_MODULES].takeIf { it.isNotBlank() }?.let { path ->
                // no hook: a jar is applied by modules.auto-reload or by the next restart, the same
                // rule a jar an operator dropped in follows
                targets += DeployTarget("modules", path, modulesDirectory)
            }
            config[DeployKeys.PATH_DATA].takeIf { it.isNotBlank() }?.let { path ->
                // no hook: a module reads its own config through PaperModule.config(), which is a
                // fresh read each call, so a redeploy lands on its next reload rather than needing
                // one fired from here
                targets += DeployTarget("data", path, dataFolder.resolve("data"))
            }

            if (targets.isEmpty()) {
                logger.warn("deploy.enabled is on but every deploy.paths.* entry is empty; not polling")
                return null
            }

            val archive = GitArchive(
                refsUrl = refsUrl,
                // environment first, and an empty config value is absent rather than a blank
                // username, so a public repository sends no Authorization header at all
                archiveUrlTemplate = archiveUrl,
                username = System.getenv(DeployKeys.USERNAME.environmentVariable)
                    ?: config[DeployKeys.USERNAME].ifEmpty { null },
                password = System.getenv(DeployKeys.PASSWORD.environmentVariable)
                    ?: config[DeployKeys.PASSWORD].ifEmpty { null },
                timeout = DeployKeys.timeout(config[DeployKeys.TIMEOUT_SECONDS]),
            )

            return GitDeploy(
                archive = archive,
                ref = DeployKeys.refOf(config[DeployKeys.BRANCH]),
                serverId = serverId,
                folder = config[DeployKeys.FOLDER].replace(SERVER_PLACEHOLDER, serverId).trim('/'),
                targets = targets,
                stateFile = dataFolder.resolve("deploy-state.properties"),
                workDirectory = dataFolder.resolve(".deploy-tmp"),
                logger = logger,
            )
        }
    }
}
