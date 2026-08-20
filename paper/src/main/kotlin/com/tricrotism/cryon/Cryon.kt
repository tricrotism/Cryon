package com.tricrotism.cryon

import com.github.retrooper.packetevents.PacketEvents
import com.tricrotism.cryon.bedrock.BedrockBridge
import com.tricrotism.cryon.command.*
import com.tricrotism.cryon.common.colony.Colony
import com.tricrotism.cryon.common.colony.SharedColony
import com.tricrotism.cryon.common.concurrent.CryonIO
import com.tricrotism.cryon.common.cooldown.CooldownService
import com.tricrotism.cryon.common.cooldown.MemoryCooldowns
import com.tricrotism.cryon.common.currency.Currencies
import com.tricrotism.cryon.common.currency.CurrencyService
import com.tricrotism.cryon.common.data.Database
import com.tricrotism.cryon.common.data.DatabaseConfig
import com.tricrotism.cryon.common.data.SqlDatabase
import com.tricrotism.cryon.common.data.SqlDialect
import com.tricrotism.cryon.common.diagnostic.Retention
import com.tricrotism.cryon.common.flag.FeatureFlags
import com.tricrotism.cryon.common.locale.*
import com.tricrotism.cryon.common.lock.DistributedLock
import com.tricrotism.cryon.common.lock.StoreDistributedLock
import com.tricrotism.cryon.common.module.ModuleManager
import com.tricrotism.cryon.common.module.ServiceRegistry
import com.tricrotism.cryon.common.module.remote.RemoteModules
import com.tricrotism.cryon.common.module.remote.UpdateResult
import com.tricrotism.cryon.common.net.*
import com.tricrotism.cryon.common.server.*
import com.tricrotism.cryon.common.signal.LocalSignals
import com.tricrotism.cryon.common.signal.Signals
import com.tricrotism.cryon.common.text.Mini
import com.tricrotism.cryon.inventory.DefaultInventorySearch
import com.tricrotism.cryon.menu.AdminMenu
import com.tricrotism.cryon.module.*
import com.tricrotism.cryon.network.NetworkStatus
import com.tricrotism.cryon.network.NodeReporter
import com.tricrotism.cryon.network.agones.AgonesClient
import com.tricrotism.cryon.network.agones.AgonesLifecycle
import com.tricrotism.cryon.paper.api.CryonPaper
import com.tricrotism.cryon.paper.api.PaperModuleContext
import com.tricrotism.cryon.paper.api.bar.ActionBars
import com.tricrotism.cryon.paper.api.bar.BossBars
import com.tricrotism.cryon.paper.api.bedrock.BedrockService
import com.tricrotism.cryon.paper.api.command.CommandService
import com.tricrotism.cryon.paper.api.event.Events
import com.tricrotism.cryon.paper.api.event.Subscription
import com.tricrotism.cryon.paper.api.inventory.InventorySearch
import com.tricrotism.cryon.paper.api.menu.MenuPalette
import com.tricrotism.cryon.paper.api.placeholder.PlaceholderService
import com.tricrotism.cryon.paper.api.scheduler.CryonDispatchers
import com.tricrotism.cryon.paper.api.scheduler.Schedulers
import com.tricrotism.cryon.papi.CorePlaceholders
import com.tricrotism.cryon.papi.PapiBridge
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kotlinx.coroutines.*
import org.bukkit.Server
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.event.EventPriority
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import xyz.xenondevs.invui.InvUI
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * The bootstrap. On load it scans `plugins/Cryon/modules/` for feature jars and loads each in its
 * own isolated classloader (parent exposes the shared API + Paper + kotlin-stdlib bundled here),
 * discovering its [Module]s via [ServiceLoader]; on enable it stands up the infrastructure and
 * drives the load → enable lifecycle. Features intertwine through the [ServiceRegistry], never by
 * referencing each other's classes.
 */
class Cryon : JavaPlugin() {

    private val log: Logger = LoggerFactory.getLogger("Cryon")
    private lateinit var manager: ModuleManager
    private lateinit var loader: ModuleLoader
    private lateinit var commandRegistry: CommandRegistry
    private val watchers = ArrayList<ModuleWatcher>()
    private var remoteModules: RemoteModules? = null
    private var remoteTask: ScheduledTask? = null

