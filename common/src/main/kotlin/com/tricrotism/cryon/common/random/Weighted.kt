package com.tricrotism.cryon.common.random

/** An object that carries its own selection weight for [RandomSelector.weighted]. */
interface Weighted {

    /** This object's weight; must be non-negative, and positive to ever be picked. */
    val weight: Double
}

/** Assigns a weight to an element — the pluggable weight source for [RandomSelector.weighted]. */
fun interface Weigher<E> {

    /** The weight of [element]; must be non-negative. */
    fun weigh(element: E): Double
}

/** Pairs a [value] with a selection [weight], so plain objects can feed [RandomSelector.weighted]. */
data class WeightedObject<T>(val value: T, override val weight: Double) : Weighted {
    init {
        require(weight >= 0) { "weight cannot be negative" }
    }
}
