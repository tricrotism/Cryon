package com.tricrotism.cryon.common.random

/**
 * Assigns a weight to an element. The pluggable weight source for [RandomSelector.weighted].
 */
fun interface Weigher<E> {

    /** The weight of [element]; must be non-negative. */
    fun weigh(element: E): Double
}
