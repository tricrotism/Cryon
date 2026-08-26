package com.tricrotism.cryon.command

import com.tricrotism.cryon.common.diagnostic.Retention
import com.tricrotism.cryon.common.flag.FeatureFlags
import com.tricrotism.cryon.common.locale.MessageService
import com.tricrotism.cryon.common.module.ModuleManager
import com.tricrotism.cryon.common.module.ModuleState
import com.tricrotism.cryon.common.module.remote.RemoteModules
import com.tricrotism.cryon.common.module.remote.UpdateResult
import com.tricrotism.cryon.common.server.PresenceEntry
import com.tricrotism.cryon.common.text.CommonMessages
import com.tricrotism.cryon.common.text.Mini
import com.tricrotism.cryon.menu.AdminMenu
import com.tricrotism.cryon.module.ModuleLoader
import com.tricrotism.cryon.network.NetworkStatus
import com.tricrotism.cryon.paper.api.command.*
import com.tricrotism.cryon.paper.api.placeholder.PlaceholderService
import com.tricrotism.cryon.paper.api.scheduler.Schedulers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.command.RemoteConsoleCommandSender
import org.bukkit.entity.Player
import java.io.File
import java.util.*

/**
 * The built-in module manager, annotation-defined and gated by `cryon.admin`.
 *
 * Lifecycle: `list | info <id> | enable <id> | disable <id> | reload <id>`. Jar-level hot-swap:
 * `load <jar> | unload <id> | scan`, plus `reload-api` for the cascade that reloads the shared `api/`
 * contract layer and every module with it. `network` prints this server's deployment shape and
 * `lang reload` re-reads the message files from disk.
 *
 * Feature kill switches: `flags [scope]`, `flag enable|disable|clear <feature> [scope]`,
 * `flag status <feature> [player]`, `flag delete <feature>`, `flag reload`. **A scope is `global`
 * (the default), `server` for this server's own pool, another server's name, or simply a player's
 * name** — `player:<name>` still parses, but nothing suggests it any more, because decorating a name
 * is work the command can do itself.
 *
 * Three things exist to keep the surface findable. Bare `/cryon` opens the menu or prints the help,
 * whichever `commands.menu` makes primary; `menu` and `help [page]` reach the other one regardless,
 * and the console always gets the help. Every unknown id answers with the nearest real one as a
 * one-click correction (see [CommandUi.unknown]) rather than only rejecting what was typed.
 *
 * `<id>`/`<jar>`/`<scope>` tab-complete from live state. Admin-facing English output.
 */
