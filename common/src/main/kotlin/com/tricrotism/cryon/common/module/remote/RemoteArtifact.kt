package com.tricrotism.cryon.common.module.remote

/**
 * One feature jar tracked in a remote repository.
 *
 * **Maven has no branches, so a branch is spliced into the version.** `1.0.0` on branch `main`
 * resolves to `1.0.0-main-SNAPSHOT`, which is what CI publishes, and switching a server to a
 * feature branch is a one-word config edit rather than a second repository. A blank branch is
 * plain `1.0.0-SNAPSHOT`, and a version already carrying its own `-SNAPSHOT` suffix is taken as
 * written so an artifact can opt out of the convention entirely.
 *
 * A version with no `-SNAPSHOT` anywhere is a **pin**: it resolves to exactly one immutable jar,
 * is fetched once, and never changes again. That is the right shape for a production server that
 * wants remote delivery without remote surprises.
 */
data class RemoteArtifact(
    val repository: String,
    val group: String,
    val artifact: String,
    val version: String,
    val branch: String,
) {

    val id: String = "$group:$artifact"

    val resolvedVersion: String = when {
        version.endsWith(SNAPSHOT) -> version
        branch.isBlank() -> version + SNAPSHOT
        else -> "$version-$branch$SNAPSHOT"
    }

    val isSnapshot: Boolean = resolvedVersion.endsWith(SNAPSHOT)

    private val directory: String = "${group.replace('.', '/')}/$artifact/$resolvedVersion"

    val metadataPath: String = "$directory/maven-metadata.xml"

    // The name on disk, and deliberately **stable across versions**.
    //
    // A versioned filename would leave the previous build sitting beside the new one in `modules/`,
    // and the loader would then discover the same module twice. Writing to one name means a new
    // build is a *replacement*, which is exactly the file event the hot-reload watcher already
    // knows how to turn into a swap
    val fileName: String = "$artifact.jar"

    /**
     * The jar for one resolved build, where `build` is the version Maven actually published under.
     */
    fun jarPath(build: String): String = "$directory/$artifact-$build.jar"

    /**
     * What a pinned (non-snapshot) artifact publishes: one jar named after the version itself.
     */
    fun pinnedJarPath(): String = jarPath(resolvedVersion)

    override fun toString(): String = "$id:$resolvedVersion"

    private companion object {
        const val SNAPSHOT = "-SNAPSHOT"
    }
}
