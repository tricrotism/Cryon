package com.tricrotism.cryon.common.currency

import com.tricrotism.cryon.common.number.PackedDecimal
import java.util.*

/**
 * One ranked balance, as of the last [CurrencyService.refreshLeaderboards].
 */
data class Ranking(val position: Int, val player: UUID, val balance: PackedDecimal)
