package com.tricrotism.cryon.common.server

/**
 * The seam to whatever can actually make a node exist.
 *
 * Deliberately tiny and deliberately not implemented here: the core has no business knowing about
 * Agones fleets or Kubernetes, and a deployment that scales by hand should be able to leave this
 * absent rather than stub it. Publish an implementation into the `ServiceRegistry` and [Provisioner]
 * starts being able to create; leave it out and it stays a query.
 */
interface NodeAllocator {

    /**
     * Ask for one more node of [serverId]. Answers whether the request was accepted, **not** whether
     * a node is ready. Readiness is observed through the registry, because the orchestrator's idea
     * of "created" and the server's idea of "accepting players" are different events.
     */
    suspend fun allocate(serverId: String): Boolean
}
