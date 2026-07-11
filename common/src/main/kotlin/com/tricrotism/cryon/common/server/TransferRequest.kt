package com.tricrotism.cryon.common.server

import com.tricrotism.cryon.common.server.TransferRequest.CHANNEL
import java.util.*

/**
 * The wire format for a routing request broadcast on [CHANNEL]. Every proxy receives it; the one that
 * owns the player performs the connection and the rest ignore it. Shared so the sender ([RedisPlayerRouter])
 * and the Velocity-side listener agree on the encoding.
 */
object TransferRequest {

    const val CHANNEL = "cryon:routing:transfer"
    private val SEP = Char(0)

    fun encode(player: UUID, instanceId: String): String = "$player$SEP$instanceId"

    fun decode(message: String): Pair<UUID, String>? {
        val parts = message.split(SEP)
        if (parts.size != 2) return null
        val player = runCatching { UUID.fromString(parts[0]) }.getOrNull() ?: return null
        return player to parts[1]
    }
}
