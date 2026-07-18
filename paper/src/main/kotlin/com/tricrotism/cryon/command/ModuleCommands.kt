package com.tricrotism.cryon.command

import com.tricrotism.cryon.common.flag.FeatureFlags
import com.tricrotism.cryon.common.module.ModuleManager
import com.tricrotism.cryon.common.module.ModuleState
import com.tricrotism.cryon.common.text.CommonMessages
import com.tricrotism.cryon.common.text.Mini
import com.tricrotism.cryon.module.ModuleLoader
import com.tricrotism.cryon.network.NetworkStatus
import com.tricrotism.cryon.paper.api.command.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.io.File
import java.util.*

/**
 * `/cryon modules | info <id> | enable <id> | disable <id> | reload <id>` — runtime *lifecycle*;
 * `load <jar> | unload <id> | scan` — jar-level *hot-swap*; `reload-api` — cascade-reload the
 * shared `api/` contract layer plus every module; `network` — this server's deployment shape; and
 * the feature kill switches:
 * `flags [scope]`, `flag enable|disable|clear <feature> [scope]`, `flag status <feature> [player]`,
 * `flag delete <feature>`, `flag reload` — where scope is `global` (default), a server name, or
 * `player:<name>`. The built-in module manager, annotation-defined and gated by `cryon.admin`.
 * `<id>`/`<jar>` tab-complete from the live state. Admin-facing English output.
 */
