package com.tricrotism.cryon.common.currency

import java.math.BigDecimal
import java.util.*

/**
 * A deposit the database has not taken yet.
 *
 * @param opId what makes a replay exactly-once, generated where the deposit is decided
 * @param starting the currency's starting balance, carried rather than looked up because the drain
 *   runs before modules register their currencies
 */
class PendingCredit(
    val opId: String,
    val scope: String,
    val currency: String,
    val player: UUID,
    val amount: BigDecimal,
    val starting: BigDecimal,
    val reason: String,
    val at: Long,
)
