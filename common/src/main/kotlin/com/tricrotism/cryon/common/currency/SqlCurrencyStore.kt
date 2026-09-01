package com.tricrotism.cryon.common.currency

import com.tricrotism.cryon.common.data.Database
import com.tricrotism.cryon.common.data.SqlIndex
import com.tricrotism.cryon.common.data.SqlSession
import com.tricrotism.cryon.common.number.PackedDecimal
import java.math.BigDecimal
import java.util.*

/**
 * SQL-backed balances: one table for every currency, keyed by `(scope, currency, uuid)`.
 *
 * One table rather than mchub2's table-per-currency, which is what makes a new currency a
 * registration call instead of a migration.
 *
 * `exact` is the authority. The other three columns are **derived on every write and never read back
 * as truth**: they exist only so the database can order what a string cannot: `magnitude` for the
 * cross-exponent order, `positive` to keep negatives out of a ranking, and `packed` (the
 * [PackedDecimal.raw] bits) to break ties inside one magnitude, where the exponent is fixed and the
 * bits therefore order by mantissa.
 */
internal class SqlCurrencyStore(private val database: Database) : CurrencyStore {

    override suspend fun init() {
        database.update(
            """
        CREATE TABLE IF NOT EXISTS $TABLE (
            scope VARCHAR(96) NOT NULL,
            currency VARCHAR(64) NOT NULL,
            uuid VARCHAR(36) NOT NULL,
            exact ${database.dialect.longText} NOT NULL,
            packed BIGINT NOT NULL,
            magnitude INT NOT NULL,
            positive BOOLEAN NOT NULL,
            ranked BOOLEAN NOT NULL DEFAULT TRUE,
            PRIMARY KEY (scope, currency, uuid)${database.dialect.inlineIndexes(INDEXES)}
        )
        """.trimIndent()
        )
        database.createIndexes(TABLE, INDEXES)
        database.update(
            """
        CREATE TABLE IF NOT EXISTS $OPS_TABLE (
            op_id VARCHAR(36) NOT NULL PRIMARY KEY,
            applied_at BIGINT NOT NULL
        )
        """.trimIndent()
        )
    }

    override suspend fun balance(scope: String, currency: String, player: UUID): BigDecimal? =
        database.query(
            "SELECT exact FROM $TABLE WHERE scope = ? AND currency = ? AND uuid = ?",
            scope, currency, player.toString(),
        ) { ExactBalance.decode(it.getString(1)) }.firstOrNull()

    override suspend fun balances(scope: String, player: UUID): Map<String, BigDecimal> =
        database.query(
            "SELECT currency, exact FROM $TABLE WHERE scope = ? AND uuid = ?",
            scope, player.toString(),
        ) { it.getString(1) to ExactBalance.decode(it.getString(2)) }.toMap()

    override suspend fun open(
        scope: String,
        currency: String,
        player: UUID,
        starting: BigDecimal,
        ranked: Boolean,
    ) {
        database.insertIfAbsent(
            TABLE, KEYS, COLUMNS,
            scope, currency, player.toString(), *derived(starting), ranked,
        )
    }

    /**
     * Claim [opId] and move the balance in one transaction, so the two cannot come apart.
     *
     * The claim is what makes this exactly-once: a replay finds the id present, changes nothing, and
     * answers false. The row is opened at [starting] first, because a deposit queued during an outage
     * may be the very first thing that ever happened to this account.
     */
    override suspend fun applyCredit(
        opId: String,
        scope: String,
        currency: String,
        player: UUID,
        amount: BigDecimal,
        starting: BigDecimal,
    ): Boolean = database.transaction { session ->
        val claimed = session.update(
            database.dialect.insertIfAbsent(OPS_TABLE, OPS_KEYS, OPS_COLUMNS),
            opId, System.currentTimeMillis(),
        )
        if (claimed == 0) return@transaction false

        val current = read(session, scope, currency, player) ?: starting
        val next = current.add(amount)
        session.update(
            database.dialect.upsert(TABLE, KEYS, VALUE_COLUMNS),
            scope, currency, player.toString(), *derived(next),
        )
        true
    }

    override suspend fun pruneOps(before: Long) {
        database.update("DELETE FROM $OPS_TABLE WHERE applied_at < ?", before)
    }

    override suspend fun compareAndSet(
        scope: String,
        currency: String,
        player: UUID,
        expected: BigDecimal,
        next: BigDecimal,
    ): Boolean = database.update(
        "UPDATE $TABLE SET exact = ?, packed = ?, magnitude = ?, positive = ? " +
                "WHERE scope = ? AND currency = ? AND uuid = ? AND exact = ?",
        *derived(next), scope, currency, player.toString(), ExactBalance.encode(expected),
    ) > 0

    /**
     * Read and overwrite in one transaction, so the value reported back is the one this write
     * replaced rather than one an interleaving write had already moved on from.
     */
    override suspend fun set(
        scope: String,
        currency: String,
        player: UUID,
        amount: BigDecimal,
    ): BigDecimal? = database.transaction { session ->
        val previous = read(session, scope, currency, player)
        session.update(
            database.dialect.upsert(TABLE, KEYS, VALUE_COLUMNS),
            scope, currency, player.toString(), *derived(amount),
        )
        previous
    }

