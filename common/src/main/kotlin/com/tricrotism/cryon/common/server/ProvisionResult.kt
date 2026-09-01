package com.tricrotism.cryon.common.server

/**
 * What [Provisioner.provision] managed to do.
 */
sealed interface ProvisionResult {

    /** A node matching the selector already existed, or one was created and became ready. */
    data class Ready(val node: Node) : ProvisionResult

    /**
     * Nothing matched and nothing was created, either [ProvisionRequest.createIfMissing] was false,
     * or creation is not possible on this deployment.
     */
    data object Unavailable : ProvisionResult

    /**
     * Creation was asked for and started, but the node did not report ready inside the wait.
     *
     * Distinct from [Unavailable] on purpose: a node is probably still coming up, so the caller
     * should tell the player to try shortly rather than that nothing exists. Collapsing the two
     * turns "wait ten seconds" into "it is broken".
     */
    data object Pending : ProvisionResult

    /** The backing orchestrator refused or failed. [reason] is for the log, not for a player. */
    data class Failed(val reason: String) : ProvisionResult
}
