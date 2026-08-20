package com.tricrotism.cryon.common.server

import com.tricrotism.cryon.common.net.KeyValueStore
import org.slf4j.Logger
import java.time.Duration

/** What kind of process an entry in the presence hash describes. */
enum class PresenceKind {
    PROXY,
    GEYSER,
}

/**
 * One process announcing that it is up, and what it is.
 *
 * [detail] is whatever that kind of process is worth knowing beyond its name: a listen address, a
 * player count, a version. It is free text because nothing branches on it; it exists to be read by a
 * human in `/cryon network`.
 */
data class PresenceEntry(
    val kind: PresenceKind,
    val id: String,
    val detail: String,
    val heartbeat: Long,
) {
    fun encode(): String = "$heartbeat$SEP${kind.name}$SEP$id$SEP$detail"

    companion object {
        private val SEP = Char(1)

        fun decode(raw: String): PresenceEntry? {
            val parts = raw.split(SEP, limit = 4)
            if (parts.size != 4) return null
            val heartbeat = parts[0].toLongOrNull() ?: return null
            val kind = runCatching { PresenceKind.valueOf(parts[1]) }.getOrNull() ?: return null
            return PresenceEntry(kind, parts[2], parts[3], heartbeat)
        }
    }
}

/**
 * Who else is on the network that the server registry cannot see.
 *
 * [ServerRegistry] holds the things a player can be routed *to*, and deliberately nothing else:
 * proxies never register themselves, and Geyser registers no node, because either one appearing as a
 * routing candidate would be a bug rather than a feature. That leaves an operator with no way to ask
 * "is the proxy up, is Geyser hooked up", which is what this answers. Keeping it apart from the
 * registry is the point, not an oversight: nothing here can ever be returned by `bestNode`.
 *
 * Shaped like [com.tricrotism.cryon.common.colony.SharedColony], for the same reasons. One hash
 * rather than a key per process, because a key-per-process scheme is read with `keys("prefix*")`,
 * which walks the whole keyspace. `hset` expires the *hash* and not the field, so each entry carries
 * its own heartbeat and stale ones are dropped on read.
 *
 * Nothing withdraws on shutdown: a process that stops announcing ages out of the hash within the
 * timeout, which is the same way the registry treats a node whose pod was killed. A departure an
 * operator sees a few seconds late is worth more than a blocking call on every shutdown path.
 *
 * Without Redis this is a per-process map that only ever contains that process, which is the truth of
 * a single-JVM deployment rather than a degraded mode.
 */
class Presence(
    private val store: KeyValueStore,
    private val logger: Logger,
    private val timeout: Duration = DEFAULT_TIMEOUT,
) {

    /** Publish this process, replacing its previous entry. Call on the heartbeat interval. */
    suspend fun announce(kind: PresenceKind, id: String, detail: String) {
        val entry = PresenceEntry(kind, id, detail, System.currentTimeMillis())
        try {
            store.hset(KEY, "${kind.name}$FIELD_SEP$id", entry.encode(), timeout.multipliedBy(HASH_TTL_MULTIPLIER))
        } catch (e: Exception) {
            logger.warn("Could not announce {} presence for {}", kind.name.lowercase(), id, e)
        }
    }

    /** Every process announced within the timeout, newest heartbeat first. */
    suspend fun all(): List<PresenceEntry> {
        val cutoff = System.currentTimeMillis() - timeout.toMillis()
        return try {
            store.hgetAll(KEY).values
                .mapNotNull(PresenceEntry::decode)
                .filter { it.heartbeat >= cutoff }
                .sortedByDescending { it.heartbeat }
        } catch (e: Exception) {
            logger.warn("Could not read the presence hash", e)
            emptyList()
        }
    }

    companion object {
        private const val KEY = "cryon:presence"
        private const val FIELD_SEP = ':'

        /** How long an entry is trusted after its last heartbeat. */
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(30)

        /** The hash outlives an entry by enough that a brief outage does not drop everyone at once. */
        const val HASH_TTL_MULTIPLIER = 4L
    }
}
