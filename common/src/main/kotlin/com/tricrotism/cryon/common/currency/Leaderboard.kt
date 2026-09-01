package com.tricrotism.cryon.common.currency

/**
 * An optional ranking over a currency's balances.
 *
 * Opt-in per currency because a leaderboard is a periodic `ORDER BY` over every row of that currency
 *. Cheap for one currency and not free for twenty. A currency with no [Currency.leaderboard] never
 * runs the query at all.
 */
data class Leaderboard(

    /**
     * How many entries to keep. The query is `LIMIT`ed to this, so it is a cost, not a display cap.
     */
    val size: Int = 10,
) {
    init {
        require(size > 0) { "Leaderboard size must be positive, got $size" }
    }
}
