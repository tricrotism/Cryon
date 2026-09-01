package com.tricrotism.cryon.common.colony

/**
 * Told when this node takes or loses a crown. Registered per service; never called concurrently.
 */
interface ColonyListener {

    /**
     * This node is now the queen for [service] and should start doing its work.
     *
     * **Load whatever state the job needs from SQL here, do not expect it handed over.** The
     * previous queen may have died rather than resigned, so there is nothing to hand over, which is
     * why a queen's durable state belongs in the database and not in its heap.
     */
    suspend fun onPromote(service: ClusterService) {}

    /**
     * This node is no longer the queen. Stop the work; somebody else has already started it.
     */
    suspend fun onDemote(service: ClusterService) {}
}
