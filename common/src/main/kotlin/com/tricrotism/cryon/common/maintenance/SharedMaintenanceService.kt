package com.tricrotism.cryon.common.maintenance

import com.tricrotism.cryon.common.data.Database
import com.tricrotism.cryon.common.net.Messenger
import com.tricrotism.cryon.common.net.MessengerSubscription
import org.slf4j.Logger
import java.util.concurrent.CompletableFuture
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

    /** Lowercased names allowed to bypass maintenance. Replaced wholesale on every change/sync. */
    @Volatile
    private var allowed: Set<String> = emptySet()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var subscription: MessengerSubscription? = null
    private var allowSubscription: MessengerSubscription? = null

    fun init() {
        database?.update(
            "CREATE TABLE IF NOT EXISTS $TABLE (id INT PRIMARY KEY, enabled BOOLEAN NOT NULL, message TEXT NOT NULL)"
        )?.thenCompose { load() }
            ?.exceptionally { logger.error("Failed to initialize the maintenance table", it); null }
        database?.update("CREATE TABLE IF NOT EXISTS $ALLOW_TABLE (name TEXT PRIMARY KEY)")
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
        database?.update(
            "INSERT INTO $TABLE (id, enabled, message) VALUES (1, ?, ?) " +
                    "ON CONFLICT (id) DO UPDATE SET enabled = EXCLUDED.enabled, message = EXCLUDED.message",
            enabled, current,
        )
        return messenger.publish(CHANNEL, "$enabled$SEP$current")
    }

    override fun allowlist(): Set<String> = allowed

    override fun isAllowed(name: String): Boolean = name.lowercase() in allowed

    override fun allow(name: String): Boolean {
        val key = name.lowercase()
        if (key in allowed) return false
        allowed = allowed + key
        database?.update("INSERT INTO $ALLOW_TABLE (name) VALUES (?) ON CONFLICT (name) DO NOTHING", key)
        messenger.publish(ALLOW_CHANNEL, allowed.joinToString(ALLOW_SEP))
        return true
    }

    override fun disallow(name: String): Boolean {
        val key = name.lowercase()
        if (key !in allowed) return false
        allowed = allowed - key
        database?.update("DELETE FROM $ALLOW_TABLE WHERE name = ?", key)
        messenger.publish(ALLOW_CHANNEL, allowed.joinToString(ALLOW_SEP))
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
            .thenAccept { rows -> allowed = rows.mapTo(HashSet()) { it.lowercase() } }

    private fun onSync(raw: String) {
        val parts = raw.split(SEP, limit = 2)
        if (parts.size != 2) return
        enabled = parts[0].toBoolean()
        message = parts[1]
        listeners.forEach { runCatching { it() } }
    }

    private fun onAllowSync(raw: String) {
        allowed = if (raw.isEmpty()) emptySet() else raw.split(ALLOW_SEP).toSet()
    }

    private companion object {
        private const val TABLE = "cryon_maintenance"
        private const val ALLOW_TABLE = "cryon_maintenance_allow"
        private const val CHANNEL = "cryon:maintenance:sync"
        private const val ALLOW_CHANNEL = "cryon:maintenance:allow"
        private val SEP = Char(0)
        private val ALLOW_SEP = Char(0).toString()
    }
}
