package com.tricrotism.cryon.paper.api.inventory

import com.tricrotism.cryon.paper.api.extension.getTag
import com.tricrotism.cryon.paper.api.extension.hasTag
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.*

/**
 * Searches every online player's inventory for items a predicate accepts, spreading the scan across
 * ticks so a whole network is never walked in a single one. The core registers an implementation
 * into the `ServiceRegistry`; a feature resolves it with `services.get(InventorySearch::class)`.
 *
 * The scan is asynchronous by nature: each player's inventory is read on that player's own region/
 * entity thread so it stays correct on Folia, so results are not available when [search] returns.
 * They arrive through [onComplete], invoked exactly once on the global thread with every match found.
 */
interface InventorySearch {

    /** One located item: who was holding it, the inventory slot it sat in, and the stack size. */
    data class Match(val playerId: UUID, val playerName: String, val slot: Int, val amount: Int)

    /** Scan all online players; [matcher] runs against each non-empty slot. */
    fun search(matcher: (ItemStack) -> Boolean, onComplete: (List<Match>) -> Unit)

    /** Items carrying [key] in their persistent-data container (a whole custom-item type). */
    fun byTag(key: NamespacedKey, onComplete: (List<Match>) -> Unit): Unit =
        search({ it.hasTag(key) }, onComplete)

    /** Items whose [key] tag equals [value] — pin down one unique item by its stored id string. */
    fun <P : Any, C : Any> byTag(
        key: NamespacedKey,
        type: PersistentDataType<P, C>,
        value: C,
        onComplete: (List<Match>) -> Unit,
    ): Unit = search({ it.getTag(key, type) == value }, onComplete)
}
