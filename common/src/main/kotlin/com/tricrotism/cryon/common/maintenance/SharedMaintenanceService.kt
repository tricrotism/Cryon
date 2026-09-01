package com.tricrotism.cryon.common.maintenance

import com.tricrotism.cryon.common.concurrent.CryonIO
import com.tricrotism.cryon.common.data.Database
import com.tricrotism.cryon.common.net.Messenger
import com.tricrotism.cryon.common.net.MessengerSubscription
import kotlinx.coroutines.*
import org.slf4j.Logger
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

/**
 * The one [MaintenanceService] implementation: state rides the [Messenger], with SQL as the durable
 * single-row source of truth (a proxy restart re-reads the last state). Same shape as `FeatureFlags`:
 * write-through then broadcast, own echo applied idempotently. The [Database] is optional (without it,
 * state is per-proxy and resets on restart).
 *
 * Enforced wherever logins arrive: the proxy, and Geyser ahead of it. A single-server deployment
 * still has exactly one proxy, so its in-process state is already network-wide truth, and nothing on
 * Paper reads this service.
 *
 * **With a database, state is re-pulled on an interval as well as at boot.** A broadcast only reaches
 * a process that was listening, and [init] gives up if SQL is unreachable, which is an ordinary
 * startup race rather than an exotic failure: a process that came up before the database would
 * otherwise hold its default (maintenance off) for its whole life while the network was closed. The
 * re-pull is what makes SQL the source of truth in practice and not just on paper.
 */
class SharedMaintenanceService(
    private val database: Database?,
    private val messenger: Messenger,
    defaultMessage: String,
    private val logger: Logger,
    private val refreshInterval: Duration = DEFAULT_REFRESH,
) : MaintenanceService {

    @Volatile
    private var enabled = false

    @Volatile
    private var message = defaultMessage

    // Lowercased names allowed to bypass maintenance.
    //
    // A concurrent set mutated by delta, not a snapshot swapped wholesale, because both ends of that
    // were lossy: `allowed = allowed + name` is a read-modify-write, and broadcasting the whole set
    // made every proxy adopt the sender's view of it. Two admins adding a name at once dropped one of
    // them from every proxy's memory while its row sat in the database, and nothing repaired that
    // until a restart. Per-name adds and removes are idempotent instead, so our own echo is a no-op
    // and a concurrent change on another proxy composes with ours. Same shape as `FeatureFlags`
    private val allowed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Owns the persistence behind every mutation. The in-memory update stays synchronous and the SQL
    // write plus broadcast are launched behind it, which is exactly what the futures did before.
    // See `FeatureFlags`, which this deliberately mirrors
    private val scope = CoroutineScope(
        SupervisorJob() + CryonIO.dispatcher + CoroutineExceptionHandler { _, error ->
            logger.error("Unhandled failure in a coroutine of the maintenance service", error)
        }
    )

    private fun persist(what: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to persist maintenance change ({})", what, e)
            }
        }
    }

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var subscription: MessengerSubscription? = null
    private var allowSubscription: MessengerSubscription? = null

    fun init() {
        val db = database
        if (db != null) {
            persist("init state") {
                db.update(
                    "CREATE TABLE IF NOT EXISTS $TABLE (id INT PRIMARY KEY, enabled BOOLEAN NOT NULL, message TEXT NOT NULL)"
                )
                load()
            }
            persist("init allowlist") {
                db.update("CREATE TABLE IF NOT EXISTS $ALLOW_TABLE (name VARCHAR(64) PRIMARY KEY)")
                loadAllow()
            }
        }
        subscription = messenger.subscribe(CHANNEL, ::onSync)
        allowSubscription = messenger.subscribe(ALLOW_CHANNEL, ::onAllowSync)
        if (db != null && refreshInterval.isPositive()) startRefresh()
    }

    /**
     * Re-read the durable state on an interval, forever, until the scope is cancelled.
     *
     * Delays first, so the loop never races [init]'s own load, and swallows a failed pass rather than
     * ending the loop: the database being briefly unreachable is the case this exists to survive.
     */
    private fun startRefresh() {
        scope.launch {
            while (isActive) {
                delay(refreshInterval.toMillis().milliseconds)
                try {
                    refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Could not re-pull maintenance state; keeping the state we have", e)
                }
            }
        }
    }

    /**
     * Pull state and allowlist from SQL and adopt them.
     *
     * Removals are applied only to names that were already present *before* the read, so a name added
     * on this process while the query was in flight is never wiped by its result. A name genuinely
     * removed elsewhere is in that snapshot and absent from the read, so it goes.
     */
    private suspend fun refresh() {
        val known = allowed.toSet()
        val row = database!!.query("SELECT enabled, message FROM $TABLE WHERE id = $STATE_ROW") {
            it.getBoolean(1) to it.getString(2)
        }.firstOrNull()
        val fresh = database.query("SELECT name FROM $ALLOW_TABLE") { it.getString(1).lowercase() }.toSet()

        row?.let { (freshEnabled, freshMessage) ->
            if (freshEnabled != enabled || freshMessage != message) {
                enabled = freshEnabled
                message = freshMessage
                listeners.forEach { runCatching { it() } }
            }
        }
        allowed.addAll(fresh)
        allowed.removeAll(known - fresh)
    }

    override fun isEnabled(): Boolean = enabled

    override fun message(): String = message

    override suspend fun set(enabled: Boolean, message: String?) {
        this.enabled = enabled
        if (message != null) this.message = message
        val current = this.message
        database?.upsert(TABLE, STATE_KEYS, STATE_COLUMNS, STATE_ROW, enabled, current)
        messenger.publish(CHANNEL, "$enabled$SEP$current")
    }

    override fun allowlist(): Set<String> = allowed.toSet()

    override fun isAllowed(name: String): Boolean = name.lowercase() in allowed

    override fun allow(name: String): Boolean {
        val key = name.lowercase()
        if (!allowed.add(key)) return false
        persist("allow $key") {
            database?.insertIfAbsent(ALLOW_TABLE, ALLOW_COLUMNS, ALLOW_COLUMNS, key)
            messenger.publish(ALLOW_CHANNEL, ALLOW_ADD + key)
        }
        return true
    }

    override fun disallow(name: String): Boolean {
        val key = name.lowercase()
        if (!allowed.remove(key)) return false
        persist("disallow $key") {
            database?.update("DELETE FROM $ALLOW_TABLE WHERE name = ?", key)
            messenger.publish(ALLOW_CHANNEL, ALLOW_REMOVE + key)
        }
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
        scope.cancel("The maintenance service was closed")
    }

    private suspend fun load() {
        val rows = database!!.query("SELECT enabled, message FROM $TABLE WHERE id = 1") {
            it.getBoolean(1) to it.getString(2)
        }
        rows.firstOrNull()?.let { (e, m) -> enabled = e; message = m }
    }

    private suspend fun loadAllow() {
        database!!.query("SELECT name FROM $ALLOW_TABLE") { it.getString(1) }
            .forEach { allowed.add(it.lowercase()) }
    }

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
        // How often the durable state is re-pulled. Overridable per deployment
        private val DEFAULT_REFRESH: Duration = Duration.ofSeconds(30)

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
