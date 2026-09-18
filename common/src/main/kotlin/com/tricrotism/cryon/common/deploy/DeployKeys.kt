package com.tricrotism.cryon.common.deploy

import com.tricrotism.cryon.common.config.ConfigSchema
import java.time.Duration

/**
 * The keys the git deploy reads, declared beside it because all three loaders wire the same block.
 *
 * Credentials are environment-first with no default, the rule the remote-module repositories already
 * follow: `CRYON_DEPLOY_USERNAME` and `CRYON_DEPLOY_PASSWORD`, with a personal access token in the
 * password. The empty defaults allow a token in `config.yml`, but that file is the one an operator is
 * most likely to paste into a support channel.
 */
object DeployKeys : ConfigSchema() {

    val ENABLED = boolean("deploy.enabled", false)
    val REFS_URL = string("deploy.refs-url", "")
    val ARCHIVE_URL = string("deploy.archive-url", "")

    /**
     * The branch to deploy from, as a bare name.
     *
     * A bare name because that is what an operator types in a pull request, mirroring
     * `remote.artifacts[].branch`. Anything already starting with `refs/` is taken verbatim, which is
     * how a server pins to a tag. A branch the repository does not have falls back rather than
     * failing; see [GitDeploy].
     */
    val BRANCH = nonBlankString("deploy.branch", "main")

    /**
     * The directory in the repository holding this server's files.
     *
     * One repository can then carry `geyser/`, `velocity/`, `lifesteal8/` side by side, and a file
     * only ever reaches the server whose folder it sits in. `{server}` resolves to this node's pool
     * name, which is the right default: a pool of interchangeable nodes wants one folder between
     * them. Name it explicitly when the folder is not called after the pool.
     */
    val FOLDER = nonBlankString("deploy.folder", "{server}")

    /**
     * The directory every server takes files from, laid down under [FOLDER].
     *
     * A feature configured the same on every server is written once here instead of copied into each
     * server folder, and a server that disagrees carries only the file it changes: the layers merge
     * per file, so `global/data/metrics/config.yml` still lands on a server whose own folder holds
     * nothing for `metrics`.
     *
     * Blank turns the shared layer off. A folder the repository does not carry is not a
     * misconfiguration and says nothing, unlike [FOLDER], whose absence means this server gets
     * nothing at all.
     */
    val GLOBAL_FOLDER = string("deploy.global-folder", "global")

    val POLL_SECONDS = long("deploy.poll-seconds", 60L, 15L..86400L)
    val TIMEOUT_SECONDS = long("deploy.timeout-seconds", 30L, 5L..600L)
    val USERNAME = string("deploy.username", "")
    val PASSWORD = string("deploy.password", "")
    val PATH_CONFIG = string("deploy.paths.config", "config.yml")
    val PATH_LANG = string("deploy.paths.lang", "lang")
    val PATH_MODULES = string("deploy.paths.modules", "modules")

    /**
     * Contract jars, mirroring `plugins/Cryon/api/`.
     *
     * Separate from [PATH_MODULES] because the two land in different classloaders: a contract jar
     * has to load from the shared parent or a provider and its consumer hold two copies of the same
     * interface. A repository carrying a feature whose API another repository compiles against has
     * nowhere else to put it.
     */
    val PATH_API = string("deploy.paths.api", "api")

    /**
     * Per-module config directories, mirroring `plugins/Cryon/data/<module-id>/`.
     *
     * A module whose directory the folder does not carry is not overridden at all: it extracts the
     * default bundled in its own jar on first run, exactly as it does with no deploy configured. So a
     * server folder only has to hold what it actually changes.
     */
    val PATH_DATA = string("deploy.paths.data", "data")

    /**
     * @return [BRANCH] as a full ref
     */
    fun refOf(branch: String): String =
        if (branch.startsWith("refs/")) branch else "refs/heads/$branch"

    fun timeout(seconds: Long): Duration = Duration.ofSeconds(seconds)
}
