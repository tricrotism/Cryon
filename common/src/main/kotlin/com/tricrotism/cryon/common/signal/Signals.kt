package com.tricrotism.cryon.common.signal

/**
 * The in-process bus for values that cross module boundaries.
 *
 * **What it is for, and why `Events` is not enough.** `Events` carries Bukkit events and `Packets`
 * carries wire packets. Both are *the platform's* vocabulary, both are notifications about
 * something that already happened, and neither can carry a type a feature invented. This carries the
 * project's own vocabulary, and it hands the value back: a listener can raise a payout, apply a
 * discount, veto a purchase or attach a tag, and the emitter uses what comes out.
 *
 * That difference is the point. `CLAUDE.md` warns that *"a multiplier/buff/drop-chance/cost modifier
 * must hook every relevant call site. Firing in one of four paths is a bug"*, and today that is a
 * discipline problem: the author of a sell path has to remember every feature that might want to
 * modify it, and features cannot register interest in something that has not been written yet. A
 * dispatch point inverts it. The sell path emits one signal and is done; the four features that care
 * subscribe, and a fifth added next month needs no change to the sell path at all.
 *
 * ```
 * // in the module that owns the payout
 * val payout = signals.dispatch(SellPayout(player, item, base))
 * currency.deposit(player, payout.amount)
 *
 * // in any other module, wired in onEnable
 * track(signals.on<SellPayout> { it.amount *= rankMultiplier(it.player) })
 * ```
 *
 * **In-process only, and deliberately so.** Handlers mutate the value in place, which cannot survive
 * a network hop. A cross-server broadcast is `Messenger`, and the two are not interchangeable.
 *
 * **Ordering is by [priority], then registration.** A modifier that must see every other change (a
 * cap, a rounding step, an audit) registers late; one that establishes a base registers early.
 * Handlers within a priority run in the order they subscribed, which is stable but not something to
 * design against. If the order matters, say so with a priority.
 *
 * Registered by the core, so `services.get<Signals>()` always resolves. Thread-safe.
 */
interface Signals {

    /**
     * Hand [signal] to every subscriber for its type and return it.
     *
     * Suspending, because a handler may need to read a balance or a setting to decide. That does
     * mean a dispatch is only as fast as its slowest subscriber. Keep handlers to arithmetic and
     * cached reads, and put anything that has to go to the database behind a cache rather than
     * making every emitter of that signal wait for it.
     *
     * A handler that throws is logged and skipped: one broken module must not stop the others from
     * modifying the value, and must not fail the operation that emitted it.
     */
    suspend fun <T : Signal> dispatch(signal: T): T

    /**
     * Subscribe to every signal assignable to [type]. Close the handle to stop.
     *
     * Assignable rather than exact, so a handler on a supertype sees the subtypes, which is what
     * makes a general audit or metrics listener possible without naming every signal in the project.
     */
    fun <T : Signal> on(type: Class<T>, priority: Int = 0, handler: suspend (T) -> Unit): SignalSubscription

    /**
     * Handle to a subscription. Register through `PaperModule.track(…)` so it dies with the module.
     */
    fun interface SignalSubscription : AutoCloseable
}

/**
 * Reified [Signals.on]: `signals.on<SellPayout> { … }`.
 */
inline fun <reified T : Signal> Signals.on(
    priority: Int = 0,
    noinline handler: suspend (T) -> Unit,
): Signals.SignalSubscription = on(T::class.java, priority, handler)

/**
 * Dispatch [signal] and answer whether it survived, the shape a cancellable emitter wants.
 *
 * Reads as the check it is (`if (!signals.allows(x)) return`) rather than as a dispatch whose result
 * the caller has to remember to inspect, which is the way this goes wrong.
 */
suspend fun <T : Cancellable> Signals.allows(signal: T): Boolean = dispatch(signal).let { !it.cancelled }

