package com.tricrotism.cryon.paper.api.bedrock

import net.kyori.adventure.text.Component

/**
 * One field in a [BedrockService.sendCustomForm]. Responses come back keyed by [id].
 */
sealed interface FormField {
    val id: String

    data class Input(
        override val id: String,
        val label: Component,
        val placeholder: String = "",
        val default: String = "",
    ) : FormField

    data class Toggle(
        override val id: String,
        val label: Component,
        val default: Boolean = false,
    ) : FormField

    data class Dropdown(
        override val id: String,
        val label: Component,
        val options: List<String>,
        val defaultIndex: Int = 0,
    ) : FormField

    data class Slider(
        override val id: String,
        val label: Component,
        val min: Float,
        val max: Float,
        val step: Int = 1,
        val default: Float = min,
    ) : FormField
}
