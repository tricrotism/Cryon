package com.tricrotism.cryon.common.currency

/**
 * How a withdraw ended.
 *
 * Only [COMPLETED] means money moved, so it is the only one that may hand goods over. The other two
 * are both refusals and differ in whose fault it is, which is what a message to the player has to get
 * right.
 */
enum class WithdrawResult {

    COMPLETED,

    /** The player did not have it. Nothing was taken. */
    INSUFFICIENT,

    /**
     * The ledger could not be reached, so affordability was never established. Nothing was taken.
     *
     * A debit cannot be queued the way a deposit can: it needs an authoritative read, and deferring
     * it would authorise a spend nobody can verify.
     */
    UNAVAILABLE,
}
