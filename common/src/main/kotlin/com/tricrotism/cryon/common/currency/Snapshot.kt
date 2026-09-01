package com.tricrotism.cryon.common.currency

import java.math.BigDecimal

/**
 * What an account's effective balance is made of while the database is away.
 *
 * @param baseline the balance SQL last confirmed
 * @param delta what has happened since and has not been written down yet
 */
class Snapshot(val baseline: BigDecimal, val delta: BigDecimal) {

    val effective: BigDecimal
        get() = baseline.add(delta)
}
