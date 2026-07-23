package com.tricrotism.cryon

import com.tricrotism.cryon.Cryon.Companion.FLUSH_TIMEOUT_SECONDS
import com.tricrotism.cryon.command.LanguageCommands
import com.tricrotism.cryon.command.ModuleCommands
import com.tricrotism.cryon.common.data.Database
import com.tricrotism.cryon.common.data.DatabaseConfig
import com.tricrotism.cryon.common.data.SqlDatabase
import com.tricrotism.cryon.common.data.SqlDialect
import com.tricrotism.cryon.common.flag.FeatureFlags
import com.tricrotism.cryon.common.locale.*
import com.tricrotism.cryon.common.module.ModuleManager
import com.tricrotism.cryon.common.module.ServiceRegistry
import com.tricrotism.cryon.common.net.*
import com.tricrotism.cryon.common.server.*
import com.tricrotism.cryon.common.text.Mini
import com.tricrotism.cryon.inventory.DefaultInventorySearch
import com.tricrotism.cryon.module.CommandRegistry
import com.tricrotism.cryon.module.ModuleLoader
import com.tricrotism.cryon.module.ModuleWatcher
import com.tricrotism.cryon.module.SparkSupport
import com.tricrotism.cryon.network.InstanceReporter
import com.tricrotism.cryon.network.NetworkStatus
import com.tricrotism.cryon.network.agones.AgonesClient
import com.tricrotism.cryon.network.agones.AgonesLifecycle
import com.tricrotism.cryon.paper.api.CryonPaper
import com.tricrotism.cryon.paper.api.PaperModuleContext
import com.tricrotism.cryon.paper.api.command.CommandService
import com.tricrotism.cryon.paper.api.event.Events
import com.tricrotism.cryon.paper.api.inventory.InventorySearch
import com.tricrotism.cryon.paper.api.placeholder.PlaceholderService
import com.tricrotism.cryon.paper.api.scheduler.Schedulers
import com.tricrotism.cryon.papi.CorePlaceholders
import com.tricrotism.cryon.papi.PapiBridge
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Server
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.event.EventPriority
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The bootstrap. On enable it scans `plugins/Cryon/modules/` for feature jars, loads each in its
 * own isolated classloader (parent exposes the shared API + Paper + kotlin-stdlib bundled here),
 * discovers its [Module]s via [ServiceLoader], then drives the load → enable lifecycle. Features
 * intertwine through the [ServiceRegistry], never by referencing each other's classes.
 */
class Cryon : JavaPlugin() {

    private val log: Logger = LoggerFactory.getLogger("Cryon")
    private lateinit var manager: ModuleManager
    private lateinit var loader: ModuleLoader
    private lateinit var commandRegistry: CommandRegistry
    private val watchers = ArrayList<ModuleWatcher>()

    private lateinit var featureFlags: FeatureFlags
    private var database: Database? = null
    private var localeStore: LocaleStore? = null
    private var registry: ServerRegistry? = null
    private var reporter: InstanceReporter? = null
    private var agonesLifecycle: AgonesLifecycle? = null
    private var handoff: HandoffCoordinator? = null
    private var corePlaceholders: AutoCloseable? = null
    private var heartbeatSeconds: Long = 5

    // The transport. Always installed — Redis when configured, in-process otherwise — so the services
    // above it have one implementation each and features never branch on the deployment mode.
    private lateinit var messenger: Messenger
    private lateinit var store: KeyValueStore
    private lateinit var identity: InstanceIdentity

    /** Whether the transport reaches other processes. Cross-process-only services hang off this. */
    private var sharedTransport = false

