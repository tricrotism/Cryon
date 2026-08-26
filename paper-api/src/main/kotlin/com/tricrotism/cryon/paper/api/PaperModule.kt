package com.tricrotism.cryon.paper.api

import com.tricrotism.cryon.common.module.*
import com.tricrotism.cryon.common.server.PlayerHandoff
import com.tricrotism.cryon.paper.api.bedrock.BedrockService
import com.tricrotism.cryon.paper.api.command.CommandService
import com.tricrotism.cryon.paper.api.placeholder.PlaceholderProvider
import com.tricrotism.cryon.paper.api.placeholder.PlaceholderService
import com.tricrotism.cryon.paper.api.scheduler.CryonDispatchers
import com.tricrotism.cryon.paper.api.scheduler.Schedulers
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kotlinx.coroutines.*
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import org.slf4j.Logger
import java.io.File
import java.lang.Runnable
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile

/**
 * Base class for Paper-side feature modules. Captures the [PaperModuleContext] in [onLoad] and
 * exposes the handles a feature needs, plus [listen] which registers a Bukkit listener that is
 * automatically unregistered on disable (no leaked handlers).
 *
 * Override [onLoad] to publish services (call `super.onLoad(context)` first), [onEnable] to wire
 * listeners/tasks and resolve peer services via [services], and [onDisable] for extra teardown
 * (call `super.onDisable()` to keep listener cleanup). [Module.postLoad] is there for the rarer case
 * of needing peers *enabled* rather than merely loaded; it takes no super call.
 */
abstract class PaperModule : Module {

    private lateinit var moduleContext: PaperModuleContext
    private val listeners = ArrayList<Listener>()

    @Volatile
    private var scopeStarted = false
    private val flushes = ArrayList<AutoCloseable>()
    private val placeholders = ArrayList<AutoCloseable>()
    private val tasks = ArrayList<ScheduledTask>()
    private val closeables = ArrayList<AutoCloseable>()

    protected val context: PaperModuleContext get() = moduleContext
    protected val plugin: Plugin get() = moduleContext.plugin
    protected val server: Server get() = moduleContext.server
    protected val services: ServiceRegistry get() = moduleContext.services
    protected val logger: Logger get() = moduleContext.logger

    /**
     * Bedrock-client support. Always present, with no Floodgate it reports every player as Java and
     * sends nothing, so menus can ask without branching on whether Geyser is installed.
     */
    protected val bedrock: BedrockService get() = services.get<BedrockService>()

    /**
     * This module's own directory, `plugins/Cryon/data/<id>/`, created on first use.
     *
     * Not `plugin.dataFolder`: that is the **core's** folder, and every module writing into it shares
     * one flat namespace with no owner recorded anywhere. Two modules that both want a `config.yml`
     * silently get one file, and the second one to load reads the first one's settings.
     *
     * Deliberately a sibling of `modules/` rather than a directory inside it. `modules/` is an input
     * the admin drops jars into and the hot-reload watcher owns; keeping written state out of it means
     * "clear modules/ and re-copy the jars" cannot take a module's data with it.
     */
    protected val dataFolder: File by lazy {
        File(File(plugin.dataFolder, "data"), id).apply { mkdirs() }
    }

    /**
     * Load [name] from [dataFolder], writing the copy bundled in this module's jar the first time.
     *
     * Reads the resource through *this module's* classloader, so each jar ships its own defaults and
     * they cannot collide. Returns a fresh read each call, which is also how you reload: hold the
     * result in a field, call again on reload, swap the field. There is no hidden cached instance,
     * because a stale one that nothing invalidates is the bug this is meant to remove.
     *
     * ```
     * private var settings = config()          // onEnable
     * fun reload() { settings = config() }     // /yourcommand reload
     * ```
     */
    protected fun config(name: String = "config.yml"): YamlConfiguration {
        val file = File(dataFolder, name)
        if (!file.exists()) extractDefault(name, file)
        return YamlConfiguration.loadConfiguration(file)
    }

    /** Copy this jar's bundled [name] to [file]. Absent is fine: the module then starts from empty. */
    private fun extractDefault(name: String, file: File) {
        val bundled = bundledResource(name) ?: return
        try {
            file.writeBytes(bundled)
            logger.info("Wrote default $name for module '$id'")
        } catch (e: Exception) {
            logger.error("Could not write the default $name for module '$id'", e)
        }
    }

    /**
     * Read [name] out of **this module's own jar**, rather than through the classloader.
     *
     * `getResourceAsStream` delegates to the parent first, and the parent here is the core — whose
     * jar also contains a `config.yml`. A module asking for its own default would silently be handed
     * the core's and write that into its folder, which looks like a corrupted default rather than a
     * lookup landing one classloader too high. Going straight to the code source cannot be ambiguous.
     */
    private fun bundledResource(name: String): ByteArray? {
        val location = runCatching { javaClass.protectionDomain?.codeSource?.location }.getOrNull() ?: return null
        val source = runCatching { File(location.toURI()) }.getOrNull() ?: return null
        if (source.isDirectory) return File(source, name).takeIf(File::isFile)?.readBytes()
        return runCatching {
            JarFile(source).use { jar ->
                jar.getJarEntry(name)?.let { entry -> jar.getInputStream(entry).use { it.readBytes() } }
            }
        }.getOrNull()
    }

