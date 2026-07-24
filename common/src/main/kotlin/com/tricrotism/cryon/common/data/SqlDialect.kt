package com.tricrotism.cryon.common.data

import com.tricrotism.cryon.common.data.SqlDialect.Companion.identifier
import java.sql.SQLException

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

        /** Every Postgres cluster has a `postgres` database; you cannot create one from inside itself. */
        override fun maintenanceUrl(config: DatabaseConfig): String =
            "jdbc:postgresql://${config.host}:${config.port}/postgres"

        /** Postgres has no `IF NOT EXISTS` here, which is fine: this only runs after one was missing. */
        override fun createDatabaseSql(config: DatabaseConfig): String =
            "CREATE DATABASE \"${identifier(config.database)}\""

        /** `3D000` is `invalid_catalog_name` — the database in the URL does not exist. */
        override fun isMissingDatabase(error: SQLException): Boolean = error.sqlState == "3D000"
    },

    MYSQL("mysql", "com.mysql.cj.jdbc.Driver", 3306) {
        override fun jdbcUrl(config: DatabaseConfig): String =
            "jdbc:mysql://${config.host}:${config.port}/${config.database}"

        /** Connector/J accepts an empty database in the URL, so there is no maintenance schema to pick. */
        override fun maintenanceUrl(config: DatabaseConfig): String =
            "jdbc:mysql://${config.host}:${config.port}/"

        override fun createDatabaseSql(config: DatabaseConfig): String =
            "CREATE DATABASE IF NOT EXISTS `${identifier(config.database)}`"

        /** 1049 is `ER_BAD_DB_ERROR`. Matched on the vendor code: SQLSTATE 42000 is far broader. */
        override fun isMissingDatabase(error: SQLException): Boolean = error.errorCode == 1049
    },

    /**
     * Embedded, zero-setup SQL: [DatabaseConfig.database] is a file path (host/port are ignored).
     * `AUTO_SERVER` lets more than one process share the file; `MODE=PostgreSQL` makes H2 accept the
     * Postgres-flavoured SQL features write. Local to one process — not shared network-wide state.
     */
    H2("h2", "org.h2.Driver", 0) {
        override fun jdbcUrl(config: DatabaseConfig): String =
            "jdbc:h2:file:${config.database};AUTO_SERVER=TRUE;MODE=PostgreSQL"
        // No creation members: H2 makes the file on first connect, so a missing database is not a
        // state this backend can be in.
    };

    /** Build the JDBC URL for this backend from [config]. */
    abstract fun jdbcUrl(config: DatabaseConfig): String

    /**
     * A URL for a database guaranteed to exist on this server, used only to create the real one.
     * Null when the backend needs no such step.
     */
    open fun maintenanceUrl(config: DatabaseConfig): String? = null

    /**
     * DDL creating [DatabaseConfig.database], to run against [maintenanceUrl]. Null alongside a null
     * [maintenanceUrl].
     *
     * The name is interpolated, not bound: DDL takes no parameters on any of these backends. That is
     * why it goes through [identifier] first.
     */
    open fun createDatabaseSql(config: DatabaseConfig): String? = null

    /**
     * Whether [error] means precisely "the database named in the URL does not exist".
     *
     * Deliberately narrow. A refused connection, a bad password and a missing `CREATEDB` grant are
     * all failures this must answer **false** to — creating a database in response to any of them
     * would replace a clear error with a confusing one.
     */
    open fun isMissingDatabase(error: SQLException): Boolean = false

    companion object {
        /** Resolve by [id], case-insensitively; throws on an unknown id. */
        fun of(id: String): SqlDialect =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
                ?: error("Unknown database type '$id' (expected one of: ${entries.joinToString { it.id }})")

        private val SAFE_IDENTIFIER = Regex("[A-Za-z0-9_]{1,63}")

        /**
         * [name] if it is a plain SQL identifier, else an error.
         *
         * The only guard between `database.database` and a `CREATE DATABASE` statement it is
         * concatenated into. Both backends do support quoted names with punctuation in them, but a
         * name this narrow is one nobody has to think about — and a database exotic enough to need
         * more than this is one an operator should be creating by hand anyway.
         */
        fun identifier(name: String): String =
            if (SAFE_IDENTIFIER.matches(name)) name
            else error("Refusing to create a database named '$name': expected letters, digits or underscores")
    }
}
