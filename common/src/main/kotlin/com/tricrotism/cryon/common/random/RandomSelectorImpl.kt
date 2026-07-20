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

private fun interface IndexSelector {
    fun pickIndex(random: Random): Int
}

private class BoundedSelector(private val bound: Int) : IndexSelector {
    override fun pickIndex(random: Random): Int = random.nextInt(bound)
}

/**
 * O(1) weighted index selection via Vose's alias method, built in O(n). Each of the `size` columns
 * holds one primary index with probability `probability[i]` and an alias otherwise, so a pick is a
 * single column roll plus one coin flip.
 */
private class WeightedSelector(normalized: DoubleArray) : IndexSelector {

    private val probability: DoubleArray
    private val alias: IntArray

    init {
        val size = normalized.size
        val average = 1.0 / size
        val working = normalized.copyOf()

        val small = IntArray(size)
        var smallSize = 0
        val large = IntArray(size)
        var largeSize = 0
        for (i in 0 until size) {
            if (working[i] < average) small[smallSize++] = i else large[largeSize++] = i
        }

        val pr = DoubleArray(size)
        val al = IntArray(size)
        while (smallSize != 0 && largeSize != 0) {
            val less = small[--smallSize]
            val more = large[--largeSize]
            pr[less] = working[less] * size
            al[less] = more
            working[more] += working[less] - average
            if (working[more] < average) small[smallSize++] = more else large[largeSize++] = more
        }
        while (smallSize != 0) pr[small[--smallSize]] = 1.0
        while (largeSize != 0) pr[large[--largeSize]] = 1.0

        probability = pr
        alias = al
    }

    override fun pickIndex(random: Random): Int {
        val column = random.nextInt(probability.size)
        return if (random.nextDouble() < probability[column]) column else alias[column]
    }
}
