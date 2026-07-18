package com.tricrotism.cryon.velocity

import com.tricrotism.cryon.common.locale.Messages
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import java.util.*

/**
 * Locale-aware messaging for Velocity command sources. A player's message renders in their client
 * locale; the console (and any player without one) falls back to the installed default. All proxy
 * command feedback goes through here so nothing is hardcoded English — see the `lang/` bundle.
 */

fun CommandSource.resolvedLocale(): Locale =
    (this as? Player)?.effectiveLocale ?: Messages.service()?.defaultLocale ?: Locale.US

fun CommandSource.sendLocalized(key: String, vararg resolvers: TagResolver) {
    sendMessage(Messages.get(resolvedLocale(), key, *resolvers))
}
