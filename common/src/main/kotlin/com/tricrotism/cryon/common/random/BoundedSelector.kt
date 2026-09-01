package com.tricrotism.cryon.common.random

import java.util.*

internal class BoundedSelector(private val bound: Int) : IndexSelector {
    override fun pickIndex(random: Random): Int = random.nextInt(bound)
}
