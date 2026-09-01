package com.tricrotism.cryon.geyser

import com.tricrotism.cryon.common.concurrent.CryonIO
import com.tricrotism.cryon.common.config.Config
import com.tricrotism.cryon.common.config.ConfigMigrator
import com.tricrotism.cryon.common.config.CoreKeys
import com.tricrotism.cryon.common.config.YamlConfigSource
import com.tricrotism.cryon.common.data.*
import com.tricrotism.cryon.common.deploy.DeployKeys
import com.tricrotism.cryon.common.deploy.DeployResult
import com.tricrotism.cryon.common.deploy.GitDeploy
import com.tricrotism.cryon.common.locale.DirectoryMessageSource
import com.tricrotism.cryon.common.locale.LangScanner
import com.tricrotism.cryon.common.locale.MessageService
import com.tricrotism.cryon.common.locale.Messages
import com.tricrotism.cryon.common.maintenance.MaintenanceService
import com.tricrotism.cryon.common.maintenance.SharedMaintenanceService
import com.tricrotism.cryon.common.module.JarWatcher
import com.tricrotism.cryon.common.module.ModuleManager
import com.tricrotism.cryon.common.module.PluginPresence
import com.tricrotism.cryon.common.module.ServiceRegistry
import com.tricrotism.cryon.common.net.*
import com.tricrotism.cryon.common.server.Presence
import com.tricrotism.cryon.common.server.PresenceKind
import com.tricrotism.cryon.common.server.ServerRegistry
import com.tricrotism.cryon.common.server.SharedServerRegistry
import com.tricrotism.cryon.geyser.api.command.AnnotationCommands
import com.tricrotism.cryon.geyser.command.ModuleCommands
import com.tricrotism.cryon.geyser.maintenance.MaintenanceCommand
import com.tricrotism.cryon.geyser.maintenance.MaintenanceGate
import com.tricrotism.cryon.geyser.motd.BedrockMotd
import com.tricrotism.cryon.geyser.motd.MotdCommand
import com.tricrotism.cryon.geyser.motd.MotdListener
import kotlinx.coroutines.*
import org.geysermc.event.subscribe.Subscribe
import org.geysermc.geyser.api.GeyserApi
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCommandsEvent
import org.geysermc.geyser.api.event.lifecycle.GeyserPreInitializeEvent
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent
import org.geysermc.geyser.api.extension.Extension
import org.slf4j.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.*

/**
 * The Geyser loader entrypoint, the third platform loader beside the Paper core and
 * `CryonVelocityPlugin`, and deliberately the same shape as the proxy one: read the config, stand up
 * the shared `:common` infrastructure, install the i18n, then load feature modules over the same
 * module system, and tear it all down in dependency order.
 *
 * Geyser registers an extension instance on its own event bus when it enables it, so the `@Subscribe`
 * methods below need no wiring of their own.
 *
 * Everything is stood up on [GeyserPreInitializeEvent], which fires while Geyser is still building
 * itself. That is not a stylistic choice: `GeyserDefineCommandsEvent` is fired from the command
 * registry's constructor later in the same startup, so a module that wants to contribute a command
 * has to exist and be subscribed before then. Post-initialize would be too late for exactly the
 * modules that most want it.
 *
 * What is deliberately *not* here, and belongs to the proxy: player routing and the handoff pause
 * (Geyser is not what connects a player to a backend) and dynamic backend registration.
 */
class CryonGeyserExtension : Extension {

    private lateinit var log: Logger
    private var database: Database? = null
    private var registry: ServerRegistry? = null

    // What this Geyser calls itself in the presence hash, resolved as `NodeIdentity` resolves a node
    // id, random suffix and all: the presence hash is keyed by this name, so two processes falling
    // back to the same literal would silently overwrite each other and read as one
    private val geyserId: String by lazy {
        sequenceOf(System.getenv("CRYON_NODE"), System.getenv("HOSTNAME"))
            .firstOrNull { !it.isNullOrBlank() }
            ?: "geyser-${UUID.randomUUID().toString().take(8)}"
    }

    private var maintenance: MaintenanceService? = null
    private var maintenanceGate: MaintenanceGate? = null
    private var motd: BedrockMotd? = null
    private var manager: ModuleManager? = null
    private var loader: GeyserModuleLoader? = null
    private val watchers = ArrayList<JarWatcher>()
    private var gitDeploy: GitDeploy? = null
    private var deployPoller: java.util.concurrent.ScheduledExecutorService? = null

