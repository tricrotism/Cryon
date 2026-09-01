package com.tricrotism.cryon.common.random

/**
 * An object that carries its own selection weight for [RandomSelector.weighted].
 */
interface Weighted {

    // This object's weight; must be non-negative, and positive to ever be picked
    val weight: Double
}
