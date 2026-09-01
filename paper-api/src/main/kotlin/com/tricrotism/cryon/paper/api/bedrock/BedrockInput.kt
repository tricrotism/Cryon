package com.tricrotism.cryon.paper.api.bedrock

/**
 * How a Bedrock client is being driven. Mirrors Floodgate's input mode without exposing its types.
 */
enum class BedrockInput {
    KEYBOARD_MOUSE,
    TOUCH,
    CONTROLLER,
    UNKNOWN;

    /**
     * Touch and unknown are the layouts worth special-casing: a touch player has no hotbar keys and no
     * hover tooltips, so anything that depends on either needs a form instead.
     */
    val isTouchLike: Boolean get() = this == TOUCH || this == UNKNOWN
}
