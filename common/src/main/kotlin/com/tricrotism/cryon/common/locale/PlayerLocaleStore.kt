package com.tricrotism.cryon.common.locale

import com.tricrotism.cryon.common.data.Database
import com.tricrotism.cryon.common.net.Messenger
import com.tricrotism.cryon.common.net.MessengerSubscription
import java.util.*
import java.util.concurrent.CompletableFuture
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
) : LocaleStore {
    // present-value = override; present-empty = no override; absent = this player isn't on this server.
    private val cache = ConcurrentHashMap<UUID, Optional<Locale>>()
    private val subscription: MessengerSubscription = messenger.subscribe(CHANNEL, ::onInvalidate)

    /** Create the backing table. */
    fun init(): CompletableFuture<Void> =
        database.update(
            "CREATE TABLE IF NOT EXISTS $TABLE (uuid VARCHAR(36) PRIMARY KEY, locale VARCHAR(35) NOT NULL)"
        ).thenAccept { }

    /** Load [uuid]'s stored override into the cache. Call on join. */
    fun load(uuid: UUID): CompletableFuture<Void> {
        cache.putIfAbsent(uuid, Optional.empty())
        return reread(uuid)
    }

    /**
     * Re-read a player already in the cache, without putting them back if they have left.
     *
     * [load] claims the slot first because a join means the player is here; an invalidation says
     * nothing about that, and their quit can land between the check and the read. Everything else
     * here writes through `computeIfPresent` for the same reason.
     */
    private fun reread(uuid: UUID): CompletableFuture<Void> {
        return database
            .query("SELECT locale FROM $TABLE WHERE uuid = ?", uuid.toString()) { it.getString(1) }
            .thenAccept { rows ->
                val stored = Optional.ofNullable(rows.firstOrNull()?.let(LangScanner::parseLocale))
                cache.computeIfPresent(uuid) { _, _ -> stored }
            }
    }

    /** Evict [uuid] from the cache. Call on quit. */
    fun unload(uuid: UUID) {
        cache.remove(uuid)
    }

    /** The cached override for [uuid], or null (no override, or not loaded). Synchronous. */
    override fun cached(uuid: UUID): Locale? = cache[uuid]?.orElse(null)

    /** Set [uuid]'s override, persist it, and invalidate other servers. */
    override fun set(uuid: UUID, locale: Locale): CompletableFuture<Void> =
        database.upsert(TABLE, KEYS, COLUMNS, uuid.toString(), locale.toString())
            .thenCompose {
                cache.computeIfPresent(uuid) { _, _ -> Optional.of(locale) }
                messenger.publish(CHANNEL, uuid.toString())
            }

    /** Clear [uuid]'s override. */
    override fun clear(uuid: UUID): CompletableFuture<Void> =
        database.update("DELETE FROM $TABLE WHERE uuid = ?", uuid.toString())
            .thenCompose {
                cache.computeIfPresent(uuid) { _, _ -> Optional.empty() }
                messenger.publish(CHANNEL, uuid.toString())
            }

    override fun close() {
        subscription.unsubscribe()
    }

    private fun onInvalidate(message: String) {
        val uuid = runCatching { UUID.fromString(message) }.getOrNull() ?: return
        if (cache.containsKey(uuid)) reread(uuid)
    }

    private companion object {
        private const val CHANNEL = "cryon:locale:invalidate"
        private const val TABLE = "cryon_player_locale"
        private val KEYS = listOf("uuid")
        private val COLUMNS = listOf("uuid", "locale")
    }
}
