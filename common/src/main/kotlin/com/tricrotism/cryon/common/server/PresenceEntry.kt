package com.tricrotism.cryon.common.server

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
