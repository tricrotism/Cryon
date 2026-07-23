package com.tricrotism.cryon.common.text

import com.tricrotism.cryon.common.locale.Messages
import com.tricrotism.cryon.common.text.CommonMessages.KEY_PREFIX
import com.tricrotism.cryon.common.text.CommonMessages.alert
import com.tricrotism.cryon.common.text.CommonMessages.basic
import com.tricrotism.cryon.common.text.CommonMessages.defaultLocale
import com.tricrotism.cryon.common.text.CommonMessages.error
import com.tricrotism.cryon.common.text.CommonMessages.info
import com.tricrotism.cryon.common.text.CommonMessages.success
import com.tricrotism.cryon.common.text.CommonMessages.warn
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import java.util.*

/**
 * Message builder: a single optional prefix + a palette-aware body. Bodies stay **localized** — the
 * canned phrases resolve through [Messages] by `cryon.common.*` key, English inline as fallback
 * (overridable via any `lang/<locale>.properties`). The prefix is one shared, lang-driven base
 * (`cryon.common.prefix`, resolved in [defaultLocale], blank by default so there is no glyph); it is
 * not per-message-type. The canned phrases take a [Locale] (default [defaultLocale]); the paper send
 * extensions pass the player's locale.
 */
object CommonMessages {

    var defaultLocale: Locale = Locale.US

    /** Soft separator for [basic]/[alert]. */
    val ARROWS: Component = Component.text("»").color(CryonPalette.SLATE_GRAY)

    const val KEY_PREFIX = "cryon.common.prefix"
    const val KEY_NO_PERMISSION = "cryon.common.no_permission"
    const val KEY_NEVER_JOINED = "cryon.common.never_joined"
    const val KEY_NOT_ONLINE = "cryon.common.not_online"
    const val KEY_INVALID_AMOUNT = "cryon.common.invalid_amount"
    const val KEY_NOT_ENOUGH = "cryon.common.not_enough"
    const val KEY_FEATURE_DISABLED = "cryon.common.feature_disabled"

    /**
     * Prepend the shared, lang-driven base prefix ([KEY_PREFIX], resolved in [defaultLocale]) to
     * [content]. A blank prefix (the default) drops it, so the message is just its body. There is no
     * per-type styling: [error]/[success]/[info]/[warn] are call-site sugar that all render identically.
     */
    fun message(content: Component): Component {
        val prefix = Messages.rawOr(defaultLocale, KEY_PREFIX, "")
        if (prefix.isBlank()) return content
        return Component.empty().append(Mini.format(prefix)).appendSpace().append(content)
    }

    fun message(content: String): Component = message(Mini.format("<off_white>$content"))

    fun error(content: Component): Component = message(content)
    fun error(content: String): Component = message(content)
    fun success(content: Component): Component = message(content)
    fun success(content: String): Component = message(content)
    fun info(content: Component): Component = message(content)
    fun info(content: String): Component = message(content)
    fun warn(content: Component): Component = message(content)
    fun warn(content: String): Component = message(content)

    fun errorWithId(content: Component, id: String): Component =
        message(content).append(Mini.format(" <slate_gray>(<id>)", Placeholder.unparsed("id", id)))

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
