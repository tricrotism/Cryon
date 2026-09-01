package com.tricrotism.cryon.common.deploy

import java.nio.file.Path

/**
 * One thing the deploy repository delivers: where it lives, where it lands, and what happens after.
 *
 * @param repositoryPath path in the repository, where `{server}` is replaced with this node's serverId
 * @param destination a file target when [repositoryPath] names a file, a directory otherwise
 * @param apply runs only when something changed. Its failure is logged rather than propagated: the
 *   files are already on disk, so refusing to record the commit would retry the same failing hook
 *   forever
 */
class DeployTarget(
    val name: String,
    val repositoryPath: String,
    val destination: Path,
    val apply: () -> Unit = {},
)