    override fun onEnable() {
        CryonPaper.init(this) // so Schedulers/Events can reach the plugin

        val messageService = MessageService()
        Messages.install(messageService)
        Mini.format("<off_white>")
        registerAdminLang(messageService)
        registerOwnLang(messageService)

        val services = ServiceRegistry(log).apply { register(MessageService::class, messageService) }
        setupInfrastructure(services)

        setupNetwork(services)
        val status = reportNetwork(services)

        manager = ModuleManager(log)
        services.register(ModuleManager::class, manager) // so modules can query their own enabled-state

        // The command registry must exist before any module onLoad runs, so registerCommands resolves it.
        commandRegistry = CommandRegistry(server, log)
        services.register(CommandService::class, commandRegistry)

        val papi = PapiBridge(this, log)
        services.register(PlaceholderService::class, papi)
        corePlaceholders = papi.register(CORE_COMMAND_OWNER, CorePlaceholders(identity))

        val context = CryonContext(this, server, log, services)

        val apiDir = File(dataFolder, "api").apply { mkdirs() }
        val modulesDir = File(dataFolder, "modules").apply { mkdirs() }
        loader = ModuleLoader(
            manager,
            messageService,
            context,
            log,
            modulesDir,
            File(dataFolder, ".module-cache"),
            javaClass.classLoader
        )

        // Shared cross-module contract layer (api/) parents every feature loader, then register the
        // feature jars in modules/ before driving the global two-phase load → enable.
        loader.loadSharedApi(apiDir)
        loader.prepareCache()
        loader.registerAll()

        manager.loadAll(context)
        manager.enableAll()

        seedAdminLang(messageService) // after modules so their keys land in the reference file too

        bootstrapCommands(messageService, status, papi) // after modules so the boot flush sees their contributions
        startWatchers(modulesDir, apiDir)
        announceReady(services) // only now can this server actually serve the players routed to it

        // Next tick: spark may enable after us, and the splice reads loaded modules either way.
        val authors = pluginMeta.authors.joinToString(", ").ifEmpty { "Cryon" }
        Schedulers.globalLater(1) { SparkSupport.install(server, loader, authors, log) }
    }

    /**
     * Start the dev hot-reload watchers when enabled. They run when `modules.auto-reload` is true,
     * which **defaults to `!production`** — so a `production: false` (dev) server watches `modules/`
     * (per-jar hot-swap) and `api/` (a full `reloadApi` cascade on any change) automatically, while a
     * production server doesn't. The `/cryon load|unload|scan|reload-api` commands work regardless.
     * Best-effort: a watcher failure degrades to manual hot-swap, never blocks boot.
     */
    private fun startWatchers(modulesDir: File, apiDir: File) {
        val production = config.getBoolean("production", true)
        val autoReload = config.getBoolean("modules.auto-reload", !production)
        if (!autoReload) {
            log.info(
                "Hot-reload watchers off (production={}); use /cryon load|unload|scan|reload-api to hot-swap",
                production
            )
            return
        }
        startWatcher(
            modulesDir, "modules",
            onChanged = { jar ->
                runCatching { loader.loadJar(jar) }.onFailure {
                    log.error(
                        "Hot-load failed for {}",
                        jar.name,
                        it
                    )
                }
            },
            onDeleted = { jar ->
                runCatching { loader.unloadJar(jar) }.onFailure {
                    log.error(
                        "Hot-unload failed for {}",
                        jar.name,
                        it
                    )
                }
            },
        )
        // Any change to a contract jar means re-linking every module, so both edges trigger the cascade.
        val reloadApi: (File) -> Unit =
            { runCatching { loader.reloadApi() }.onFailure { log.error("api/ reload failed", it) } }
        startWatcher(apiDir, "api", onChanged = reloadApi, onDeleted = reloadApi)
    }

    private fun startWatcher(dir: File, label: String, onChanged: (File) -> Unit, onDeleted: (File) -> Unit) {
        try {
            watchers += ModuleWatcher(dir, log, onChanged, onDeleted).also { it.start() }
        } catch (e: Exception) {
            log.error("Failed to start the {} watcher; falling back to manual hot-swap", label, e)
        }
    }

    /** Register a disk `plugins/Cryon/lang/` folder so admins can override/add translations. */
    private fun registerAdminLang(messageService: MessageService) {
        val langDir = File(dataFolder, "lang").apply { mkdirs() }
        messageService.addSource(DirectoryMessageSource(langDir))
    }

