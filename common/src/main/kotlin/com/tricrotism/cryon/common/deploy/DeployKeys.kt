package com.tricrotism.cryon.common.deploy

import com.tricrotism.cryon.common.config.ConfigKeys
import java.time.Duration

/**
 * The keys the git deploy reads, declared beside it because all three loaders wire the same block.
 *
 * Credentials are environment-first with no default, the rule the remote-module repositories already
 * follow: `CRYON_DEPLOY_USERNAME` and `CRYON_DEPLOY_PASSWORD`, with a personal access token in the
 * password. The empty defaults allow a token in `config.yml`, but that file is the one an operator is
 * most likely to paste into a support channel.
 */
object DeployKeys {

    val ENABLED = ConfigKeys.boolean("deploy.enabled", false)
    val REFS_URL = ConfigKeys.string("deploy.refs-url", "")
    val ARCHIVE_URL = ConfigKeys.string("deploy.archive-url", "")

    /**
     * The branch to deploy from, as a bare name.
     *
     * A bare name because that is what an operator types in a pull request, mirroring
     * `remote.artifacts[].branch`. Anything already starting with `refs/` is taken verbatim, which is
     * how a server pins to a tag. A branch the repository does not have falls back rather than
     * failing; see [GitDeploy].
     */
    val BRANCH = ConfigKeys.nonBlankString("deploy.branch", "main")

    /**
     * The directory in the repository holding this server's files.
     *
     * One repository can then carry `geyser/`, `velocity/`, `lifesteal8/` side by side, and a file
     * only ever reaches the server whose folder it sits in. `{server}` resolves to this node's pool
     * name, which is the right default: a pool of interchangeable nodes wants one folder between
     * them. Name it explicitly when the folder is not called after the pool.
     */
    val FOLDER = ConfigKeys.nonBlankString("deploy.folder", "{server}")

    val POLL_SECONDS = ConfigKeys.long("deploy.poll-seconds", 60L, 15L..86400L)
    val TIMEOUT_SECONDS = ConfigKeys.long("deploy.timeout-seconds", 30L, 5L..600L)
    val USERNAME = ConfigKeys.string("deploy.username", "")
    val PASSWORD = ConfigKeys.string("deploy.password", "")
    val PATH_CONFIG = ConfigKeys.string("deploy.paths.config", "config.yml")
    val PATH_LANG = ConfigKeys.string("deploy.paths.lang", "lang")
    val PATH_MODULES = ConfigKeys.string("deploy.paths.modules", "modules")

    /**
     * Per-module config directories, mirroring `plugins/Cryon/data/<module-id>/`.
     *
     * A module whose directory the folder does not carry is not overridden at all: it extracts the
     * default bundled in its own jar on first run, exactly as it does with no deploy configured. So a
     * server folder only has to hold what it actually changes.
     */
    val PATH_DATA = ConfigKeys.string("deploy.paths.data", "data")

    /**
     * @return [BRANCH] as a full ref
     */
    fun refOf(branch: String): String =
        if (branch.startsWith("refs/")) branch else "refs/heads/$branch"

    fun timeout(seconds: Long): Duration = Duration.ofSeconds(seconds)
}
