package com.tricrotism.cryon.paper.api.menu

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import xyz.xenondevs.invui.gui.Structure

/**
 * The filler panes every menu draws its frame from, addressed by the legacy colour code of the colour
 * you want: `0` black, `f` white, `e` yellow, and so on.
 *
 * The point is that a [Structure] string reads as the menu's picture:
 *
 * ```kotlin
 * Structure(
 *     "0 0 0 0 0 0 0 0 0",
 *     "0 . . . . . . . 0",
 *     "0 0 0 0 e 0 0 0 0",
 * )
 * ```
 *
 * These are registered as InvUI **global ingredients** by the core at startup, so no module declares
 * them: any structure anywhere can use a colour code for a pane and `.` for an empty slot, and only the
 * characters that carry real content need an `addIngredient` call.
 *
 * Panes are built with the tooltip hidden and an empty name, so hovering one shows nothing at all
 * rather than a stray "Black Stained Glass Pane".
 */
object MenuPalette {

    /**
     * Legacy colour code -> pane material.
     * */
    private val PANES: Map<Char, Material> = mapOf(
        '0' to Material.BLACK_STAINED_GLASS_PANE,
        '1' to Material.BLUE_STAINED_GLASS_PANE,
        '2' to Material.GREEN_STAINED_GLASS_PANE,
        '3' to Material.CYAN_STAINED_GLASS_PANE,
        '4' to Material.RED_STAINED_GLASS_PANE,
        '5' to Material.PURPLE_STAINED_GLASS_PANE,
        '6' to Material.ORANGE_STAINED_GLASS_PANE,
        '7' to Material.LIGHT_GRAY_STAINED_GLASS_PANE,
        '8' to Material.GRAY_STAINED_GLASS_PANE,
        '9' to Material.MAGENTA_STAINED_GLASS_PANE,
        'a' to Material.LIME_STAINED_GLASS_PANE,
        'b' to Material.LIGHT_BLUE_STAINED_GLASS_PANE,
        'c' to Material.BROWN_STAINED_GLASS_PANE,
        'd' to Material.PINK_STAINED_GLASS_PANE,
        'e' to Material.YELLOW_STAINED_GLASS_PANE,
        'f' to Material.WHITE_STAINED_GLASS_PANE,
    )

    /**
     * The character that means "leave this slot empty" (air), not a pane.
     */
    const val EMPTY = '.'

    /**
     * A hidden-tooltip filler pane in the given colour code, or null if the code isn't a colour.
     */
    fun pane(code: Char): ItemStack? = PANES[code.lowercaseChar()]?.let(::pane)

    fun pane(material: Material): ItemStack = ItemStack(material).apply {
        editMeta { meta ->
            meta.displayName(Component.empty())
            meta.isHideTooltip = true
        }
    }

    /**
     * Register every colour code plus [EMPTY] as a global InvUI ingredient. Called once by the core in
     * `onEnable`; calling it again is harmless but pointless.
     *
     * Deliberately not frozen afterwards ([Structure.freezeGlobalIngredients]), a module is still
     * free to add its own global ingredient, and freezing would turn that into a hard failure.
     */
    fun installGlobalIngredients() {
        PANES.keys.forEach { code -> Structure.addGlobalIngredient(code, pane(PANES.getValue(code))) }
        Structure.addGlobalIngredient(EMPTY, ItemStack(Material.AIR))
    }
}
