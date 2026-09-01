package com.tricrotism.cryon.common.random

import java.util.*

internal fun interface IndexSelector {
    fun pickIndex(random: Random): Int
}
