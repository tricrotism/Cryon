package com.tricrotism.cryon.common.data

import java.sql.SQLException

/**
 * A secondary index on [columns] of a table, named [name].
 *
 * A type rather than a string because the two backends that take indexes separately and the one that
 * takes them inline need the same three facts spelled two different ways — see
 * [SqlDialect.inlineIndexes] and [SqlDialect.indexStatements].
 */
data class SqlIndex(val name: String, val columns: List<String>, val unique: Boolean = false)

/**
 * A supported SQL backend. Everything above [SqlDatabase] is plain JDBC and dialect-agnostic; the
 * only things that differ are the driver class and how the JDBC URL is built — the server backends
 * take a `//host:port/database`, while embedded [H2] takes a file path. Only backends whose driver
 * the core actually ships are listed, so selecting one can never fail with a missing driver.
 *
 * The column-type members exist for the same reason [upsert] does: a schema written against one
 * backend's spelling fails at `CREATE TABLE` on the other two, so DDL goes through these rather than
 * naming types directly.
 */
enum class SqlDialect(val id: String, val driverClass: String, val defaultPort: Int) {

    POSTGRESQL("postgresql", "org.postgresql.Driver", 5432) {
        override fun jdbcUrl(config: DatabaseConfig): String =
            "jdbc:postgresql://${config.host}:${config.port}/${config.database}"

        override fun upsert(table: String, keys: List<String>, columns: List<String>): String {
            val updates = columns - keys.toSet()
            if (updates.isEmpty()) return insertIfAbsent(table, keys, columns)
            return insert(table, columns) + " ON CONFLICT (${list(keys)}) DO UPDATE SET " +
                    updates.joinToString(", ") { "$it = EXCLUDED.$it" }
        }

        override fun insertIfAbsent(table: String, keys: List<String>, columns: List<String>): String =
            insert(table, columns) + " ON CONFLICT (${list(keys)}) DO NOTHING"

        /** A `WHERE` on `DO UPDATE` guards the whole row at once, so assignment order carries no meaning. */
        override fun upsertIfGreater(
            table: String,
            keys: List<String>,
            columns: List<String>,
            guard: String,
        ): String {
            val updates = columns - keys.toSet()
            if (updates.isEmpty()) return insertIfAbsent(table, keys, columns)
            return insert(table, columns) + " ON CONFLICT (${list(keys)}) DO UPDATE SET " +
                    updates.joinToString(", ") { "$it = EXCLUDED.$it" } +
                    " WHERE $table.$guard < EXCLUDED.$guard"
        }

        override val tinyInt: String get() = "SMALLINT"
        override val doublePrecision: String get() = "DOUBLE PRECISION"

        /** Postgres has one variable-length binary type and it takes no length. */
        override fun binary(bytes: Int): String = "BYTEA"
        override val largeBinary: String get() = "BYTEA"

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
        /**
         * `useAffectedRows=true` is not optional, and it is not a tuning knob.
         *
         * Connector/J defaults it to false, which sets `CLIENT_FOUND_ROWS` and makes `executeUpdate`
         * report the rows *matched* rather than the rows *changed*. Every conditional write in this
         * layer asks "did mine win?" and reads the answer as zero-or-not: [upsertIfGreater] returns
         * zero when its guard refused a stale row, and [insertIfAbsent] returns zero when the row was
         * already there. Under the default, a matched-but-unchanged row reports **one**, so both
         * answers inverted — a stale write reported success and a duplicate insert reported that it
         * had created the row. Silently, because a wrong count throws nothing.
         *
         * With this set, MySQL reports 1 for an insert, 2 for an update it applied and 0 for a row it
         * left alone, which is the contract Postgres and H2 already honour.
         */
        override fun jdbcUrl(config: DatabaseConfig): String =
            "jdbc:mysql://${config.host}:${config.port}/${config.database}?useAffectedRows=true"

        /**
         * `VALUES(col)` is deprecated in favour of a row alias from MySQL 8.0.20, but the alias form
         * is MySQL-only and this driver is also what a MariaDB deployment uses, where only `VALUES()`
         * exists. Deprecated and universal beats current and forked.
         */
        override fun upsert(table: String, keys: List<String>, columns: List<String>): String {
            val updates = columns - keys.toSet()
            if (updates.isEmpty()) return insertIfAbsent(table, keys, columns)
            return insert(table, columns) + " ON DUPLICATE KEY UPDATE " +
                    updates.joinToString(", ") { "$it = VALUES($it)" }
        }

        /** Assigning a key column to itself is the standard no-op body; MySQL has no `DO NOTHING`. */
        override fun insertIfAbsent(table: String, keys: List<String>, columns: List<String>): String =
            insert(table, columns) + " ON DUPLICATE KEY UPDATE ${keys.first()} = ${keys.first()}"

        /**
         * MySQL has no `WHERE` on `ON DUPLICATE KEY UPDATE`, so the guard is repeated per column as an
         * `IF`.
         *
         * **[guard] is assigned last, and that ordering is load-bearing.** MySQL evaluates these
         * assignments left to right and each one sees the results of the ones before it, so a guard
         * written first would leave every following `IF` comparing the incoming value against itself —
         * which is never less than itself, so nothing after it would ever update.
         */
        override fun upsertIfGreater(
            table: String,
            keys: List<String>,
            columns: List<String>,
            guard: String,
        ): String {
            val updates = columns - keys.toSet()
            if (updates.isEmpty()) return insertIfAbsent(table, keys, columns)
            val ordered = if (guard in updates) (updates - guard) + guard else updates
            return insert(table, columns) + " ON DUPLICATE KEY UPDATE " +
                    ordered.joinToString(", ") { "$it = IF($guard < VALUES($guard), VALUES($it), $it)" }
        }

        override val largeBinary: String get() = "LONGBLOB"

        /**
         * Inline, because MySQL has no `CREATE INDEX IF NOT EXISTS` — a separate statement would
         * throw on every boot after the first, and the alternative is swallowing an error that also
         * covers the ones worth hearing about.
         */
        override fun inlineIndexes(indexes: List<SqlIndex>): String =
            indexes.joinToString("") {
                ", ${if (it.unique) "UNIQUE KEY" else "INDEX"} ${it.name} (${list(it.columns)})"
            }

        override fun indexStatements(table: String, indexes: List<SqlIndex>): List<String> = emptyList()

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

        /**
         * `MODE=PostgreSQL` covers a lot, but not this: H2 2.x accepts neither `ON CONFLICT` nor
         * `ON DUPLICATE KEY`, in any mode. Its own `MERGE … KEY` is the upsert, and it requires the
         * key columns to carry a primary key or unique index.
         */
        override fun upsert(table: String, keys: List<String>, columns: List<String>): String =
            "MERGE INTO $table (${list(columns)}) KEY (${list(keys)}) VALUES (${placeholders(columns.size)})"

        /**
         * `MERGE … KEY` always overwrites, so leaving an existing row alone needs the standard
         * `MERGE … USING` form with only a not-matched branch.
         */
        override fun insertIfAbsent(table: String, keys: List<String>, columns: List<String>): String =
            "MERGE INTO $table USING (VALUES (${placeholders(columns.size)})) AS s(${list(columns)}) " +
                    "ON ${keys.joinToString(" AND ") { "$table.$it = s.$it" }} " +
                    "WHEN NOT MATCHED THEN INSERT (${list(columns)}) " +
                    "VALUES (${columns.joinToString(", ") { "s.$it" }})"

        /** Standard `MERGE`'s `WHEN MATCHED AND` takes the guard directly, so both branches are one statement. */
        override fun upsertIfGreater(
            table: String,
            keys: List<String>,
            columns: List<String>,
            guard: String,
        ): String {
            val updates = columns - keys.toSet()
            if (updates.isEmpty()) return insertIfAbsent(table, keys, columns)
            return "MERGE INTO $table USING (VALUES (${placeholders(columns.size)})) AS s(${list(columns)}) " +
                    "ON ${keys.joinToString(" AND ") { "$table.$it = s.$it" }} " +
                    "WHEN MATCHED AND $table.$guard < s.$guard THEN UPDATE SET " +
                    updates.joinToString(", ") { "$it = s.$it" } + " " +
                    "WHEN NOT MATCHED THEN INSERT (${list(columns)}) " +
                    "VALUES (${columns.joinToString(", ") { "s.$it" }})"
        }
    };