    // The extension's coroutine scope, cancelled on shutdown. The Geyser twin of the proxy's: the
    // Geyser event and command APIs are not suspending, so this is where calls into `:common`'s
    // suspending services are launched from
    private val scope = CoroutineScope(
        SupervisorJob() + CryonIO.dispatcher + CoroutineExceptionHandler { _, error ->
            log.error("Unhandled failure in a Cryon Geyser coroutine", error)
        }
    )

    private lateinit var messenger: Messenger
    private lateinit var store: KeyValueStore
    private var sharedTransport = false

    @Subscribe
    fun onPreInitialize(event: GeyserPreInitializeEvent) {
        log = GeyserLoggerAdapter(logger())
        val dataDirectory = dataFolder()
        val cfg = loadConfig(dataDirectory)
        val services = ServiceRegistry(log)
        setupLocale(services, dataDirectory)
        setupInfrastructure(services, cfg)
        setupNetwork(services, cfg)
        setupMaintenance(services, cfg)
        setupMotd(dataDirectory)
        setupModules(services, dataDirectory, cfg)
        startGitDeploy(services, dataDirectory, cfg)
        log.info("Cryon Geyser loader enabled")
    }

    /**
     * Contribute the loader's own commands. Modules subscribe to this event themselves through
     * `GeyserModule.subscribe`, which is why they are loaded before it fires.
     */
    @Subscribe
    fun onDefineCommands(event: GeyserDefineCommandsEvent) {
        val service = maintenance ?: return
        val bedrockMotd = motd ?: return
        AnnotationCommands.register(
            event,
            this,
            MaintenanceCommand(service, GeyserApi.api(), scope),
            MotdCommand(bedrockMotd),
        )
        val mgr = manager
        val ldr = loader
        if (mgr != null && ldr != null) AnnotationCommands.register(event, this, ModuleCommands(mgr, ldr))
    }

    @Subscribe
    fun onShutdown(event: GeyserShutdownEvent) {
        deployPoller?.shutdownNow()
        deployPoller = null
        gitDeploy = null
        watchers.forEach { runCatching { it.close() } }
        watchers.clear()
        manager?.disableAll()
        loader?.close()
        maintenanceGate?.close()
        maintenance?.close()
        registry?.close()
        if (::messenger.isInitialized) messenger.close()
        if (::store.isInitialized) store.close()
        database?.close()
        // Last: a launch on a canceled scope is silently inert, so cancelling ahead of the teardown
        // above would drop the work it dispatches through this scope without a line in the log.
        scope.cancel("Geyser is shutting down")
        SpillStore.install(null)
        CryonIO.shutdown()
    }

    /**
     * Load `config.yml`, first bringing it up to date with the one shipped in this jar so a key
     * added since it was written is actually there to read. See [ConfigMigrator].
     */
    private fun loadConfig(dataDirectory: Path): Config {
        Files.createDirectories(dataDirectory)
        val configFile = dataDirectory.resolve("config.yml")
        val template = javaClass.getResourceAsStream("/config.yml")?.use { it.readBytes().decodeToString() }
        if (template == null) {
            log.error("config.yml is missing from the Cryon jar, so defaults cannot be migrated")
        } else {
            runCatching { ConfigMigrator.migrate(template, configFile, log) }
                .onFailure { log.error("Failed to migrate config.yml, continuing with what is on disk", it) }
                .onSuccess { if (it) log.info("config.yml updated with keys added since it was written") }
        }
        return Config(YamlConfigSource.load(configFile))
    }

