package com.tricrotism.cryon.common.locale

import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [LocaleStore] used when no database + redis is configured. Overrides live for the server's
 * uptime and **reset on restart**. There's no persistence and no cross-server sync. Installed by the
 * core as the fallback so `/language` works on any setup; resolution still falls back to the client
 * locale for anyone who hasn't set an override.
 */
class MemoryLocaleStore : LocaleStore {

    private val overrides = ConcurrentHashMap<UUID, Locale>()

    override fun cached(uuid: UUID): Locale? = overrides[uuid]

    override suspend fun set(uuid: UUID, locale: Locale) {
        overrides[uuid] = locale
    }

    override suspend fun clear(uuid: UUID) {
        overrides.remove(uuid)
    }
}
