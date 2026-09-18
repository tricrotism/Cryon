package com.tricrotism.cryon.paper.api.extension

import com.tricrotism.cryon.common.locale.MessageObservers
import com.tricrotism.cryon.common.locale.MessageService
import com.tricrotism.cryon.common.text.CommonMessages
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.entity.Player

/**
 * Render [key] in [player]'s resolved locale (override ?: client; with fallback chain).
 */
fun MessageService.render(player: Player, key: String, vararg resolvers: TagResolver): Component =
    render(player.resolvedLocale(), key, *resolvers)

/**
 * Render [key] in [player]'s locale and send it wrapped in the shared [CommonMessages] base prefix,
 * the localized ack path. `messages.send(player, "shop.too_poor", …)`.
 *
 * This is also where a chat impression is reported, and deliberately the **only** message seam that
 * reports one. [render] above turns a key into a component that may end up in a menu icon, an action
 * bar, a log line, or nowhere at all; counting those would fill the denominator of every "did anyone
 * act on this" figure with text no player ever saw. Instrument the display, never the render - and
 * the display is also the only site that knows which surface it is. Costs one field read when nothing
 * is observing, which is the normal case.
 */
fun MessageService.send(player: Player, key: String, vararg resolvers: TagResolver) {
    val rendered = render(player.resolvedLocale(), key, *resolvers)
    player.sendMessage(CommonMessages.message(rendered))
    if (MessageObservers.active) {
        MessageObservers.shown(player.uniqueId, key, MessageObservers.SURFACE_CHAT, rendered)
    }
}
