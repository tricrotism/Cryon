package com.tricrotism.cryon.common.currency

import java.math.BigDecimal

/**
 * The four balances a completed [CurrencyStore.transfer] moved between, for the change events.
 */
internal data class TransferMove(
    val fromBefore: BigDecimal,
    val fromAfter: BigDecimal,
    val toBefore: BigDecimal,
    val toAfter: BigDecimal,
)
