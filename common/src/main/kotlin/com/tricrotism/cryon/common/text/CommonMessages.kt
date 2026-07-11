package com.tricrotism.cryon.common.text

import com.tricrotism.cryon.common.locale.Messages
import com.tricrotism.cryon.common.text.CommonMessages.alert
import com.tricrotism.cryon.common.text.CommonMessages.basic
import com.tricrotism.cryon.common.text.CommonMessages.defaultLocale
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import java.util.*

/**
 * Prefixed message builder: a coloured [MessageType] **icon** + a palette-aware body. Bodies stay
 * **localized** — the canned phrases resolve through [Messages] by `cryon.common.*` key, English
 * inline as fallback (overridable via any `lang/<locale>.properties`). Prefix icons are
 * language-neutral, so only the canned phrases take a [Locale] (default [defaultLocale]); the paper
 * send extensions pass the player's locale.
 */
object CommonMessages {

    var defaultLocale: Locale = Locale.US

    /** Soft separator for [basic]/[alert]. */
    val ARROWS: Component = Component.text("»").color(CryonPalette.SLATE_GRAY)

    const val KEY_NO_PERMISSION = "cryon.common.no_permission"
    const val KEY_NEVER_JOINED = "cryon.common.never_joined"
    const val KEY_NOT_ONLINE = "cryon.common.not_online"
    const val KEY_INVALID_AMOUNT = "cryon.common.invalid_amount"
    const val KEY_NOT_ENOUGH = "cryon.common.not_enough"
    const val KEY_FEATURE_DISABLED = "cryon.common.feature_disabled"

    fun message(type: MessageType, content: Component): Component =
        Component.empty().append(type.prefix).appendSpace().append(content)

    fun message(type: MessageType, content: String): Component =
        message(type, Mini.format("<off_white>$content"))

    fun error(content: Component): Component = message(MessageType.ERROR, content)
    fun error(content: String): Component = message(MessageType.ERROR, content)
    fun success(content: Component): Component = message(MessageType.SUCCESS, content)
    fun success(content: String): Component = message(MessageType.SUCCESS, content)
    fun info(content: Component): Component = message(MessageType.INFO, content)
    fun info(content: String): Component = message(MessageType.INFO, content)
    fun warn(content: Component): Component = message(MessageType.WARN, content)
    fun warn(content: String): Component = message(MessageType.WARN, content)

    fun errorWithId(content: Component, id: String): Component =
        message(MessageType.ERROR, content).append(Mini.format(" <slate_gray>(<id>)", Placeholder.unparsed("id", id)))

    fun errorWithId(content: String, id: String): Component =
        errorWithId(Mini.format("<off_white>$content"), id)

    fun noPermission(locale: Locale = defaultLocale): Component =
        error(Messages.getOr(locale, KEY_NO_PERMISSION, "<off_white>You do not have access to this command."))

    fun featureDisabled(feature: String, locale: Locale = defaultLocale): Component =
        error(
            Messages.getOr(
                locale, KEY_FEATURE_DISABLED,
                "<crimson><feature></crimson> <off_white>is currently disabled.",
                Placeholder.unparsed("feature", prettyFlagName(feature)),
            )
        )

    private fun prettyFlagName(feature: String): String =
        feature.lowercase().split('_').filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

    /** "<name> has never joined the server before!" */
    fun errorPlayer(name: String, locale: Locale = defaultLocale): Component =
        error(
            Messages.getOr(
                locale, KEY_NEVER_JOINED,
                "<highlight_blue><name></highlight_blue> <off_white>has never joined the server before!",
                Placeholder.unparsed("name", name),
            )
        )

    /** "<name> is not online!" */
    fun notOnline(name: String, locale: Locale = defaultLocale): Component =
        error(
            Messages.getOr(
                locale, KEY_NOT_ONLINE,
                "<highlight_blue><name></highlight_blue> <off_white>is not online!",
                Placeholder.unparsed("name", name),
            )
        )

    /** "<amount> is not a valid amount!" */
    fun errorAmount(amount: String, locale: Locale = defaultLocale): Component =
        error(
            Messages.getOr(
                locale, KEY_INVALID_AMOUNT,
                "<highlight_blue><amount></highlight_blue> <off_white>is not a valid amount!",
                Placeholder.unparsed("amount", amount),
            )
        )

    fun notEnoughCurrency(currency: Component, locale: Locale = defaultLocale): Component =
        error(
            Messages.getOr(
                locale, KEY_NOT_ENOUGH,
                "<off_white>You do not have enough <currency><off_white>!",
                Placeholder.component("currency", currency),
            )
        )

    fun notEnoughCurrency(currency: String, locale: Locale = defaultLocale): Component =
        notEnoughCurrency(Mini.format("<off_white>$currency"), locale)

    /** Generic prefixed message with a custom prefix component and the `»` separator. */
    fun basic(prefix: Component, content: Component): Component =
        Component.text()
            .append(prefix)
            .appendSpace()
            .append(ARROWS)
            .appendSpace()
            .append(content)
            .build()

    fun alert(content: Component): Component =
        basic(Component.text("✦").color(CryonPalette.SCARLET).decoration(TextDecoration.BOLD, true), content)

    fun alert(content: String): Component =
        alert(Mini.format("<off_white>$content"))
}