    override suspend fun top(
        scope: String,
        currency: String,
        size: Int,
    ): List<Pair<UUID, BigDecimal>> = database.query(
        "SELECT uuid, exact FROM $TABLE " +
                "WHERE scope = ? AND currency = ? AND ranked = ? AND positive = ? " +
                "ORDER BY magnitude DESC, packed DESC LIMIT $size",
        scope, currency, true, true,
    ) { UUID.fromString(it.getString(1)) to ExactBalance.decode(it.getString(2)) }

    /**
     * Both writes in one transaction, so there is no instant at which the money is nowhere.
     *
     * The compare-and-set guards stay inside it. They are no longer defending against a lost update
     * (the transaction's row locks do that) but against a stale read *within* this attempt, and a
     * refusal costs a rollback and one retry instead of a wrong balance. Ordering the two accounts
     * by uuid keeps concurrent opposite-direction transfers from deadlocking each other; the retry
     * covers the case the backend detects one anyway and aborts us.
     */
    override suspend fun transfer(
        scope: String,
        currency: String,
        from: UUID,
        to: UUID,
        amount: BigDecimal,
        starting: BigDecimal,
        allowNegative: Boolean,
    ): TransferMove? {
        repeat(MAX_TRANSFER_ATTEMPTS) {
            when (val outcome = attemptTransfer(scope, currency, from, to, amount, starting, allowNegative)) {
                Refused -> return null
                Contended -> Unit
                else -> return outcome as TransferMove
            }
        }

        throw IllegalStateException("Gave up moving $currency from $from to $to after contention")
    }

    private suspend fun attemptTransfer(
        scope: String,
        currency: String,
        from: UUID,
        to: UUID,
        amount: BigDecimal,
        starting: BigDecimal,
        allowNegative: Boolean,
    ): Any? = database.transaction { session ->
        val insert = database.dialect.insertIfAbsent(TABLE, KEYS, COLUMNS)
        for (player in listOf(from, to).sortedBy(UUID::toString)) {
            session.update(insert, scope, currency, player.toString(), *derived(starting), true)
        }

        val fromBefore = read(session, scope, currency, from) ?: starting
        val fromAfter = fromBefore.subtract(amount)
        if (!allowNegative && fromAfter.signum() < 0) return@transaction Refused

        val debited = session.update(
            CAS_SQL, *derived(fromAfter), scope, currency, from.toString(), ExactBalance.encode(fromBefore),
        )
        if (debited == 0) return@transaction Contended

        val toBefore = read(session, scope, currency, to) ?: starting
        val toAfter = toBefore.add(amount)
        val credited = session.update(
            CAS_SQL, *derived(toAfter), scope, currency, to.toString(), ExactBalance.encode(toBefore),
        )
        if (credited == 0) return@transaction Contended

        TransferMove(fromBefore, fromAfter, toBefore, toAfter)
    }

    private fun read(session: SqlSession, scope: String, currency: String, player: UUID): BigDecimal? =
        session.query(
            "SELECT exact FROM $TABLE WHERE scope = ? AND currency = ? AND uuid = ?",
            scope, currency, player.toString(),
        ) { ExactBalance.decode(it.getString(1)) }.firstOrNull()

    /** The four value columns, in [COLUMNS] order, all from the one authoritative number. */
    private fun derived(value: BigDecimal): Array<Any> {
        val packed = PackedDecimal.of(value)
        return arrayOf(
            ExactBalance.encode(value),
            packed.raw(),
            packed.magnitude,
            value.signum() >= 0,
        )
    }

    /** Rolled-back outcomes, kept apart from a [TransferMove] so `null` can keep meaning "refused". */
    private object Refused
    private object Contended

    private companion object {
        const val TABLE = "cryon_currency"

        const val CAS_SQL =
            "UPDATE cryon_currency SET exact = ?, packed = ?, magnitude = ?, positive = ? " +
                    "WHERE scope = ? AND currency = ? AND uuid = ? AND exact = ?"

        /** Retries for a transfer losing its guard to a concurrent write, or aborted as a deadlock victim. */
        const val MAX_TRANSFER_ATTEMPTS = 8
        val KEYS = listOf("scope", "currency", "uuid")
        val VALUE_COLUMNS = listOf("scope", "currency", "uuid", "exact", "packed", "magnitude", "positive")
        val COLUMNS = VALUE_COLUMNS + "ranked"

        val INDEXES = listOf(
            SqlIndex("idx_cryon_currency_top", listOf("scope", "currency", "magnitude", "packed")),
        )

        /**
         * Ids of credits already applied, so a replayed journal entry cannot double a deposit.
         *
         * Pruned rather than kept forever: an id is only useful while its journal entry could still
         * be replayed, and nothing survives an outage longer than the journal that holds it.
         */
        const val OPS_TABLE = "cryon_currency_ops"
        val OPS_KEYS = listOf("op_id")
        val OPS_COLUMNS = listOf("op_id", "applied_at")
    }
}
