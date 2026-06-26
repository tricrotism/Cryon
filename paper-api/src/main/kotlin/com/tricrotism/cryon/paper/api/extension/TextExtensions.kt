package com.tricrotism.cryon.paper.api.extension

import com.tricrotism.cryon.common.text.CommonMessages
import com.tricrotism.cryon.common.text.Mini
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.entity.Player

/** Parse a MiniMessage string with the Cryon palette tags (cached when no resolvers are passed). */
fun String.mm(vararg resolvers: TagResolver): Component =
    if (resolvers.isEmpty()) Mini.format(this) else Mini.format(this, *resolvers)

// Prefixed acks for any audience (player, console, …). The icon prefix is language-neutral, so
// these need no locale; only the canned phrases below (which have localized bodies) take one.
fun Audience.sendError(message: String) = sendMessage(CommonMessages.error(message))
fun Audience.sendError(message: Component) = sendMessage(CommonMessages.error(message))
fun Audience.sendSuccess(message: String) = sendMessage(CommonMessages.success(message))
fun Audience.sendSuccess(message: Component) = sendMessage(CommonMessages.success(message))
fun Audience.sendInfo(message: String) = sendMessage(CommonMessages.info(message))
fun Audience.sendInfo(message: Component) = sendMessage(CommonMessages.info(message))
fun Audience.sendWarn(message: String) = sendMessage(CommonMessages.warn(message))
fun Audience.sendWarn(message: Component) = sendMessage(CommonMessages.warn(message))

// Fully localized canned phrases (localized body in the player's resolved locale).
fun Player.sendNoPermission() = sendMessage(CommonMessages.noPermission(resolvedLocale()))
fun Player.sendUnknownPlayer(name: String) = sendMessage(CommonMessages.errorPlayer(name, resolvedLocale()))
fun Player.sendNotOnline(name: String) = sendMessage(CommonMessages.notOnline(name, resolvedLocale()))
fun Player.sendInvalidAmount(amount: String) = sendMessage(CommonMessages.errorAmount(amount, resolvedLocale()))
fun Player.sendNotEnough(currency: Component) =
    sendMessage(CommonMessages.notEnoughCurrency(currency, resolvedLocale()))
