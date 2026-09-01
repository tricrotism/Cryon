package com.tricrotism.cryon.common.signal

/**
 * A signal whose journey can be stopped.
 *
 * Distinct from *modifying* a signal: a cancelled signal means the thing it describes should not
 * happen at all, which the emitter has to check for. [Signals.dispatch] returns the value either
 * way and does not decide on the emitter's behalf, because a bus that silently swallowed the result
 * would make "nobody cancelled" and "somebody cancelled and I ignored it" look identical.
 */
interface Cancellable : Signal {
    var cancelled: Boolean
}
