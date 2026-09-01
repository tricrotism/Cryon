package com.tricrotism.cryon.common.currency

import com.tricrotism.cryon.common.net.KeyValueStore
import org.slf4j.Logger
import java.math.BigDecimal
import java.time.Duration
import java.util.*

/**
 * Shared account state that keeps an economy live while the database is not.
 *
 * `CurrencyJournal` keeps credits through an outage but cannot authorise a spend: a journal on one
 * node's disk is invisible to the others, so two servers would each authorise against the same
 * money. Putting the pending state in the shared store fixes that. Redis is usually up when Postgres
 * is not, since they are separate services.
 *
 * Two keys, never one. `baseline` caches the balance SQL last confirmed; `delta` accumulates what has
 * happened since. Merged into a single "current balance in Redis" they would be a second source of
 * truth, and recovery would mean reconciling two numbers with no record of what each had seen. Kept
 * apart, recovery is arithmetic: add `delta` to SQL, clear it, refresh `baseline`.
 *
 * The effective balance is `baseline + delta`, and a spend is one atomic conditional change to
 * `delta`, so a burst cannot outrun its own writes and two nodes cannot both win.
 *
 * Redis is not durable by default and a lost `delta` is money that moved and was never written down,
 * so this wants AOF on. Deltas are stored without expiry for the same reason.
 */
class PendingLedger(
    private val store: KeyValueStore,
    private val logger: Logger,
) {

    /**
     * Record that SQL confirmed [balance], so an outage starting now has a floor to work from.
     *
     * A cache refresh and nothing more. Losing it only means a later outage cannot authorise spending
     * for this account, which is a refusal rather than a wrong answer.
     */
    suspend fun rebase(scope: String, currency: String, player: UUID, balance: BigDecimal) {
        runCatching { store.set(baselineKey(scope, currency, player), balance.toPlainString(), BASELINE_TTL) }
            .onFailure { logger.debug("Could not cache a currency baseline", it) }
    }

    /**
     * @return the baseline and the unapplied delta, or null when there is no baseline to build on
     */
    suspend fun snapshot(scope: String, currency: String, player: UUID): Snapshot? {
        val baseline = store.get(baselineKey(scope, currency, player))?.let(::BigDecimal) ?: return null
        val delta = store.get(deltaKey(scope, currency, player))?.let(::BigDecimal) ?: BigDecimal.ZERO

        return Snapshot(baseline, delta)
    }

    /**
     * Move the account by [amount], refusing if it would drop the effective balance below [floor].
     *
     * @param floor null for a credit, zero for a debit on a currency that forbids negatives
     * @return the effective balance after the change, or null when refused or not recorded. Null is a
     *   refusal in both cases: a caller that hands goods over on it is making the unverified spend
     *   this type exists to prevent
     */
    suspend fun adjust(
        scope: String,
        currency: String,
        player: UUID,
        amount: BigDecimal,
        floor: BigDecimal?,
    ): BigDecimal? {
        val baselineKey = baselineKey(scope, currency, player)
        val deltaKey = deltaKey(scope, currency, player)

        repeat(MAX_ATTEMPTS) {
            val baseline = store.get(baselineKey)?.let(::BigDecimal) ?: return null
            val current = store.get(deltaKey)
            val next = (current?.let(::BigDecimal) ?: BigDecimal.ZERO).add(amount)
            val effective = baseline.add(next)

            if (floor != null && effective < floor) return null
            if (store.compareAndSet(deltaKey, current, next.toPlainString())) return effective
        }

        logger.warn("Gave up adjusting the pending {} ledger for {} under contention", currency, player)

        return null
    }

    /**
     * Take the whole delta for [player], leaving zero behind.
     *
     * Taking rather than reading is what makes a drain safe to run on every node at once: exactly one
     * wins the compare-and-set, and only that one owes the database a write. The caller must make the
     * taken amount durable before doing anything else with it.
     *
     * @return what was taken, or null when there was nothing or another node took it first
     */
    suspend fun take(scope: String, currency: String, player: UUID): BigDecimal? {
        val deltaKey = deltaKey(scope, currency, player)
        val zero = BigDecimal.ZERO.toPlainString()

        repeat(MAX_ATTEMPTS) {
            val current = store.get(deltaKey) ?: return null
            val delta = BigDecimal(current)

            if (delta.signum() == 0) {
                store.compareAndSet(deltaKey, current, zero)
                return null
            }

            if (store.compareAndSet(deltaKey, current, zero)) return delta
        }

        return null
    }

    /**
     * Put [amount] back after a failed apply, so a database that refused the write owes it still.
     *
     * Failing here is the one place money is genuinely lost: the caller reaches it because the
     * journal would not take the amount either, so it is now in neither. The log carries everything
     * needed to re-credit it by hand, because nothing else will.
     */
    suspend fun restore(scope: String, currency: String, player: UUID, amount: BigDecimal) {
        if (adjust(scope, currency, player, amount, floor = null) != null) return

        logger.error(
            "LOST {} of '{}' for {} in scope '{}': it reached neither the journal nor the pending " +
                    "ledger, and must be re-credited by hand",
            amount.toPlainString(), currency, player, scope,
        )
    }

    /**
     * @return every account holding an unapplied delta. Used only by the drain, which is not hot
     */
    suspend fun pending(): List<PendingAccount> = runCatching {
        store.keys("$DELTA_PREFIX*").mapNotNull { key ->
            val parts = key.removePrefix(DELTA_PREFIX).split(SEPARATOR)
            if (parts.size != 3) return@mapNotNull null
            val player = runCatching { UUID.fromString(parts[2]) }.getOrNull() ?: return@mapNotNull null

            PendingAccount(parts[0], parts[1], player)
        }
    }.onFailure { logger.warn("Could not list pending currency deltas", it) }.getOrDefault(emptyList())

    private fun baselineKey(scope: String, currency: String, player: UUID) =
        "$BASELINE_PREFIX$scope$SEPARATOR$currency$SEPARATOR$player"

    private fun deltaKey(scope: String, currency: String, player: UUID) =
        "$DELTA_PREFIX$scope$SEPARATOR$currency$SEPARATOR$player"

    private companion object {
        const val BASELINE_PREFIX = "cryon:currency:baseline:"
        const val DELTA_PREFIX = "cryon:currency:delta:"
        const val SEPARATOR = "|"
        val BASELINE_TTL: Duration = Duration.ofHours(6)
        const val MAX_ATTEMPTS = 8
    }
}
