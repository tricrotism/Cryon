package com.tricrotism.cryon.velocity.config

import com.tricrotism.cryon.common.config.ConfigKeys

/**
 * The config keys only the proxy reads. Anything Paper or Geyser also reads belongs in `CoreKeys`.
 *
 * `motd.width` is the proxy's alone: Geyser renders the same segments as two plain strings and
 * measures nothing, so it has no width to honour.
 */
object VelocityKeys {

    // how long a handoff flush may take before the transfer proceeds anyway
    val HANDOFF_TIMEOUT_SECONDS = ConfigKeys.long("network.handoff-timeout-seconds", 5L, 1L..120L)

    // negative leaves the ping protocol alone, so this is not bounded to a real protocol range
    val MAINTENANCE_PING_PROTOCOL = ConfigKeys.int("maintenance.ping-protocol", -1)

    // server pools a player needs cryon.server.<serverId> to enter
    val RESTRICTED_SERVERS = ConfigKeys.strings("network.restricted-servers")

    // visible width of the server-list MOTD in default-font pixels
    val MOTD_WIDTH = ConfigKeys.int("motd.width", 256, 1..4096)
}
