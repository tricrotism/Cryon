package com.tricrotism.cryon.common.locale

import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [LocaleStore] used when no database + redis is configured. Overrides live for the server's
 * uptime and **reset on restart** — there's no persistence and no cross-server sync. Installed by the
 * core as the fallback so `/language` works on any setup; resolution still falls back to the client
 * locale for anyone who hasn't set an override.
 */
class MemoryLocaleStore : LocaleStore {

    private val overrides = ConcurrentHashMap<UUID, Locale>()

    override fun cached(uuid: UUID): Locale? = overrides[uuid]

    override fun set(uuid: UUID, locale: Locale): CompletableFuture<Void> {
        overrides[uuid] = locale
        return CompletableFuture.completedFuture(null)
    }

    override fun clear(uuid: UUID): CompletableFuture<Void> {
        overrides.remove(uuid)
        return CompletableFuture.completedFuture(null)
    }
}
