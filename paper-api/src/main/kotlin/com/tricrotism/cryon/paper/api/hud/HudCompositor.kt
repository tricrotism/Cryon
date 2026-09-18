package com.tricrotism.cryon.paper.api.hud

import com.tricrotism.cryon.common.text.FontWidth
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.*
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import kotlin.math.abs

/**
 * Composes one HUD layer out of font glyphs placed at pixel offsets.
 *
 * The cursor is the client's text cursor. A glyph advances it by the glyph's width, [space] moves it
 * by any amount in either direction, and [at] is a space to an absolute x. Under [HudLayout.LINEAR]
 * spaces are ignored and glyphs simply follow one another, so the same calls produce a readable line
 * on a client that cannot overlap glyphs.
 *
 * Every run is drawn white with no shadow unless asked otherwise, because a bitmap glyph is tinted by
 * the text colour and a shadow is a dark copy of it one pixel down. Adjacent glyphs with the same style
 * share one component. Nothing is cached here: the caller decides when a redraw is needed, and a
 * redraw allocates only the strings and components it emits.
 *
 * One instance per composer, reused through [begin], and never shared between threads.
 */
class HudCompositor(
    private val font: GlyphFont,
    private val layout: HudLayout,
    capacity: Int = DEFAULT_CAPACITY,
) {

    private val run = StringBuilder(capacity)
    private val parts = ArrayList<Component>(INITIAL_PARTS)
    private var runFont: Key? = null
    private var runColor: TextColor = NamedTextColor.WHITE
    private var runBold = false

    var cursor: Int = 0
        private set

    fun begin(): HudCompositor {
        run.setLength(0)
        parts.clear()
        runFont = null
        runColor = NamedTextColor.WHITE
        runBold = false
        cursor = 0
        return this
    }

    fun space(px: Int): HudCompositor {
        if (layout == HudLayout.LINEAR || px == 0) return this
        ensureRun(font.key, runColor, bold = false)
        var remaining = px

        while (remaining != 0) {
            val step = minOf(abs(remaining), font.maxSpace)
            run.appendCodePoint(font.space(if (remaining < 0) -step else step))
            remaining += if (remaining < 0) step else -step
        }

        cursor += px
        return this
    }

    fun at(x: Int): HudCompositor = space(x - cursor)

    @JvmOverloads
    fun glyph(glyph: Glyph, color: TextColor = NamedTextColor.WHITE): HudCompositor {
        ensureRun(font.key, color, bold = false)
        run.appendCodePoint(glyph.codepoint)
        cursor += glyph.advance
        return this
    }

    /**
     * Ordinary text in [row], a font whose ascent puts the baseline where this row should sit.
     * [half] is for a row font that draws the vanilla sheet at half height, where the client
     * halves each glyph's width and keeps the one pixel gap after it.
     */
    @JvmOverloads
    fun text(text: String, row: Key, color: TextColor, bold: Boolean = false, half: Boolean = false): HudCompositor {
        ensureRun(row, color, bold)
        run.append(text)
        cursor += if (half) halfWidth(text, bold) else FontWidth.of(text, bold)
        return this
    }

    /**
     * A rendered component in [row]: the root takes the row font and no shadow, children that set
     * their own colour keep it. The cursor advances by the plain text's width, so a bold child is
     * measured a pixel short per glyph.
     */
    fun component(component: Component, row: Key, half: Boolean = false): HudCompositor {
        val plain = PLAIN.serialize(component)
        raw(component.font(row).shadowColor(ShadowColor.none()))
        cursor += if (half) halfWidth(plain) else FontWidth.of(plain)
        return this
    }

    /**
     * A component that takes no room: a shader-positioned overlay whose own spacer cancels its
     * advance. Appended between runs and never measured.
     */
    fun raw(component: Component): HudCompositor {
        flush()
        runFont = null
        parts.add(component)
        return this
    }

    fun build(anchor: HudAnchor): Component {
        if (anchor == HudAnchor.CENTRE) space(-cursor)
        flush()
        return Component.text().append(parts).build()
    }

    private fun ensureRun(key: Key, color: TextColor, bold: Boolean) {
        if (runFont == key && runColor == color && runBold == bold) return
        flush()
        runFont = key
        runColor = color
        runBold = bold
    }

    private fun flush() {
        val key = runFont ?: return
        if (run.isEmpty()) return
        val style = Style.style()
            .font(key)
            .color(runColor)
            .shadowColor(ShadowColor.none())
            .decoration(TextDecoration.BOLD, runBold)
            .build()
        parts.add(Component.text(run.toString(), style))
        run.setLength(0)
    }

    companion object {
        const val DEFAULT_CAPACITY = 512
        private const val INITIAL_PARTS = 16
        private val PLAIN = PlainTextComponentSerializer.plainText()

        /**
         * @return the advance of [text] drawn at half height, per glyph `(width * 0.5 + 0.5).toInt() + 1`
         */
        fun halfWidth(text: String, bold: Boolean = false): Int {
            var total = 0
            for (c in text) total += ((FontWidth.advance(c, bold) - 1) * 0.5 + 0.5).toInt() + 1
            return total
        }
    }
}
