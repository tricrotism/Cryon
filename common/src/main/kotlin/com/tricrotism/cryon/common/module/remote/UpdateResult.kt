package com.tricrotism.cryon.common.module.remote

/**
 * What one artifact's poll did. Failures are per-artifact so one bad coordinate cannot stall the rest.
 */
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
