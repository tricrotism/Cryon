package com.tricrotism.cryon.paper.api.extension

import com.tricrotism.cryon.common.flag.FeatureFlags
import org.bukkit.entity.Player

/**
 * The one-line command guard: true if [feature] is on for [player] (player > server > global >
 * default), else sends the standard "\<Feature\> is currently disabled." ack and returns false.
 *
 * ```
 * if (!flags.guard(player, ShopModule.FLAG_SELL)) return
 * ```
 */
fun FeatureFlags.guard(player: Player, feature: String): Boolean {
    if (isEnabled(feature, player.uniqueId)) return true
    player.sendFeatureDisabled(feature)
    return false
}
