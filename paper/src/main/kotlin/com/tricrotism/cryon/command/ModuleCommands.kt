package com.tricrotism.cryon.command

import com.tricrotism.cryon.common.module.ModuleManager
import com.tricrotism.cryon.common.module.ModuleState
import com.tricrotism.cryon.common.text.CommonMessages
import com.tricrotism.cryon.common.text.Mini
import com.tricrotism.cryon.module.ModuleLoader
import com.tricrotism.cryon.paper.api.command.Arg
import com.tricrotism.cryon.paper.api.command.Command
import com.tricrotism.cryon.paper.api.command.Permission
import com.tricrotism.cryon.paper.api.command.Subcommand
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.command.CommandSender
import java.io.File

/**
 * `/cryon modules | info <id> | enable <id> | disable <id> | reload <id>` — runtime *lifecycle*;
 * `load <jar> | unload <id> | scan` — jar-level *hot-swap*; and `reload-api` — cascade-reload the
 * shared `api/` contract layer plus every module. The built-in module manager, annotation-defined and
 * gated by `cryon.admin`. `<id>`/`<jar>` tab-complete from the live state. Admin-facing English output.
 */
@Command("cryon", "Cryon module manager")
@Permission("cryon.admin")
class ModuleCommands(private val modules: ModuleManager, private val loader: ModuleLoader) {

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
            Component.textOfChildren(
                CommonMessages.info(
                    Mini.format(
                        "<off_white>Module <highlight><id></highlight> is <state> ",
                        Placeholder.unparsed("id", id),
                        Placeholder.component("state", stateLabel(state)),
                    )
                ),
                actionButtons(id, state),
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
            resyncCommands(sender)
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

    @Subcommand("load")
    fun load(sender: CommandSender, @Arg("jar", suggests = "loadableJars") jar: String) {
        val file = File(loader.modulesDir, jar)
        if (!file.isFile || !jar.endsWith(".jar")) {
            sender.sendMessage(
                CommonMessages.error(
                    line(
                        "<off_white>No jar <highlight><id></highlight> in modules/.",
                        jar
                    )
                )
            )
            return
        }
        val enabled = loader.loadJar(file)
        if (enabled.isEmpty()) {
            sender.sendMessage(
                CommonMessages.warn(
                    line(
                        "<off_white>Loaded <highlight><id></highlight> but no module enabled — check console.",
                        jar
                    )
                )
            )
            return
        }
        sender.sendMessage(
            CommonMessages.success(
                Mini.format(
                    "<off_white>Loaded <highlight><jar></highlight> — enabled <highlight><list></highlight>.",
                    Placeholder.unparsed("jar", jar),
                    Placeholder.unparsed("list", enabled.joinToString(", ")),
                )
            )
        )
        resyncCommands(sender)
    }

    @Subcommand("unload")
    fun unload(sender: CommandSender, @Arg("id", suggests = "moduleIds") id: String) {
        if (!modules.has(id)) {
            sender.sendMessage(CommonMessages.error(notFound(id)))
            return
        }
        val removed = loader.unloadModule(id)
        if (removed == null) {
            sender.sendMessage(
                CommonMessages.error(
                    line(
                        "<off_white><highlight><id></highlight> isn't a jar-loaded module.",
                        id
                    )
                )
            )
            return
        }
        sender.sendMessage(
            CommonMessages.success(
                Mini.format(
                    "<off_white>Unloaded <highlight><list></highlight>. The jar stays in modules/ — delete it to remove permanently.",
                    Placeholder.unparsed("list", removed.joinToString(", ")),
                )
            )
        )
        resyncCommands(sender)
    }

    @Subcommand("reload-api")
    fun reloadApi(sender: CommandSender) {
        val enabled = loader.reloadApi()
        sender.sendMessage(
            CommonMessages.success(
                Mini.format(
                    "<off_white>Reloaded the api/ layer and <highlight><count></highlight> module(s).",
                    Placeholder.unparsed("count", enabled.size.toString()),
                )
            )
        )
        resyncCommands(sender)
    }

    @Subcommand("scan")
    fun scan(sender: CommandSender) {
        val enabled = loader.loadNew()
        if (enabled.isEmpty()) {
            sender.sendMessage(CommonMessages.info(Mini.format("<off_white>No new feature jars to load.")))
            return
        }
        sender.sendMessage(
            CommonMessages.success(
                Mini.format(
                    "<off_white>Loaded new modules: <highlight><list></highlight>.",
                    Placeholder.unparsed("list", enabled.joinToString(", ")),
                )
            )
        )
        resyncCommands(sender)
    }

    /** Suggester referenced by `@Arg(suggests = "moduleIds")`. */
    @Suppress("unused")
    fun moduleIds(): Collection<String> = modules.ids()

    /** Suggester for `/cryon load` — jars sitting in modules/ that aren't loaded yet. */
    @Suppress("unused")
    fun loadableJars(): Collection<String> = loader.loadableJarNames()

    private fun list(sender: CommandSender) {
        val states = modules.states()
        sender.sendMessage(
            Component.textOfChildren(
                CommonMessages.info(
                    Mini.format(
                        "<off_white>Loaded modules <highlight>(<count>)</highlight>: ",
                        Placeholder.unparsed("count", states.size.toString())
                    )
                ),
                button(
                    "↻ refresh", "sky_blue", "/cryon modules",
                    Mini.format("<sky_blue><b>↻ Refresh</b></sky_blue><newline><slate_gray>Re-run this list"),
                ),
            )
        )
        if (states.isEmpty()) {
            sender.sendMessage(Mini.format("  <slate_gray>none"))
            return
        }
        for ((id, state) in states) {
            sender.sendMessage(
                Component.textOfChildren(
                    Mini.format(
                        "  <slate_gray>•</slate_gray> <off_white><id></off_white> <state> ",
                        Placeholder.unparsed("id", id),
                        Placeholder.component("state", stateLabel(state)),
                    ),
                    actionButtons(id, state),
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
            resyncCommands(sender)
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

    /** The clickable action row shown after a module — a state-aware toggle plus reload and info. */
    private fun actionButtons(id: String, state: ModuleState): Component {
        val toggle = if (state == ModuleState.ENABLED) {
            button("■", "scarlet", "/cryon disable $id", actionHover("scarlet", "■ Disable", "disable", id))
        } else {
            button("▶", "emerald", "/cryon enable $id", actionHover("emerald", "▶ Enable", "enable", id))
        }
        return Component.textOfChildren(
            toggle,
            Component.space(),
            button("↻", "sky_blue", "/cryon reload $id", actionHover("sky_blue", "↻ Reload", "reload", id)),
            Component.space(),
            button("ⓘ", "gold", "/cryon info $id", actionHover("gold", "ⓘ Info", "view details for", id)),
        )
    }

    /** A bracketed, palette-coloured label that runs [command] on click and shows [hover] on mouse-over. */
    private fun button(label: String, tag: String, command: String, hover: Component): Component =
        Mini.format("<slate_gray>[</slate_gray><$tag>$label</$tag><slate_gray>]</slate_gray>")
            .clickEvent(ClickEvent.runCommand(command))
            .hoverEvent(HoverEvent.showText(hover))

    private fun actionHover(tag: String, title: String, action: String, id: String): Component =
        Mini.format(
            "<$tag><b><t></b></$tag><newline><slate_gray>Click to $action <highlight><id></highlight>",
            Placeholder.unparsed("t", title),
            Placeholder.unparsed("id", id),
        )

    /** Push refreshed command trees so a toggled module's commands also appear/vanish in tab-complete. */
    private fun resyncCommands(sender: CommandSender) {
        sender.server.onlinePlayers.forEach { it.updateCommands() }
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
