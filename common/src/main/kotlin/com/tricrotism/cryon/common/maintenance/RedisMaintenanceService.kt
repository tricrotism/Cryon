package com.tricrotism.cryon.common.maintenance

import com.tricrotism.cryon.common.data.Database
import com.tricrotism.cryon.common.net.Messenger
import com.tricrotism.cryon.common.net.MessengerSubscription
import org.slf4j.Logger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [MaintenanceService] over Redis pub/sub, with SQL as the durable single-row source of truth (a
 * proxy restart re-reads the last state). Same shape as `FeatureFlags`: write-through then broadcast,
 * own echo applied idempotently. Requires a [Messenger]; the [Database] is optional (without it, state
 * is per-proxy and resets on restart).
 */
class RedisMaintenanceService(
    private val database: Database?,
    private val messenger: Messenger,
    defaultMessage: String,
    private val logger: Logger,
) : MaintenanceService {

    @Volatile
    private var enabled = false

    @Volatile
    private var message = defaultMessage

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var subscription: MessengerSubscription? = null

    fun init() {
        database?.update(
            "CREATE TABLE IF NOT EXISTS $TABLE (id INT PRIMARY KEY, enabled BOOLEAN NOT NULL, message TEXT NOT NULL)"
        )?.thenCompose { load() }
            ?.exceptionally { logger.error("Failed to initialize the maintenance table", it); null }
        subscription = messenger.subscribe(CHANNEL, ::onSync)
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

    override fun onChange(listener: () -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    override fun close() {
        subscription?.unsubscribe()
        subscription = null
        listeners.clear()
    }

    private fun load(): CompletableFuture<Void> =
        database!!.query("SELECT enabled, message FROM $TABLE WHERE id = 1") { it.getBoolean(1) to it.getString(2) }
            .thenAccept { rows -> rows.firstOrNull()?.let { (e, m) -> enabled = e; message = m } }

    private fun onSync(raw: String) {
        val parts = raw.split(SEP, limit = 2)
        if (parts.size != 2) return
        enabled = parts[0].toBoolean()
        message = parts[1]
        listeners.forEach { runCatching { it() } }
    }

    private companion object {
        private const val TABLE = "cryon_maintenance"
        private const val CHANNEL = "cryon:maintenance:sync"
        private val SEP = Char(0)
    }
}
