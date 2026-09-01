package com.tricrotism.cryon.common.currency

import com.tricrotism.cryon.common.number.PackedDecimal
import java.math.BigDecimal
import java.util.*

/**
 * Where balances actually live, **exactly**.
 *
 * The store speaks [BigDecimal] while the service above it speaks [PackedDecimal], and that split is
 * the whole point. A packed value carries 14 significant figures, which is right for an amount, a
 * display and a multiplier, and wrong for a *running total*: adding 100 to 1e20 in 14 figures is a
 * no-op, so the deposit disappears and the matching purchase is free. The ledger therefore keeps the
 * full value and only the presentation rounds.
 *
 * The mutating primitive is [compareAndSet]: the arithmetic happens in the JVM, so the write must
 * only land if the stored value has not moved since it was read. A losing write is refused and the
 * caller retries against the new balance.
 */
internal interface CurrencyStore {

    suspend fun init()

    /**
     * The stored balance, or null when the player has no account in this currency.
     */
    suspend fun balance(scope: String, currency: String, player: UUID): BigDecimal?

    /**
     * Every stored balance for [player] in [scope], keyed by currency id.
     */
    suspend fun balances(scope: String, player: UUID): Map<String, BigDecimal>

    /**
     * Create the account at [starting] if absent. No-op when it already exists.
     */
    suspend fun open(
        scope: String,
        currency: String,
        player: UUID,
        starting: BigDecimal,
        ranked: Boolean,
    )

    /**
     * Add [amount] to a balance exactly once, keyed by [opId].
     *
     * The replay half of [CurrencyJournal]: a deposit that could not be written during an outage is
     * applied here when the database returns. A `compareAndSet` that throws leaves the caller unable
     * to tell whether it committed, so the id is claimed and the balance moved in one transaction and
     * a second application finds the id already there.
     *
     * The add is a read-compute-write inside that transaction rather than `SET exact = exact + ?`,
     * because the stored value is encoded text plus derived columns. That is fine at recovery, where
     * the caller serializes per account.
     *
     * @return whether this call applied it, so a caller can tell "done" from "already done"
     */
    suspend fun applyCredit(
        opId: String,
        scope: String,
        currency: String,
        player: UUID,
        amount: BigDecimal,
        starting: BigDecimal,
    ): Boolean

    /**
     * Forget applied-operation ids older than [before], so the idempotency table stays bounded.
     */
    suspend fun pruneOps(before: Long)

    /**
     * Store [next] only if the stored balance is still exactly [expected].
     *
     * False means somebody else wrote first and nothing changed. The caller must re-read and redo
     * its arithmetic. An unconditional write would silently discard whatever landed in between,
     * which for money is a lost deposit or a free purchase.
     */
    suspend fun compareAndSet(
        scope: String,
        currency: String,
        player: UUID,
        expected: BigDecimal,
        next: BigDecimal,
    ): Boolean

    /**
     * Overwrite unconditionally, answering the balance that was there. Administration only, every
     * ordinary path goes through the CAS.
     *
     * The previous value comes back because the change event needs it: read separately it would be
     * whatever the read cache happened to hold, which on a node the player has not touched is the
     * currency's starting balance rather than their actual one.
     */
    suspend fun set(scope: String, currency: String, player: UUID, amount: BigDecimal): BigDecimal?

    /**
     * The [size] highest ranked balances, descending.
     */
    suspend fun top(scope: String, currency: String, size: Int): List<Pair<UUID, BigDecimal>>

    /**
     * Move [amount] from one account to the other, **all or nothing**.
     *
     * Null means [from] could not afford it and neither row was touched. An exception means the move
     * did not happen, not that it half happened: that is the whole reason this is one call rather
     * than a withdraw followed by a deposit. Two separate writes cannot be made safe from above,
     * because the gap between them is not an error path. A process killed there leaves the money
     * gone with nothing thrown and nothing to log.
     */
    suspend fun transfer(
        scope: String,
        currency: String,
        from: UUID,
        to: UUID,
        amount: BigDecimal,
        starting: BigDecimal,
        allowNegative: Boolean,
    ): TransferMove?
}