@Command("cryon", "Cryon module manager")
@Permission("cryon.admin")
class ModuleCommands(
    private val modules: ModuleManager,
    private val loader: ModuleLoader,
    private val flags: FeatureFlags,
    private val commands: CommandService,
    private val network: NetworkStatus,
) {

    @Subcommand
    fun overview(sender: CommandSender) = list(sender)

    /** What this server was told to be, what it actually is, and any way the two disagree. */
    @Subcommand("network")
    fun network(sender: CommandSender) {
        val identity = network.identity
        sender.sendMessage(Mini.format("<off_white>Network"))
        line(sender, "Mode", identity.mode.name.lowercase())
        line(sender, "Family", identity.family)
        line(sender, "Instance", identity.instanceId)
        line(sender, "Transport", network.transport)
        line(sender, "Database", if (network.persistent) "on" else "off")
        line(sender, "Live in family", network.familySize().toString())

        val warnings = network.warnings()
        if (warnings.isEmpty()) {
            sender.sendMessage(Mini.format("  <success>Deployment matches the declared mode."))
            return
        }
        for (warning in warnings) {
            sender.sendMessage(
                Mini.format("  <error>! <text>", Placeholder.unparsed("text", warning))
            )
        }
    }

    private fun line(sender: CommandSender, label: String, value: String) {
        sender.sendMessage(
            Mini.format(
                "  <slate_gray><label>:</slate_gray> <highlight><value>",
                Placeholder.unparsed("label", label),
                Placeholder.unparsed("value", value),
            )
        )
    }

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
        printCommands(sender, id)
    }

    /** List the module's registered commands: name, aliases, description, and per-subcommand usages. */
    private fun printCommands(sender: CommandSender, id: String) {
        val descriptors = commands.describe(id)
        if (descriptors.isEmpty()) {
            sender.sendMessage(Mini.format("  <slate_gray>No commands."))
            return
        }
        sender.sendMessage(Mini.format("  <off_white>Commands:"))
        for (descriptor in descriptors) {
            sender.sendMessage(commandHeader(descriptor))
            if (descriptor.description.isNotEmpty()) {
                sender.sendMessage(
                    Mini.format(
                        "      <slate_gray><desc></slate_gray>",
                        Placeholder.unparsed("desc", descriptor.description),
                    )
                )
            }
            for (usage in descriptor.usages) {
                sender.sendMessage(
                    Mini.format(
                        "      <sky_blue><usage></sky_blue>",
                        Placeholder.unparsed("usage", usage)
                    )
                )
            }
        }
    }

    /** `• /f (alias: faction) [cryon.admin]`: the command name, its aliases, and permission gate. */
    private fun commandHeader(descriptor: CommandDescriptor): Component {
        val parts = mutableListOf(
            Mini.format(
                "  <slate_gray>•</slate_gray> <highlight>/<name></highlight>",
                Placeholder.unparsed("name", descriptor.name)
            ),
        )
        if (descriptor.aliases.isNotEmpty()) {
            parts += Mini.format(
                " <slate_gray>(alias: <aliases>)</slate_gray>",
                Placeholder.unparsed("aliases", descriptor.aliases.joinToString(", ")),
            )
        }
        descriptor.permission?.let {
            parts += Mini.format(" <gold>[<perm>]</gold>", Placeholder.unparsed("perm", it))
        }
        return Component.textOfChildren(*parts.toTypedArray())
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

    @Subcommand("flags")
    fun flagsAll(sender: CommandSender) {
        val scopes = flags.scopes()
        if (scopes.isEmpty()) {
            sender.sendMessage(CommonMessages.info(Mini.format("<off_white>No feature flags registered.")))
            return
        }
        sender.sendMessage(CommonMessages.info(Mini.format("<off_white>Feature flags by scope:")))
        for ((scope, entries) in scopes) {
            sender.sendMessage(
                Mini.format(
                    "  <u><slate_gray><scope></slate_gray></u>",
                    Placeholder.unparsed("scope", scopeLabel(scope))
                )
            )
            for ((feature, enabled) in entries) sender.sendMessage(flagLine(scope, feature, enabled))
        }
    }

    @Subcommand("flags")
    fun flagsScoped(sender: CommandSender, @Arg("scope", suggests = "flagScopes") @Greedy scope: String) {
        val resolved = resolveScope(sender, scope) ?: return
        val entries = flags.scopes()[resolved]
        if (entries.isNullOrEmpty()) {
            sender.sendMessage(
                CommonMessages.info(
                    line(
                        "<off_white>No overrides for <highlight><id></highlight>.",
                        scopeLabel(resolved)
                    )
                )
            )
            return
        }
        sender.sendMessage(
            CommonMessages.info(
                line(
                    "<off_white>Overrides for <highlight><id></highlight>:",
                    scopeLabel(resolved)
                )
            )
        )
        for ((feature, enabled) in entries) sender.sendMessage(flagLine(resolved, feature, enabled))
    }

    @Subcommand("flag", "enable")
    fun flagEnable(sender: CommandSender, @Arg("feature", suggests = "flagIds") feature: String) =
        setFlag(sender, feature, FeatureFlags.GLOBAL_SCOPE, enabled = true)

    @Subcommand("flag", "enable")
    fun flagEnableScoped(
        sender: CommandSender,
        @Arg("feature", suggests = "flagIds") feature: String,
        @Arg("scope", suggests = "flagScopes") @Greedy scope: String,
    ) = setFlag(sender, feature, scope, enabled = true)

    @Subcommand("flag", "disable")
    fun flagDisable(sender: CommandSender, @Arg("feature", suggests = "flagIds") feature: String) =
        setFlag(sender, feature, FeatureFlags.GLOBAL_SCOPE, enabled = false)

    @Subcommand("flag", "disable")
    fun flagDisableScoped(
        sender: CommandSender,
        @Arg("feature", suggests = "flagIds") feature: String,
        @Arg("scope", suggests = "flagScopes") @Greedy scope: String,
    ) = setFlag(sender, feature, scope, enabled = false)

    @Subcommand("flag", "clear")
    fun flagClear(sender: CommandSender, @Arg("feature", suggests = "flagIds") feature: String) =
        clearFlag(sender, feature, FeatureFlags.GLOBAL_SCOPE)

    @Subcommand("flag", "clear")
    fun flagClearScoped(
        sender: CommandSender,
        @Arg("feature", suggests = "flagIds") feature: String,
        @Arg("scope", suggests = "flagScopes") @Greedy scope: String,
    ) = clearFlag(sender, feature, scope)

    @Subcommand("flag", "status")
    fun flagStatus(sender: CommandSender, @Arg("feature", suggests = "flagIds") feature: String) =
        printStatus(sender, feature, null, null)

    @Subcommand("flag", "status")
    fun flagStatusPlayer(
        sender: CommandSender,
        @Arg("feature", suggests = "flagIds") feature: String,
        @Arg("player", suggests = "onlinePlayerNames") player: String,
    ) {
        val uuid = resolvePlayerId(sender, player) ?: return
        printStatus(sender, feature, uuid, player)
    }

    @Subcommand("flag", "delete")
    fun flagDelete(sender: CommandSender, @Arg("feature", suggests = "flagIds") feature: String) {
        if ((sender as? Player)?.uniqueId != DELETE_AUTHORIZED) {
            sender.sendMessage(CommonMessages.error(Mini.format("<off_white>You are not authorised to delete feature flags.")))
            return
        }
        flags.delete(feature)
        sender.sendMessage(
            CommonMessages.success(
                line(
                    "<off_white>Permanently deleted <highlight><id></highlight> from every scope.",
                    feature.uppercase()
                )
            )
        )
    }

    @Subcommand("flag", "reload")
    fun flagReload(sender: CommandSender) {
        if (flags.reload()) {
            sender.sendMessage(CommonMessages.success(Mini.format("<off_white>Reloading feature flags from the database.")))
        } else {
            sender.sendMessage(CommonMessages.warn(Mini.format("<off_white>No database configured — flags are in-memory only, nothing to reload.")))
        }
    }

    /** Suggester referenced by `@Arg(suggests = "moduleIds")`. */
    @Suppress("unused")
    fun moduleIds(): Collection<String> = modules.ids()

    /** Suggester for flag features — every registered/overridden flag ID. */
    @Suppress("unused")
    fun flagIds(): Collection<String> = flags.features()

    /** Suggester for flag scopes: global, this server, and `player:<name>` for everyone online. */
    @Suppress("unused")
    fun flagScopes(): Collection<String> = buildList {
        add(FeatureFlags.GLOBAL_SCOPE)
        add(flags.serverName)
        Bukkit.getOnlinePlayers().forEach { add(FeatureFlags.PLAYER_SCOPE_PREFIX + it.name) }
    }

    /** Suggester for player arguments. */
    @Suppress("unused")
    fun onlinePlayerNames(): Collection<String> = Bukkit.getOnlinePlayers().map { it.name }

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

    /** Turn a flag on/off in a scope, ack with what happened where. */
    private fun setFlag(sender: CommandSender, feature: String, rawScope: String, enabled: Boolean) {
        val scope = resolveScope(sender, rawScope) ?: return
        flags.set(scope, feature, enabled)
        sender.sendMessage(
            CommonMessages.success(
                Mini.format(
                    "<off_white>Turned <highlight><feature></highlight> <state> <off_white>for <highlight><label></highlight>.",
                    Placeholder.unparsed("feature", feature.uppercase()),
                    Placeholder.parsed("state", if (enabled) "<emerald>ON" else "<scarlet>OFF"),
                    Placeholder.unparsed(
                        "label",
                        if (scope == FeatureFlags.GLOBAL_SCOPE) "everyone" else scopeLabel(scope)
                    ),
                )
            )
        )
    }

    private fun clearFlag(sender: CommandSender, feature: String, rawScope: String) {
        val scope = resolveScope(sender, rawScope) ?: return
        val template =
            if (flags.remove(
                    scope,
                    feature
                )
            ) "<off_white>Cleared the <highlight><label></highlight> <off_white>entry for <highlight><feature></highlight><off_white> — it falls back to the next layer."
            else "<off_white><highlight><feature></highlight> <off_white>has no entry for <highlight><label></highlight><off_white>."
        sender.sendMessage(
            CommonMessages.info(
                Mini.format(
                    template,
                    Placeholder.unparsed("feature", feature.uppercase()),
                    Placeholder.unparsed("label", scopeLabel(scope)),
                )
            )
        )
    }

    /** The layered status breakdown: effective result, then each layer's entry (or its silence). */
    private fun printStatus(sender: CommandSender, feature: String, player: UUID?, playerName: String?) {
        sender.sendMessage(
            CommonMessages.info(
                line(
                    "<off_white>Status for <highlight><id></highlight>:",
                    feature.uppercase()
                )
            )
        )
        sender.sendMessage(layerLine("Result", flags.isEnabled(feature, player)))
        if (player != null) {
            sender.sendMessage(layerLine("Player ($playerName)", flags.override(flags.playerScope(player), feature)))
        }
        sender.sendMessage(layerLine("Server (${flags.serverName})", flags.override(flags.serverName, feature)))
        sender.sendMessage(layerLine("Global", flags.override(FeatureFlags.GLOBAL_SCOPE, feature)))
    }

    private fun layerLine(label: String, value: Boolean?): Component = Mini.format(
        "  <slate_gray><label>:</slate_gray> <state>",
        Placeholder.unparsed("label", label),
        Placeholder.parsed(
            "state",
            when (value) {
                null -> "<slate_gray>no entry"
                true -> "<emerald>ON"
                false -> "<scarlet>OFF"
            },
        ),
    )

    /** One flag row with scope-targeted toggle/clear buttons. */
    private fun flagLine(scope: String, feature: String, enabled: Boolean): Component {
        val base = Mini.format(
            "    <slate_gray>•</slate_gray> <off_white><feature></off_white> <state> ",
            Placeholder.unparsed("feature", feature),
            Placeholder.parsed("state", if (enabled) "<emerald>ON" else "<scarlet>OFF"),
        )
        val arg = commandScope(scope) ?: return base
        val toggle = if (enabled) {
            button(
                "■",
                "scarlet",
                "/cryon flag disable $feature $arg",
                actionHover("scarlet", "■ Disable", "turn off", feature)
            )
        } else {
            button(
                "▶",
                "emerald",
                "/cryon flag enable $feature $arg",
                actionHover("emerald", "▶ Enable", "turn on", feature)
            )
        }
        return Component.textOfChildren(
            base,
            toggle,
            Component.space(),
            button(
                "↺",
                "sky_blue",
                "/cryon flag clear $feature $arg",
                actionHover("sky_blue", "↺ Clear", "clear this entry for", feature)
            ),
        )
    }

    /** Resolve a typed scope — `global`, a server name, or `player:<name>` — to its storage key. */
    private fun resolveScope(sender: CommandSender, raw: String): String? {
        val trimmed = raw.trim()
        if (!trimmed.lowercase().startsWith(FeatureFlags.PLAYER_SCOPE_PREFIX)) return trimmed
        val name = trimmed.substring(FeatureFlags.PLAYER_SCOPE_PREFIX.length)
        return resolvePlayerId(sender, name)?.let(flags::playerScope)
    }

    /** An online or previously-seen player's UUID, or ack the sender that they're unknown. */
    private fun resolvePlayerId(sender: CommandSender, name: String): UUID? {
        val uuid = Bukkit.getPlayerExact(name)?.uniqueId ?: Bukkit.getOfflinePlayerIfCached(name)?.uniqueId
        if (uuid == null) sender.sendMessage(CommonMessages.errorPlayer(name))
        return uuid
    }

    /** The scope argument that reaches [scope] from a command, or null for an unresolvable player scope. */
    private fun commandScope(scope: String): String? {
        if (!scope.startsWith(FeatureFlags.PLAYER_SCOPE_PREFIX)) return scope
        val uuid = runCatching { UUID.fromString(scope.substring(FeatureFlags.PLAYER_SCOPE_PREFIX.length)) }.getOrNull()
        val name = uuid?.let { Bukkit.getOfflinePlayer(it).name } ?: return null
        return FeatureFlags.PLAYER_SCOPE_PREFIX + name
    }

    /** `player:<uuid>` scopes display as `player <name>`; other scopes as themselves. */
    private fun scopeLabel(scope: String): String {
        if (!scope.startsWith(FeatureFlags.PLAYER_SCOPE_PREFIX)) return scope
        val raw = scope.substring(FeatureFlags.PLAYER_SCOPE_PREFIX.length)
        val name = runCatching { UUID.fromString(raw) }.getOrNull()?.let { Bukkit.getOfflinePlayer(it).name }
        return "player ${name ?: raw}"
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

    private companion object {
        /** The only account allowed to permanently delete a flag from every scope. Mainly debug */
        private val DELETE_AUTHORIZED: UUID = UUID.fromString("9cce3a11-63e5-4ece-8ba6-cf6c8b5557c8")
    }
}
