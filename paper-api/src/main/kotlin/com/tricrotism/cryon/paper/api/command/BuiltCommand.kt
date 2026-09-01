package com.tricrotism.cryon.paper.api.command

import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack

/**
 * A built command tree ready to hand to Paper's registrar or splice into the live dispatcher.
 */
data class BuiltCommand(
    val name: String,
    val node: LiteralCommandNode<CommandSourceStack>,
    val description: String?,
    val aliases: List<String>,
)