    /**
     * This module's coroutine scope, canceled when the module disables.
     *
     * **Launch every coroutine here, never in `GlobalScope` or an ad-hoc `CoroutineScope`.** A
     * coroutine is a live reference to the code that started it, so one still suspended after
     * `/cryon unload` — parked on a database call, waiting out a `delay`, sitting in a `Mutex` queue
     * — holds this module's classloader open and eventually resumes into classes that are gone. That
     * is the same leak [track] exists for, and the scope is how the suspending half of the module
     * gets it: cancelling the parent cancels every child, transitively, in one move.
     *
     * Dispatches on [CryonDispatchers.Global] by default, so a `launch { }` body may touch the
     * Bukkit API for server-wide state; `withContext(CryonDispatchers.Async)` for I/O, and
     * `withContext(CryonDispatchers.entity(player))` for one player's own state. A [SupervisorJob]
     * parents it, so one failed coroutine does not take its siblings down with it, and an uncaught
     * failure is logged against this module rather than reaching a default handler that cannot say
     * which module it came from.
     *
     * **Cancellation is cooperative.** It unblocks anything suspended at a suspension point, but a
     * thread already inside a blocking JDBC or Redis call runs to completion — teardown that must
     * *finish* rather than merely stop belongs in [onDisable] before the super call, not in a
     * coroutine racing it.
     */
    protected val scope: CoroutineScope by lazy {
        scopeStarted = true
        CoroutineScope(
            SupervisorJob() +
                    CryonDispatchers.Global +
                    CoroutineName(id) +
                    CoroutineExceptionHandler { _, error ->
                        if (error is CancellationException) return@CoroutineExceptionHandler
                        logger.error("Unhandled failure in a coroutine of module '$id'", error)
                    }
        )
    }

    /** Resolve a required peer service: sugar for `services.get<T>()`. */
    protected inline fun <reified T : Any> service(): T = services.get()

    /** Resolve an optional peer service, or null: sugar for `services.find<T>()`. */
    protected inline fun <reified T : Any> serviceOrNull(): T? = services.find()

    override fun onLoad(context: ModuleContext) {
        moduleContext = context as PaperModuleContext
    }

    /** Register a Bukkit listener that is automatically unregistered when this module disables. */
    protected fun listen(listener: Listener) {
        server.pluginManager.registerEvents(listener, plugin)
        listeners.add(listener)
    }

    /**
     * Schedule a repeating task that is automatically cancelled when this module disables.
     *
     * Prefer these over calling [Schedulers] directly for anything repeating. Cryon's tasks are owned
     * by the **core** plugin, not by your jar, so Bukkit's own per-plugin cancellation never fires for
     * a module: a timer still running after `/cryon unload` keeps firing into a closed classloader for
     * the rest of the server's uptime, and stacks another copy on every hot-reload. Scheduling
     * directly is fine as long as you cancel the handle yourself in [onDisable].
     */
    protected fun globalTimer(delayTicks: Long, periodTicks: Long, task: (ScheduledTask) -> Unit): ScheduledTask =
        Schedulers.globalTimer(delayTicks, periodTicks, task).also { tasks += it }

    /** Repeating async task, canceled on disable. See [globalTimer]. */
    protected fun asyncTimer(
        initialDelay: Long,
        period: Long,
        unit: TimeUnit,
        task: (ScheduledTask) -> Unit,
    ): ScheduledTask = Schedulers.asyncTimer(initialDelay, period, unit, task).also { tasks += it }

    /** Repeating task on [location]'s region, canceled on disable. See [globalTimer]. */
    protected fun regionTimer(
        location: Location,
        delayTicks: Long,
        periodTicks: Long,
        task: (ScheduledTask) -> Unit,
    ): ScheduledTask = Schedulers.regionTimer(location, delayTicks, periodTicks, task).also { tasks += it }

    /**
     * Repeating task following [entity], canceled on disable. Null when the entity is already gone.
     *
     * The entity scheduler drops the task when its entity is removed, which covers a player logging
     * out, but not this module unloading while they are still online, which is what [globalTimer]
     * describes.
     */
    protected fun entityTimer(
        entity: Entity,
        delayTicks: Long,
        periodTicks: Long,
        retired: Runnable? = null,
        task: (ScheduledTask) -> Unit,
    ): ScheduledTask? =
        Schedulers.entityTimer(entity, delayTicks, periodTicks, retired, task)?.also { tasks += it }

