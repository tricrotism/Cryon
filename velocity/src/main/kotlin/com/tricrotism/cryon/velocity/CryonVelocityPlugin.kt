package com.tricrotism.cryon.velocity

import com.google.inject.Inject
import com.tricrotism.cryon.common.data.Database
import com.tricrotism.cryon.common.data.DatabaseConfig
import com.tricrotism.cryon.common.data.SqlDatabase
import com.tricrotism.cryon.common.data.SqlDialect
import com.tricrotism.cryon.common.locale.DirectoryMessageSource
import com.tricrotism.cryon.common.locale.LangScanner
import com.tricrotism.cryon.common.locale.MessageService
import com.tricrotism.cryon.common.locale.Messages
import com.tricrotism.cryon.common.maintenance.MaintenanceService
import com.tricrotism.cryon.common.maintenance.SharedMaintenanceService
import com.tricrotism.cryon.common.module.ModuleManager
import com.tricrotism.cryon.common.module.ServiceRegistry
import com.tricrotism.cryon.common.net.*
import com.tricrotism.cryon.common.server.DefaultPlayerRouter
import com.tricrotism.cryon.common.server.PlayerRouter
import com.tricrotism.cryon.common.server.ServerRegistry
import com.tricrotism.cryon.common.server.SharedServerRegistry
import com.tricrotism.cryon.velocity.api.command.AnnotationCommands
import com.tricrotism.cryon.velocity.config.VelocityConfig
import com.tricrotism.cryon.velocity.maintenance.MaintenanceCommand
import com.tricrotism.cryon.velocity.maintenance.MaintenanceListener
import com.tricrotism.cryon.velocity.motd.Motd
import com.tricrotism.cryon.velocity.motd.MotdCommand
import com.tricrotism.cryon.velocity.motd.MotdListener
import com.tricrotism.cryon.velocity.network.BackendSynchronizer
import com.tricrotism.cryon.velocity.network.HandoffListener
import com.tricrotism.cryon.velocity.network.TransferListener
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import org.slf4j.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * The Velocity loader entrypoint, mirroring the Paper core `Cryon`. Velocity injects the proxy
 * handles here (the only place `@Inject` appears); feature modules stay no-arg `ServiceLoader`-discovered.
 * On init it wires the shared `:common` infra (Database/Messenger/RedisStore), the [ServerRegistry]
 * and dynamic backend/routing sync, then its own [ModuleManager] over the same module system as Paper.
 */
class CryonVelocityPlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @DataDirectory private val dataDirectory: Path,
) {
    private var database: Database? = null
    private var registry: ServerRegistry? = null
    private var backendSync: BackendSynchronizer? = null
    private var transfers: TransferListener? = null
    private var maintenance: MaintenanceService? = null
    private var manager: ModuleManager? = null
    private var loader: VelocityModuleLoader? = null

    // The transport, mirroring the Paper core: always installed, Redis when configured and in-process
    // otherwise, so everything above it has exactly one implementation.
    private lateinit var messenger: Messenger
    private lateinit var store: KeyValueStore
    private var sharedTransport = false

    @Subscribe
    fun onProxyInit(event: ProxyInitializeEvent) {
        val cfg = loadConfig()
        val services = ServiceRegistry(logger)
        setupLocale(services)
        setupInfrastructure(services, cfg)
        setupNetwork(services, cfg)
        setupMaintenance(services, cfg)
        setupMotd(services)
        setupModules(services)
        logger.info("Cryon proxy loader enabled")
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        manager?.disableAll()
        loader?.close()
        backendSync?.stop()
        transfers?.stop()
        maintenance?.close()
        registry?.close()
        if (::messenger.isInitialized) messenger.close()
        if (::store.isInitialized) store.close()
        database?.close()
    }

    private fun loadConfig(): VelocityConfig {
        Files.createDirectories(dataDirectory)
        val configFile = dataDirectory.resolve("config.yml")
        if (!Files.exists(configFile)) {
            javaClass.getResourceAsStream("/config.yml")?.use { Files.copy(it, configFile) }
        }
        return VelocityConfig.load(configFile)
    }

    /**
     * Bootstrap the shared i18n on the proxy, mirroring the Paper core so proxy commands localize by
     * client locale. The admin `plugins/cryon/lang/` folder is added first (so it overrides), then the
     * bundle inside this jar. Registered into the `ServiceRegistry` for velocity feature modules.
     */
    private fun setupLocale(services: ServiceRegistry) {
        val messageService = MessageService()
        Messages.install(messageService)
        val langDir = File(dataDirectory.toFile(), "lang").apply { mkdirs() }
        messageService.addSource(DirectoryMessageSource(langDir))
        ownJar()?.let { jar -> LangScanner.fromJar(jar)?.let(messageService::addSource) }
        services.register(MessageService::class, messageService)
    }

    private fun ownJar(): File? = runCatching {
        File(javaClass.protectionDomain.codeSource.location.toURI())
    }.getOrNull()

    private fun setupInfrastructure(services: ServiceRegistry, cfg: VelocityConfig) {
        if (cfg.boolean("database.enabled", false)) {
            try {
                val dialect = SqlDialect.of(cfg.string("database.type", "postgresql"))
                val db = SqlDatabase(
                    DatabaseConfig(
                        host = cfg.string("database.host", "localhost"),
                        port = cfg.int("database.port", dialect.defaultPort),
                        database = cfg.string("database.database", "cryon"),
                        username = cfg.string("database.username", "cryon"),
                        password = cfg.string("database.password", ""),
                        maxPoolSize = cfg.int("database.max-pool-size", 10),
                        dialect = dialect,
                    )
                )
                database = db
                services.register(Database::class, db)
                logger.info("Database connected (${dialect.id})")
            } catch (e: Exception) {
                logger.error("Failed to initialize the database... continuing without it", e)
            }
        }
        setupTransport(services, cfg)
    }

    /** Install the transport every other service is built on. Mirrors the Paper core exactly. */
    private fun setupTransport(services: ServiceRegistry, cfg: VelocityConfig) {
        if (cfg.boolean("redis.enabled", false)) {
            try {
                val config = RedisConfig(cfg.string("redis.uri", "redis://localhost:6379/0"))
                messenger = RedisMessenger(config)
                store = RedisKeyValueStore(config)
                sharedTransport = true
                logger.info("Redis connected — state is shared across the network")
            } catch (e: Exception) {
                logger.error("Failed to initialize Redis... falling back to in-process state", e)
                if (::messenger.isInitialized) runCatching { messenger.close() }
                if (::store.isInitialized) runCatching { store.close() }
            }
        }
        if (!sharedTransport) {
            messenger = LocalMessenger(logger)
            store = MemoryKeyValueStore()
            logger.info("State is in-process only (no redis) — this proxy sees a static backend list")
        }
        services.register(Messenger::class, messenger)
        services.register(KeyValueStore::class, store)
    }

    /**
     * Dynamic backends, routing, and the handoff pause. All of it is inherently cross-process — the
     * instances being discovered and flushed live in other JVMs — so unlike the Paper core's registry
     * this genuinely has nothing to do without a shared transport, and says so.
     */
    private fun setupNetwork(services: ServiceRegistry, cfg: VelocityConfig) {
        if (!sharedTransport) {
            logger.info("Dynamic routing off (no redis) — configure backends in velocity.toml")
            return
        }
        if (!cfg.boolean("network.registry-enabled", true)) {
            logger.info("Server registry disabled by config (network.registry-enabled=false)")
            return
        }
        val heartbeat = cfg.long("network.heartbeat-seconds", 5).coerceAtLeast(1)
        val reg = SharedServerRegistry(store, messenger, database, Duration.ofSeconds(heartbeat * 3), logger)
        reg.init()
        registry = reg
        services.register(ServerRegistry::class, reg)
        services.register(PlayerRouter::class, DefaultPlayerRouter(reg, messenger))
        backendSync = BackendSynchronizer(proxy, reg, logger).also { it.start() }
        transfers = TransferListener(proxy, messenger, logger).also { it.start() }

        // Hold each backend switch open until the server being left has saved the player — see
        // HandoffListener. Only meaningful once a player can move between instances at all.
        val timeout = Duration.ofSeconds(cfg.long("network.handoff-timeout-seconds", 5).coerceAtLeast(1))
        proxy.eventManager.register(this, HandoffListener(messenger, reg, timeout, logger))
        logger.info("Player handoff on — transfers wait up to {}s for the source server to flush", timeout.toSeconds())
    }

    /**
     * Maintenance lives here rather than on Paper, on either transport: it is enforced where logins
     * arrive, and a single-server deployment still has exactly one proxy, so in-process state is
     * already network-wide truth.
     */
    private fun setupMaintenance(services: ServiceRegistry, cfg: VelocityConfig) {
        val service = SharedMaintenanceService(
            database,
            messenger,
            cfg.string("maintenance.default-message", "The network is under maintenance."),
            logger,
        ).also { it.init() }
        maintenance = service
        services.register(MaintenanceService::class, service)
        proxy.eventManager.register(this, MaintenanceListener(service, cfg.int("maintenance.ping-protocol", -1)))
        AnnotationCommands.register(proxy.commandManager, MaintenanceCommand(service, proxy))
        logger.info("Maintenance mode available (/maintenance on|off [message], add|remove|list)")
    }

    /** The MOTD system: a top/bottom line of left/center/right anchored segments, `/motd reload`able. */
    private fun setupMotd(services: ServiceRegistry) {
        val maintenanceService = maintenance ?: return
        val motd = Motd(dataDirectory.resolve("config.yml")).also { it.reload() }
        proxy.eventManager.register(this, MotdListener(motd, maintenanceService))
        AnnotationCommands.register(proxy.commandManager, MotdCommand(motd))
        logger.info("MOTD available (/motd reload)")
    }

    private fun setupModules(services: ServiceRegistry) {
        val dataDir = dataDirectory.toFile()
        val apiDir = File(dataDir, "api").apply { mkdirs() }
        val modulesDir = File(dataDir, "modules").apply { mkdirs() }
        val mgr = ModuleManager(logger)
        services.register(ModuleManager::class, mgr)
        val ctx = VelocityContext(proxy, this, logger, services)
        val ldr = VelocityModuleLoader(mgr, logger, modulesDir, File(dataDir, ".module-cache"), javaClass.classLoader)
        ldr.loadSharedApi(apiDir)
        ldr.prepareCache()
        ldr.registerAll()
        mgr.loadAll(ctx)
        mgr.enableAll()
        manager = mgr
        loader = ldr
    }
}
