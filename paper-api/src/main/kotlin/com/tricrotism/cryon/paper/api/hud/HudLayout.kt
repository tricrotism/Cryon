package com.tricrotism.cryon.paper.api.hud

/**
 * What the viewer's client can draw.
 */
enum class HudLayout {

    /**
     * Java: glyphs may overlap through negative spaces and rows stack through shifted-ascent fonts.
     */
    LAYERED,

    /**
     * Bedrock: every glyph is one line high and there is no negative advance, so runs are sequential.
     */
    LINEAR,
}