    private lateinit var messageService: MessageService
    private lateinit var services: ServiceRegistry
    private lateinit var context: PaperModuleContext
    private lateinit var apiDir: File
    private lateinit var modulesDir: File

    private lateinit var featureFlags: FeatureFlags
    private var currencies: Currencies? = null
    private var leaderboardTask: ScheduledTask? = null
    private var presenceTask: ScheduledTask? = null
    private var database: Database? = null
    private var localeStore: LocaleStore? = null
    private var registry: ServerRegistry? = null
    private var reporter: NodeReporter? = null
    private var agonesLifecycle: AgonesLifecycle? = null
    private var handoff: HandoffCoordinator? = null
    private var corePlaceholders: AutoCloseable? = null
    private var adminMenu: AdminMenu? = null
    private var bedrockService: AutoCloseable? = null
    private var heartbeatSeconds: Long = 5
    private val subscriptions = ArrayList<Subscription>()

    private var colony: SharedColony? = null
    private var colonyTask: ScheduledTask? = null
    private val retention = Retention()

    private val scope = CoroutineScope(
        SupervisorJob() + CryonDispatchers.Global + CoroutineExceptionHandler { _, error ->
            log.error("Unhandled failure in a Cryon core coroutine", error)
        }
    )

    // The transport. Always installed: Redis when configured, in-process otherwise, so the services
    // above it have one implementation each and features never branch on the deployment shape.
    private lateinit var messenger: Messenger
    private lateinit var store: KeyValueStore
    private lateinit var identity: NodeIdentity

    /** Whether the transport reaches other processes. Cross-process-only services hang off this. */
    private var sharedTransport = false

    /**
     * Discover the feature jars and give them their one shot at the pre-enable world. Bukkit calls
     * `onLoad` on every plugin before it enables any, so this is the only phase from which a module
     * can reach a third-party registry that seals itself on enable, WorldGuard's flag registry
     * being the case this exists for. See `Module.preLoad`.
     *
     * Only jar discovery lives here. Everything that depends on Cryon's own infrastructure (the
     * database, the transport, commands, placeholders, menus) stays in [onEnable], and so does the
     * `onLoad` → `onEnable` drive that modules actually build on.
     */
    override fun onLoad() {
        CryonPaper.init(this) // so Schedulers/Events can reach the plugin
        initPackets()

        messageService = MessageService()
        Messages.install(messageService)
        Mini.format("<off_white>")
        registerAdminLang(messageService)
        registerOwnLang(messageService)

        services = ServiceRegistry(log).apply { register<MessageService>(messageService) }

        manager = ModuleManager(log)
        services.register<ModuleManager>(manager) // so modules can query their own enabled-state

        context = CryonContext(this, server, log, services)

        apiDir = File(dataFolder, "api").apply { mkdirs() }
        modulesDir = File(dataFolder, "modules").apply { mkdirs() }
        loader = ModuleLoader(
            manager,
            messageService,
            context,
            log,
            modulesDir,
            File(dataFolder, ".module-cache"),
            javaClass.classLoader,
            retention,
        )

        loader.loadSharedApi(apiDir)
        loader.prepareCache()
        loader.registerAll()

        manager.preLoadAll(context)
    }

    override fun onEnable() {
        initMenus()
        runCatching { PacketEvents.getAPI()?.init() }
            .onFailure { log.error("Failed to start the packet layer; Packets subscriptions will not fire", it) }

        setupInfrastructure(services)

        setupNetwork(services)
        val status = reportNetwork(services)

        commandRegistry = CommandRegistry(server, log)
        services.register<CommandService>(commandRegistry)

        val papi = PapiBridge(this, log)
        services.register<PlaceholderService>(papi)

        val bedrock = BedrockBridge.create(log)
        services.register<BedrockService>(bedrock)
        bedrockService = bedrock as? AutoCloseable
        corePlaceholders = papi.register(CORE_COMMAND_OWNER, CorePlaceholders(identity))

        val menu = AdminMenu(manager, featureFlags, status, bedrock, scope)
        adminMenu = menu

        manager.loadAll(context)
        manager.enableAll()
        manager.postLoadAll()

        seedAdminLang(messageService)

        startRemoteModules(modulesDir)
        bootstrapCommands(
            messageService,
            status,
            papi,
            menu
        )
        startWatchers(modulesDir, apiDir)
        announceReady(services)

        val authors = pluginMeta.authors.joinToString(", ").ifEmpty { "Cryon" }
        Schedulers.globalLater(1) { SparkSupport.install(server, loader, authors, log) }
    }

