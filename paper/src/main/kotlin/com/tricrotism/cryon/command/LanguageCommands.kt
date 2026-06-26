package com.tricrotism.cryon.command

import com.tricrotism.cryon.common.locale.LangScanner
import com.tricrotism.cryon.common.locale.Locales
import com.tricrotism.cryon.common.locale.MessageService
import com.tricrotism.cryon.common.text.CommonMessages
import com.tricrotism.cryon.paper.api.command.Arg
import com.tricrotism.cryon.paper.api.command.Command
import com.tricrotism.cryon.paper.api.command.Subcommand
import com.tricrotism.cryon.paper.api.extension.clearLanguage
import com.tricrotism.cryon.paper.api.extension.resolvedLocale
import com.tricrotism.cryon.paper.api.extension.setLanguage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Sound
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.slf4j.LoggerFactory
import java.util.*

/**
 * `/language [set <code> | clear]` — self-service player language override, annotation-defined, backed
 * by the persistent, cross-server `PlayerLocaleStore`. `set` confirms in the new language; `clear`
 * confirms in the client locale. Messages live in the core's `lang/` bundles.
 */
@Command("language", "Set your language preference", "lang")
class LanguageCommands(private val messages: MessageService) {

    private val log = LoggerFactory.getLogger("Cryon")

    @Subcommand
    fun current(sender: CommandSender) {
        val player = player(sender) ?: return
        player.sendMessage(
            CommonMessages.info(
                messages.render(
                    player.resolvedLocale(),
                    "language.current",
                    Placeholder.unparsed("locale", player.resolvedLocale().toString())
                )
            )
        )
    }

    @Subcommand("set")
    fun set(sender: CommandSender, @Arg("code", suggests = "locales") code: String) {
        val player = player(sender) ?: return
        val locale = parse(code)
        if (locale == null) {
            player.sendMessage(
                CommonMessages.error(
                    messages.render(
                        player.resolvedLocale(),
                        "language.invalid",
                        Placeholder.unparsed("input", code)
                    )
                )
            )
            return
        }
        player.setLanguage(locale).exceptionally { log.warn("Failed to persist locale for {}", player.name, it); null }
        player.sendMessage(
            CommonMessages.success(
                messages.render(
                    locale,
                    "language.set",
                    Placeholder.unparsed("locale", locale.toString())
                )
            )
        )
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f)
    }

    @Subcommand("clear")
    fun clear(sender: CommandSender) {
        val player = player(sender) ?: return
        player.clearLanguage().exceptionally { log.warn("Failed to clear locale for {}", player.name, it); null }
        player.sendMessage(CommonMessages.success(messages.render(player.locale(), "language.cleared")))
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1f, 1f)
    }

    @Subcommand("reset")
    fun reset(sender: CommandSender) = clear(sender)

    /** Suggester referenced by `@Arg(suggests = "locales")`. */
    @Suppress("unused")
    fun locales(): Collection<String> = COMMON_LOCALES

    private fun player(sender: CommandSender): Player? {
        if (sender !is Player) {
            sender.sendMessage(messages.render(CommonMessages.defaultLocale, "language.player_only"))
            return null
        }
        if (Locales.store == null) {
            sender.sendMessage(CommonMessages.error(messages.render(sender.resolvedLocale(), "language.unavailable")))
            return null
        }
        return sender
    }

    private fun parse(code: String): Locale? {
        val locale = runCatching { LangScanner.parseLocale(code.replace('-', '_')) }.getOrNull() ?: return null
        return locale.takeIf { it.language.isNotBlank() }
    }

    private companion object {
        private val COMMON_LOCALES =
            listOf("en_US", "en_GB", "de_DE", "es_ES", "fr_FR", "pt_BR", "ru_RU", "zh_CN", "ja_JP")
    }
}
