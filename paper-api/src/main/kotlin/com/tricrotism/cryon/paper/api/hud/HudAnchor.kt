package com.tricrotism.cryon.paper.api.hud

/**
 * Where the client puts the start of the composed line.
 */
enum class HudAnchor {

    /**
     * Boss bar, action bar and title: the client centres the line by its total advance, so the
     * composition closes with a space back to zero and every x is measured from the screen centre.
     */
    CENTRE,

    /**
     * A container title: drawn from a fixed left edge, so x is measured from there.
     */
    LEFT,
}
