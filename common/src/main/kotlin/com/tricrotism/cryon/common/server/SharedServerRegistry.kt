package com.tricrotism.cryon.common.server

import com.tricrotism.cryon.common.data.Database
import com.tricrotism.cryon.common.data.Migration
import com.tricrotism.cryon.common.data.migrate
import com.tricrotism.cryon.common.net.KeyValueStore
import com.tricrotism.cryon.common.net.Messenger
import com.tricrotism.cryon.common.net.MessengerSubscription
import org.slf4j.Logger
import java.time.Duration
import java.util.*
import java.util.concurrent.*

/**
 * The one [ServerRegistry] implementation, over whatever [KeyValueStore] + [Messenger] transport the
 * deployment provides and, optionally, SQL for the durable server catalog. Mirrors FeatureFlags'
 * "broadcast the value, apply idempotently" pattern, but liveness is TTL-based: each owned instance is
 * written to a key with an expiry ([ttl] = heartbeat × 3), so a crashed pod's entry simply lapses.
 * Every process keeps a [replica]; a graceful stop broadcasts an explicit removal, while a crash is
 * detected by the local [reap]er dropping entries older than [ttl].
 *
 * On a shared (Redis) transport this is the whole network's directory. On the in-process transport it
 * degenerates to a directory whose only member is this server, which is not a special case but the
 * literal truth of a single-server deployment, so callers need no second code path: `bestNode` of
 * your own serverId simply answers "you".
 */
