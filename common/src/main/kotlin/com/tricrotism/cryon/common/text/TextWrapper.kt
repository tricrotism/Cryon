package com.tricrotism.cryon.common.text

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextDecoration

/**
 * Wraps an Adventure [Component] onto multiple lines at a pixel width in the Minecraft default font
 * ([FontWidth]), preserving each segment's style. Wrapping is word-based (it breaks between words,
 * never mid-word) and measures bold text 1px wider per character, matching the vanilla font. A
 * non-text child (e.g. a translatable or keybind) whose rendered width isn't knowable here counts as
 * a single default-width glyph.
 *
 * ```
 * val lines = TextWrapper.wrap("<off_white>A long line of lore that should wrap.".mm())
 * val lore = TextWrapper.wrapPrefixed("<gray>» ".mm(), description)
 * ```
 */
object TextWrapper {

    const val DEFAULT_MAX_WIDTH = 180
    private val WORD_BOUNDARY = Regex("(?<= )")

    /**
     * Wrap [component] so no line exceeds [maxWidth] pixels. A non-text component passes through as one line.
     */
    fun wrap(component: Component, maxWidth: Int = DEFAULT_MAX_WIDTH): List<Component> {
        if (component !is TextComponent) return listOf(component)

        val lines = ArrayList<Component>()
        var line = Component.text()
        var width = 0

        for (segment in flatten(component)) {
            val bold = segment.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE
            if (segment is TextComponent) {
                val style = segment.style()
                for (word in segment.content().split(WORD_BOUNDARY)) {
                    if (word.isEmpty()) continue
                    val wordWidth = FontWidth.of(word, bold)
                    if (width > 0 && width + wordWidth > maxWidth) {
                        lines.add(line.build())
                        line = Component.text()
                        width = 0
                    }
                    line.append(Component.text(word, style))
                    width += wordWidth
                }
            } else {
                line.append(segment)
                width += FontWidth.advance('A', bold)
            }
        }

        lines.add(line.build())
        return lines
    }

    /**
     * Wrap [content] and prefix every line with [prefix], reserving the prefix's width so wrapped
     * lines still fit within [maxWidth].
     */
    fun wrapPrefixed(prefix: Component, content: Component, maxWidth: Int = DEFAULT_MAX_WIDTH): List<Component> =
        wrap(content, maxWidth - width(prefix)).map { Component.empty().append(prefix).append(it) }

    /**
     * Pixel width of [component] rendered in the default font, accounting for bold.
     */
    fun width(component: Component): Int {
        if (component !is TextComponent) return FontWidth.advance('A')
        var total = 0
        for (segment in flatten(component)) {
            val bold = segment.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE
            total += if (segment is TextComponent) {
                FontWidth.of(segment.content(), bold)
            } else {
                FontWidth.advance('A', bold)
            }
        }
        return total
    }

    /**
     * Flatten [component]'s tree into a pre-order list of segments, each carrying its fully-merged
     * style (so a segment can be measured and re-emitted on its own). Unset decorations are pinned to
     * `false` up front, so an ancestor's italics/bold don't silently leak into a child.
     */
    fun flatten(component: TextComponent): List<Component> {
        val flattened = ArrayList<Component>()
        val toCheck = ArrayDeque<Component>()
        toCheck.addLast(component.style(enforceStates(component.style())))

        while (toCheck.isNotEmpty()) {
            val parent = toCheck.removeLast()
            if (parent !is TextComponent || parent.content().isNotEmpty()) flattened.add(parent)

            val children = parent.children()
            for (i in children.indices.reversed()) {
                val child = children[i]
                toCheck.addLast(
                    if (child is TextComponent) child.style(parent.style().merge(child.style())) else child
                )
            }
        }
        return flattened
    }

    private fun enforceStates(style: Style): Style {
        val builder = style.toBuilder()
        style.decorations().forEach { (decoration, state) ->
            if (state == TextDecoration.State.NOT_SET) builder.decoration(decoration, false)
        }
        return builder.build()
    }
}
