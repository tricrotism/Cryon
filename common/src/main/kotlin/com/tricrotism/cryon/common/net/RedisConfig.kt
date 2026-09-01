package com.tricrotism.cryon.common.net

/**
 * Connection settings for the cross-server transport. [uri] e.g. `redis://:password@host:6379/0`.
 */
data class RedisConfig(val uri: String)
