package com.tricrotism.cryon.common.random

/**
 * Pairs a [value] with a selection [weight], so plain objects can feed [RandomSelector.weighted].
 */
data class WeightedObject<T>(val value: T, override val weight: Double) : Weighted {
    init {
        require(weight >= 0) { "weight cannot be negative" }
    }
}
