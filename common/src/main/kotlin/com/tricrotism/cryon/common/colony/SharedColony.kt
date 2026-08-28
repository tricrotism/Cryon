package com.tricrotism.cryon.common.colony

import com.tricrotism.cryon.common.net.KeyValueStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.Logger
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.absoluteValue

/**
 * [Colony] over the [KeyValueStore], so it reaches every node on Redis and stays in-process without it.
 *
 * **Advertisements live in one hash, not one key per node.** A key-per-node scheme would need
 * `keys("prefix*")` to read the cluster, and that walks the whole keyspace rather than the handful
 * of entries it wants. The cost scales with everything else stored in Redis. One `hgetAll` is a
 * single round trip proportional to the pool.
 *
 * The consequence is that the hash's TTL is not a per-node liveness signal: `KeyValueStore.hset`
 * expires the *hash*, not the field. So each advertisement carries its own heartbeat timestamp and
 * stale ones are discarded on read, exactly as `KeyValueStore.hset`'s own documentation recommends.
 * The hash TTL remains as the backstop that stops an abandoned pool's entry living forever.
 */
class SharedColony(
    private val nodeId: String,
    private val serverId: String,
    private val store: KeyValueStore,
    private val logger: Logger,
    private val heartbeatTimeout: Duration = DEFAULT_TIMEOUT,
) : Colony {

    private val elector = ColonyElector(nodeId)
    private val services = ConcurrentHashMap<String, ClusterService>()
    private val listeners = ConcurrentHashMap<String, ColonyListener>()

    @Volatile
    private var leaving = false

    /** Scoped to the pool: two different servers may both run a service named `market`. */
    private val key = "cryon:colony:$serverId"

    override fun register(service: ClusterService, listener: ColonyListener?) {
        services[service.id] = service
        listener?.let { listeners[service.id] = it }
        elector.host(service.id)
    }

    override fun isQueen(service: ClusterService): Boolean =
        elector.mode(service.id) == ColonyMode.Queen

    override fun status(service: ClusterService): QueenStatus = elector.status(service.id)

    override fun shards(service: ClusterService): List<String> = elector.shards(service.id)

    override suspend fun route(service: ClusterService, strategy: ShardingStrategy): String? {
        val shard = strategy.shard()
        repeat(ROUTE_ATTEMPTS) { attempt ->
            val resolved = if (shard == 0) resolveQueen(service) else resolveShard(service, shard)
            if (resolved != null) return resolved
            if (service.optional && elector.shards(service.id).isEmpty()) return null
            if (attempt < ROUTE_ATTEMPTS - 1) delay(ROUTE_BACKOFF_MILLIS)
        }
        logger.warn("Could not route to '{}': no queen or shard available", service.id)
        return null
    }

    /** Null while contested, because picking a side is how two nodes act on the same answer. */
    private fun resolveQueen(service: ClusterService): String? =
        (elector.status(service.id) as? QueenStatus.Elected)?.nodeId

    private fun resolveShard(service: ClusterService, shard: Int): String? {
        val shards = elector.shards(service.id)
        if (shards.isEmpty()) return null
        return shards[shard.absoluteValue % shards.size]
    }

    override suspend fun tick() {
        if (services.isEmpty()) return
        try {
            publish()
            val decisions = elector.apply(read(), leaving)
            announce(decisions)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Colony tick failed; keeping the last known cluster view", e)
        }
    }

    override suspend fun shutdown() {
        leaving = true
        try {
            publish()
            announce(elector.apply(read(), leaving = true))
            store.hdel(key, nodeId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Could not resign cleanly; the heartbeat will expire instead", e)
        }
    }

    private suspend fun publish() {
        val advertisement = ColonyAdvertisement(
            nodeId = nodeId,
            heartbeat = System.currentTimeMillis(),
            leaving = leaving,
            claims = elector.claims(),
        )
        store.hset(key, nodeId, advertisement.encode(), hashTtl)
    }

    /** Every live advertisement in the pool; stale ones are dropped rather than trusted. */
    private suspend fun read(): List<ColonyAdvertisement> {
        val cutoff = System.currentTimeMillis() - heartbeatTimeout.toMillis()
        return store.hgetAll(key).values
            .mapNotNull(ColonyAdvertisement::decode)
            .filter { it.heartbeat >= cutoff }
    }

    private suspend fun announce(decisions: ColonyElector.Decisions) {
        for (id in decisions.promoted) {
            logger.info("Elected queen of '{}' for server '{}'", id, serverId)
            notify(id) { service, listener -> listener.onPromote(service) }
        }
        for (id in decisions.demoted) {
            logger.info("Resigned as queen of '{}'", id)
            notify(id) { service, listener -> listener.onDemote(service) }
        }
    }

    /**
     * A listener that throws must not stop the others being told, and must not leave the elector
     * disagreeing with reality. The mode has already changed by the time this runs, so the failure
     * is the feature's to handle, not a reason to un-elect.
     */
    private suspend fun notify(
        serviceId: String,
        call: suspend (ClusterService, ColonyListener) -> Unit,
    ) {
        val service = services[serviceId] ?: return
        val listener = listeners[serviceId] ?: return
        try {
            call(service, listener)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Colony listener for '{}' failed", serviceId, e)
        }
    }

    /** Comfortably longer than the timeout, so the hash outlives the entries it is holding. */
    private val hashTtl: Duration = heartbeatTimeout.multipliedBy(HASH_TTL_MULTIPLIER)

    private companion object {

        /**
         * How long an advertisement is trusted after its last heartbeat.
         *
         * Sized against the tick interval the platform drives, not against a wall-clock intuition:
         * it has to cover a missed tick plus the jitter of a busy server, or a healthy node gets
         * declared dead and its crown handed to somebody else for one cycle.
         */
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(15)

        const val HASH_TTL_MULTIPLIER = 4L

        /** Enough to ride out an election settling; short enough not to hang a command. */
        const val ROUTE_ATTEMPTS = 4
        const val ROUTE_BACKOFF_MILLIS = 250L
    }
}
