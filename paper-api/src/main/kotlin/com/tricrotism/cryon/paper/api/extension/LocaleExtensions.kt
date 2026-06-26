package com.tricrotism.cryon.paper.api.extension

import com.tricrotism.cryon.common.locale.MessageService
import com.tricrotism.cryon.common.text.CommonMessages
import com.tricrotism.cryon.common.text.MessageType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.entity.Player

/** Render [key] in [player]'s resolved locale (override ?: client; with fallback chain). */
fun MessageService.render(player: Player, key: String, vararg resolvers: TagResolver): Component =
    render(player.resolvedLocale(), key, *resolvers)

/** Render [key] in [player]'s resolved locale and send it to them. */
fun MessageService.send(player: Player, key: String, vararg resolvers: TagResolver) =
    player.sendMessage(render(player.resolvedLocale(), key, *resolvers))

/**
 * Render [key] in [player]'s locale and send it wrapped in a [CommonMessages] prefix — the
 * localized + styled path. `messages.send(player, MessageType.ERROR, "shop.too_poor", …)`.
 */
fun MessageService.send(player: Player, type: MessageType, key: String, vararg resolvers: TagResolver) =
    player.sendMessage(CommonMessages.message(type, render(player.resolvedLocale(), key, *resolvers)))
