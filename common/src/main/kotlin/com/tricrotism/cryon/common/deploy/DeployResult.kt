package com.tricrotism.cryon.common.deploy

/**
 * What a deploy poll did, so a command can say something more useful than "ok".
 */
sealed interface DeployResult {

    /**
     * The repository is already at [commit] locally.
     */
    data class UpToDate(val commit: String) : DeployResult

    /**
     * [changed] files were written from [commit].
     */
    data class Applied(val commit: String, val changed: List<String>) : DeployResult

    /**
     * The repository could not be reached, or the tree could not be read. Nothing was changed.
     */
    data class Failed(val reason: String) : DeployResult
}
