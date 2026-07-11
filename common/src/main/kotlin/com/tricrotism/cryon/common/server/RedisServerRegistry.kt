package com.tricrotism.cryon.common.server

import com.tricrotism.cryon.common.data.Database
import com.tricrotism.cryon.common.net.Messenger
import com.tricrotism.cryon.common.net.MessengerSubscription
import com.tricrotism.cryon.common.net.RedisStore
import org.slf4j.Logger
import java.time.Duration
import java.util.*
import java.util.concurrent.*

/**
 * [ServerRegistry] backed by Redis for live state and, optionally, SQL for the durable family
 * catalog. Mirrors FeatureFlags' "broadcast the value, apply idempotently" pattern, but liveness is
 * TTL-based: each owned instance is written to a Redis key with an expiry ([ttl] = heartbeat × 3), so
 * a crashed pod's key simply expires. Every process keeps a [replica]; a graceful stop broadcasts an
 * explicit removal, while a crash is detected by the local [reap]er dropping entries older than [ttl].
 */
class RedisServerRegistry(
    private val store: RedisStore,
    private val messenger: Messenger,
    private val database: Database?,
    private val ttl: Duration,
    private val logger: Logger,
) : ServerRegistry {

    // The whole-network view, synced over pub/sub. lastHeartbeat is re-stamped to local time on store.
    private val replica = ConcurrentHashMap<String, ServerInstance>()

    // What this process registered — authoritative for rebuilding its own heartbeats.
    private val owned = ConcurrentHashMap<String, ServerInstance>()

    private val listeners = CopyOnWriteArrayList<(ServerRegistryEvent) -> Unit>()
    private var subscription: MessengerSubscription? = null
    private val reaper = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "cryon-registry-reaper").apply { isDaemon = true }
    }

    fun init() {
        createCatalog()
        warmUp()
        subscription = messenger.subscribe(EVENTS_CHANNEL, ::onEvent)
        val period = (ttl.toMillis() / 2).coerceAtLeast(1000)
        reaper.scheduleAtFixedRate(::reap, period, period, TimeUnit.MILLISECONDS)
    }

    override fun register(instance: ServerInstance): CompletableFuture<Void> {
        val stamped = instance.copy(lastHeartbeat = System.currentTimeMillis())
        owned[stamped.instanceId] = stamped
        upsertFamily(stamped)
        return writeAndBroadcast(stamped, ADD)
    }

    override fun heartbeat(instanceId: String, playerCount: Int, state: InstanceState): CompletableFuture<Void> {
        val base = owned[instanceId] ?: return CompletableFuture.completedFuture(null)
        val updated = base.copy(playerCount = playerCount, state = state, lastHeartbeat = System.currentTimeMillis())
        owned[instanceId] = updated
        return writeAndBroadcast(updated, UPD)
    }

    override fun deregister(instanceId: String): CompletableFuture<Void> {
        val family = owned.remove(instanceId)?.family ?: replica[instanceId]?.family ?: ""
        return store.delete(instanceKey(instanceId))
            .thenCompose { messenger.publish(EVENTS_CHANNEL, "$DEL$ENVELOPE$instanceId$ENVELOPE$family") }
    }

    override fun instance(instanceId: String): ServerInstance? = replica[instanceId]

    override fun instances(): Collection<ServerInstance> = replica.values.toList()

    override fun family(family: String): List<ServerInstance> = replica.values.filter { it.family == family }

    override fun bestInstance(family: String): ServerInstance? =
        replica.values.asSequence()
            .filter { it.family == family && it.state == InstanceState.READY && it.playerCount < it.maxPlayers }
            .minWithOrNull(compareBy({ it.playerCount }, { it.instanceId }))

    override fun tryReserve(instanceId: String, player: UUID): CompletableFuture<Boolean> {
        val instance = replica[instanceId] ?: return CompletableFuture.completedFuture(false)
        if (instance.maxPlayers <= 0) return CompletableFuture.completedFuture(true)
        val now = System.currentTimeMillis()
        val holdMillis = ttl.toMillis()
        return store.evalInt(
            RESERVE_SCRIPT,
            listOf(RESERVED_PREFIX + instanceId),
            listOf(
                now.toString(),
                (now + holdMillis).toString(),
                instance.maxPlayers.toString(),
                instance.playerCount.toString(),
                player.toString(),
                holdMillis.toString(),
            ),
        ).thenApply { it == 1L }.exceptionally { false }
    }

    override fun onChange(listener: (ServerRegistryEvent) -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    override fun close() {
        reaper.shutdownNow()
        subscription?.unsubscribe()
        subscription = null
        listeners.clear()
    }

    private fun writeAndBroadcast(instance: ServerInstance, type: String): CompletableFuture<Void> {
        val line = ServerInstanceCodec.encode(instance)
        return store.set(instanceKey(instance.instanceId), line, ttl)
            .thenCompose { messenger.publish(EVENTS_CHANNEL, "$type$ENVELOPE$line") }
    }

    /** Apply a broadcast from any node (including our own echo — idempotent). */
    private fun onEvent(message: String) {
        val split = message.indexOf(ENVELOPE)
        if (split < 0) return
        when (message.substring(0, split)) {
            ADD, UPD -> {
                val instance = ServerInstanceCodec.decode(message.substring(split + 1)) ?: return
                val stamped = instance.copy(lastHeartbeat = System.currentTimeMillis())
                val existed = replica.put(stamped.instanceId, stamped) != null
                fire(if (existed) ServerRegistryEvent.Updated(stamped) else ServerRegistryEvent.Added(stamped))
            }

            DEL -> {
                val parts = message.substring(split + 1).split(ENVELOPE)
                val instanceId = parts.getOrNull(0) ?: return
                val family = parts.getOrNull(1) ?: replica[instanceId]?.family ?: ""
                if (replica.remove(instanceId) != null) fire(ServerRegistryEvent.Removed(instanceId, family))
            }
        }
    }

    /** Drop replica entries we have not heard from within [ttl] — how every node detects a crash. */
    private fun reap() {
        val cutoff = System.currentTimeMillis() - ttl.toMillis()
        val iterator = replica.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.lastHeartbeat < cutoff) {
                iterator.remove()
                fire(ServerRegistryEvent.Removed(entry.key, entry.value.family))
                logger.info("Registry reaped stale instance {}", entry.key)
            }
        }
    }

    private fun warmUp() {
        store.keys("$INSTANCE_PREFIX*")
            .thenCompose { keys ->
                if (keys.isEmpty()) CompletableFuture.completedFuture(emptyList<String?>()) else store.mget(keys)
            }
            .thenAccept { values ->
                values.filterNotNull().mapNotNull(ServerInstanceCodec::decode).forEach { instance ->
                    replica[instance.instanceId] = instance.copy(lastHeartbeat = System.currentTimeMillis())
                }
                logger.info("Server registry warmed up with {} live instance(s)", replica.size)
            }
            .exceptionally { logger.error("Failed to warm up the server registry", it); null }
    }

    private fun createCatalog() {
        val db = database ?: return
        db.update(
            """
            CREATE TABLE IF NOT EXISTS cryon_server_family (
                family        VARCHAR(64) PRIMARY KEY,
                policy        VARCHAR(16) NOT NULL DEFAULT 'persistent',
                max_players   INT NOT NULL DEFAULT 0,
                min_shards    INT NOT NULL DEFAULT 1,
                target_shards INT NOT NULL DEFAULT 1
            )
            """.trimIndent()
        ).exceptionally { logger.error("Failed to create the server-family catalog", it); 0 }
    }

    private fun upsertFamily(instance: ServerInstance) {
        val db = database ?: return
        db.update(
            "INSERT INTO cryon_server_family (family, max_players) VALUES (?, ?) ON CONFLICT (family) DO NOTHING",
            instance.family, instance.maxPlayers,
        ).exceptionally { 0 }
    }

    private fun fire(event: ServerRegistryEvent) {
        listeners.forEach { runCatching { it(event) }.onFailure { e -> logger.error("Registry listener failed", e) } }
    }

    private fun instanceKey(instanceId: String): String = INSTANCE_PREFIX + instanceId

    private companion object {
        private const val EVENTS_CHANNEL = "cryon:registry:events"
        private const val INSTANCE_PREFIX = "cryon:registry:instance:"
        private const val RESERVED_PREFIX = "cryon:registry:reserved:"
        private const val ADD = "ADD"
        private const val UPD = "UPD"
        private const val DEL = "DEL"
        private val ENVELOPE = Char(3)

        // Atomic capacity reservation over a per-instance sorted set of {player -> expiry}. Prunes
        // expired holds, rejects if current players + live holds would meet capacity, else records the
        // hold (score = expiry) and returns 1. KEYS[1]=reserved set; ARGV=now, expiry, capacity,
        // players, player, ttlMillis.
        private val RESERVE_SCRIPT = """
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1])
            local held = redis.call('ZCARD', KEYS[1])
            if (tonumber(ARGV[4]) + held) >= tonumber(ARGV[3]) then return 0 end
            redis.call('ZADD', KEYS[1], ARGV[2], ARGV[5])
            redis.call('PEXPIRE', KEYS[1], ARGV[6])
            return 1
        """.trimIndent()
    }
}