@Command("cryon", "Cryon module manager")
@Permission("cryon.admin")
class ModuleCommands(
    private val modules: ModuleManager,
    private val loader: ModuleLoader,
    private val flags: FeatureFlags,
    private val commands: CommandService,
    private val network: NetworkStatus,
    private val messages: MessageService,
    private val placeholders: PlaceholderService,
    private val menu: AdminMenu,
    private val menuFirst: Boolean,
    private val retention: Retention,
    private val remote: RemoteModules?,
    private val scope: CoroutineScope,
) {

    /**
     * What the remote poller is tracking and what it has already fetched.
     *
     * Reads only the recorded state, so it answers instantly and says nothing about the network.
     * `remote check` is the one that goes and looks.
     */
    @Subcommand("remote")
    fun remote(sender: CommandSender) {
        val poller = remote ?: run {
            sender.sendMessage(
                CommonMessages.info(
                    Mini.format("<off_white>Remote modules are off. Set <highlight>remote.enabled</highlight> in config.yml.")
                )
            )
            return
        }
        sender.sendMessage(CommonMessages.info(Mini.format("<off_white>Remote modules:")))
        for (artifact in poller.artifacts) {
            val installed = poller.installedRevision(artifact)
            sender.sendMessage(
                Mini.format(
                    "<off_white> <highlight><id></highlight> <gray>(<version>)</gray> <state>",
                    Placeholder.unparsed("id", artifact.id),
                    Placeholder.unparsed("version", artifact.resolvedVersion),
                    Placeholder.parsed(
                        "state",
                        if (installed == null) "<scarlet>never fetched" else "<emerald>fetched",
                    ),
                )
            )
        }
    }

    /**
     * Poll every configured artifact now instead of waiting for the timer.
     *
     * Whether anything downloaded then *runs* is still `modules.auto-reload`'s decision, so the
     * reply distinguishes a build that swapped from one that is merely on disk. The work is
     * launched rather than awaited because it is network I/O and the caller is on a region thread.
     */
    @Subcommand("remote", "check")
    fun remoteCheck(sender: CommandSender) {
        val poller = remote ?: run {
            sender.sendMessage(CommonMessages.error(Mini.format("<off_white>Remote modules are off.")))
            return
        }
        sender.sendMessage(CommonMessages.info(Mini.format("<off_white>Checking for module updates...")))
        scope.launch {
            val results = poller.pollAll()
            val installed = results.filterIsInstance<UpdateResult.Installed>()
            val failed = results.filterIsInstance<UpdateResult.Failed>()
            Schedulers.global {
                for ((artifact, reason) in failed) {
                    sender.sendMessage(
                        CommonMessages.error(
                            Mini.format(
                                "<off_white><id>: <reason>",
                                Placeholder.unparsed("id", artifact.id),
                                Placeholder.unparsed("reason", reason),
                            )
                        )
                    )
                }
                if (installed.isEmpty()) {
                    sender.sendMessage(
                        CommonMessages.info(Mini.format("<off_white>Everything is already up to date."))
                    )
                    return@global
                }
                for ((artifact) in installed) {
                    sender.sendMessage(
                        CommonMessages.success(
                            Mini.format(
                                "<off_white>Downloaded <highlight><id></highlight> <gray>(<jar>)</gray>",
                                Placeholder.unparsed("id", artifact.id),
                                Placeholder.unparsed("jar", artifact.fileName),
                            )
                        )
                    )
                }
            }
        }
    }

    /**
     * Bare `/cryon`. A player gets whichever surface `commands.menu` makes primary; console always
     * gets the help, having nowhere to put a window.
     */
    @Subcommand
    fun overview(sender: CommandSender) {
        if (menuFirst && sender is Player) menu.open(sender) else help(sender)
    }

    /**
     * Open the admin menu regardless of which surface is primary.
     */
    @Subcommand("menu")
    fun menu(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(CommonMessages.error(Mini.format("<off_white>The menu needs a player. Try /cryon help.")))
            return
        }
        menu.open(sender)
    }

    @Subcommand("help")
    fun help(sender: CommandSender) = help(sender, 1)

    @Subcommand("help")
    fun help(sender: CommandSender, @Arg("page") page: Int) {
        val pages = (HELP.size + HELP_PAGE_SIZE - 1) / HELP_PAGE_SIZE
        val current = page.coerceIn(1, pages)
        sender.sendMessage(
            CommonMessages.info(
                Mini.format(
                    "<off_white>Cryon commands <slate_gray>(page <p>/<n>)",
                    Placeholder.unparsed("p", current.toString()),
                    Placeholder.unparsed("n", pages.toString()),
                )
            )
        )
        var section: String? = null
        for (entry in HELP.drop((current - 1) * HELP_PAGE_SIZE).take(HELP_PAGE_SIZE)) {
            if (entry.section != section) {
                section = entry.section
                sender.sendMessage(
                    Mini.format("  <u><slate_gray><s></slate_gray></u>", Placeholder.unparsed("s", section))
                )
            }
            sender.sendMessage(CommandUi.usage(entry.path, entry.description))
        }
        sender.sendMessage(pageFooter(current, pages))
    }

    private fun pageFooter(current: Int, pages: Int): Component {
        if (pages <= 1) return Mini.format("  <slate_gray>Every command also tab-completes.")
        val parts = mutableListOf<Component>(Mini.format("  "))
        if (current > 1) {
            parts += CommandUi.button(
                "← prev", "sky_blue", "/cryon help ${current - 1}",
                CommandUi.hover("sky_blue", "Previous page", "Page ${current - 1}"),
            )
            parts += Component.space()
        }
        if (current < pages) {
            parts += CommandUi.button(
                "next →", "sky_blue", "/cryon help ${current + 1}",
                CommandUi.hover("sky_blue", "Next page", "Page ${current + 1}"),
            )
        }
        return Component.textOfChildren(*parts.toTypedArray())
    }

    /** What this server was told to be, what it actually is, and any way the two disagree. */
    @Subcommand("network")
    fun network(sender: CommandSender) {
        val identity = network.identity
        sender.sendMessage(Mini.format("<off_white>Network"))
        line(sender, "Server", identity.serverId)
        line(sender, "Expect", identity.expectation.name.lowercase().replace('_', '-'))
        line(sender, "Node", identity.nodeId)
        line(sender, "Transport", network.transport)
        line(sender, "Database", if (network.persistent) "on" else "off")
        line(sender, "Live nodes", network.nodeCount().toString())

        val servers = network.servers()
        if (servers.isNotEmpty()) {
            sender.sendMessage(Mini.format("<off_white>Game servers"))
            servers.forEach { (serverId, count) ->
                val self = if (serverId == identity.serverId) " (this one)" else ""
                line(sender, "  $serverId$self", "$count node${if (count == 1) "" else "s"}")
            }
        }
        hookup(sender, "Proxies", network.proxies())
        hookup(sender, "Geyser", network.geysers())

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

    /**
     * One line for a kind of process that announces itself rather than registering into the registry.
     *
     * "not detected" rather than "0", because zero proxies is not a measurement: it means nothing is
     * publishing, which without a shared transport is simply what one JVM looks like.
     */
    private fun hookup(sender: CommandSender, label: String, entries: List<PresenceEntry>) {
        if (entries.isEmpty()) {
            sender.sendMessage(
                Mini.format(
                    "  <slate_gray><label>:</slate_gray> <error>not detected",
                    Placeholder.unparsed("label", label),
                )
            )
            return
        }
        line(sender, label, entries.joinToString(", ") { "${it.id} (${it.detail})" })
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
            unknownModule(sender, id, "info")
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
        printDependencies(sender, id)
        printCommands(sender, id)
        printPlaceholders(sender, id)
    }

    /**
     * List what the module declared it needs, and its place in a sub-module tree. The one line that
     * answers "why is this FAILED" for a module the loader refused before running it.
     */
    private fun printDependencies(sender: CommandSender, id: String) {
        modules.parentOf(id)?.let { line(sender, "Parent", it) }
        val children = modules.childrenOf(id)
        if (children.isNotEmpty()) line(sender, "Sub-modules", children.joinToString(", "))
        val declared = modules.dependenciesOf(id)
        if (declared.isEmpty()) return
        line(
            sender,
            "Depends on",
            declared.joinToString(", ") { it.description + if (it.hard) "" else " (soft)" },
        )
    }

    /** List the PlaceholderAPI namespaces the module owns, e.g. `%afkarea_…%`. Omitted when it has none. */
    private fun printPlaceholders(sender: CommandSender, id: String) {
        val namespaces = placeholders.identifiers(id)
        if (namespaces.isEmpty()) return
        sender.sendMessage(Mini.format("  <off_white>Placeholders:"))
        for (namespace in namespaces) {
            sender.sendMessage(
                Mini.format(
                    "      <sky_blue>%<namespace>_…%</sky_blue>",
                    Placeholder.unparsed("namespace", namespace),
                )
            )
        }
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
    fun reloadModule(sender: CommandSender, @Arg("id", suggests = "moduleIds") id: String) = onLoaderThread {
        if (!modules.has(id)) {
            unknownModule(sender, id, "reload")
            return@onLoaderThread
        }
        if (modules.reload(id)) {
            sender.sendMessage(CommonMessages.success(line("<off_white>Reloaded <highlight><id></highlight>.", id)))
            commands.refresh()
        } else {
            sender.sendMessage(
                CommonMessages.error(
                    line(
                        "<off_white>Failed to reload <highlight><id></highlight>. Check console.",
                        id
                    )
                )
            )
        }
    }

    @Subcommand("load")
    fun load(sender: CommandSender, @Arg("jar", suggests = "loadableJars") jar: String) = onLoaderThread {
        val file = File(loader.modulesDir, jar)
        if (!file.isFile || !jar.endsWith(".jar")) {
            CommandUi.unknown(sender, "jar", jar, loader.loadableJarNames()) { "/cryon load $it" }
            return@onLoaderThread
        }
        val enabled = loader.loadJar(file)
        if (enabled.isEmpty()) {
            sender.sendMessage(
                CommonMessages.warn(
                    line(
                        "<off_white>Loaded <highlight><id></highlight> but no module enabled. Check console.",
                        jar
                    )
                )
            )
            return@onLoaderThread
        }
        sender.sendMessage(
            CommonMessages.success(
                Mini.format(
                    "<off_white>Loaded <highlight><jar></highlight>. Enabled <highlight><list></highlight>.",
                    Placeholder.unparsed("jar", jar),
                    Placeholder.unparsed("list", enabled.joinToString(", ")),
                )
            )
        )
    }

    @Subcommand("unload")
    fun unload(sender: CommandSender, @Arg("id", suggests = "moduleIds") id: String) = onLoaderThread {
        if (!modules.has(id)) {
            unknownModule(sender, id, "unload")
            return@onLoaderThread
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
            return@onLoaderThread
        }
        sender.sendMessage(
            CommonMessages.success(
                Mini.format(
                    "<off_white>Unloaded <highlight><list></highlight>. The jar stays in modules/. Delete it to remove permanently.",
                    Placeholder.unparsed("list", removed.joinToString(", ")),
                )
            )
        )
    }

    @Subcommand("reload-api")
    fun reloadApi(sender: CommandSender) = onLoaderThread {
        val enabled = loader.reloadApi()
        sender.sendMessage(
            CommonMessages.success(
                Mini.format(
                    "<off_white>Reloaded the api/ layer and <highlight><count></highlight> module(s).",
                    Placeholder.unparsed("count", enabled.size.toString()),
                )
            )
        )
    }

    @Subcommand("scan")
    fun scan(sender: CommandSender) = onLoaderThread {
        val enabled = loader.loadNew()
        if (enabled.isEmpty()) {
            sender.sendMessage(CommonMessages.info(Mini.format("<off_white>No new feature jars to load.")))
            return@onLoaderThread
        }
        sender.sendMessage(
            CommonMessages.success(
                Mini.format(
                    "<off_white>Loaded new modules: <highlight><list></highlight>.",
                    Placeholder.unparsed("list", enabled.joinToString(", ")),
                )
            )
        )
    }

    /**
     * What the collector has and has not reclaimed, per unloaded jar.
     *
     * The check nothing else can do for a loader framework: a jar's classes stay resident until its
     * classloader is collected, and a module that left a listener, a task or a captured lambda
     * behind pins it forever. Server-wide tooling cannot attribute this — every module is
     * `com.tricrotism.cryon.*` to a package-prefix heap histogram — so the only honest evidence is
     * watching the loader itself go away.
     *
     * **Read the trend, not one number.** A live count of 1 immediately after an unload usually just
     * means no collection has run yet; suggest a GC and re-check. A count that climbs with each
     * reload of the same jar is the leak.
     */
    @Subcommand("retention")
    fun retention(sender: CommandSender) {
        val report = retention.report()
        if (report.isEmpty()) {
            sender.sendMessage(
                CommonMessages.info(Mini.format("<off_white>Nothing unloaded yet, so nothing to watch."))
            )
            return
        }
        sender.sendMessage(CommonMessages.info(Mini.format("<off_white>Classloader retention since boot:")))
        for ((key, retained) in report.entries.sortedByDescending { it.value.live }) {
            val colour = if (retained.live > 0) "<scarlet>" else "<emerald>"
            sender.sendMessage(
                Mini.format(
                    "  <slate_gray><key></slate_gray> $colour<live></> live, <off_white><collected></off_white> collected of <off_white><total></off_white>",
                    Placeholder.unparsed("key", key.removePrefix("module-jar:")),
                    Placeholder.unparsed("live", retained.live.toString()),
                    Placeholder.unparsed("collected", retained.collected.toString()),
                    Placeholder.unparsed("total", retained.registered.toString()),
                )
            )
        }
        if (report.values.any { it.live > 0 }) {
            sender.sendMessage(
                Mini.format(
                    "<slate_gray>A live count right after an unload is normal — no collection has run. " +
                            "One that climbs across reloads of the same jar is a leak."
                )
            )
        }
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
        if (sender !is ConsoleCommandSender && sender !is RemoteConsoleCommandSender) {
            sender.sendMessage(CommonMessages.error(Mini.format("<off_white>Feature flags can only be deleted from the server console.")))
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
            sender.sendMessage(CommonMessages.warn(Mini.format("<off_white>No database configured. Flags are in-memory only, nothing to reload.")))
        }
    }

    /** Re-read every message source from disk. The admin `lang/` override and every module's bundle. */
    @Subcommand("lang", "reload")
    fun langReload(sender: CommandSender) {
        messages.reload()
        sender.sendMessage(CommonMessages.success(Mini.format("<off_white>Reloaded language files from disk.")))
    }

    /** Suggester referenced by `@Arg(suggests = "moduleIds")`. */
    @Suppress("unused")
    fun moduleIds(): Collection<String> = modules.ids()

    /** Suggester for flag features, every registered/overridden flag ID. */
    @Suppress("unused")
    fun flagIds(): Collection<String> = flags.features()

    /** Suggester for flag scopes: global, this server, and `player:<name>` for everyone online. */
    @Suppress("unused")
    fun flagScopes(): Collection<String> = buildList {
        add(FeatureFlags.GLOBAL_SCOPE)
        add(SERVER_SCOPE_KEYWORD)
        add(flags.serverId)
        Bukkit.getOnlinePlayers().forEach { add(it.name) }
    }

    /** Suggester for player arguments. */
    @Suppress("unused")
    fun onlinePlayerNames(): Collection<String> = Bukkit.getOnlinePlayers().map { it.name }

    /** Suggester for `/cryon load`. Jars sitting in modules/ that aren't loaded yet. */
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
            // Sub-modules are indented under their parent: the map is in registration order, which is
            // depth-first, so the tree reads without sorting anything here.
            sender.sendMessage(
                Component.textOfChildren(
                    Mini.format(
                        "  <indent><slate_gray>•</slate_gray> <off_white><id></off_white> <state> ",
                        Placeholder.unparsed("indent", if (modules.parentOf(id) == null) "" else "  "),
                        Placeholder.unparsed("id", id),
                        Placeholder.component("state", stateLabel(state)),
                    ),
                    actionButtons(id, state),
                )
            )
        }
    }

    private fun toggle(sender: CommandSender, id: String, enable: Boolean) = onLoaderThread {
        if (!modules.has(id)) {
            unknownModule(sender, id, if (enable) "enable" else "disable")
            return@onLoaderThread
        }
        val verb = if (enable) "enabled" else "disabled"
        val changed = if (enable) modules.enable(id) else modules.disable(id)
        if (changed && enable) modules.postLoad(id)
        if (changed) {
            sender.sendMessage(CommonMessages.success(line("<off_white>Module <highlight><id></highlight> $verb.", id)))
            commands.refresh()
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

    /** The clickable action row shown after a module, a state-aware toggle plus reload and info. */
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
            ) "<off_white>Cleared the <highlight><label></highlight> <off_white>entry for <highlight><feature></highlight><off_white>, it falls back to the next layer."
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
        sender.sendMessage(layerLine("Server (${flags.serverId})", flags.override(flags.serverId, feature)))
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

    /**
     * Resolve a typed scope to its storage key.
     *
     * Accepts `global`, `server` (or this server's own name), `player:<name>`, and a **bare player
     * name**, which is what an operator reaches for and what the old spelling made them decorate. The
     * order matters: the reserved words and the real server name win before a name is treated as a
     * player, so a server called `global` cannot be shadowed by someone logging in under that name.
     * Anything else is passed through as a literal scope, which is how another server in the pool is
     * still addressable by name from here.
     */
    private fun resolveScope(sender: CommandSender, raw: String): String? {
        val trimmed = raw.trim()
        val lower = trimmed.lowercase()
        if (lower == FeatureFlags.GLOBAL_SCOPE) return FeatureFlags.GLOBAL_SCOPE
        if (lower == SERVER_SCOPE_KEYWORD || lower == flags.serverId.lowercase()) return flags.serverId
        if (lower.startsWith(FeatureFlags.PLAYER_SCOPE_PREFIX)) {
            val name = trimmed.substring(FeatureFlags.PLAYER_SCOPE_PREFIX.length)
            return resolvePlayerId(sender, name)?.let(flags::playerScope)
        }
        Bukkit.getPlayerExact(trimmed)?.let { return flags.playerScope(it.uniqueId) }
        return trimmed
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
        return playerName(scope.substring(FeatureFlags.PLAYER_SCOPE_PREFIX.length))
    }

    /** `player:<uuid>` scopes display as `player <name>`; other scopes as themselves. */
    private fun scopeLabel(scope: String): String {
        if (!scope.startsWith(FeatureFlags.PLAYER_SCOPE_PREFIX)) return scope
        val raw = scope.substring(FeatureFlags.PLAYER_SCOPE_PREFIX.length)
        return "player ${playerName(raw) ?: raw}"
    }

    /**
     * The name behind a `player:` scope, or null if this server has never seen them.
     *
     * Never `getOfflinePlayer(uuid).name`, which reads like a getter but loads and decompresses that
     * player's `.dat` off disk: `/cryon flags` calls this once per listed row, on the main thread. The
     * online player answers for free, and the server's own profile cache answers for everyone else.
     */
    private fun playerName(rawUuid: String): String? {
        val uuid = runCatching { UUID.fromString(rawUuid) }.getOrNull() ?: return null
        Bukkit.getPlayer(uuid)?.let { return it.name }
        val profile = Bukkit.createProfile(uuid)
        return if (profile.completeFromCache()) profile.name else null
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

    /**
     * Run a module-graph mutation on the one global region thread.
     *
     * [ModuleLoader], [ModuleManager] and the command registry all hold plain maps and document
     * themselves as main-thread only. That is true on Paper, where commands dispatch on the main
     * thread, but not on Folia, where a player's command runs on their own region thread while the
     * hot-reload watcher is already dispatching through the global scheduler. Funnelling every
     * mutating entry point through one lane keeps the invariant true on both, without making four
     * maps concurrent for a path that runs a handful of times a session.
     *
     * Note this moves the command's feedback off the sender's own thread. That is fine: sending to a
     * player is one of the few APIs Folia allows from anywhere, because it only queues a packet, but
     * it is the reason nothing else in here may touch the sender beyond messaging them.
     */
    private fun onLoaderThread(body: () -> Unit) {
        Schedulers.global { body() }
    }

    private fun line(template: String, id: String): Component = Mini.format(template, Placeholder.unparsed("id", id))

    /**
     * Reject an unknown module id, offering the nearest real one as a one-click correction.
     */
    private fun unknownModule(sender: CommandSender, id: String, verb: String) =
        CommandUi.unknown(sender, "module", id, modules.ids()) { "/cryon $verb $it" }

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

    private class HelpEntry(val section: String, val path: String, val description: String)

    private companion object {

        const val HELP_PAGE_SIZE = 8

        /** Addresses this server's own pool without typing its name. */
        const val SERVER_SCOPE_KEYWORD = "server"

        /**
         * The help, grouped by what an operator is trying to do rather than by the order the methods
         * happen to be declared in. Brigadier already tab-completes every one of these; what it cannot
         * say is which of them belong together, or what any of them is for.
         */
        val HELP = listOf(
            HelpEntry("Modules", "cryon list", "Every module and its state"),
            HelpEntry("Modules", "cryon info <id>", "Commands, placeholders and state for one module"),
            HelpEntry("Modules", "cryon enable <id>", "Enable a loaded module"),
            HelpEntry("Modules", "cryon disable <id>", "Disable a running module"),
            HelpEntry("Modules", "cryon reload <id>", "Disable then re-enable a module"),
            HelpEntry("Jars", "cryon load <jar>", "Load and enable a jar from modules/"),
            HelpEntry("Jars", "cryon unload <id>", "Unload the jar a module came from"),
            HelpEntry("Jars", "cryon scan", "Load every jar in modules/ that is not loaded yet"),
            HelpEntry("Jars", "cryon reload-api", "Reload the api/ layer and every module with it"),
            HelpEntry("Jars", "cryon remote", "Feature jars tracked in a remote Maven repository"),
            HelpEntry("Jars", "cryon remote check", "Poll the repository for new builds now"),
            HelpEntry("Flags", "cryon flags [scope]", "Feature flags, all scopes or one"),
            HelpEntry("Flags", "cryon flag enable <feature> [scope]", "Turn a feature on"),
            HelpEntry("Flags", "cryon flag disable <feature> [scope]", "Turn a feature off"),
            HelpEntry("Flags", "cryon flag clear <feature> [scope]", "Drop one scope's override"),
            HelpEntry("Flags", "cryon flag status <feature> [player]", "The layered breakdown"),
            HelpEntry("Flags", "cryon flag reload", "Re-read the flags from the database"),
            HelpEntry("Server", "cryon network", "This server's deployment shape"),
            HelpEntry("Server", "cryon retention", "Whether unloaded module jars were actually collected"),
            HelpEntry("Server", "cryon menu", "The same actions as a menu"),
            HelpEntry("Server", "cryon lang reload", "Re-read the language files from disk"),
        )
    }
}
