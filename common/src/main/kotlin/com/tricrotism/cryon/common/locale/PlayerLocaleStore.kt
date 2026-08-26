package com.tricrotism.cryon.common.locale

import com.tricrotism.cryon.common.concurrent.CryonIO
import com.tricrotism.cryon.common.data.Database
import com.tricrotism.cryon.common.net.Messenger
import com.tricrotism.cryon.common.net.MessengerSubscription
import kotlinx.coroutines.*
import org.slf4j.Logger
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent, cross-server per-player language override. Backed by SQL (source of truth) with an
 * in-memory cache for synchronous reads at send time, kept fresh across servers via a Redis
 * invalidation broadcast. Resolution (`override ?: client locale`) is platform-side. See
 * `Player.resolvedLocale()`.
 *
 * Lifecycle: [init] once, [load] on join, [unload] on quit, [set]/[clear] to change.
 */
class PlayerLocaleStore(
    private val database: Database,
    private val messenger: Messenger,
    private val logger: Logger,
) : LocaleStore {
    // present-value = override; present-empty = no override; absent = this player isn't on this server.
    private val cache = ConcurrentHashMap<UUID, Optional<Locale>>()

    /**
     * Runs the re-reads an invalidation triggers. The subscription handler is not suspending — it
     * runs on the transport's ordered delivery thread — so the SQL read it needs has to be launched
     * rather than awaited, and this is what owns and cancels those.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + CryonIO.dispatcher + CoroutineExceptionHandler { _, error ->
            logger.error("Unhandled failure in the player locale store", error)
        }
    )

    private val subscription: MessengerSubscription = messenger.subscribe(CHANNEL, ::onInvalidate)

    /** Create the backing table. */
    suspend fun init() {
        database.update(
            "CREATE TABLE IF NOT EXISTS $TABLE (uuid VARCHAR(36) PRIMARY KEY, locale VARCHAR(35) NOT NULL)"
        )
    }

    /** Load [uuid]'s stored override into the cache. Call on join. */
    suspend fun load(uuid: UUID) {
        cache.putIfAbsent(uuid, Optional.empty())
        reread(uuid)
    }

    /**
     * Re-read a player already in the cache, without putting them back if they have left.
     *
     * [load] claims the slot first because a join means the player is here; an invalidation says
     * nothing about that, and their quit can land between the check and the read. Everything else
     * here writes through `computeIfPresent` for the same reason.
     */
    private suspend fun reread(uuid: UUID) {
        val rows = database.query("SELECT locale FROM $TABLE WHERE uuid = ?", uuid.toString()) { it.getString(1) }
        val stored = Optional.ofNullable(rows.firstOrNull()?.let(LangScanner::parseLocale))
        cache.computeIfPresent(uuid) { _, _ -> stored }
    }

    /** Evict [uuid] from the cache. Call on quit. */
    fun unload(uuid: UUID) {
        cache.remove(uuid)
    }

    /** The cached override for [uuid], or null (no override, or not loaded). Synchronous. */
    override fun cached(uuid: UUID): Locale? = cache[uuid]?.orElse(null)

    /** Set [uuid]'s override, persist it, and invalidate other servers. */
    override suspend fun set(uuid: UUID, locale: Locale) {
        database.upsert(TABLE, KEYS, COLUMNS, uuid.toString(), locale.toString())
        cache.computeIfPresent(uuid) { _, _ -> Optional.of(locale) }
        messenger.publish(CHANNEL, uuid.toString())
    }

    /** Clear [uuid]'s override. */
    override suspend fun clear(uuid: UUID) {
        database.update("DELETE FROM $TABLE WHERE uuid = ?", uuid.toString())
        cache.computeIfPresent(uuid) { _, _ -> Optional.empty() }
        messenger.publish(CHANNEL, uuid.toString())
    }

    override fun close() {
        subscription.unsubscribe()
        scope.cancel("The locale store was closed")
    }

    /**
     * Re-read a player another server just changed.
     *
     * A failed read is logged rather than left to a default handler, and it matters more than the
     * line suggests: the broadcast has already been consumed, so nothing tries again and this node
     * would go on serving the old override for the rest of its life with no sign of why.
     */
    private fun onInvalidate(message: String) {
        val uuid = runCatching { UUID.fromString(message) }.getOrNull() ?: return
        if (!cache.containsKey(uuid)) return
        scope.launch {
            try {
                reread(uuid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Failed to re-read the locale override for {}", uuid, e)
            }
        }
    }

    private companion object {
        private const val CHANNEL = "cryon:locale:invalidate"
        private const val TABLE = "cryon_player_locale"
        private val KEYS = listOf("uuid")
        private val COLUMNS = listOf("uuid", "locale")
    }
}
