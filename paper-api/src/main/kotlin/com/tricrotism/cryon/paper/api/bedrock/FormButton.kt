package com.tricrotism.cryon.paper.api.bedrock

import net.kyori.adventure.text.Component

/**
 * One tappable row in a [BedrockService.sendSimpleForm].
 */
data class FormButton(val label: Component, val onTap: () -> Unit)
