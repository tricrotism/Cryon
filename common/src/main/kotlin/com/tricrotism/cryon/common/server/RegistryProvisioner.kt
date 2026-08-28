package com.tricrotism.cryon.common.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.Logger
import kotlin.time.Duration.Companion.milliseconds

/**
 * [Provisioner] over the [ServerRegistry], optionally backed by a [NodeAllocator].
 *
 * Matching is a scan of the registry's in-memory replica, so the query path costs no I/O at all,
 * which matters because "is there room" is asked on join, on warp and on every party action, and a
 * round trip per ask would put network latency on all of them.
 */
class RegistryProvisioner(
    private val registry: ServerRegistry,
    private val allocator: () -> NodeAllocator?,
    private val logger: Logger,
) : Provisioner {

    override suspend fun provision(request: ProvisionRequest): ProvisionResult {
        match(request)?.let { return ProvisionResult.Ready(it) }
        if (!request.createIfMissing) return ProvisionResult.Unavailable

        val allocator = allocator() ?: run {
            // Not an error: a static pool genuinely cannot grow, and saying "unavailable" is the
            // truth. Logged at debug because a caller asking optimistically is normal.
            logger.debug("Nothing matched in '{}' and no allocator is installed", request.serverId)
            return ProvisionResult.Unavailable
        }

        val accepted = try {
            allocator.allocate(request.serverId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Allocator failed for '{}'", request.serverId, e)
            return ProvisionResult.Failed(e.message ?: "allocator threw")
        }
        if (!accepted) return ProvisionResult.Failed("the allocator refused")

        return await(request)
    }

    /**
     * Wait for a newly allocated node to appear in the registry and match.
     *
     * Polls the replica rather than subscribing to registry events, because the replica *is* the
     * event stream's product. A node becomes visible here exactly when its heartbeat lands, and a
     * subscription would only move the same wait somewhere less obvious. The poll is a scan of a
     * list of tens, every quarter second, for at most one boot.
     */
    private suspend fun await(request: ProvisionRequest): ProvisionResult {
        val deadline = System.nanoTime() + request.waitMillis * NANOS_PER_MILLI
        while (System.nanoTime() < deadline) {
            delay(POLL_MILLIS.milliseconds)
            match(request)?.let { return ProvisionResult.Ready(it) }
        }
        // The node is probably still booting, so this is not the same answer as "nothing exists".
        // The caller should say "try again shortly", not "unavailable". See ProvisionResult.Pending.
        logger.info(
            "Allocated a node of '{}' but none reported ready within {}ms",
            request.serverId, request.waitMillis,
        )
        return ProvisionResult.Pending
    }

    /**
     * The emptiest matching node, so repeated provisions of the same pool spread rather than piling
     * onto whichever one the registry happens to list first. Ties break on node id for stability.
     */
    private fun match(request: ProvisionRequest): Node? =
        registry.nodesOf(request.serverId)
            .filter(request.selector::matches)
            .minWithOrNull(compareBy({ it.playerCount }, { it.nodeId }))

    private companion object {
        const val POLL_MILLIS = 250L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
