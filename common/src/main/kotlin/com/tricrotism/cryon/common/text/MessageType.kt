package com.tricrotism.cryon.common.text

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration

/**
 * Prefix style for [CommonMessages]: a bold, coloured **icon** (language-neutral, so it needs no
 * localization). Tune the glyphs/colours here. Glyphs are from blocks the vanilla Unicode font
 * covers; swap for resource-pack PUA glyphs later for a fully custom look.
 */
enum class MessageType(val color: TextColor, val icon: String) {
    ERROR(CryonPalette.ERROR, "✖"),     // ✖
    SUCCESS(CryonPalette.SUCCESS, "✔"),  // ✔
    INFO(CryonPalette.HIGHLIGHT_BLUE, "✦"), // ✦
    WARN(CryonPalette.WARNING, "⚠"),     // ⚠
    ;

    val prefix: Component = Component.text(icon).color(color).decoration(TextDecoration.BOLD, true)
}
