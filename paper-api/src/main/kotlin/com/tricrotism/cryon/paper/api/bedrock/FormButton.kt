package com.tricrotism.cryon.paper.api.bedrock

import net.kyori.adventure.text.Component

/**
 * One tappable row in a [BedrockService.sendSimpleForm]. [image] is optional and sits in the
 * middle so trailing-lambda callers keep compiling.
 */
data class FormButton @JvmOverloads constructor(
    val label: Component,
    val image: FormImage? = null,
    val onTap: () -> Unit,
)
