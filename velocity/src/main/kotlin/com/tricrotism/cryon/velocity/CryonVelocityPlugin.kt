package com.tricrotism.cryon.velocity

import com.google.inject.Inject
import com.tricrotism.cryon.common.concurrent.CryonIO
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
import com.tricrotism.cryon.common.server.*
import com.tricrotism.cryon.velocity.api.bedrock.BedrockService
import com.tricrotism.cryon.velocity.api.command.AnnotationCommands
import com.tricrotism.cryon.velocity.bedrock.BedrockBridge
import com.tricrotism.cryon.velocity.config.VelocityConfig
import com.tricrotism.cryon.velocity.maintenance.MaintenanceCommand
import com.tricrotism.cryon.velocity.maintenance.MaintenanceListener
import com.tricrotism.cryon.velocity.motd.Motd
import com.tricrotism.cryon.velocity.motd.MotdCommand
import com.tricrotism.cryon.velocity.motd.MotdListener
import com.tricrotism.cryon.velocity.network.BackendSynchronizer
import com.tricrotism.cryon.velocity.network.HandoffListener
import com.tricrotism.cryon.velocity.network.ServerAccessListener
import com.tricrotism.cryon.velocity.network.TransferListener
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import kotlinx.coroutines.*
import org.slf4j.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.*

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
    private var presence: Presence? = null

    /**
     * What this proxy calls itself in the presence hash, resolved exactly as `NodeIdentity` resolves a
     * node id, so a proxy and a game server in the same pod agree on their name. The random suffix
     * matters: the presence hash is keyed by this name, so two proxies falling back to the same
     * literal would silently overwrite each other and read as one.
     */
    private val proxyId: String by lazy {
        sequenceOf(System.getenv("CRYON_NODE"), System.getenv("HOSTNAME"))
            .firstOrNull { !it.isNullOrBlank() }
            ?: "proxy-${UUID.randomUUID().toString().take(8)}"
    }

    private var backendSync: BackendSynchronizer? = null
    private var transfers: TransferListener? = null
    private var maintenance: MaintenanceService? = null
    private var maintenanceListener: MaintenanceListener? = null
    private var manager: ModuleManager? = null
    private var loader: VelocityModuleLoader? = null

    /**
     * The proxy's coroutine scope, canceled on shutdown.
     *
     * The proxy twin of `PaperModule.scope`: Velocity's event and command APIs are not suspending,
     * so the places that call into `:common`'s suspending services bridge through this — `launch`
     * for fire-and-forget, `future` where Velocity wants a `CompletionStage` to resume on.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + CryonIO.dispatcher + CoroutineExceptionHandler { _, error ->
            logger.error("Unhandled failure in a Cryon proxy coroutine", error)
        }
    )

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
        setupServerAccess(cfg)
        setupBedrock(services)
        setupMotd(services)
        setupModules(services)
        logger.info("Cryon proxy loader enabled")
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        scope.cancel("The proxy is shutting down")
        manager?.disableAll()
        loader?.close()
        backendSync?.stop()
        transfers?.stop()
        maintenanceListener?.close()
        maintenance?.close()
        registry?.close()
        if (::messenger.isInitialized) messenger.close()
        if (::store.isInitialized) store.close()
        database?.close()
        CryonIO.shutdown()
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
        services.register<MessageService>(messageService)
    }

    private fun ownJar(): File? = runCatching {
        File(javaClass.protectionDomain.codeSource.location.toURI())
    }.getOrNull()

    private fun setupInfrastructure(services: ServiceRegistry, cfg: VelocityConfig) {
        if (cfg.boolean("database.enabled", false)) {
            try {
                val dialect = SqlDialect.of(cfg.string("database.type", "postgresql"))
                val db = SqlDatabase.connect(
                    DatabaseConfig(
                        host = cfg.string("database.host", "localhost"),
                        port = cfg.int("database.port", dialect.defaultPort),
                        database = cfg.string("database.database", "cryon"),
                        username = cfg.string("database.username", "cryon"),
                        password = cfg.string("database.password", ""),
                        maxPoolSize = cfg.int("database.max-pool-size", 10),
                        dialect = dialect,
                    ),
                    logger,
                )
                database = db
                services.register<Database>(db)
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
                messenger = RedisMessenger(config, logger)
                store = RedisKeyValueStore(config)
                sharedTransport = true
                logger.info("Redis connected. State is shared across the network")
            } catch (e: Exception) {
                logger.error("Failed to initialize Redis... falling back to in-process state", e)
                if (::messenger.isInitialized) runCatching { messenger.close() }
                if (::store.isInitialized) runCatching { store.close() }
            }
        }
        if (!sharedTransport) {
            messenger = LocalMessenger(logger)
            store = MemoryKeyValueStore()
            logger.info("State is in-process only (no redis), this proxy sees a static backend list")
        }
        services.register<Messenger>(messenger)
        services.register<KeyValueStore>(store)
    }

    /**
     * Dynamic backends, routing, and the handoff pause. All of it is inherently cross-process (the
     * nodes being discovered and flushed live in other JVMs), so unlike the Paper core's registry
     * this genuinely has nothing to do without a shared transport, and says so.
     */
    private fun setupNetwork(services: ServiceRegistry, cfg: VelocityConfig) {
        if (!sharedTransport) {
            logger.info("Dynamic routing off (no redis). Configure backends in velocity.toml")
            return
        }
        if (!cfg.boolean("network.registry-enabled", true)) {
            logger.info("Server registry disabled by config (network.registry-enabled=false)")
            return
        }
        val heartbeat = cfg.long("network.heartbeat-seconds", 5).coerceAtLeast(1)
        startPresence(Duration.ofSeconds(heartbeat))
        val reg = SharedServerRegistry(store, messenger, database, Duration.ofSeconds(heartbeat * 3), logger)
        reg.init()
        registry = reg
        services.register<ServerRegistry>(reg)
        services.register<PlayerRouter>(DefaultPlayerRouter(reg, messenger))
        backendSync = BackendSynchronizer(proxy, reg, logger).also { it.start() }
        transfers = TransferListener(proxy, messenger, logger).also { it.start() }

        // Hold each backend switch open until the server being left has saved the player. See
        // HandoffListener. Only meaningful once a player can move between nodes at all.
        val timeout = Duration.ofSeconds(cfg.long("network.handoff-timeout-seconds", 5).coerceAtLeast(1))
        proxy.eventManager.register(this, HandoffListener(messenger, reg, timeout, logger, scope))
        logger.info("Player handoff on. Transfers wait up to {}s for the source server to flush", timeout.toSeconds())
    }

    /**
     * Announce this proxy so an operator can see it from anywhere, including from a game server.
     *
     * A proxy is deliberately absent from [ServerRegistry], because a proxy that could be returned by
     * `bestNode` would be a routing bug. [Presence] is the separate, non-routable answer to "is the
     * proxy up", and this is the only thing that publishes one.
     */
    private fun startPresence(interval: Duration) {
        val presence = Presence(store, logger)
        this.presence = presence
        scope.launch {
            while (isActive) {
                presence.announce(
                    PresenceKind.PROXY,
                    proxyId,
                    "${proxy.playerCount} players, ${proxy.allServers.size} backends",
                )
                delay(interval.toMillis())
            }
        }
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
            Duration.ofSeconds(cfg.long("maintenance.refresh-seconds", 30)),
        ).also { it.init() }
        maintenance = service
        services.register<MaintenanceService>(service)
        val listener = MaintenanceListener(service, cfg.int("maintenance.ping-protocol", -1))
        maintenanceListener = listener
        proxy.eventManager.register(this, listener)
        AnnotationCommands.register(proxy.commandManager, MaintenanceCommand(service, proxy, scope))
        logger.info("Maintenance mode available (/maintenance on|off [message], add|remove|list)")
    }

    /**
     * Gate every backend switch on whether the player may actually enter the target: maintenance,
     * node state, and per-server access. Installed after maintenance because it enforces it, and
     * independent of the transport — a static one-node deployment still has both a maintenance
     * toggle and closed servers.
     */
    private fun setupServerAccess(cfg: VelocityConfig) {
        val service = maintenance ?: return
        val restricted = cfg.strings("network.restricted-servers").map { it.lowercase() }.toSet()
        proxy.eventManager.register(this, ServerAccessListener(registry, service, restricted))
        if (restricted.isNotEmpty()) {
            logger.info("Access-restricted servers: {} (permission cryon.server.<id>)", restricted.joinToString())
        }
    }

    /**
     * Bedrock identity, always registered so a proxy feature never branches on whether Geyser is
     * installed. Identity only: nothing on the proxy asks a player a question, so the Cumulus form
     * machinery stays on Paper where the menus are.
     */
    private fun setupBedrock(services: ServiceRegistry) {
        services.register<BedrockService>(BedrockBridge.create(proxy, logger))
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
        services.register<ModuleManager>(mgr)
        val ctx = VelocityContext(proxy, this, logger, services, dataDirectory)
        val ldr = VelocityModuleLoader(mgr, logger, modulesDir, File(dataDir, ".module-cache"), javaClass.classLoader)
        ldr.loadSharedApi(apiDir)
        ldr.prepareCache()
        ldr.registerAll()
        mgr.loadAll(ctx)
        mgr.enableAll()
        mgr.postLoadAll()
        manager = mgr
        loader = ldr
    }
}
