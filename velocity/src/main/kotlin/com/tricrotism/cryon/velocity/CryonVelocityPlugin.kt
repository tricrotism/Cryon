package com.tricrotism.cryon.velocity

import com.google.inject.Inject
import com.tricrotism.cryon.common.data.Database
import com.tricrotism.cryon.common.data.DatabaseConfig
import com.tricrotism.cryon.common.data.PostgresDatabase
import com.tricrotism.cryon.common.maintenance.MaintenanceService
import com.tricrotism.cryon.common.maintenance.RedisMaintenanceService
import com.tricrotism.cryon.common.module.ModuleManager
import com.tricrotism.cryon.common.module.ServiceRegistry
import com.tricrotism.cryon.common.net.*
import com.tricrotism.cryon.common.server.PlayerRouter
import com.tricrotism.cryon.common.server.RedisPlayerRouter
import com.tricrotism.cryon.common.server.RedisServerRegistry
import com.tricrotism.cryon.common.server.ServerRegistry
import com.tricrotism.cryon.velocity.config.VelocityConfig
import com.tricrotism.cryon.velocity.maintenance.MaintenanceCommand
import com.tricrotism.cryon.velocity.maintenance.MaintenanceListener
import com.tricrotism.cryon.velocity.network.BackendSynchronizer
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
    private var messenger: Messenger? = null
    private var redisStore: RedisStore? = null
    private var registry: ServerRegistry? = null
    private var backendSync: BackendSynchronizer? = null
    private var transfers: TransferListener? = null
    private var maintenance: MaintenanceService? = null
    private var manager: ModuleManager? = null
    private var loader: VelocityModuleLoader? = null

    @Subscribe
    fun onProxyInit(event: ProxyInitializeEvent) {
        val cfg = loadConfig()
        val services = ServiceRegistry(logger)
        setupInfrastructure(services, cfg)
        setupNetwork(services, cfg)
        setupMaintenance(services, cfg)
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
        messenger?.close()
        redisStore?.close()
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

    private fun setupInfrastructure(services: ServiceRegistry, cfg: VelocityConfig) {
        if (cfg.boolean("database.enabled", false)) {
            try {
                val db = PostgresDatabase(
                    DatabaseConfig(
                        host = cfg.string("database.host", "localhost"),
                        port = cfg.int("database.port", 5432),
                        database = cfg.string("database.database", "cryon"),
                        username = cfg.string("database.username", "cryon"),
                        password = cfg.string("database.password", ""),
                        maxPoolSize = cfg.int("database.max-pool-size", 10),
                    )
                )
                database = db
                services.register(Database::class, db)
                logger.info("PostgreSQL connected")
            } catch (e: Exception) {
                logger.error("Failed to initialize PostgreSQL... continuing without it", e)
            }
        }
        if (cfg.boolean("redis.enabled", false)) {
            try {
                val config = RedisConfig(cfg.string("redis.uri", "redis://localhost:6379/0"))
                val redis = RedisMessenger(config)
                messenger = redis
                services.register(Messenger::class, redis)
                val store = LettuceRedisStore(config)
                redisStore = store
                services.register(RedisStore::class, store)
                logger.info("Redis connected")
            } catch (e: Exception) {
                logger.error("Failed to initialize Redis... continuing without it", e)
            }
        }
    }

    private fun setupNetwork(services: ServiceRegistry, cfg: VelocityConfig) {
        val store = redisStore
        val redis = messenger
        if (store == null || redis == null) {
            logger.warn("Server registry off (needs redis) — the proxy cannot route dynamically")
            return
        }
        if (!cfg.boolean("network.registry-enabled", true)) return
        val heartbeat = cfg.long("network.heartbeat-seconds", 5).coerceAtLeast(1)
        val reg = RedisServerRegistry(store, redis, database, Duration.ofSeconds(heartbeat * 3), logger)
        reg.init()
        registry = reg
        services.register(ServerRegistry::class, reg)
        services.register(PlayerRouter::class, RedisPlayerRouter(reg, redis))
        backendSync = BackendSynchronizer(proxy, reg, logger).also { it.start() }
        transfers = TransferListener(proxy, redis, logger).also { it.start() }
    }

    private fun setupMaintenance(services: ServiceRegistry, cfg: VelocityConfig) {
        val redis = messenger ?: return
        val service = RedisMaintenanceService(
            database,
            redis,
            cfg.string("maintenance.default-message", "The network is under maintenance."),
            logger,
        ).also { it.init() }
        maintenance = service
        services.register(MaintenanceService::class, service)
        proxy.eventManager.register(this, MaintenanceListener(service, cfg.int("maintenance.ping-protocol", -1)))
        val meta = proxy.commandManager.metaBuilder("maintenance").build()
        proxy.commandManager.register(meta, MaintenanceCommand(service))
        logger.info("Maintenance mode available (/maintenance on|off [message])")
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
