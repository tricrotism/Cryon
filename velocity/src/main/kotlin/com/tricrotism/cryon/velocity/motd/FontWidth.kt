package com.tricrotism.cryon.velocity.motd

/**
 * Pixel widths of the Minecraft default font, used to left/center/right anchor MOTD segments by
 * padding with spaces. Values are glyph advance (glyph width + 1px inter-character spacing); a space
 * advances 4px, which is the granularity alignment can hit. Bold text is 1px wider per character —
 * not accounted for here, so a heavily bold segment drifts by roughly (chars) pixels; keep MOTD
 * segments mostly unbolded for tight alignment.
 */
object FontWidth {

    private const val DEFAULT = 6
    const val SPACE = 4

    // Glyph advance for the characters that aren't the default 6px. Derived from the vanilla font.
    private val WIDTHS: Map<Char, Int> = buildMap {
        put(' ', SPACE)
        for (c in "!.,:;i|") put(c, 2)
        for (c in "'l`") put(c, 3)
        for (c in "I[]t") put(c, 4)
        for (c in "\"(){}<>fk*") put(c, 5)
        put('@', 7)
        put('~', 7)
    }

    /** Total pixel width of [text] as plain (unformatted) text in the default font. */
    fun of(text: String): Int = text.sumOf { WIDTHS[it] ?: DEFAULT }
}
