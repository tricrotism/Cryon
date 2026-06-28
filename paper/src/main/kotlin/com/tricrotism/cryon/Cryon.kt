package com.tricrotism.cryon

import com.tricrotism.cryon.command.LanguageCommands
import com.tricrotism.cryon.command.ModuleCommands
import com.tricrotism.cryon.common.data.Database
import com.tricrotism.cryon.common.data.DatabaseConfig
import com.tricrotism.cryon.common.data.PostgresDatabase
import com.tricrotism.cryon.common.locale.*
import com.tricrotism.cryon.common.module.ModuleManager
import com.tricrotism.cryon.common.module.ServiceRegistry
import com.tricrotism.cryon.common.net.Messenger
import com.tricrotism.cryon.common.net.RedisConfig
import com.tricrotism.cryon.common.net.RedisMessenger
import com.tricrotism.cryon.module.ModuleLoader
import com.tricrotism.cryon.module.ModuleWatcher
import com.tricrotism.cryon.paper.api.CryonPaper
import com.tricrotism.cryon.paper.api.PaperModuleContext
import com.tricrotism.cryon.paper.api.command.AnnotationCommands
import com.tricrotism.cryon.paper.api.event.Events
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Server
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

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
    private val watchers = ArrayList<ModuleWatcher>()

    private var database: Database? = null
    private var messenger: Messenger? = null
    private var localeStore: LocaleStore? = null

    override fun onEnable() {
        CryonPaper.init(this) // so Schedulers/Events can reach the plugin

        val messageService = MessageService()
        Messages.install(messageService)
        registerAdminLang(messageService) // plugins/Cryon/lang/ overrides, highest priority
        registerOwnLang(messageService)   // the core's own bundled lang/ (e.g. the /language command)

        val services = ServiceRegistry(log).apply { register(MessageService::class, messageService) }
        setupInfrastructure(services)

        manager = ModuleManager(log)
        services.register(ModuleManager::class, manager) // so modules can query their own enabled-state
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

        registerCommands(messageService) // after modules so /cryon sees their state
        startWatchers(modulesDir, apiDir)
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

    /** Register the built-in `@Command` classes onto Paper's native Brigadier registrar. */
    private fun registerCommands(messageService: MessageService) {
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            // Guard each handler: this runs inside Paper's lifecycle dispatch, which rethrows fatally.
            for (handler in arrayOf(ModuleCommands(manager, loader), LanguageCommands(messageService))) {
                try {
                    AnnotationCommands.register(event.registrar(), handler)
                } catch (t: Throwable) {
                    log.error("Failed to register core command \"{}\" skipping...", handler.javaClass.simpleName, t)
                }
            }
        }
    }

    /** Wire SQL + Redis if configured, then the cross-server locale store. Failures degrade gracefully. */
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
                val redis = RedisMessenger(RedisConfig(cfg.getString("redis.uri", "redis://localhost:6379/0")!!))
                messenger = redis
                services.register(Messenger::class, redis)
                log.info("Redis connected")
            } catch (e: Exception) {
                log.error("Failed to initialize Redis... continuing without it", e)
            }
        }

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

    override fun onDisable() {
        watchers.forEach { runCatching { it.close() } }
        watchers.clear()
        if (::manager.isInitialized) manager.disableAll()
        if (::loader.isInitialized) loader.close() // closes module loaders + the shared api/ parent
        localeStore?.close()
        messenger?.close()
        database?.close()
    }

    private class CryonContext(
        override val plugin: Plugin,
        override val server: Server,
        override val logger: Logger,
        override val services: ServiceRegistry,
    ) : PaperModuleContext
}