    /**
     * Bootstrap the shared i18n, mirroring the proxy so Geyser-side output localizes by client
     * locale. The admin `lang/` folder under the extension's data directory is added first (so it
     * overrides), then the bundle inside this jar.
     */
    private fun setupLocale(services: ServiceRegistry, dataDirectory: Path) {
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

    private fun setupInfrastructure(services: ServiceRegistry, cfg: Config) {
        if (cfg[CoreKeys.DATABASE_ENABLED]) {
            try {
                val dialect = SqlDialect.of(cfg[CoreKeys.DATABASE_TYPE])
                val db = SqlDatabase.connect(
                    DatabaseConfig(
                        host = cfg[CoreKeys.DATABASE_HOST],
                        port = cfg.find(CoreKeys.DATABASE_PORT) ?: dialect.defaultPort,
                        database = cfg[CoreKeys.DATABASE_NAME],
                        username = cfg[CoreKeys.DATABASE_USERNAME],
                        password = cfg[CoreKeys.DATABASE_PASSWORD],
                        maxPoolSize = cfg[CoreKeys.DATABASE_MAX_POOL_SIZE],
                        dialect = dialect,
                    ),
                    log,
                )
                database = db
                services.register<Database>(db)
                log.info("Database connected ({})", dialect.id)
            } catch (e: Exception) {
                log.error("Failed to initialize the database... continuing without it", e)
            }
        }
        setupTransport(services, cfg)
    }

    /**
     * Install the transport every other service is built on. Mirrors the Paper core and the proxy.
     */
    private fun setupTransport(services: ServiceRegistry, cfg: Config) {
        if (cfg[CoreKeys.REDIS_ENABLED]) {
            try {
                val config = RedisConfig(cfg[CoreKeys.REDIS_URI])
                messenger = RedisMessenger(config, log)
                store = RedisKeyValueStore(config)
                sharedTransport = true
                log.info("Redis connected. State is shared across the network")
            } catch (e: Exception) {
                log.error("Failed to initialize Redis... falling back to in-process state", e)
                if (::messenger.isInitialized) runCatching { messenger.close() }
                if (::store.isInitialized) runCatching { store.close() }
            }
        }
        if (!sharedTransport) {
            messenger = LocalMessenger(log)
            store = MemoryKeyValueStore()
            log.info("State is in-process only (no redis), this Geyser sees nothing the network does")
        }
        services.register<Messenger>(messenger)
        services.register<KeyValueStore>(store)
    }

    /**
     * The read-only half of the network layer. Geyser reads the registry so a feature module can
     * answer "which servers are up" without a proxy handle; it never registers a node of its own,
     * because Geyser is not a place a player can be, and it never routes, because the connect is
     * performed by the proxy.
     */
    private fun setupNetwork(services: ServiceRegistry, cfg: Config) {
        if (!sharedTransport) {
            log.info("Server registry off (no redis). Geyser reads no network state")
            return
        }
        if (!cfg[CoreKeys.REGISTRY_ENABLED]) {
            log.info("Server registry disabled by config (network.registry-enabled=false)")
            return
        }
        val heartbeat = cfg[CoreKeys.HEARTBEAT_SECONDS]
        startPresence(Duration.ofSeconds(heartbeat))
        val reg = SharedServerRegistry(store, messenger, database, Duration.ofSeconds(heartbeat * 3), log)
        reg.init()
        registry = reg
        services.register<ServerRegistry>(reg)
    }

    /**
     * Announce this Geyser so an operator can see from a game server whether Bedrock is hooked up.
     *
     * Geyser registers no node, because it is not somewhere a player can be routed. [Presence] is the
     * separate, non-routable answer to "is Geyser up", and this is the only thing that publishes one.
     */
    private fun startPresence(interval: Duration) {
        val presence = Presence(store, log)
        scope.launch {
            while (isActive) {
                presence.announce(
                    PresenceKind.GEYSER,
                    geyserId,
                    "${geyserApi().onlineConnections().size} bedrock online",
                )
                delay(interval.toMillis())
            }
        }
    }

    /**
     * The same network-wide maintenance state the proxies hold, so Geyser can refuse a Bedrock login
     * at its own edge rather than letting the player travel to the proxy to be kicked there. See
     * [MaintenanceGate] for the one rule that cannot be enforced this early.
     */
    private fun setupMaintenance(services: ServiceRegistry, cfg: Config) {
        val service = SharedMaintenanceService(
            database,
            messenger,
            cfg[CoreKeys.MAINTENANCE_MESSAGE],
            log,
            Duration.ofSeconds(cfg[CoreKeys.MAINTENANCE_REFRESH_SECONDS]),
        ).also { it.init() }
        maintenance = service
        services.register<MaintenanceService>(service)
        val gate = MaintenanceGate(service)
        maintenanceGate = gate
        eventBus().register(gate)
        log.info("Maintenance mode available (/maintenance on|off [message], add|remove|list)")
    }

    /**
     * The Bedrock server-list MOTD, reading the same `motd.*` block the proxy does.
     */
    private fun setupMotd(dataDirectory: Path) {
        val service = maintenance ?: return
        val bedrockMotd = BedrockMotd(dataDirectory.resolve("config.yml")).also { it.reload() }
        motd = bedrockMotd
        eventBus().register(MotdListener(bedrockMotd, service))
        log.info("Bedrock MOTD available (/motd reload)")
    }

    private fun setupModules(services: ServiceRegistry, dataDirectory: Path, cfg: Config) {
        val dataDir = dataDirectory.toFile()
        val apiDir = File(dataDir, "api").apply { mkdirs() }
        val modulesDir = File(dataDir, "modules").apply { mkdirs() }
        // Before any module builds a repository. See the Paper loader for why it sits beside
        // `modules/` rather than inside it.
        SpillStore.install(dataDirectory.resolve(".spill"))
        val mgr = ModuleManager(log, PluginPresence { GeyserApi.api().extensionManager().extension(it) != null })
        services.register<ModuleManager>(mgr)
        val ctx = GeyserContext(GeyserApi.api(), this, log, services, dataDirectory)
        val ldr = GeyserModuleLoader(
            mgr, ctx, log, modulesDir, File(dataDir, ".module-cache"), javaClass.classLoader,
        )
        ldr.loadSharedApi(apiDir)
        ldr.prepareCache()
        ldr.registerAll()
        mgr.loadAll(ctx)
        mgr.enableAll()
        mgr.postLoadAll()
        manager = mgr
        loader = ldr
        startWatchers(cfg, modulesDir, apiDir, ldr)
    }

    /**
     * Start the dev hot-reload watchers, the Geyser twin of the Paper core's. Gated by
     * `modules.auto-reload`, which defaults to `!production`, so a dev deployment picks up a replaced
     * feature jar on its own and a production one does not. `/cryon modules load|unload|scan|reload-api`
     * works either way. Best-effort: a watcher that fails to start degrades to manual hot-swap.
     */
    /**
     * Poll the deploy repository, the Geyser twin of the core's. See `GitDeploy`.
     *
     * `{server}` resolves to `geyser`: like a proxy, this belongs to no pool, so a repository holds
     * its config at `servers/geyser/config.yml`.
     *
     * Its own single daemon thread because Geyser exposes no scheduler to an extension. Shut down
     * with `shutdownNow` rather than drained: a poll in flight has already recorded whatever it
     * wrote, and the next start re-applies the same commit anyway.
     */
    private fun startGitDeploy(services: ServiceRegistry, dataDirectory: Path, cfg: Config) {
        val configFile = dataDirectory.resolve("config.yml")
        val deploy = GitDeploy.from(
            config = cfg,
            serverId = "geyser",
            dataFolder = dataDirectory,
            configFile = configFile,
            langDirectory = dataDirectory.resolve("lang"),
            modulesDirectory = dataDirectory.resolve("modules"),
            onConfigChanged = {
                cfg.reload(YamlConfigSource.load(configFile))
                    .forEach { log.error("A config reload listener failed", it) }
                log.info("config.yml reloaded from the deploy repository")
            },
            onLangChanged = {
                services.find(MessageService::class)?.reload()
                log.info("Language files reloaded from the deploy repository")
            },
            logger = log,
        ) ?: return

        gitDeploy = deploy
        val seconds = cfg[DeployKeys.POLL_SECONDS]
        val poller = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "Cryon-Deploy").apply { isDaemon = true }
        }
        deployPoller = poller
        poller.scheduleWithFixedDelay({
            when (val result = deploy.poll()) {
                is DeployResult.UpToDate -> Unit
                is DeployResult.Applied -> log.info(
                    "Deployed {} ({} file(s)): {}",
                    result.commit.take(8), result.changed.size, result.changed.joinToString(", "),
                )

                is DeployResult.Failed -> log.warn("Deploy poll failed: {}", result.reason)
            }
        }, 15, seconds, java.util.concurrent.TimeUnit.SECONDS)
        log.info("Deploying branch {} every {}s", cfg[DeployKeys.BRANCH], seconds)
    }

    private fun startWatchers(cfg: Config, modulesDir: File, apiDir: File, ldr: GeyserModuleLoader) {
        val production = cfg[CoreKeys.PRODUCTION]
        if (!(cfg.find(CoreKeys.MODULES_AUTO_RELOAD) ?: !production)) {
            log.info(
                "Hot-reload watchers off (production={}); use /cryon modules load|unload|scan|reload-api to hot-swap",
                production,
            )
            return
        }
        startWatcher(modulesDir, "modules", ldr, onChanged = { ldr.loadJar(it) }, onDeleted = { ldr.unloadJar(it) })
        val reloadApi: (File) -> Unit = { ldr.reloadApi() }
        startWatcher(apiDir, "api", ldr, onChanged = reloadApi, onDeleted = reloadApi)
    }

    private fun startWatcher(
        dir: File,
        label: String,
        ldr: GeyserModuleLoader,
        onChanged: (File) -> Unit,
        onDeleted: (File) -> Unit,
    ) {
        try {
            watchers += JarWatcher(dir, log, ldr::submit, onChanged, onDeleted).also { it.start() }
        } catch (e: Exception) {
            log.error("Failed to start the {} watcher; falling back to manual hot-swap", label, e)
        }
    }
}
