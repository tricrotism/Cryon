package com.tricrotism.cryon.paper.api.hud

/**
 * One bitmap glyph of a HUD font: its code point and how far the cursor moves after drawing it.
 */
data class Glyph(val codepoint: Int, val advance: Int)
