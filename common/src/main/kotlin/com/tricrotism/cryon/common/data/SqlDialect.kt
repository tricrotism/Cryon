package com.tricrotism.cryon.common.data

/**
 * A supported SQL backend. Everything above [SqlDatabase] is plain JDBC and dialect-agnostic; the
 * only things that differ are the driver class and how the JDBC URL is built — the server backends
 * take a `//host:port/database`, while embedded [H2] takes a file path. Only backends whose driver
 * the core actually ships are listed, so selecting one can never fail with a missing driver.
 */
enum class SqlDialect(val id: String, val driverClass: String, val defaultPort: Int) {

    POSTGRESQL("postgresql", "org.postgresql.Driver", 5432) {
        override fun jdbcUrl(config: DatabaseConfig): String =
            "jdbc:postgresql://${config.host}:${config.port}/${config.database}"
    },

    MYSQL("mysql", "com.mysql.cj.jdbc.Driver", 3306) {
        override fun jdbcUrl(config: DatabaseConfig): String =
            "jdbc:mysql://${config.host}:${config.port}/${config.database}"
    },

    /**
     * Embedded, zero-setup SQL: [DatabaseConfig.database] is a file path (host/port are ignored).
     * `AUTO_SERVER` lets more than one process share the file; `MODE=PostgreSQL` makes H2 accept the
     * Postgres-flavoured SQL features write. Local to one process — not shared network-wide state.
     */
    H2("h2", "org.h2.Driver", 0) {
        override fun jdbcUrl(config: DatabaseConfig): String =
            "jdbc:h2:file:${config.database};AUTO_SERVER=TRUE;MODE=PostgreSQL"
    };

    /** Build the JDBC URL for this backend from [config]. */
    abstract fun jdbcUrl(config: DatabaseConfig): String

    companion object {
        /** Resolve by [id], case-insensitively; throws on an unknown id. */
        fun of(id: String): SqlDialect =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
                ?: error("Unknown database type '$id' (expected one of: ${entries.joinToString { it.id }})")
    }
}