    /** Build the JDBC URL for this backend from [config]. */
    abstract fun jdbcUrl(config: DatabaseConfig): String

    /**
     * `INSERT` into [table] with a `?` per entry of [columns], overwriting the non-key columns of a
     * row whose [keys] already match. Reached through `Database.upsert`, which binds the parameters
     * in [columns] order.
     *
     * This is the one statement the layer above cannot write portably: all three backends spell it
     * differently and none of them accepts another's spelling, so a hand-written upsert works on
     * exactly one of them and fails at runtime on the other two.
     */
    abstract fun upsert(table: String, keys: List<String>, columns: List<String>): String

    /** As [upsert], but a row whose [keys] already match is left exactly as it is. */
    abstract fun insertIfAbsent(table: String, keys: List<String>, columns: List<String>): String

    /**
     * As [upsert], but an existing row is overwritten **only when the incoming [guard] column is
     * strictly greater than the stored one** — the portable spelling of an optimistic-concurrency
     * write. [guard] must appear in [columns] and must not be one of the [keys].
     *
     * This is what a version-stamped row needs and what [upsert] cannot express: a plain upsert lets
     * a slow writer's stale copy land on top of a newer one, and the loser is whichever write the
     * network happened to delay. A monotonic counter is the same shape — guarding on the counter
     * itself makes the write a `max`, so a replayed or out-of-order update cannot walk it backwards.
     *
     * Each backend spells the guard somewhere different — Postgres on the conflict clause, MySQL per
     * assigned column, H2 on the `WHEN MATCHED` branch — which is exactly why it belongs here.
     */
    abstract fun upsertIfGreater(
        table: String,
        keys: List<String>,
        columns: List<String>,
        guard: String,
    ): String