    /**
     * Register a resource closed automatically when this module disables: an `Events` `Subscription`,
     * a `ServerRegistry.onChange` or `MaintenanceService.onChange` handle, a client, anything else that
     * lives as long as the module.
     *
     * ```
     * track(Events.subscribe<PlayerDeathEvent>().handler { … })
     * ```
     *
     * These are the handles that strand a classloader when forgotten: each parks a lambda defined by
     * *your* jar inside an object owned by the core, which outlives your unload. Closed in reverse
     * registration order, after listeners and tasks are already down.
     *
     * **For module-lifetime resources only.** Nothing removes an entry before disable, so tracking a
     * per-use object, a `ConfirmMenu.Dialog` per click, a window per open. Grows this list for the
     * module's whole life. Those belong in your own collection, keyed by player and pruned when the
     * dialog resolves; close what is left of it in [onDisable].
     */
    protected fun <T : AutoCloseable> track(closeable: T): T = closeable.also { closeables += it }

    /** Whether this module is currently in the `ENABLED` state, per the [ModuleManager]. */
    protected fun isEnabled(): Boolean =
        services.find<ModuleManager>()?.state(id) == ModuleState.ENABLED

    /**
     * Register `@Command` [handlers]. **Call from [onLoad].** The handlers are contributed to the
     * core [CommandService], gated on [isEnabled] so while this module is disabled they become
     * unavailable (and reappear on re-enable) without being re-registered.
     *
     * Registration is boot-window-agnostic: at boot the core flushes every contribution through its
     * single COMMANDS lifecycle handler; a module loaded or reloaded at runtime (hot-swap,
     * `/cryon load`, `reload-api`) has its tree spliced straight into the live dispatcher, so its
     * commands appear immediately with no server restart.
     */
    protected fun registerCommands(vararg handlers: Any) {
        val commands = services.find<CommandService>()
        if (commands == null) {
            logger.error("CommandService unavailable! Commands for module '$id' will not register")
            return
        }
        commands.register(id, ::isEnabled, handlers.toList())
    }

    /**
     * Register `@Command` [handlers] as branches of a **shared** root. See
     * [CommandService.registerBranch]. **Call from [onLoad]**, like [registerCommands].
     *
     * Use this, not [registerCommands], when the root literal is a namespace several modules live
     * under (`/int <module> …`). [registerCommands] would have this module take sole title to the
     * root and evict every other contributor.
     */
    protected fun registerBranchCommands(vararg handlers: Any) {
        val commands = services.find<CommandService>()
        if (commands == null) {
            logger.error("CommandService unavailable! Branch commands for module '$id' will not register")
            return
        }
        commands.registerBranch(id, ::isEnabled, handlers.toList())
    }

    /**
     * Register how this module writes one player's state down, so the core can flush it before the
     * player is handed to another instance. See [PlayerHandoff] for why saving on quit is too late.
     * Automatically unregistered on disable.
     *
     * [flush] runs off the main thread, must not touch the Bukkit API, and must be safe to call while
     * the player is still online. Register from [onEnable].
     *
     * It suspends, and the transfer waits on it: returning before the write lands defeats the point,
     * and never returning stalls the player on the loading screen. It is invoked by the core rather
     * than from this module's [scope], so it keeps running through a disable — which is deliberate,
     * since the last flush has to survive teardown.
     */
    protected fun onFlush(
        name: String,
        stage: Int = PlayerHandoff.DEFAULT_STAGE,
        flush: suspend (UUID) -> Unit,
    ) {
        val handoff = services.find<PlayerHandoff>()
        if (handoff == null) {
            logger.error("PlayerHandoff unavailable! '$name' for module $id will never flush")
            return
        }
        flushes += handoff.onFlush("$id/$name", stage, flush)
    }

    /**
     * Publish a [PlaceholderProvider], a `%<identifier>_…%` PlaceholderAPI namespace, through the core
     * bridge. A no-op when PlaceholderAPI is absent. Automatically unregistered on disable. Register from
     * [onEnable]; the callback runs on PlaceholderAPI's thread, so keep it cheap and thread-safe.
     */
    protected fun registerPlaceholders(provider: PlaceholderProvider) {
        val placeholderService = services.find<PlaceholderService>()
        if (placeholderService == null) {
            logger.warn("PlaceholderService unavailable! Placeholders for module '$id' will not register")
            return
        }
        placeholders += placeholderService.register(id, provider)
    }

    /**
     * Release everything this module acquired, consumers before what they consume.
     *
     * The scope is cancelled **after** the tasks and listeners that dispatch into it, not before.
     * `launch` on a cancelled scope is silently inert: the body never runs and nothing is raised. So
     * cancelling first opens a window where a still-registered handler, a quit handler saving state
     * being the case that costs, appears to do its work and drops it without a line in the log.
     *
     * Every step is guarded, because one throwing release must not strand the ones behind it.
     */
    override fun onDisable() {
        tasks.forEach { runCatching { it.cancel() } }
        tasks.clear()
        listeners.forEach { runCatching { HandlerList.unregisterAll(it) } }
        listeners.clear()
        if (scopeStarted) runCatching { scope.cancel("Module '$id' disabled") }
        closeables.asReversed().forEach { runCatching { it.close() } }
        closeables.clear()
        flushes.forEach { runCatching { it.close() } }
        flushes.clear()
        placeholders.forEach { runCatching { it.close() } }
        placeholders.clear()
    }
}