    /**
     * Start the dev hot-reload watchers when enabled. They run when `modules.auto-reload` is true,
     * which **defaults to `!production`**, so a `production: false` (dev) server watches `modules/`
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

        val reloadApi: (File) -> Unit =
            { runCatching { loader.reloadApi() }.onFailure { log.error("api/ reload failed", it) } }
        startWatcher(apiDir, "api", onChanged = reloadApi, onDeleted = reloadApi)
    }

    /**
     * Start polling a remote Maven repository for new feature jars, when `remote.enabled` is on.
     *
     * The poller only writes files into `modules/`. Whether a downloaded build then *applies* is
     * decided by `modules.auto-reload` and nothing else: with the watcher running a replaced jar is
     * hot-swapped exactly as if an admin had dropped it in, and with the watcher off the build waits
     * on disk for the next restart or an explicit `/cryon load`. There is deliberately no second
     * switch, because a remote update that could apply while local ones could not would be a
     * surprise nobody asked for.
     *
     * The first poll is delayed rather than run at boot: blocking startup on a network fetch would
     * make an unreachable repository an unbootable server, and the modules already on disk are the
     * ones this server was last known to run.
     */
    private fun startRemoteModules(modulesDir: File) {
        val remote = RemoteModuleConfig.build(config, modulesDir, dataFolder, log) ?: return
        remoteModules = remote
        val seconds = config.getLong("remote.poll-seconds", 300).coerceAtLeast(30)
        remoteTask = Schedulers.asyncTimer(REMOTE_FIRST_POLL_SECONDS, seconds, TimeUnit.SECONDS) {
            scope.launch { reportRemote(remote.pollAll()) }
        }
    }

    /**
     * Log what a poll did, and say plainly when a build landed but will not run yet, since a jar
     * that changed on disk without changing in memory is the one outcome an operator would
     * otherwise have to infer.
     */
    private fun reportRemote(results: List<UpdateResult>) {
        val applies = config.getBoolean("modules.auto-reload", !config.getBoolean("production", true))
        for (result in results) when (result) {
            is UpdateResult.Installed -> if (applies) {
                log.info("Remote module {} updated to {}", result.artifact, result.to)
            } else {
                log.info(
                    "Remote module {} downloaded ({}), and applies on restart or /cryon load {}",
                    result.artifact,
                    result.to,
                    result.artifact.fileName,
                )
            }

            is UpdateResult.Failed ->
                log.warn("Remote module {} could not be updated: {}", result.artifact, result.reason)

            is UpdateResult.UpToDate -> Unit
        }
    }

    private fun startWatcher(dir: File, label: String, onChanged: (File) -> Unit, onDeleted: (File) -> Unit) {
        try {
            watchers += ModuleWatcher(dir, log, onChanged, onDeleted).also { it.start() }
        } catch (e: Exception) {
            log.error("Failed to start the {} watcher; falling back to manual hot-swap", label, e)
        }
    }

    /**
     * Bind InvUI to this plugin and install the shared menu ingredients.
     *
     * Menus are core infrastructure: InvUI is shaded here, so it binds once and feature modules only
     * build `Gui`s. [InvUI.setPlugin] throws if called twice, so a module must never call it, and it
     * has to run before any module can open a window, hence its position at the top of `onEnable`.
     * Not `onLoad`, even though that phase now exists: it belongs to `Module.preLoad`, which is
     * forbidden from touching anything but the third-party registry it came for.
     */
    private fun initMenus() {
        if (menusBound) return
        val invui = InvUI.getInstance()
        invui.setPlugin(this)
        invui.setExceptionHandler { message, error -> log.error("InvUI: {}", message, error) }
        MenuPalette.installGlobalIngredients()
        menusBound = true
    }

