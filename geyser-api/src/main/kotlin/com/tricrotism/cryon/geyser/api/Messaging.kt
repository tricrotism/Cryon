package com.tricrotism.cryon.geyser.api

import com.tricrotism.cryon.common.locale.Locales
import com.tricrotism.cryon.common.locale.MessageService
import com.tricrotism.cryon.common.locale.Messages
import com.tricrotism.cryon.common.text.CommonMessages
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.geysermc.geyser.api.command.CommandSource
import java.util.*

/**
 * The one seam between Cryon's Adventure messaging and Geyser's `CommandSource`, whose only send
 * takes a plain `String`. Everything above this line stays `Component`: build with `Mini`,
 * `CommonMessages` and `MessageService` exactly as on Paper and Velocity, and let this render.
 *
 * Rendering is legacy section-sign, which is what a Bedrock client's chat understands. That is a
 * lossy step (hex colours collapse to the nearest legacy colour, hover and click events are dropped),
 * so it belongs here at the boundary rather than anywhere a feature can reach it.
 */
private val legacy: LegacyComponentSerializer = LegacyComponentSerializer.legacySection()

/** Render [this] to the legacy section-sign string Geyser's `sendMessage` accepts. */
fun Component.toGeyserString(): String = legacy.serialize(this)

/**
 * The locale to render for this source: their **persistent, cross-server override** if set, else the
 * locale Geyser reports for the connection (`en_US` form), else the installed default. Same
 * resolution order as `Player.resolvedLocale()` on Paper, reading the same [Locales] store.
 */
fun CommandSource.resolvedLocale(): Locale {
    val override = playerUuid()?.let { Locales.store?.cached(it) }
    if (override != null) return override
    val tag: String? = locale()
    val reported = tag?.takeIf { it.isNotEmpty() }?.let { Locale.forLanguageTag(it.replace('_', '-')) }
    return reported?.takeIf { it.language.isNotEmpty() } ?: Messages.service()?.defaultLocale ?: Locale.US
}

/** Send an Adventure [message], rendered for this source. */
fun CommandSource.sendMessage(message: Component) = sendMessage(message.toGeyserString())

/** Send [key] rendered in this source's locale, wrapped in the shared [CommonMessages] base prefix. */
fun CommandSource.sendLocalized(key: String, vararg resolvers: TagResolver) =
    sendMessage(CommonMessages.message(Messages.get(resolvedLocale(), key, *resolvers)))

/** The localized "you may not do that" ack. */
fun CommandSource.sendNoPermission() = sendMessage(CommonMessages.noPermission(resolvedLocale()))

/** Render [key] in [source]'s resolved locale (override ?: reported; with fallback chain). */
fun MessageService.render(source: CommandSource, key: String, vararg resolvers: TagResolver): Component =
    render(source.resolvedLocale(), key, *resolvers)

/** Render [key] in [source]'s locale and send it wrapped in the shared base prefix. */
fun MessageService.send(source: CommandSource, key: String, vararg resolvers: TagResolver) =
    source.sendMessage(CommonMessages.message(render(source, key, *resolvers)))
