package com.tricrotism.cryon.velocity.api.bedrock

/**
 * How a Bedrock client is being driven. Mirrors Floodgate's input mode without exposing its types.
 *
 * **Duplicated from `com.tricrotism.cryon.paper.api.bedrock.BedrockInput`, deliberately**, exactly
 * like the `@Command` model: `:paper-api` carries Bukkit types and `:velocity` must stay Bukkit-free,
 * so the two copies are kept in step by hand rather than shared.
 */
enum class BedrockInput {
    KEYBOARD_MOUSE,
    TOUCH,
    CONTROLLER,
    UNKNOWN;

    /**
     * Touch and unknown are the layouts worth special-casing: a touch player has no hotbar keys and no
     * hover tooltips, so anything that depends on either needs a different presentation.
     */
    val isTouchLike: Boolean get() = this == TOUCH || this == UNKNOWN
}