    /**
     * Bind PacketEvents to this plugin, so `Packets` works for the core and every feature.
     *
     * Like InvUI this is core infrastructure a module must never set up itself: the library is shaded
     * here unrelocated and its API is a static singleton, so one `setAPI` in one classloader is the
     * whole contract. `load()` belongs in `onLoad` (it installs the injector before any connection can
     * exist); `init()` runs in `onEnable`, ahead of the modules that subscribe.
     *
     * Best-effort, matching spark and PlaceholderAPI: a failure here logs and leaves `Packets`
     * unavailable rather than taking the server down with it.
     */
    private fun initPackets() {
        try {
            PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this))
            PacketEvents.getAPI().settings.checkForUpdates(false) // the core pins the version
            PacketEvents.getAPI().load()
        } catch (t: Throwable) {
            log.error("Failed to load the packet layer; Packets subscriptions will not fire", t)
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
     * of an empty folder. Only missing keys are added and existing overrides are preserved, then the
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
        menu: AdminMenu,
    ) {
        val menuFirst = config.getBoolean("commands.menu", true)
        val handlers = mutableListOf(
            ModuleCommands(
                manager, loader, featureFlags, commandRegistry, status, messageService, placeholders, menu,
                menuFirst, retention, remoteModules, scope,
            ),
            LanguageCommands(messageService, scope),
        )
        currencies?.let { service ->
            handlers += BalanceCommand(service, messageService, scope)
            handlers += PayCommand(service, messageService, scope)
            handlers += CurrencyAdminCommands(service, messageService, scope)
        }
        commandRegistry.register(CORE_COMMAND_OWNER, { true }, handlers)
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
                val db = SqlDatabase.connect(
                    DatabaseConfig(
                        host = cfg.getString("database.host", "localhost")!!,
                        port = cfg.getInt("database.port", dialect.defaultPort),
                        database = cfg.getString("database.database", "cryon")!!,
                        username = cfg.getString("database.username", "cryon")!!,
                        password = cfg.getString("database.password", "")!!,
                        maxPoolSize = cfg.getInt("database.max-pool-size", 10),
                        dialect = dialect,
                    ),
                    log,
                )
                database = db
                services.register<Database>(db)
                log.info("Database connected (${dialect.id})")
            } catch (e: Exception) {
                log.error("Failed to initialize the database... continuing without it", e)
            }
        }

        setupTransport(services, cfg)

        identity = resolveIdentity(cfg)
        services.register<NodeIdentity>(identity)
        heartbeatSeconds = cfg.getLong("network.heartbeat-seconds", 5).coerceAtLeast(1)

        featureFlags = FeatureFlags(identity.serverId, database, messenger, log)
        featureFlags.init()
        services.register<FeatureFlags>(featureFlags)

        setupCurrency(services, cfg)

        services.register<InventorySearch>(DefaultInventorySearch())
        BossBars.install()
        ActionBars.install()
        services.register<CooldownService>(MemoryCooldowns())
        services.register<Retention>(retention)
        services.register<Signals>(LocalSignals(log))
        services.register<DistributedLock>(StoreDistributedLock(store, log))

        val db = database
        val locale: LocaleStore = if (db != null) {
            PlayerLocaleStore(db, messenger).also { s ->
                scope.launch {
                    runCatching { s.init() }.onFailure { log.error("Failed to create locale table", it) }
                }
                subscriptions += Events.subscribe<PlayerJoinEvent>()
                    .handler { event ->
                        val uuid = event.player.uniqueId
                        scope.launch { s.load(uuid) }
                    }
                subscriptions += Events.subscribe<PlayerQuitEvent>()
                    .handler { event -> s.unload(event.player.uniqueId) }
                log.info("Persistent player locale enabled")
            }
        } else {
            log.info("Player locale overrides are in-memory only (no database), they reset on restart")
            MemoryLocaleStore()
        }
        localeStore = locale
        Locales.install(locale)
    }

    /**
     * Stand up the currency ledger, if `currency.enabled` says this server has an economy.
     *
     * **Off unless asked for.** A ledger nobody spends through is a table, a broadcast channel and
     * three commands that only get in the way, and the servers that want one say so; the ones that
     * don't should not have to turn it off. Off, no [CurrencyService] is registered and the
     * `/balance`, `/pay` and `/currency` commands are never contributed, so a feature resolving it
     * with `find` gets null and has to say so rather than spend against a service that isn't there.
     * Nothing is destroyed either way: the table keeps its rows and they are exactly where they were
     * when it is switched on again.
     */
    private fun setupCurrency(services: ServiceRegistry, cfg: FileConfiguration) {
        if (!cfg.getBoolean("currency.enabled", false)) {
            log.info("Currencies are off; set currency.enabled=true in config.yml to register the service")
            return
        }
        val service = Currencies(identity.serverId, database, messenger, log)
        scope.launch { service.init() }
        currencies = service
        services.register<CurrencyService>(service)
        val rankSeconds = cfg.getLong("currency.leaderboard-refresh-seconds", 300).coerceAtLeast(30)
        leaderboardTask = Schedulers.asyncTimer(rankSeconds, rankSeconds, TimeUnit.SECONDS) {
            scope.launch { service.refreshLeaderboards() }
        }
    }

    /**
     * Install the [Messenger] + [KeyValueStore] every other service is built on. Redis when it is
     * configured *and* reachable, this process otherwise. Either way both are registered, so nothing
     * downstream has to cope with their absence. A Redis that fails to connect falls back rather than
     * half-installing: a live messenger beside a dead store is the one state no caller expects.
     */
    private fun setupTransport(services: ServiceRegistry, cfg: FileConfiguration) {
        if (cfg.getBoolean("redis.enabled")) {
            try {
                val redisConfig = RedisConfig(cfg.getString("redis.uri", "redis://localhost:6379/0")!!)
                messenger = RedisMessenger(redisConfig, log)
                store = RedisKeyValueStore(redisConfig)
                sharedTransport = true
                log.info("Redis connected. State is shared across the network")
            } catch (e: Exception) {
                log.error("Failed to initialize Redis... falling back to in-process state", e)
                closeQuietly()
            }
        }
        if (!sharedTransport) {
            messenger = LocalMessenger(log)
            store = MemoryKeyValueStore()
            log.info("State is in-process only (no redis). Correct for a single server, not for a pool")
        }
        services.register<Messenger>(messenger)
        services.register<KeyValueStore>(store)
    }

    /** Drop whatever a half-finished Redis setup managed to open. */
    private fun closeQuietly() {
        if (::messenger.isInitialized) runCatching { messenger.close() }
        if (::store.isInitialized) runCatching { store.close() }
    }

    /**
     * Resolve this process's network identity, env-first, falling back to config and Paper's own
     * values.
     *
     * `network.family`/`network.instance-id`/`network.mode` are the names these keys had before they
     * were renamed to match what operators call them, and are still read so an existing config keeps
     * working. Deprecated: they are announced once at boot and go away next release.
     */
    private fun resolveIdentity(cfg: FileConfiguration): NodeIdentity = NodeIdentity.resolve(
        configServerId = cfg.legacy("network.server", "network.family") ?: cfg.getString("server-name"),
        configNodeId = cfg.legacy("network.node", "network.instance-id"),
        configAddress = cfg.getString("network.address"),
        configPort = cfg.getInt("network.port", 0),
        fallbackPort = server.port,
        configMaxPlayers = cfg.getInt("network.max-players", 0),
        fallbackMaxPlayers = server.maxPlayers,
        configExpectation = cfg.legacy("network.expect", "network.mode"),
        onUnknownExpectation = { log.error("Unknown network.expect '{}'. Falling back to one-node", it) },
    )

    /**
     * Read [key], falling back to the name it used to have and saying so once.
     *
     * Warns rather than failing: an operator who has not read the changelog should get a running
     * server and a line telling them what to rename, not a boot failure over a word.
     */
    private fun FileConfiguration.legacy(key: String, former: String): String? {
        if (isSet(key)) return getString(key)?.takeIf { it.isNotBlank() }
        if (isSet(former)) {
            val old = getString(former)?.takeIf { it.isNotBlank() }
            if (old != null) {
                log.warn("config.yml: '{}' has been renamed to '{}'. Still honoured, but rename it", former, key)
                return old
            }
        }
        return getString(key)?.takeIf { it.isNotBlank() }
    }

    /**
     * Wire the services features resolve during load: the registry, the router, and player handoff.
     * Runs **before** modules load, so a module can register its flush and read the registry in
     * `onLoad`/`onEnable`, but deliberately stops short of announcing this server as ready, which is
     * [announceReady]'s job once the modules that will actually serve players are enabled.
     *
     * The registry is installed on either transport: over the in-process one it simply contains this
     * server alone, which is what a single-server deployment *is* rather than a degraded mode of a
     * pool. Gated by `network.registry-enabled`.
     */
    private fun setupNetwork(services: ServiceRegistry) {
        setupHandoff(services)
        setupColony(services)
        if (!config.getBoolean("network.registry-enabled", true)) {
            log.info("Server registry disabled by config (network.registry-enabled=false)")
            return
        }
        val reg = SharedServerRegistry(store, messenger, database, Duration.ofSeconds(heartbeatSeconds * 3), log)
        reg.init()
        registry = reg
        services.register<ServerRegistry>(reg)

        if (sharedTransport) services.register<PlayerRouter>(DefaultPlayerRouter(reg, messenger))
        services.register<Provisioner>(
            RegistryProvisioner(reg, { services.find<NodeAllocator>() }, log)
        )
        reporter = NodeReporter(reg, identity, server, Duration.ofSeconds(heartbeatSeconds), log, scope)
            .also { it.register() }
    }

    private fun setupColony(services: ServiceRegistry) {
        val instance = SharedColony(
            identity.nodeId,
            identity.serverId,
            store,
            log,
            Duration.ofSeconds(heartbeatSeconds * 3),
        )
        colony = instance
        services.register<Colony>(instance)
        colonyTask = Schedulers.asyncTimer(heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS) {
            scope.launch { instance.tick() }
        }
    }

    /**
     * Install the flush registry and drive it from quit. Handled at [EventPriority.MONITOR] so every
     * module's own quit handler has finished updating its state before we write that state down.
     */
    private fun setupHandoff(services: ServiceRegistry) {
        val coordinator = HandoffCoordinator(identity.nodeId, messenger, log)
        coordinator.init()
        handoff = coordinator
        services.register<PlayerHandoff>(coordinator)
        subscriptions += Events.subscribe<PlayerQuitEvent>(EventPriority.MONITOR)
            .handler { event ->
                val uuid = event.player.uniqueId
                scope.launch { coordinator.flushOnQuit(uuid) }
            }
    }

    /** Advertise this server as READY, now that the modules serving its players are enabled. */
    private fun announceReady(services: ServiceRegistry) {
        val rep = reporter ?: return
        val reg = registry ?: return
        rep.ready()
        setupAgones(services, identity.serverId, rep, reg)
    }

    /** Say what this server is, and make any disagreement with `network.expect` impossible to miss. */
    private fun reportNetwork(services: ServiceRegistry): NetworkStatus {
        val status = NetworkStatus(identity, sharedTransport, database != null) { registry }
        services.register<NetworkStatus>(status)
        status.report(log)
        startPresenceRefresh(status)
        return status
    }

    /**
     * Keep [NetworkStatus]'s view of the proxies and Geysers current.
     *
     * They announce into a hash rather than the registry, because neither is somewhere a player can be
     * routed, so reading them is a suspending call that `/cryon network` and the admin menu cannot
     * make while drawing. The timer holds a snapshot for them, the same shape as the leaderboard
     * refresh. Pointless without a shared transport, where the hash only ever contains this process.
     */
    private fun startPresenceRefresh(status: NetworkStatus) {
        if (!sharedTransport) return
        val presence = Presence(store, log)
        presenceTask = Schedulers.asyncTimer(0, heartbeatSeconds, TimeUnit.SECONDS) {
            scope.launch { status.updatePresence(presence.all()) }
        }
    }

    /** Attach the Agones lifecycle when running under a sidecar; a no-op anywhere else. */
    private fun setupAgones(
        services: ServiceRegistry,
        serverId: String,
        reporter: NodeReporter,
        registry: ServerRegistry
    ) {
        val agones = AgonesClient.detect(log) ?: return
        // shutdown-when-empty differs per serverId (persistent shards reclaim; ephemeral self-shutdown on
        // match end), so it's env-first, one shared config file, per-Fleet env override.
        val shutdownWhenEmpty = System.getenv("CRYON_AGONES_SHUTDOWN_WHEN_EMPTY")?.toBooleanStrictOrNull()
            ?: config.getBoolean("network.agones.shutdown-when-empty", false)
        val options = AgonesLifecycle.Options(
            healthSeconds = config.getLong("network.agones.health-seconds", 5).coerceAtLeast(1),
            shutdownWhenEmpty = shutdownWhenEmpty,
            emptyGraceSeconds = config.getLong("network.agones.empty-grace-seconds", 60),
            minInstances = config.getInt("network.agones.min-instances", 1),
        )
        val life = AgonesLifecycle(agones, reporter::currentPlayers, { registry.nodesOf(serverId).size }, options, log)
        agonesLifecycle = life
        services.register<AgonesLifecycle>(life)
        life.start()
    }

    override fun onDisable() {
        remoteTask?.let { runCatching { it.cancel() } }
        remoteTask = null
        remoteModules = null
        watchers.forEach { runCatching { it.close() } }
        watchers.clear()
        subscriptions.forEach { runCatching { it.unregister() } }
        subscriptions.clear()
        adminMenu?.let { runCatching { it.close() } }
        adminMenu = null
        SparkSupport.uninstall(log)
        agonesLifecycle?.let { runCatching { it.stop() } }
        reporter?.let { runCatching { it.drain() } }
        flushOnlinePlayers()
        reporter?.let { runCatching { it.stop() } }
        if (::manager.isInitialized) manager.disableAll()
        bedrockService?.let { runCatching { it.close() } }
        corePlaceholders?.let { runCatching { it.close() } }
        if (::loader.isInitialized) loader.close()
        registry?.let { runCatching { it.close() } }
        handoff?.let { runCatching { it.close() } }
        runCatching { ActionBars.uninstall() }
        runCatching { BossBars.uninstall() }
        colonyTask?.let { runCatching { it.cancel() } }
        colonyTask = null
        colony?.let { instance ->
            runCatching {
                runBlocking { withTimeout(COLONY_RESIGN_TIMEOUT_MILLIS.milliseconds) { instance.shutdown() } }
            }.onFailure { log.warn("Timed out resigning colony crowns on shutdown") }
        }
        colony = null
        leaderboardTask?.let { runCatching { it.cancel() } }
        leaderboardTask = null
        presenceTask?.let { runCatching { it.cancel() } }
        presenceTask = null
        currencies?.close()
        currencies = null
        if (::featureFlags.isInitialized) featureFlags.close()
        localeStore?.close()
        scope.cancel("The Cryon core is shutting down")
        if (::messenger.isInitialized) messenger.close()
        if (::store.isInitialized) store.close()
        database?.close()
        if (::services.isInitialized) services.clear()
        runCatching { PacketEvents.getAPI()?.terminate() }
        Locales.install(null)
        CryonIO.shutdown()
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
        runCatching {
            runBlocking {
                withTimeout(TimeUnit.SECONDS.toMillis(FLUSH_TIMEOUT_SECONDS).milliseconds) {
                    online.map { async { coordinator.flush(it) } }.awaitAll()
                }
            }
        }.onFailure { log.error("Timed out flushing players on shutdown, some state may be lost", it) }
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

        /** Whether InvUI is already bound in this classloader. See [initMenus]. */
        @Volatile
        var menusBound = false

        /** How long shutdown waits for player flushes before giving up and saying so. */
        const val FLUSH_TIMEOUT_SECONDS = 10L

        /** How long shutdown waits for the colony to resign before letting the heartbeat expire it. */
        const val COLONY_RESIGN_TIMEOUT_MILLIS = 2_000L

        /** Long enough that a boot finishes before the first repository fetch competes with it. */
        const val REMOTE_FIRST_POLL_SECONDS = 15L
    }
}
