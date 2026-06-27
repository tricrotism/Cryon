package com.tricrotism.cryon

import com.tricrotism.cryon.command.LanguageCommands
import com.tricrotism.cryon.command.ModuleCommands
import com.tricrotism.cryon.common.data.Database
import com.tricrotism.cryon.common.data.DatabaseConfig
import com.tricrotism.cryon.common.data.PostgresDatabase
import com.tricrotism.cryon.common.locale.*
import com.tricrotism.cryon.common.module.Module
import com.tricrotism.cryon.common.module.ModuleManager
import com.tricrotism.cryon.common.module.ServiceRegistry
import com.tricrotism.cryon.common.net.Messenger
import com.tricrotism.cryon.common.net.RedisConfig
import com.tricrotism.cryon.common.net.RedisMessenger
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
import java.net.URLClassLoader
import java.util.*

/**
 * The bootstrap. On enable it scans `plugins/Cryon/modules/` for feature jars, loads each in its
 * own isolated classloader (parent exposes the shared API + Paper + kotlin-stdlib bundled here),
 * discovers its [Module]s via [ServiceLoader], then drives the load → enable lifecycle. Features
 * intertwine through the [ServiceRegistry], never by referencing each other's classes.
 */
class Cryon : JavaPlugin() {

    private val log: Logger = LoggerFactory.getLogger("Cryon")
    private lateinit var manager: ModuleManager
    private val moduleLoaders = ArrayList<URLClassLoader>()
    private var apiLoader: URLClassLoader? = null

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

        // Shared cross-module contract layer: every jar in api/ is loaded once, into the parent of
        // all feature loaders, so features can intertwine through the same shared types.
        val apiDir = File(dataFolder, "api").apply { mkdirs() }
        val moduleParent = loadSharedApi(apiDir)

        val modulesDir = File(dataFolder, "modules").apply { mkdirs() }
        discover(modulesDir, moduleParent, messageService)

        manager.loadAll(context)
        manager.enableAll()

        registerCommands(messageService) // Cloud commands, after modules so /cryon sees their state
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
            AnnotationCommands.register(event.registrar(), ModuleCommands(manager), LanguageCommands(messageService))
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
        if (::manager.isInitialized) manager.disableAll()
        moduleLoaders.forEach { runCatching(it::close) }
        moduleLoaders.clear()
        // close children (modules) before the shared parent
        apiLoader?.let { runCatching(it::close) }
        apiLoader = null
        localeStore?.close()
        messenger?.close()
        database?.close()
    }

    /**
     * Load every jar in `plugins/Cryon/api/` into one shared [URLClassLoader] that becomes the parent
     * of every feature loader — the cross-module contract layer. An `*-api` jar dropped here exposes
     * its interfaces to *all* features at once (loaded once, so one shared type), letting a feature in
     * one repo register a service that a feature in another repo consumes through the registry. Returns
     * this plugin's own loader unchanged when `api/` is empty, so there's no extra layer until used.
     */
    private fun loadSharedApi(dir: File): ClassLoader {
        val jars = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".jar") }
            ?.sortedBy(File::getName)
            ?: emptyList()
        if (jars.isEmpty()) return javaClass.classLoader
        val loader = URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), javaClass.classLoader)
        apiLoader = loader
        log.info("Loaded {} shared API jar(s) from {}", jars.size, dir.path)
        return loader
    }

    private fun discover(dir: File, moduleParent: ClassLoader, messageService: MessageService) {
        val jars = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".jar") }
            ?.sortedBy(File::getName)
            ?: emptyList()
        if (jars.isEmpty()) {
            log.info("No feature jars in {}", dir.path)
            return
        }
        for (jar in jars) {
            try {
                // One isolated loader per jar; parent = the shared API layer (which exposes Paper + the
                // core API + any api/ contract jars). Features see the api/ types but not each other.
                val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), moduleParent)
                moduleLoaders.add(loader)

                // Auto-register any lang/<locale>.properties the feature bundles (read straight from
                // the jar, so a feature's bundle is never shadowed by a same-named core resource).
                LangScanner.fromJar(jar)?.let {
                    messageService.addSource(it)
                    log.info("Registered lang bundle from {}", jar.name)
                }

                val modules = ServiceLoader.load(Module::class.java, loader).toList()
                if (modules.isEmpty()) {
                    log.warn("No Module service declared in {}", jar.name)
                    continue
                }
                modules.forEach(manager::register)
                log.info("Discovered {} module(s) in {}", modules.size, jar.name)
            } catch (e: Throwable) {
                // Isolate a broken jar (ServiceConfigurationError is an Error, not an Exception).
                log.error("Failed to read feature jar {}", jar.name, e)
            }
        }
    }

    private class CryonContext(
        override val plugin: Plugin,
        override val server: Server,
        override val logger: Logger,
        override val services: ServiceRegistry,
    ) : PaperModuleContext
}
