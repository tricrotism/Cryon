package com.tricrotism.cryon.paper.api.extension

import com.tricrotism.cryon.common.locale.MessageService
import com.tricrotism.cryon.common.text.CommonMessages
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.entity.Player

/** Render [key] in [player]'s resolved locale (override ?: client; with fallback chain). */
fun MessageService.render(player: Player, key: String, vararg resolvers: TagResolver): Component =
    render(player.resolvedLocale(), key, *resolvers)

/**
 * Render [key] in [player]'s locale and send it wrapped in the shared [CommonMessages] base prefix —
 * the localized ack path. `messages.send(player, "shop.too_poor", …)`.
 */
fun MessageService.send(player: Player, key: String, vararg resolvers: TagResolver) =
    player.sendMessage(CommonMessages.message(render(player.resolvedLocale(), key, *resolvers)))
