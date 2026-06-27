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
 * invalidation broadcast. Resolution (`override ?: client locale`) is platform-side — see
 * `Player.resolvedLocale()`.
 *
 * Lifecycle: [init] once, [load] on join, [unload] on quit, [set]/[clear] to change.
 */
class PlayerLocaleStore(
    private val database: Database,
    private val messenger: Messenger,
) : LocaleStore {
    // present-value = override; present-empty = loaded, no override; absent = not loaded.
    private val cache = ConcurrentHashMap<UUID, Optional<Locale>>()
    private val subscription: MessengerSubscription = messenger.subscribe(CHANNEL, ::onInvalidate)

    /** Create the backing table. */
    fun init(): CompletableFuture<Void> =
        database.update(
            "CREATE TABLE IF NOT EXISTS cryon_player_locale (uuid VARCHAR(36) PRIMARY KEY, locale VARCHAR(35) NOT NULL)"
        ).thenAccept { }

    /** Load [uuid]'s stored override into the cache. Call on join. */
    fun load(uuid: UUID): CompletableFuture<Void> =
        database.query("SELECT locale FROM cryon_player_locale WHERE uuid = ?", uuid.toString()) { it.getString(1) }
            .thenAccept { rows -> cache[uuid] = Optional.ofNullable(rows.firstOrNull()?.let(LangScanner::parseLocale)) }

    /** Evict [uuid] from the cache. Call on quit. */
    fun unload(uuid: UUID) {
        cache.remove(uuid)
    }

    /** The cached override for [uuid], or null (no override, or not loaded). Synchronous. */
    override fun cached(uuid: UUID): Locale? = cache[uuid]?.orElse(null)

    /** Set [uuid]'s override, persist it, and invalidate other servers. */
    override fun set(uuid: UUID, locale: Locale): CompletableFuture<Void> =
        database.update(
            "INSERT INTO cryon_player_locale (uuid, locale) VALUES (?, ?) " +
                    "ON CONFLICT (uuid) DO UPDATE SET locale = EXCLUDED.locale",
            uuid.toString(), locale.toString(),
        ).thenCompose {
            cache[uuid] = Optional.of(locale)
            messenger.publish(CHANNEL, uuid.toString())
        }

    /** Clear [uuid]'s override. */
    override fun clear(uuid: UUID): CompletableFuture<Void> =
        database.update("DELETE FROM cryon_player_locale WHERE uuid = ?", uuid.toString())
            .thenCompose {
                cache[uuid] = Optional.empty()
                messenger.publish(CHANNEL, uuid.toString())
            }

    override fun close() {
        subscription.unsubscribe()
    }

    private fun onInvalidate(message: String) {
        val uuid = runCatching { UUID.fromString(message) }.getOrNull() ?: return
        if (cache.containsKey(uuid)) load(uuid) // only refresh players online on this server
    }

    private companion object {
        private const val CHANNEL = "cryon:locale:invalidate"
    }
}
