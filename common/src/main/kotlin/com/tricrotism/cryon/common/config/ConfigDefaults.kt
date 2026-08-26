package com.tricrotism.cryon.common.config

/**
 * The default for every config key more than one platform reads.
 *
 * Paper, Velocity and Geyser answer the same questions from the same `config.yml` shape, so before
 * this each of them carried its own copy of every default and each `config.yml` resource carried a
 * third. They agreed by hand, which is the arrangement that stays right until somebody tunes one of
 * them. A default that belongs to exactly one platform (`commands.menu`, `motd.width`,
 * `network.agones.*`) is not here, because sharing a value nothing else reads only hides where it
 * lives.
 *
 * The shipped `config.yml` templates still spell these values out. That duplication is deliberate
 * and the one direction that cannot drift silently: the template is documentation an operator reads
 * and edits, and [ConfigMigrator] copies their edit over the default rather than the other way
 * round.
 */
object ConfigDefaults {

    const val PRODUCTION = true

    const val DATABASE_ENABLED = false
    const val DATABASE_TYPE = "postgresql"
    const val DATABASE_HOST = "localhost"
    const val DATABASE_NAME = "cryon"
    const val DATABASE_USERNAME = "cryon"
    const val DATABASE_PASSWORD = ""
    const val DATABASE_MAX_POOL_SIZE = 10

    const val REDIS_ENABLED = false
    const val REDIS_URI = "redis://localhost:6379/0"

    const val REGISTRY_ENABLED = true
    const val HEARTBEAT_SECONDS = 5L

    const val MAINTENANCE_MESSAGE = "The network is under maintenance."
    const val MAINTENANCE_REFRESH_SECONDS = 30L
}
