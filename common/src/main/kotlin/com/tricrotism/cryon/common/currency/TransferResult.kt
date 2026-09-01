package com.tricrotism.cryon.common.currency

/**
 * How a [CurrencyService.transfer] ended.
 *
 * Three outcomes rather than a boolean, because "false" would otherwise mean both "they could not
 * afford it" and "the store is down", and the sender has to be told something different in each.
 *
 * There is deliberately no "half applied" value. The move is one transaction in the store, so either
 * both balances changed or neither did; an outcome for money that exists nowhere would describe a
 * state this cannot reach.
 */
enum class TransferResult {

    /** The amount moved. */
    COMPLETED,

    /** The sender did not have it. Neither side was touched. */
    INSUFFICIENT,

    /** The store could not be read or written. Neither side was touched. */
    FAILED,
}
