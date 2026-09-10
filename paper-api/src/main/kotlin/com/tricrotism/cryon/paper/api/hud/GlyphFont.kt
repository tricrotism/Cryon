package com.tricrotism.cryon.paper.api.hud

import net.kyori.adventure.key.Key
import kotlin.math.abs

/**
 * Where a pack's HUD font keeps its spacing glyphs.
 *
 * A space provider maps one code point per pixel width: [negativeBase] is the code point that moves
 * the cursor back one pixel, [negativeBase] + 1 moves it back two, and so on up to [maxSpace];
 * [positiveBase] is the same going forward.
 */
class GlyphFont(
    val key: Key,
    val negativeBase: Int,
    val positiveBase: Int,
    val maxSpace: Int = DEFAULT_MAX_SPACE,
) {

    /**
     * @return the code point that moves the cursor by [px], which must be non-zero and within [maxSpace]
     */
    fun space(px: Int): Int {
        require(px != 0 && abs(px) <= maxSpace) { "space of $px px is outside 1..$maxSpace" }
        return if (px < 0) negativeBase + (-px - 1) else positiveBase + (px - 1)
    }

    companion object {
        const val DEFAULT_MAX_SPACE = 512
    }
}
