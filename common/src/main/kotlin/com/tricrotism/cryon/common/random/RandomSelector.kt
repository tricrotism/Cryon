package com.tricrotism.cryon.common.random

import com.tricrotism.cryon.common.random.RandomSelector.Companion.uniform
import com.tricrotism.cryon.common.random.RandomSelector.Companion.weighted
import java.util.*
import java.util.concurrent.ThreadLocalRandom

/**
 * Randomly selects elements from a fixed collection, uniformly or by weight. Build one with
 * [uniform] or [weighted]; a weighted selector uses Vose's alias method, so construction is O(n) and
 * every [pick] afterwards is O(1).
 *
 * A selector is immutable and thread-safe once built — share one across threads and pass each thread
 * its own [Random] (or use the [ThreadLocalRandom] defaults).
 */
interface RandomSelector<E> {

    /** Pick one element using [random]. */
    fun pick(random: Random): E

    /** Pick one element using this thread's [ThreadLocalRandom]. */
    fun pick(): E = pick(ThreadLocalRandom.current())

    /** An effectively infinite lazy sequence of picks drawn from [random]. */
    fun stream(random: Random): Sequence<E>

    /** An effectively infinite lazy sequence of picks drawn from [ThreadLocalRandom]. */
    fun stream(): Sequence<E> = stream(ThreadLocalRandom.current())

    companion object {

        /** A selector that picks uniformly at random from [elements]. */
        fun <E> uniform(elements: Collection<E>): RandomSelector<E> =
            RandomSelectorImpl.uniform(elements)

        /** A selector that picks from [elements] in proportion to each one's [Weighted.weight]. */
        fun <E : Weighted> weighted(elements: Collection<E>): RandomSelector<E> =
            weighted(elements) { it.weight }

        /** A selector that picks from [elements] in proportion to the weight [weigher] assigns each. */
        fun <E> weighted(elements: Collection<E>, weigher: Weigher<E>): RandomSelector<E> =
            RandomSelectorImpl.weighted(elements, weigher)
    }
}
