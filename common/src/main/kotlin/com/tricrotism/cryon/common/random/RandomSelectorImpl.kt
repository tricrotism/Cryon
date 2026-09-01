package com.tricrotism.cryon.common.random

import java.util.*

internal class RandomSelectorImpl<E> private constructor(
    private val elements: List<E>,
    private val selection: IndexSelector,
) : RandomSelector<E> {

    override fun pick(random: Random): E = elements[selection.pickIndex(random)]

    override fun stream(random: Random): Sequence<E> = generateSequence { pick(random) }

    companion object {

        fun <E> uniform(elements: Collection<E>): RandomSelector<E> {
            require(elements.isNotEmpty()) { "elements must not be empty" }
            val array = elements.toList()
            return RandomSelectorImpl(array, BoundedSelector(array.size))
        }

        fun <E> weighted(elements: Collection<E>, weigher: Weigher<E>): RandomSelector<E> {
            require(elements.isNotEmpty()) { "elements must not be empty" }
            val array = elements.toList()
            val size = array.size

            val probabilities = DoubleArray(size)
            var total = 0.0
            for (i in 0 until size) {
                val weight = weigher.weigh(array[i])
                require(weight > 0.0) { "weigher returned a non-positive weight" }
                probabilities[i] = weight
                total += weight
            }
            for (i in 0 until size) probabilities[i] /= total

            return RandomSelectorImpl(array, WeightedSelector(probabilities))
        }
    }
}
