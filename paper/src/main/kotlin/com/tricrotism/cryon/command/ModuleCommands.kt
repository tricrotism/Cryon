package com.tricrotism.cryon.command

import com.tricrotism.cryon.common.module.ModuleManager
import com.tricrotism.cryon.common.module.ModuleState
import com.tricrotism.cryon.common.text.CommonMessages
import com.tricrotism.cryon.common.text.Mini
import com.tricrotism.cryon.paper.api.command.Arg
import com.tricrotism.cryon.paper.api.command.Command
import com.tricrotism.cryon.paper.api.command.Permission
import com.tricrotism.cryon.paper.api.command.Subcommand
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.command.CommandSender

/**
 * `/cryon modules | info <id> | enable <id> | disable <id> | reload <id>` — the built-in module
 * manager, annotation-defined and gated by `cryon.admin`. `<id>` tab-completes from the live module
 * list. Drives runtime lifecycle (no jar reload). Admin-facing English output.
 */
@Command("cryon", "Cryon module manager")
@Permission("cryon.admin")
class ModuleCommands(private val modules: ModuleManager) {

    @Subcommand
    fun overview(sender: CommandSender) = list(sender)

    @Subcommand("modules")
    fun modulesList(sender: CommandSender) = list(sender)

    @Subcommand("list")
    fun listAlias(sender: CommandSender) = list(sender)

    @Subcommand("info")
    fun info(sender: CommandSender, @Arg("id", suggests = "moduleIds") id: String) {
        val state = modules.state(id)
        if (state == null) {
            sender.sendMessage(CommonMessages.error(notFound(id)))
            return
        }
        sender.sendMessage(
            CommonMessages.info(
                Mini.format(
                    "<off_white>Module <highlight><id></highlight> is <state>",
                    Placeholder.unparsed("id", id),
                    Placeholder.component("state", stateLabel(state)),
                )
            )
        )
    }

    @Subcommand("enable")
    fun enable(sender: CommandSender, @Arg("id", suggests = "moduleIds") id: String) = toggle(sender, id, enable = true)

    @Subcommand("disable")
    fun disable(sender: CommandSender, @Arg("id", suggests = "moduleIds") id: String) =
        toggle(sender, id, enable = false)

    @Subcommand("reload")
    fun reloadModule(sender: CommandSender, @Arg("id", suggests = "moduleIds") id: String) {
        if (!modules.has(id)) {
            sender.sendMessage(CommonMessages.error(notFound(id)))
            return
        }
        if (modules.reload(id)) {
            sender.sendMessage(CommonMessages.success(line("<off_white>Reloaded <highlight><id></highlight>.", id)))
        } else {
            sender.sendMessage(
                CommonMessages.error(
                    line(
                        "<off_white>Failed to reload <highlight><id></highlight> — check console.",
                        id
                    )
                )
            )
        }
    }

    /** Suggester referenced by `@Arg(suggests = "moduleIds")`. */
    @Suppress("unused")
    fun moduleIds(): Collection<String> = modules.ids()

    private fun list(sender: CommandSender) {
        val states = modules.states()
        sender.sendMessage(
            CommonMessages.info(
                Mini.format(
                    "<off_white>Loaded modules <highlight>(<count>)</highlight>:",
                    Placeholder.unparsed("count", states.size.toString())
                )
            )
        )
        if (states.isEmpty()) {
            sender.sendMessage(Mini.format("  <slate_gray>none"))
            return
        }
        for ((id, state) in states) {
            sender.sendMessage(
                Mini.format(
                    "  <slate_gray>•</slate_gray> <off_white><id> <state>",
                    Placeholder.unparsed("id", id),
                    Placeholder.component("state", stateLabel(state)),
                )
            )
        }
    }

    private fun toggle(sender: CommandSender, id: String, enable: Boolean) {
        if (!modules.has(id)) {
            sender.sendMessage(CommonMessages.error(notFound(id)))
            return
        }
        val verb = if (enable) "enabled" else "disabled"
        val changed = if (enable) modules.enable(id) else modules.disable(id)
        if (changed) {
            sender.sendMessage(CommonMessages.success(line("<off_white>Module <highlight><id></highlight> $verb.", id)))
        } else {
            sender.sendMessage(
                CommonMessages.warn(
                    line(
                        "<off_white>Module <highlight><id></highlight> could not be $verb (already $verb, or failed).",
                        id
                    )
                )
            )
        }
    }

    private fun line(template: String, id: String): Component = Mini.format(template, Placeholder.unparsed("id", id))

    private fun notFound(id: String): Component = line("<off_white>No module <highlight><id></highlight>.", id)

    private fun stateLabel(state: ModuleState): Component {
        val color = when (state) {
            ModuleState.ENABLED -> "<emerald>"
            ModuleState.DISABLED -> "<slate_gray>"
            ModuleState.FAILED -> "<scarlet>"
            ModuleState.LOADED -> "<sky_blue>"
            ModuleState.REGISTERED -> "<gold>"
        }
        return Mini.format("$color${state.name}")
    }
}
