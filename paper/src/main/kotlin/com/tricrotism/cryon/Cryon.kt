package com.tricrotism.cryon

import com.tricrotism.cryon.command.LanguageCommands
import com.tricrotism.cryon.command.ModuleCommands
import com.tricrotism.cryon.common.data.Database
import com.tricrotism.cryon.common.data.DatabaseConfig
import com.tricrotism.cryon.common.data.PostgresDatabase
import com.tricrotism.cryon.common.flag.FeatureFlags
import com.tricrotism.cryon.common.locale.*
import com.tricrotism.cryon.common.module.ModuleManager
import com.tricrotism.cryon.common.module.ServiceRegistry
import com.tricrotism.cryon.common.net.*
import com.tricrotism.cryon.common.server.*
import com.tricrotism.cryon.common.text.Mini
import com.tricrotism.cryon.module.CommandRegistry
import com.tricrotism.cryon.module.ModuleLoader
import com.tricrotism.cryon.module.ModuleWatcher
import com.tricrotism.cryon.module.SparkSupport
import com.tricrotism.cryon.network.InstanceReporter
import com.tricrotism.cryon.network.agones.AgonesClient
import com.tricrotism.cryon.network.agones.AgonesLifecycle
import com.tricrotism.cryon.paper.api.CryonPaper
import com.tricrotism.cryon.paper.api.PaperModuleContext
import com.tricrotism.cryon.paper.api.command.CommandService
import com.tricrotism.cryon.paper.api.event.Events
import com.tricrotism.cryon.paper.api.scheduler.Schedulers
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Server
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration

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
    private var messenger: Messenger? = null
    private var localeStore: LocaleStore? = null
    private var redisStore: RedisStore? = null
    private var registry: ServerRegistry? = null
    private var reporter: InstanceReporter? = null
    private var agonesLifecycle: AgonesLifecycle? = null
    private var identity: InstanceIdentity? = null
    private var heartbeatSeconds: Long = 5

    override fun onEnable() {
        CryonPaper.init(this) // so Schedulers/Events can reach the plugin

        val messageService = MessageService()
        Messages.install(messageService)
        Mini.format("<off_white>")
        registerAdminLang(messageService)
        registerOwnLang(messageService)

        val services = ServiceRegistry(log).apply { register(MessageService::class, messageService) }
        setupInfrastructure(services)

        manager = ModuleManager(log)
        services.register(ModuleManager::class, manager) // so modules can query their own enabled-state

        // The command registry must exist before any module onLoad runs, so registerCommands resolves it.
        commandRegistry = CommandRegistry(server, log)
        services.register(CommandService::class, commandRegistry)

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

        bootstrapCommands(messageService) // after modules so the boot flush sees their contributions
        startWatchers(modulesDir, apiDir)
        setupNetwork(services) // advertise this instance to the network once everything is wired

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
     * Contribute the core's own `@Command` classes to the [commandRegistry], then install the single
     * COMMANDS lifecycle handler that flushes every queued contribution (core + modules) during the
     * boot window. After this window the registry splices runtime contributions into the live
     * dispatcher directly, so there is no second lifecycle handler anywhere.
     */
    private fun bootstrapCommands(messageService: MessageService) {
        commandRegistry.register(
            CORE_COMMAND_OWNER,
            { true },
            listOf(ModuleCommands(manager, loader, featureFlags, commandRegistry), LanguageCommands(messageService)),
        )
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            try {
                commandRegistry.flushBoot(event.registrar())
            } catch (t: Throwable) {
                log.error("Failed to flush command registrations", t)
            }
        }
    }

    /** Wire SQL + Redis if configured, then flags and the cross-server locale store. Failures degrade gracefully. */
    private fun setupInfrastructure(services: ServiceRegistry) {
        saveDefaultConfig()
        val cfg = config

        if (cfg.getBoolean("database.enabled")) {
            try {
                val db = PostgresDatabase(
                    DatabaseConfig(
                        host = cfg.getString("database.host", "localhost")!!,
                        port = cfg.getInt("database.port", 5432),
                        database = cfg.getString("database.database", "cryon")!!,
                        username = cfg.getString("database.username", "cryon")!!,
                        password = cfg.getString("database.password", "")!!,
                        maxPoolSize = cfg.getInt("database.max-pool-size", 10),
                    )
                )
                database = db
                services.register(Database::class, db)
                log.info("PostgreSQL connected")
            } catch (e: Exception) {
                log.error("Failed to initialize PostgreSQL... continuing without it", e)
            }
        }

        if (cfg.getBoolean("redis.enabled")) {
            try {
                val config = RedisConfig(cfg.getString("redis.uri", "redis://localhost:6379/0")!!)
                val redis = RedisMessenger(config)
                messenger = redis
                services.register(Messenger::class, redis)
                val store = LettuceRedisStore(config)
                redisStore = store
                services.register(RedisStore::class, store)
                log.info("Redis connected")
            } catch (e: Exception) {
                log.error("Failed to initialize Redis... continuing without it", e)
            }
        }

        val identity = resolveIdentity(cfg)
        this.identity = identity
        heartbeatSeconds = cfg.getLong("network.heartbeat-seconds", 5).coerceAtLeast(1)

        featureFlags = FeatureFlags(identity.family, database, messenger, log)
        featureFlags.init()
        services.register(FeatureFlags::class, featureFlags)

        val db = database
        val redis = messenger
        val store: LocaleStore = if (db != null && redis != null) {
            PlayerLocaleStore(db, redis).also { s ->
                s.init().exceptionally { log.error("Failed to create locale table", it); null }
                Events.subscribe(PlayerJoinEvent::class.java).handler { event -> s.load(event.player.uniqueId) }
                Events.subscribe(PlayerQuitEvent::class.java).handler { event -> s.unload(event.player.uniqueId) }
                log.info("Persistent cross-server player locale enabled")
            }
        } else {
            log.info("Player locale overrides are in-memory only (no database + redis) — they reset on restart")
            MemoryLocaleStore()
        }
        localeStore = store
        Locales.install(store)
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
    )

    /**
     * Register this instance in the shared [ServerRegistry] and start heartbeating. Requires Redis
     * (liveness is TTL-based); without it the server runs standalone, exactly as before. Gated by
     * `network.registry-enabled`.
     */
    private fun setupNetwork(services: ServiceRegistry) {
        val store = redisStore
        val redis = messenger
        val id = identity
        if (store == null || redis == null || id == null) {
            log.info("Server registry off (needs redis), this server runs standalone")
            return
        }
        if (!config.getBoolean("network.registry-enabled", true)) {
            log.info("Server registry disabled by config (network.registry-enabled=false)")
            return
        }
        val reg = RedisServerRegistry(store, redis, database, Duration.ofSeconds(heartbeatSeconds * 3), log)
        reg.init()
        registry = reg
        services.register(ServerRegistry::class, reg)
        services.register(PlayerRouter::class, RedisPlayerRouter(reg, redis))
        val rep = InstanceReporter(reg, id, server, Duration.ofSeconds(heartbeatSeconds), log).also { it.start() }
        reporter = rep
        setupAgones(services, id.family, rep, reg)
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
        reporter?.let { runCatching { it.drain(); it.stop() } } // deregister before the transport closes
        registry?.let { runCatching { it.close() } }
        if (::manager.isInitialized) manager.disableAll()
        if (::loader.isInitialized) loader.close() // closes module loaders + the shared api/ parent
        if (::featureFlags.isInitialized) featureFlags.close()
        localeStore?.close()
        messenger?.close()
        redisStore?.close()
        database?.close()
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
    }
}
