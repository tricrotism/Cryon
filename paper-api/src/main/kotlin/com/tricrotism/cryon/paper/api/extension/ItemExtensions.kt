package com.tricrotism.cryon.paper.api.extension

import com.tricrotism.cryon.paper.api.item.ItemBuilder
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/** Start an [ItemBuilder] from a material — `Material.DIAMOND.toItem().name("…")`. */
fun Material.toItem(amount: Int = 1): ItemBuilder = ItemBuilder(this, amount)

/** Wrap an existing stack in an [ItemBuilder] (works on a clone — the original is untouched). */
fun ItemStack.toBuilder(): ItemBuilder = ItemBuilder(this)

/** Apply builder edits and return the new stack — `item.modify { name("<gold>X"); glow() }`. */
fun ItemStack.modify(block: ItemBuilder.() -> Unit): ItemStack = toBuilder().apply(block).build()

/** True for a null, air, or zero-amount stack — the usual "empty slot" check. */
fun ItemStack?.isEmpty(): Boolean = this == null || type.isAir || amount <= 0

/** A clone of this stack with a different amount. */
fun ItemStack.withAmount(amount: Int): ItemStack = clone().also { it.amount = amount }

// Persistent-data (PDC) tag helpers — read straight off the stack without unpacking meta yourself.
fun <P : Any, C : Any> ItemStack.getTag(key: NamespacedKey, type: PersistentDataType<P, C>): C? =
    itemMeta?.persistentDataContainer?.get(key, type)

fun ItemStack.hasTag(key: NamespacedKey): Boolean =
    itemMeta?.persistentDataContainer?.has(key) ?: false

fun <P : Any, C : Any> ItemStack.setTag(key: NamespacedKey, type: PersistentDataType<P, C>, value: C) {
    editMeta { it.persistentDataContainer.set(key, type, value) }
}

fun ItemStack.removeTag(key: NamespacedKey) {
    editMeta { it.persistentDataContainer.remove(key) }
}
