package com.tricrotism.cryon.common.maintenance

import com.tricrotism.cryon.common.data.Database
import com.tricrotism.cryon.common.net.Messenger
import com.tricrotism.cryon.common.net.MessengerSubscription
import org.slf4j.Logger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The one [MaintenanceService] implementation: state rides the [Messenger], with SQL as the durable
 * single-row source of truth (a proxy restart re-reads the last state). Same shape as `FeatureFlags`:
 * write-through then broadcast, own echo applied idempotently. The [Database] is optional (without it,
 * state is per-proxy and resets on restart).
 *
 * Proxy-side only, on either transport. Maintenance is enforced where logins arrive, and a
 * single-server deployment still has exactly one proxy, so its in-process state is already
 * network-wide truth — nothing on Paper reads this service.
 */
class SharedMaintenanceService(
    private val database: Database?,
    private val messenger: Messenger,
    defaultMessage: String,
    private val logger: Logger,
) : MaintenanceService {

    @Volatile
    private var enabled = false

    @Volatile
    private var message = defaultMessage

    /**
     * Lowercased names allowed to bypass maintenance.
     *
     * A concurrent set mutated by delta, not a snapshot swapped wholesale, because both ends of that
     * were lossy: `allowed = allowed + name` is a read-modify-write, and broadcasting the whole set
     * made every proxy adopt the sender's view of it. Two admins adding a name at once dropped one of
     * them from every proxy's memory while its row sat in the database, and nothing repaired that
     * until a restart. Per-name adds and removes are idempotent instead, so our own echo is a no-op
     * and a concurrent change on another proxy composes with ours. Same shape as `FeatureFlags`.
     */
    private val allowed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var subscription: MessengerSubscription? = null
    private var allowSubscription: MessengerSubscription? = null

    fun init() {
        database?.update(
            "CREATE TABLE IF NOT EXISTS $TABLE (id INT PRIMARY KEY, enabled BOOLEAN NOT NULL, message TEXT NOT NULL)"
        )?.thenCompose { load() }
            ?.exceptionally { logger.error("Failed to initialize the maintenance table", it); null }
        database?.update("CREATE TABLE IF NOT EXISTS $ALLOW_TABLE (name VARCHAR(64) PRIMARY KEY)")
            ?.thenCompose { loadAllow() }
            ?.exceptionally { logger.error("Failed to initialize the maintenance allowlist table", it); null }
        subscription = messenger.subscribe(CHANNEL, ::onSync)
        allowSubscription = messenger.subscribe(ALLOW_CHANNEL, ::onAllowSync)
    }

    override fun isEnabled(): Boolean = enabled

    override fun message(): String = message

    override fun set(enabled: Boolean, message: String?): CompletableFuture<Void> {
        this.enabled = enabled
        if (message != null) this.message = message
        val current = this.message
        database?.upsert(TABLE, STATE_KEYS, STATE_COLUMNS, STATE_ROW, enabled, current)
        return messenger.publish(CHANNEL, "$enabled$SEP$current")
    }

    override fun allowlist(): Set<String> = allowed.toSet()

    override fun isAllowed(name: String): Boolean = name.lowercase() in allowed

    override fun allow(name: String): Boolean {
        val key = name.lowercase()
        if (!allowed.add(key)) return false
        database?.insertIfAbsent(ALLOW_TABLE, ALLOW_COLUMNS, ALLOW_COLUMNS, key)
        messenger.publish(ALLOW_CHANNEL, ALLOW_ADD + key)
        return true
    }

    override fun disallow(name: String): Boolean {
        val key = name.lowercase()
        if (!allowed.remove(key)) return false
        database?.update("DELETE FROM $ALLOW_TABLE WHERE name = ?", key)
        messenger.publish(ALLOW_CHANNEL, ALLOW_REMOVE + key)
        return true
    }

    override fun onChange(listener: () -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    override fun close() {
        subscription?.unsubscribe()
        subscription = null
        allowSubscription?.unsubscribe()
        allowSubscription = null
        listeners.clear()
    }

    private fun load(): CompletableFuture<Void> =
        database!!.query("SELECT enabled, message FROM $TABLE WHERE id = 1") { it.getBoolean(1) to it.getString(2) }
            .thenAccept { rows -> rows.firstOrNull()?.let { (e, m) -> enabled = e; message = m } }

    private fun loadAllow(): CompletableFuture<Void> =
        database!!.query("SELECT name FROM $ALLOW_TABLE") { it.getString(1) }
            .thenAccept { rows -> rows.forEach { allowed.add(it.lowercase()) } }

    private fun onSync(raw: String) {
        val parts = raw.split(SEP, limit = 2)
        if (parts.size != 2) return
        enabled = parts[0].toBoolean()
        message = parts[1]
        listeners.forEach { runCatching { it() } }
    }

    /**
     * Apply one name's change from any proxy, our own echo included. Both operations are idempotent.
     */
    private fun onAllowSync(raw: String) {
        if (raw.length < 2) return
        val name = raw.substring(1)
        when (raw[0]) {
            ALLOW_ADD -> allowed.add(name)
            ALLOW_REMOVE -> allowed.remove(name)
        }
    }

    private companion object {
        private const val TABLE = "cryon_maintenance"
        private const val ALLOW_TABLE = "cryon_maintenance_allow"

        private const val STATE_ROW = 1
        private val STATE_KEYS = listOf("id")
        private val STATE_COLUMNS = listOf("id", "enabled", "message")
        private val ALLOW_COLUMNS = listOf("name")
        private const val CHANNEL = "cryon:maintenance:sync"
        private const val ALLOW_CHANNEL = "cryon:maintenance:allow"
        private val SEP = Char(0)

        private const val ALLOW_ADD = '+'
        private const val ALLOW_REMOVE = '-'
    }
}
