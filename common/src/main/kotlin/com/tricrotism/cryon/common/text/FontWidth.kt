package com.tricrotism.cryon.common.text

import com.tricrotism.cryon.common.text.FontWidth.BOLD_EXTRA
import com.tricrotism.cryon.common.text.FontWidth.DEFAULT
import com.tricrotism.cryon.common.text.FontWidth.SPACE
import com.tricrotism.cryon.common.text.FontWidth.WIDTHS
import com.tricrotism.cryon.common.text.FontWidth.advance
import com.tricrotism.cryon.common.text.FontWidth.of
import com.tricrotism.cryon.common.text.FontWidth.ofLegacy


/**
 * Pixel metrics of the Minecraft default font. Used to align MOTD segments (padding with spaces,
 * [Motd.kt][com.tricrotism.cryon.velocity.motd.Motd]) and to wrap text at a pixel width ([TextWrapper]).
 *
 * Values are glyph **advance**: the glyph's own width plus the 1px inter-character gap the font draws
 * after it. So a space advances [SPACE] (4px) and a typical letter advances [DEFAULT] (6px), which is
 * the granularity space-padding can hit. **Bold** draws each glyph a second time shifted 1px right, so
 * every rendered glyph (but not the empty space) is [BOLD_EXTRA] wider; [advance]/[of] add that when
 * asked. Legacy `§x` formatting is zero-width and toggles bold, which [ofLegacy] accounts for.
 *
 * The ASCII advances are exact for the vanilla `ascii` font page. The non-ASCII entries are the
 * handful of glyphs Cryon's own UI renders (message icons, guillemets); the vanilla unicode fallback
 * is coarser than the ascii page, so treat those as close approximations and tune here if a measured
 * MOTD or wrapped line drifts. Everything not listed advances [DEFAULT], a safe default for the many
 * letters/digits/accented forms that are exactly that wide.
 */
object FontWidth {

    /**
     * Advance of a space, and the granularity space-padding alignment can reach.
     */
    const val SPACE = 4

    /**
     * Advance of any glyph not in [WIDTHS] — most letters, digits, and accented Latin forms.
     */
    const val DEFAULT = 6

    /**
     * Extra advance per rendered glyph when bold (the 1px shadow shift; the empty space is exempt).
     */
    const val BOLD_EXTRA = 1

    /**
     * The legacy formatting introducer; `§` followed by a code is zero-width.
     */
    const val LEGACY_CHAR = '§'

    private val WIDTHS: Map<Char, Int> = buildMap {
        put(' ', SPACE)

        for (c in "!.,:;i|") put(c, 2)
        for (c in "'l`") put(c, 3)
        for (c in "I[]t") put(c, 4)
        for (c in "\"(){}<>fk*") put(c, 5)
        put('@', 7)
        put('~', 7)
        put('»', 6)
        put('«', 6)
        put('•', 4)
        put('✖', 8)
        put('✔', 8)
        put('✦', 8)
        put('⚠', 8)
    }

    /**
     * Advance of a single [char] in the default font, adding the bold shift when [bold] (space exempt).
     */
    fun advance(char: Char, bold: Boolean = false): Int {
        val base = WIDTHS[char] ?: DEFAULT
        return if (bold && char != ' ') base + BOLD_EXTRA else base
    }

    /**
     * Total advance of [text] as plain (unformatted) text, optionally rendered [bold].
     */
    fun of(text: String, bold: Boolean = false): Int {
        var total = 0
        for (c in text) total += advance(c, bold)
        return total
    }

    /**
     * Advance of [text] that may carry legacy `§x` formatting: each `§x` pair is zero-width, `§l`
     * turns bold on, and `§r` or any colour code turns it off (matching vanilla, where a colour resets
     * formatting). For text you know is plain, [of] is cheaper.
     */
    fun ofLegacy(text: String): Int {
        var total = 0
        var bold = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == LEGACY_CHAR && i + 1 < text.length) {
                when (text[i + 1].lowercaseChar()) {
                    'l' -> bold = true
                    'r', in '0'..'9', in 'a'..'f' -> bold = false
                }
                i += 2
                continue
            }
            total += advance(c, bold)
            i++
        }
        return total
    }
}