class SharedServerRegistry(
    private val store: KeyValueStore,
    private val messenger: Messenger,
    private val database: Database?,
    private val ttl: Duration,
    private val logger: Logger,
) : ServerRegistry {

    // The whole-network view, synced over pub/sub. lastHeartbeat is re-stamped to local time on store.
    private val replica = ConcurrentHashMap<String, Node>()

    // What this process registered. Authoritative for rebuilding its own heartbeats.
    private val owned = ConcurrentHashMap<String, Node>()

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

    override fun register(instance: Node): CompletableFuture<Void> {
        val stamped = instance.copy(lastHeartbeat = System.currentTimeMillis())
        owned[stamped.nodeId] = stamped
        upsertServer(stamped)
        return writeAndBroadcast(stamped, ADD)
    }

    override fun heartbeat(nodeId: String, playerCount: Int, state: NodeState): CompletableFuture<Void> {
        val base = owned[nodeId] ?: return CompletableFuture.completedFuture(null)
        val updated = base.copy(playerCount = playerCount, state = state, lastHeartbeat = System.currentTimeMillis())
        owned[nodeId] = updated
        return writeAndBroadcast(updated, UPD)
    }

    override fun deregister(nodeId: String): CompletableFuture<Void> {
        val serverId = owned.remove(nodeId)?.serverId ?: replica[nodeId]?.serverId ?: ""
        return store.delete(nodeKey(nodeId))
            .thenCompose { messenger.publish(EVENTS_CHANNEL, "$DEL$ENVELOPE$nodeId$ENVELOPE$serverId") }
    }

    override fun node(nodeId: String): Node? = replica[nodeId]

    override fun nodes(): Collection<Node> = replica.values.toList()

    override fun nodesOf(serverId: String): List<Node> = replica.values.filter { it.serverId == serverId }

    override fun bestNode(serverId: String): Node? =
        replica.values.asSequence()
            .filter { it.serverId == serverId && it.state == NodeState.READY && it.playerCount < it.maxPlayers }
            .minWithOrNull(compareBy({ it.playerCount }, { it.nodeId }))

    override fun tryReserve(nodeId: String, player: UUID): CompletableFuture<Boolean> {
        val instance = replica[nodeId] ?: return CompletableFuture.completedFuture(false)
        if (instance.maxPlayers <= 0) return CompletableFuture.completedFuture(true)
        // The replica's playerCount is up to one heartbeat stale, so the hold only ever makes the
        // in-flight reservations atomic against each other, never the count they are added to.
        return store.tryHold(
            key = RESERVED_PREFIX + nodeId,
            member = player.toString(),
            ttl = ttl,
            limit = instance.maxPlayers,
            baseline = instance.playerCount,
        ).exceptionally { false }
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

    private fun writeAndBroadcast(instance: Node, type: String): CompletableFuture<Void> {
        val line = NodeCodec.encode(instance)
        return store.set(nodeKey(instance.nodeId), line, ttl)
            .thenCompose { messenger.publish(EVENTS_CHANNEL, "$type$ENVELOPE$line") }
    }

    /** Apply a broadcast from any node (including our own echo, idempotent). */
    private fun onEvent(message: String) {
        val split = message.indexOf(ENVELOPE)
        if (split < 0) return
        when (message.substring(0, split)) {
            ADD, UPD -> {
                val instance = NodeCodec.decode(message.substring(split + 1)) ?: return
                val stamped = instance.copy(lastHeartbeat = System.currentTimeMillis())
                val existed = replica.put(stamped.nodeId, stamped) != null
                fire(if (existed) ServerRegistryEvent.Updated(stamped) else ServerRegistryEvent.Added(stamped))
            }

            DEL -> {
                val parts = message.substring(split + 1).split(ENVELOPE)
                val nodeId = parts.getOrNull(0) ?: return
                val serverId = parts.getOrNull(1) ?: replica[nodeId]?.serverId ?: ""
                if (replica.remove(nodeId) != null) fire(ServerRegistryEvent.Removed(nodeId, serverId))
            }
        }
    }

    /** Drop replica entries we have not heard from within [ttl]. How every node detects a crash. */
    private fun reap() {
        val cutoff = System.currentTimeMillis() - ttl.toMillis()
        val iterator = replica.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.lastHeartbeat < cutoff) {
                iterator.remove()
                fire(ServerRegistryEvent.Removed(entry.key, entry.value.serverId))
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
                values.filterNotNull().mapNotNull(NodeCodec::decode).forEach { instance ->
                    replica[instance.nodeId] = instance.copy(lastHeartbeat = System.currentTimeMillis())
                }
                logger.info("Server registry warmed up with {} live node(s)", replica.size)
            }
            .exceptionally { logger.error("Failed to warm up the server registry", it); null }
    }

    /**
     * The durable catalog of servers, through the migrator.
     *
     * Two steps rather than one `CREATE TABLE IF NOT EXISTS`, because this table predates the
     * server/node rename: it was `cryon_server_family` with a `family` column, and `IF NOT EXISTS`
     * would leave an existing one untouched while the code above it wrote the new column names. The
     * second step carries the old rows over, including any policy or shard counts an operator edited
     * by hand, and then drops the old table so the next boot skips straight past both.
     */
    private fun createCatalog() {
        val db = database ?: return
        db.migrate(
            "cryon-core-servers",
            listOf(
                Migration(1, "create the server catalog") { session ->
                    session.update(
                        """
                        CREATE TABLE IF NOT EXISTS $SERVER_TABLE (
                            server_id     VARCHAR(64) PRIMARY KEY,
                            policy        VARCHAR(16) NOT NULL DEFAULT 'persistent',
                            max_players   INT NOT NULL DEFAULT 0,
                            min_shards    INT NOT NULL DEFAULT 1,
                            target_shards INT NOT NULL DEFAULT 1
                        )
                        """.trimIndent()
                    )
                },
                Migration(2, "adopt the pre-rename catalog") { session ->
                    val legacy = session.query(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE UPPER(table_name) = ?",
                        LEGACY_TABLE.uppercase(),
                    ) { it.getInt(1) }.firstOrNull() ?: 0
                    if (legacy == 0) return@Migration
                    session.update(
                        "INSERT INTO $SERVER_TABLE (server_id, policy, max_players, min_shards, target_shards) " +
                                "SELECT family, policy, max_players, min_shards, target_shards FROM $LEGACY_TABLE"
                    )
                    session.update("DROP TABLE $LEGACY_TABLE")
                },
            ),
            logger,
        )
    }

    private fun upsertServer(instance: Node) {
        val db = database ?: return
        db.insertIfAbsent(SERVER_TABLE, SERVER_KEYS, SERVER_COLUMNS, instance.serverId, instance.maxPlayers)
            .exceptionally { 0 }
    }

    private fun fire(event: ServerRegistryEvent) {
        listeners.forEach { runCatching { it(event) }.onFailure { e -> logger.error("Registry listener failed", e) } }
    }

    private fun nodeKey(nodeId: String): String = INSTANCE_PREFIX + nodeId

    private companion object {
        private const val EVENTS_CHANNEL = "cryon:registry:events"
        private const val SERVER_TABLE = "cryon_servers"
        private const val LEGACY_TABLE = "cryon_server_family"
        private val SERVER_KEYS = listOf("server_id")
        private val SERVER_COLUMNS = listOf("server_id", "max_players")
        private const val INSTANCE_PREFIX = "cryon:registry:instance:"
        private const val RESERVED_PREFIX = "cryon:registry:reserved:"
        private const val ADD = "ADD"
        private const val UPD = "UPD"
        private const val DEL = "DEL"
        private val ENVELOPE = Char(3)
    }
}