    /** A one-byte integer. Postgres has no single-byte type and widens to the smallest it does have. */
    open val tinyInt: String get() = "TINYINT"

    /** Double-precision float. */
    open val doublePrecision: String get() = "DOUBLE"

    /** Variable-length binary of at most [bytes]. */
    open fun binary(bytes: Int): String = "VARBINARY($bytes)"

    /** Binary with no practical length bound. */
    open val largeBinary: String get() = "BLOB"

    /**
     * Index clauses to place **inside** `CREATE TABLE`, each with a leading comma, or empty.
     *
     * Only MySQL takes them there — and it is also the only one of the three without
     * `CREATE INDEX IF NOT EXISTS`, so inline is the only spelling that stays idempotent across the
     * reboots that re-run schema creation. The other two get [indexStatements] instead; exactly one
     * of the pair is ever non-empty.
     */
    open fun inlineIndexes(indexes: List<SqlIndex>): String = ""

    /** Statements to run after `CREATE TABLE`, for the backends that take indexes separately. */
    open fun indexStatements(table: String, indexes: List<SqlIndex>): List<String> =
        indexes.map {
            "CREATE ${if (it.unique) "UNIQUE " else ""}INDEX IF NOT EXISTS ${it.name} ON $table (${list(it.columns)})"
        }

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

        /** `INSERT INTO table (a, b) VALUES (?, ?)`, the head every upsert form here starts from. */
        private fun insert(table: String, columns: List<String>): String =
            "INSERT INTO $table (${list(columns)}) VALUES (${placeholders(columns.size)})"

        private fun list(columns: List<String>): String = columns.joinToString(", ")

        private fun placeholders(count: Int): String = (1..count).joinToString(", ") { "?" }

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
