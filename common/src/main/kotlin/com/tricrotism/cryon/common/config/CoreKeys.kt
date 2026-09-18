package com.tricrotism.cryon.common.config

/**
 * Every config key more than one platform reads, declared once.
 *
 * Paper, Velocity and Geyser answer the same questions from the same `config.yml` shape, so before
 * this each carried its own copy of every default and each `config.yml` resource carried a third.
 * A key belonging to exactly one platform is declared beside that platform's reader instead.
 *
 * The shipped templates still spell these values out, because a template is documentation an operator
 * edits and [ConfigMigrator] copies their edit over the default rather than the other way round. That
 * duplication *did* drift, so each platform's drift test checks its template against this schema.
 *
 * [MODULES_AUTO_RELOAD] alone carries no default and is read with `find`, because its fallback is not
 * a constant: it follows [PRODUCTION]. It ships commented out rather than marked required.
 */
object CoreKeys : ConfigSchema() {

    val PRODUCTION = boolean("production", true)

    val DATABASE_ENABLED = boolean("database.enabled", false)
    val DATABASE_TYPE = nonBlankString("database.type", "postgresql")
    val DATABASE_HOST = nonBlankString("database.host", "localhost")

    /**
     * 0 follows the dialect's own default (postgresql 5432, mysql 3306), the same sentinel
     * [PaperKeys.NETWORK_PORT] uses for the server port.
     *
     * It was defaultless and absent from the template, which read as "unset follows the backend" but
     * did not survive contact with a real deployment: every shipped template set it to 5432, so a
     * config that switched `database.type` to mysql dialled 5432 at MySQL and failed obscurely.
     * Commenting it out fixed only fresh installs, because `ConfigMigrator` never deletes what an
     * operator wrote, so an existing file ended up carrying the key twice. A sentinel keeps the key
     * present, which is what migration needs, and still lets the dialect decide.
     */
    val DATABASE_PORT = int("database.port", 0, 0..65535)
    val DATABASE_NAME = nonBlankString("database.database", "cryon")
    val DATABASE_USERNAME = string("database.username", "cryon")
    val DATABASE_PASSWORD = string("database.password", "")
    val DATABASE_MAX_POOL_SIZE = int("database.max-pool-size", 10, 1..256)

    val REDIS_ENABLED = boolean("redis.enabled", false)
    val REDIS_URI = nonBlankString("redis.uri", "redis://localhost:6379/0")

    val REGISTRY_ENABLED = boolean("network.registry-enabled", true)
    val HEARTBEAT_SECONDS = long("network.heartbeat-seconds", 5L, 1L..3600L)

    val MAINTENANCE_MESSAGE =
        string("maintenance.default-message", "The network is under maintenance.")
    val MAINTENANCE_REFRESH_SECONDS = long("maintenance.refresh-seconds", 30L, 0L..86400L)
    val MODULES_AUTO_RELOAD = boolean("modules.auto-reload")

    // shared because the proxy and Geyser render the same block two ways; motd.width is the proxy's
    // alone, since only it measures anything
    val MOTD_ENABLED = boolean("motd.enabled", false)
    val MOTD_TOP_LEFT = string("motd.top.left", "")
    val MOTD_TOP_CENTER = string("motd.top.center", "")
    val MOTD_TOP_RIGHT = string("motd.top.right", "")
    val MOTD_BOTTOM_LEFT = string("motd.bottom.left", "")
    val MOTD_BOTTOM_CENTER = string("motd.bottom.center", "")
    val MOTD_BOTTOM_RIGHT = string("motd.bottom.right", "")

    /**
     * Keys with a computed fallback, so a template ships them commented out. Named here rather than
     * at each platform's test so three copies cannot disagree about which two they are.
     */
    val COMPUTED_FALLBACK = setOf(MODULES_AUTO_RELOAD.path)
}
