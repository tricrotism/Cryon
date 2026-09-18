package com.tricrotism.cryon.config

import com.tricrotism.cryon.common.config.ConfigSchema

/**
 * The config keys only the Paper core reads. Anything the proxy or Geyser also reads belongs in
 * `CoreKeys`.
 *
 * Declared through [ConfigSchema] rather than `ConfigKeys` so each key registers itself as it
 * initializes. That ordered list is what `ConfigDrift` checks the shipped `config.yml` against, so a
 * key added here and not written into the template fails a test instead of becoming an option no
 * operator can discover.
 *
 * The identity keys carry no default because their fallback is not a constant: `NodeIdentity` resolves
 * each from the environment, then from here, then from Paper's own server settings, and a default
 * here would shadow that last step.
 *
 * That leaves them with two environment spellings, and `NodeIdentity`'s wins. It checks its own short
 * names (`CRYON_SERVER`, `CRYON_NODE`, `CRYON_EXPECT`) before it is handed the config value, and the
 * long form a `ConfigKey` derives only reaches it through that value. Both work; set the short one.
 * They are not collapsed because the short names are what the Helm chart already uses.
 */
object PaperKeys : ConfigSchema() {

    val COMMANDS_MENU = boolean("commands.menu", true)

    val CURRENCY_ENABLED = boolean("currency.enabled", false)
    val CURRENCY_DRAIN_SECONDS = long("currency.drain-seconds", 30L, 5L..3600L)
    val CURRENCY_OFFLINE_SPENDING = boolean("currency.offline-spending", false)
    val CURRENCY_LEADERBOARD_REFRESH_SECONDS =
        long("currency.leaderboard-refresh-seconds", 300L, 30L..86400L)

    val REMOTE_ENABLED = boolean("remote.enabled", false)
    val REMOTE_POLL_SECONDS = long("remote.poll-seconds", 300L, 30L..86400L)

    val NETWORK_SERVER = string("network.server")
    val NETWORK_NODE = string("network.node")
    val NETWORK_ADDRESS = string("network.address")
    val NETWORK_PORT = int("network.port", 0, 0..65535)
    val NETWORK_MAX_PLAYERS = int("network.max-players", 0, 0..Int.MAX_VALUE)
    val NETWORK_EXPECT = string("network.expect")

    val LEGACY_NETWORK_FAMILY = hidden(string("network.family"))
    val LEGACY_NETWORK_INSTANCE_ID = hidden(string("network.instance-id"))
    val LEGACY_NETWORK_MODE = hidden(string("network.mode"))
    val LEGACY_SERVER_NAME = hidden(string("server-name"))

    val AGONES_SHUTDOWN_WHEN_EMPTY = boolean("network.agones.shutdown-when-empty", false)
    val AGONES_HEALTH_SECONDS = long("network.agones.health-seconds", 5L, 1L..3600L)
    val AGONES_EMPTY_GRACE_SECONDS = long("network.agones.empty-grace-seconds", 60L, 0L..86400L)
    val AGONES_MIN_INSTANCES = int("network.agones.min-instances", 1, 0..1024)
}
