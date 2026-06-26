package com.tricrotism.cryon.paper.api.item

import com.tricrotism.cryon.common.text.Mini
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType

/**
 * Fluent `ItemStack` builder: display name, lore, flags, glow, enchants, attributes, persistent-data
 * tags. Name/lore default to non-italic (the `<!i>` convention) and parse with the [Mini] palette,
 * so `"<off_white>…"` works. The builder owns a clone, so the source stack is never mutated; [build]
 * returns another clone.
 *
 * ```
 * val item = Material.DIAMOND_SWORD.toItem()
 *     .name("<scarlet>Frostbite")
 *     .lore("<off_white>Slows on hit.")
 *     .glow()
 *     .tag(myKey, PersistentDataType.INTEGER, 3)
 *     .build()
 *
 * val edited = existing.toBuilder().name("<gold>Renamed").build()
 * ```
 */
class ItemBuilder(item: ItemStack) {

    private val item: ItemStack = item.clone()

    constructor(material: Material, amount: Int = 1) : this(ItemStack(material, amount))

    fun amount(amount: Int): ItemBuilder = apply { item.amount = amount }

    fun name(component: Component): ItemBuilder = meta { displayName(component.noItalic()) }

    fun name(miniMessage: String, vararg resolvers: TagResolver): ItemBuilder =
        name(Mini.format(miniMessage, *resolvers))

    fun lore(lines: List<Component>): ItemBuilder = meta { lore(lines.map(Component::noItalic)) }

    fun lore(vararg lines: Component): ItemBuilder = lore(lines.toList())

    fun lore(vararg lines: String): ItemBuilder = lore(lines.map { Mini.format(it) })

    fun addLore(line: Component): ItemBuilder = meta {
        val current = lore() ?: mutableListOf()
        current.add(line.noItalic())
        lore(current)
    }

    fun flags(vararg flags: ItemFlag): ItemBuilder = meta { addItemFlags(*flags) }

    fun glow(glow: Boolean = true): ItemBuilder = meta { setEnchantmentGlintOverride(glow) }

    fun enchant(enchantment: Enchantment, level: Int): ItemBuilder =
        meta { addEnchant(enchantment, level, true) }

    fun unbreakable(unbreakable: Boolean = true): ItemBuilder = meta { isUnbreakable = unbreakable }

    fun customModelData(data: Int): ItemBuilder = meta { setCustomModelData(data) }

    fun attribute(attribute: Attribute, modifier: AttributeModifier): ItemBuilder =
        meta { addAttributeModifier(attribute, modifier) }

    fun <P, C : Any> tag(key: NamespacedKey, type: PersistentDataType<P, C>, value: C): ItemBuilder =
        meta { persistentDataContainer.set(key, type, value) }

    /** Escape hatch for anything not covered above. */
    fun meta(block: ItemMeta.() -> Unit): ItemBuilder = apply { item.editMeta { it.block() } }

    fun build(): ItemStack = item.clone()
}

/** Disable lore/name italics unless the component already set the decoration explicitly. */
private fun Component.noItalic(): Component =
    if (decoration(TextDecoration.ITALIC) == TextDecoration.State.NOT_SET)
        decoration(TextDecoration.ITALIC, false)
    else this
