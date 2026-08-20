package com.tricrotism.cryon.common.requirement

/**
 * A named condition on a subject, composable into boolean trees.
 *
 * The point is not that it replaces `(Player) -> Boolean` — it is that a lambda cannot be combined,
 * inspected or reused. Menu visibility, shop unlock conditions, skill gates and command guards are
 * all "does this subject satisfy these conditions", and each one currently spells that out its own
 * way, so a condition written for a shop entry cannot be handed to a menu node.
 *
 * **Composition happens once; evaluation allocates nothing.** [and]/[or]/[not] build a tree at
 * declaration time and [test] walks it, short-circuiting exactly as `&&` and `||` do. That matters
 * because the call sites are warm rather than cold — a menu re-resolves every node's visibility for
 * its viewer on every page draw — so a combinator that allocated per evaluation would put garbage on
 * a path that runs per click.
 *
 * ```
 * val canBuy = hasBalance(price) and notAtCap and (isVip or hasUnlock)
 * if (canBuy test player) { … }
 * ```
 *
 * Contravariant in [S], so a `Requirement<Any>` (say, a permission check written against a generic
 * subject) is usable wherever a `Requirement<Player>` is expected.
 *
 * **Implementations must be pure and cheap.** [test] is called from menu draws and event handlers,
 * on whichever thread owns the subject; it must not block, must not touch a database, and must not
 * mutate anything. Where a condition genuinely needs I/O, resolve it before building the tree and
 * close over the answer.
 */
fun interface Requirement<in S> {

    fun test(subject: S): Boolean

    companion object {

        /** Satisfied by everything. The identity for [and]; the neutral default for an optional gate. */
        val ALWAYS: Requirement<Any?> = Requirement { true }

        /** Satisfied by nothing. The identity for [or]. */
        val NEVER: Requirement<Any?> = Requirement { false }

        /**
         * Satisfied only when every one of [requirements] is, in the order given.
         *
         * Folded into a tree once here rather than kept as a list, so [test] stays a chain of
         * short-circuiting calls with no iterator. An empty list is [ALWAYS], which is the correct
         * identity: "no conditions" admits everything.
         */
        fun <S> all(requirements: List<Requirement<S>>): Requirement<S> = when (requirements.size) {
            0 -> ALWAYS
            1 -> requirements[0]
            else -> requirements.reduce { left, right -> left and right }
        }

        /** Satisfied when any of [requirements] is. Empty is [NEVER] — "no way in" admits nothing. */
        fun <S> any(requirements: List<Requirement<S>>): Requirement<S> = when (requirements.size) {
            0 -> NEVER
            1 -> requirements[0]
            else -> requirements.reduce { left, right -> left or right }
        }
    }
}

/** Both, short-circuiting: [other] is not evaluated when the receiver already fails. */
infix fun <S> Requirement<S>.and(other: Requirement<S>): Requirement<S> =
    Requirement { subject -> test(subject) && other.test(subject) }

/** Either, short-circuiting: [other] is not evaluated when the receiver already passes. */
infix fun <S> Requirement<S>.or(other: Requirement<S>): Requirement<S> =
    Requirement { subject -> test(subject) || other.test(subject) }

/** The inverse. */
operator fun <S> Requirement<S>.not(): Requirement<S> =
    Requirement { subject -> !test(subject) }
