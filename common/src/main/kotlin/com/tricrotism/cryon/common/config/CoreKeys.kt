package com.tricrotism.cryon.common.config

/**
 * Every config key more than one platform reads, declared once.
 *
 * Paper, Velocity and Geyser answer the same questions from the same `config.yml` shape, so before
 * this each carried its own copy of every default and each `config.yml` resource carried a third.
 * A key belonging to exactly one platform is declared beside that platform's reader instead.
 *
 * The shipped templates still spell these values out. That duplication cannot drift silently: the
 * template is documentation an operator edits, and [ConfigMigrator] copies their edit over the
 * default rather than the other way round.
 *
 * Two keys carry no default and are read with `find`, because their fallback is not a constant:
 * [DATABASE_PORT] follows the chosen dialect, and [MODULES_AUTO_RELOAD] follows [PRODUCTION].
 */
object CoreKeys {

    val PRODUCTION = ConfigKeys.boolean("production", true)

    val DATABASE_ENABLED = ConfigKeys.boolean("database.enabled", false)
    val DATABASE_TYPE = ConfigKeys.nonBlankString("database.type", "postgresql")
    val DATABASE_HOST = ConfigKeys.nonBlankString("database.host", "localhost")

    val DATABASE_PORT = ConfigKeys.int("database.port", range = 1..65535)
    val DATABASE_NAME = ConfigKeys.nonBlankString("database.database", "cryon")
    val DATABASE_USERNAME = ConfigKeys.string("database.username", "cryon")
    val DATABASE_PASSWORD = ConfigKeys.string("database.password", "")
    val DATABASE_MAX_POOL_SIZE = ConfigKeys.int("database.max-pool-size", 10, 1..256)

    val REDIS_ENABLED = ConfigKeys.boolean("redis.enabled", false)
    val REDIS_URI = ConfigKeys.nonBlankString("redis.uri", "redis://localhost:6379/0")

    val REGISTRY_ENABLED = ConfigKeys.boolean("network.registry-enabled", true)
    val HEARTBEAT_SECONDS = ConfigKeys.long("network.heartbeat-seconds", 5L, 1L..3600L)

    val MAINTENANCE_MESSAGE =
        ConfigKeys.string("maintenance.default-message", "The network is under maintenance.")
    val MAINTENANCE_REFRESH_SECONDS = ConfigKeys.long("maintenance.refresh-seconds", 30L, 0L..86400L)
    val MODULES_AUTO_RELOAD = ConfigKeys.boolean("modules.auto-reload")

    // shared because the proxy and Geyser render the same block two ways; motd.width is the proxy's
    // alone, since only it measures anything
    val MOTD_ENABLED = ConfigKeys.boolean("motd.enabled", false)
    val MOTD_TOP_LEFT = ConfigKeys.string("motd.top.left", "")
    val MOTD_TOP_CENTER = ConfigKeys.string("motd.top.center", "")
    val MOTD_TOP_RIGHT = ConfigKeys.string("motd.top.right", "")
    val MOTD_BOTTOM_LEFT = ConfigKeys.string("motd.bottom.left", "")
    val MOTD_BOTTOM_CENTER = ConfigKeys.string("motd.bottom.center", "")
    val MOTD_BOTTOM_RIGHT = ConfigKeys.string("motd.bottom.right", "")
}