    /** Auto-scan the core's own jar for bundled `lang/<locale>.properties`. */
    private fun registerOwnLang(messageService: MessageService) {
        val jar = runCatching { File(javaClass.protectionDomain.codeSource.location.toURI()) }.getOrNull() ?: return
        LangScanner.fromJar(jar)?.let(messageService::addSource)
    }

    /**
     * Mirror the default locale's keys (core + every module bundle) into the on-disk
     * `plugins/Cryon/lang/<default>.properties`, so admins get a complete, editable reference instead
     * of an empty folder. Only missing keys are added — existing overrides are preserved — then the
     * directory source is reloaded so the file is authoritative. Best-effort: a write failure logs and
     * never blocks boot.
     */
    private fun seedAdminLang(messageService: MessageService) {
        val locale = messageService.defaultLocale
        val file = File(File(dataFolder, "lang"), "$locale.properties")
        try {
            val added = messageService.exportMissing(locale, file)
            if (added > 0) {
                messageService.reload()
                log.info("Seeded {} missing message(s) into {}", added, file.name)
            }
        } catch (e: Exception) {
            log.warn("Could not seed the admin lang file {}", file.name, e)
        }
    }

    /**
     * Contribute the core's own `@Command` classes to the [commandRegistry], then install the single
     * COMMANDS lifecycle handler that flushes every queued contribution (core + modules) during the
     * boot window. After this window the registry splices runtime contributions into the live
     * dispatcher directly, so there is no second lifecycle handler anywhere.
     */
    private fun bootstrapCommands(
        messageService: MessageService,
        status: NetworkStatus,
        placeholders: PlaceholderService,
    ) {
        commandRegistry.register(
            CORE_COMMAND_OWNER,
            { true },
            listOf(
                ModuleCommands(manager, loader, featureFlags, commandRegistry, status, messageService, placeholders),
                LanguageCommands(messageService),
            ),
        )
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            try {
                commandRegistry.flushBoot(event.registrar())
            } catch (t: Throwable) {
                log.error("Failed to flush command registrations", t)
            }
        }
    }

    /** Wire SQL and the transport, then flags and the player locale store. Failures degrade gracefully. */
    private fun setupInfrastructure(services: ServiceRegistry) {
        saveDefaultConfig()
        val cfg = config

        if (cfg.getBoolean("database.enabled")) {
            try {
                val dialect = SqlDialect.of(cfg.getString("database.type", "postgresql")!!)
                val db = SqlDatabase(
                    DatabaseConfig(
                        host = cfg.getString("database.host", "localhost")!!,
                        port = cfg.getInt("database.port", dialect.defaultPort),
                        database = cfg.getString("database.database", "cryon")!!,
                        username = cfg.getString("database.username", "cryon")!!,
                        password = cfg.getString("database.password", "")!!,
                        maxPoolSize = cfg.getInt("database.max-pool-size", 10),
                        dialect = dialect,
                    )
                )
                database = db
                services.register(Database::class, db)
                log.info("Database connected (${dialect.id})")
            } catch (e: Exception) {
                log.error("Failed to initialize the database... continuing without it", e)
            }
        }

        setupTransport(services, cfg)

        identity = resolveIdentity(cfg)
        services.register(InstanceIdentity::class, identity) // so a feature can ask who it is
        heartbeatSeconds = cfg.getLong("network.heartbeat-seconds", 5).coerceAtLeast(1)

        featureFlags = FeatureFlags(identity.family, database, messenger, log)
        featureFlags.init()
        services.register(FeatureFlags::class, featureFlags)

        services.register(InventorySearch::class, DefaultInventorySearch())

        val db = database
        val locale: LocaleStore = if (db != null) {
            PlayerLocaleStore(db, messenger).also { s ->
                s.init().exceptionally { log.error("Failed to create locale table", it); null }
                Events.subscribe(PlayerJoinEvent::class.java).handler { event -> s.load(event.player.uniqueId) }
                Events.subscribe(PlayerQuitEvent::class.java).handler { event -> s.unload(event.player.uniqueId) }
                log.info("Persistent player locale enabled")
            }
        } else {
            log.info("Player locale overrides are in-memory only (no database) — they reset on restart")
            MemoryLocaleStore()
        }
        localeStore = locale
        Locales.install(locale)
    }

    /**
     * Install the [Messenger] + [KeyValueStore] every other service is built on. Redis when it is
     * configured *and* reachable, this process otherwise — either way both are registered, so nothing
     * downstream has to cope with their absence. A Redis that fails to connect falls back rather than
     * half-installing: a live messenger beside a dead store is the one state no caller expects.
     */
    private fun setupTransport(services: ServiceRegistry, cfg: FileConfiguration) {
        if (cfg.getBoolean("redis.enabled")) {
            try {
                val redisConfig = RedisConfig(cfg.getString("redis.uri", "redis://localhost:6379/0")!!)
                messenger = RedisMessenger(redisConfig)
                store = RedisKeyValueStore(redisConfig)
                sharedTransport = true
                log.info("Redis connected — state is shared across the network")
            } catch (e: Exception) {
                log.error("Failed to initialize Redis... falling back to in-process state", e)
                closeQuietly()
            }
        }
        if (!sharedTransport) {
            messenger = LocalMessenger(log)
            store = MemoryKeyValueStore()
            log.info("State is in-process only (no redis) — correct for a single server, not for a pool")
        }
        services.register(Messenger::class, messenger)
        services.register(KeyValueStore::class, store)
    }

    /** Drop whatever a half-finished Redis setup managed to open. */
    private fun closeQuietly() {
        if (::messenger.isInitialized) runCatching { messenger.close() }
        if (::store.isInitialized) runCatching { store.close() }
    }

    /** Resolve this process's network identity, env-first, falling back to config and Paper's own values. */
    private fun resolveIdentity(cfg: FileConfiguration): InstanceIdentity = InstanceIdentity.resolve(
        configFamily = cfg.getString("network.family")?.takeIf { it.isNotBlank() } ?: cfg.getString("server-name"),
        configInstanceId = cfg.getString("network.instance-id"),
        configAddress = cfg.getString("network.address"),
        configPort = cfg.getInt("network.port", 0),
        fallbackPort = server.port,
        configMaxPlayers = cfg.getInt("network.max-players", 0),
        fallbackMaxPlayers = server.maxPlayers,
        configMode = cfg.getString("network.mode"),
        onUnknownMode = { log.error("Unknown network.mode '{}' — falling back to single", it) },
    )

    /**
     * Wire the services features resolve during load: the registry, the router, and player handoff.
     * Runs **before** modules load, so a module can register its flush and read the registry in
     * `onLoad`/`onEnable` — but deliberately stops short of announcing this server as ready, which is
     * [announceReady]'s job once the modules that will actually serve players are enabled.
     *
     * The registry is installed on either transport: over the in-process one it simply contains this
     * server alone, which is what a single-server deployment *is* rather than a degraded mode of a
     * pool. Gated by `network.registry-enabled`.
     */
    private fun setupNetwork(services: ServiceRegistry) {
        setupHandoff(services)
        if (!config.getBoolean("network.registry-enabled", true)) {
            log.info("Server registry disabled by config (network.registry-enabled=false)")
            return
        }
        val reg = SharedServerRegistry(store, messenger, database, Duration.ofSeconds(heartbeatSeconds * 3), log)
        reg.init()
        registry = reg
        services.register(ServerRegistry::class, reg)

        if (sharedTransport) services.register(PlayerRouter::class, DefaultPlayerRouter(reg, messenger))
        reporter = InstanceReporter(reg, identity, server, Duration.ofSeconds(heartbeatSeconds), log)
            .also { it.register() }
    }

    /**
     * Install the flush registry and drive it from quit. Handled at [EventPriority.MONITOR] so every
     * module's own quit handler has finished updating its state before we write that state down.
     */
    private fun setupHandoff(services: ServiceRegistry) {
        val coordinator = HandoffCoordinator(identity.instanceId, messenger, log)
        coordinator.init()
        handoff = coordinator
        services.register(PlayerHandoff::class, coordinator)
        Events.subscribe(PlayerQuitEvent::class.java, EventPriority.MONITOR)
            .handler { event -> coordinator.flushOnQuit(event.player.uniqueId) }
    }

    /** Advertise this server as READY, now that the modules serving its players are enabled. */
    private fun announceReady(services: ServiceRegistry) {
        val rep = reporter ?: return
        val reg = registry ?: return
        rep.ready()
        setupAgones(services, identity.family, rep, reg)
    }

    /** Say what this server is, and make any disagreement with `network.mode` impossible to miss. */
    private fun reportNetwork(services: ServiceRegistry): NetworkStatus {
        val status = NetworkStatus(identity, sharedTransport, database != null) { registry }
        services.register(NetworkStatus::class, status)
        status.report(log)
        return status
    }

    /** Attach the Agones lifecycle when running under a sidecar; a no-op anywhere else. */
    private fun setupAgones(
        services: ServiceRegistry,
        family: String,
        reporter: InstanceReporter,
        registry: ServerRegistry
    ) {
        val agones = AgonesClient.detect(log) ?: return
        // shutdown-when-empty differs per family (persistent shards reclaim; ephemeral self-shutdown on
        // match end), so it's env-first — one shared config file, per-Fleet env override.
        val shutdownWhenEmpty = System.getenv("CRYON_AGONES_SHUTDOWN_WHEN_EMPTY")?.toBooleanStrictOrNull()
            ?: config.getBoolean("network.agones.shutdown-when-empty", false)
        val options = AgonesLifecycle.Options(
            healthSeconds = config.getLong("network.agones.health-seconds", 5).coerceAtLeast(1),
            shutdownWhenEmpty = shutdownWhenEmpty,
            emptyGraceSeconds = config.getLong("network.agones.empty-grace-seconds", 60),
            minInstances = config.getInt("network.agones.min-instances", 1),
        )
        val life = AgonesLifecycle(agones, reporter::currentPlayers, { registry.family(family).size }, options, log)
        agonesLifecycle = life
        services.register(AgonesLifecycle::class, life)
        life.start()
    }

    override fun onDisable() {
        watchers.forEach { runCatching { it.close() } }
        watchers.clear()
        agonesLifecycle?.let { runCatching { it.stop() } }
        reporter?.let { runCatching { it.drain() } } // stop new arrivals before we start saving
        flushOnlinePlayers()
        reporter?.let { runCatching { it.stop() } } // deregister before the transport closes
        registry?.let { runCatching { it.close() } }
        if (::manager.isInitialized) manager.disableAll()
        corePlaceholders?.let { runCatching { it.close() } } // module providers close via their own disable
        if (::loader.isInitialized) loader.close() // closes module loaders + the shared api/ parent
        handoff?.let { runCatching { it.close() } }
        if (::featureFlags.isInitialized) featureFlags.close()
        localeStore?.close()
        if (::messenger.isInitialized) messenger.close()
        if (::store.isInitialized) store.close()
        database?.close()
    }

    /**
     * Write every online player down before anything that could carry their state is torn down. Must
     * run while modules are still enabled (their state is the thing being flushed) and before the
     * database closes, which drops in-flight writes. Bounded: a stuck flush delays shutdown by
     * [FLUSH_TIMEOUT_SECONDS] and no longer.
     */
    private fun flushOnlinePlayers() {
        val coordinator = handoff ?: return
        val online = server.onlinePlayers.map { it.uniqueId }
        if (online.isEmpty()) return
        log.info("Flushing {} online player(s) before shutdown", online.size)
        val flushes = online.map { coordinator.flush(it) }.toTypedArray()
        runCatching { CompletableFuture.allOf(*flushes).orTimeout(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS).join() }
            .onFailure { log.error("Timed out flushing players on shutdown — some state may be lost", it) }
    }

    private class CryonContext(
        override val plugin: Plugin,
        override val server: Server,
        override val logger: Logger,
        override val services: ServiceRegistry,
    ) : PaperModuleContext

    private companion object {
        /** Owner key the core's own commands register under in the [CommandRegistry]. */
        const val CORE_COMMAND_OWNER = "cryon"

        /** How long shutdown waits for player flushes before giving up and saying so. */
        const val FLUSH_TIMEOUT_SECONDS = 10L
    }
}
