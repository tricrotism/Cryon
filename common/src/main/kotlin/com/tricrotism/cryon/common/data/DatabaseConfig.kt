package com.tricrotism.cryon.common.data

/**
 * Connection settings for the SQL backend. [dialect] selects which backend and driver to use.
 */
data class DatabaseConfig(
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val password: String,
    val maxPoolSize: Int = 10,
    val dialect: SqlDialect = SqlDialect.MYSQL,
)
