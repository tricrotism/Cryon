package com.tricrotism.cryon.geyser

import com.tricrotism.cryon.common.locale.LangScanner
import com.tricrotism.cryon.common.locale.MessageService
import com.tricrotism.cryon.common.locale.MessageSource
import com.tricrotism.cryon.common.module.Module
import com.tricrotism.cryon.common.module.ModuleContext
import com.tricrotism.cryon.common.module.ModuleManager
import com.tricrotism.cryon.common.module.ModuleState
import org.slf4j.Logger
import java.io.File
import java.net.URLClassLoader
import java.util.*
import java.util.concurrent.Executors

/**
 * The Geyser counterpart to the Paper and Velocity module loaders, over the same `:common` module
 * system, and with the same runtime hot-swap: a feature jar can be added, replaced or removed while
 * Geyser is up. Each jar in `<data>/modules/` is copied into a private cache and loaded from the copy
 * in its own isolated [URLClassLoader], parented to the shared `<data>/api/` contract layer so
 * cross-repo contracts resolve to the same type. Loading from the copy is what keeps the original
 * unlocked, and therefore replaceable, while its modules run.
 *
 * **Single-writer.** Boot drives it on Geyser's startup thread; everything afterwards (the `/cryon`
 * command, the watcher) goes through [submit], which is one daemon thread.
 *
 * **One thing hot-swap cannot cover here: commands.** Geyser hands out its command registrar in
 * `GeyserDefineCommandsEvent`, fired once from `CommandRegistry`'s constructor during startup, and
 * offers nothing to register against afterwards. So a module hot-loaded into a running Geyser gets
 * its listeners and services but not its commands, and those appear on the next restart. Everything
 * else, services, subscriptions, lang bundles, swaps live.
 */
class GeyserModuleLoader(
    private val manager: ModuleManager,
    private val context: ModuleContext,
    private val log: Logger,
    val modulesDir: File,
    private val cacheDir: File,
    private val coreLoader: ClassLoader,
) {

    private class LoadedJar(
        val source: File,
        val cache: File,
        val loader: URLClassLoader,
        val moduleIds: List<String>,
        val lang: MessageSource?,
    )

    // Copy-on-write: the worker thread mutates it while command suggesters read it on whichever
    // thread dispatched them.
    @Volatile
    private var jars: Map<String, LoadedJar> = LinkedHashMap()
    private val moduleToJar = HashMap<String, String>()
    private var apiLoader: URLClassLoader? = null
    private var apiDir: File? = null
    private var moduleParent: ClassLoader = coreLoader

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Cryon-Module-Loader").apply { isDaemon = true }
    }

    /** Run [block] on the loader's own thread, Geyser's stand-in for Paper's global region thread. */
    fun submit(block: () -> Unit) {
        worker.execute {
            runCatching(block).onFailure { log.error("Module operation failed", it) }
        }
    }

    /** Load the shared `api/` contract layer into one loader that parents every feature loader. */
    fun loadSharedApi(dir: File) {
        apiDir = dir
        val contracts = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".jar") }?.sortedBy(File::getName)
            ?: emptyList()
        if (contracts.isEmpty()) return
        val loader = URLClassLoader(contracts.map { it.toURI().toURL() }.toTypedArray(), coreLoader)
        apiLoader = loader
        moduleParent = loader
        log.info("Loaded {} shared API jar(s) from {}", contracts.size, dir.path)
    }

    /**
     * Reload the shared `api/` layer, a cascade for the same reason as Paper's: it parents every
     * module loader, so swapping it alone would leave running modules linked to the old contract
     * classes. Unload everything, reopen `api/`, then bring every jar back in its original order with
     * the two-phase ordering intact. Returns the ids that reached `ENABLED`.
     */
    fun reloadApi(): List<String> {
        val sources = jars.values.map { it.source }
        jars.keys.toList().asReversed().forEach(::unloadByKey)

        apiLoader?.let { runCatching { it.close() } }
        apiLoader = null
        moduleParent = coreLoader
        apiDir?.let { loadSharedApi(it) }

        val ids = manager.order(sources.flatMap { readJar(it) ?: emptyList() })
        val loaded = ids.filter { manager.load(it, context) }
        loaded.forEach(manager::enable)
        loaded.forEach(manager::postLoad)
        val enabled = loaded.filter { manager.state(it) == ModuleState.ENABLED }
        log.info("Reloaded api/ and {} module(s) ({} re-enabled)", sources.size, enabled.size)
        return enabled
    }

    /** Wipe any copies left behind by a previous run, then ensure the cache dir exists. */
    fun prepareCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
        cacheDir.mkdirs()
    }

    /** Read and register every jar in `modules/`; the caller then drives loadAll/enableAll. */
    fun registerAll() {
        val files = jarFiles()
        if (files.isEmpty()) {
            log.info("No feature jars in {}", modulesDir.path)
            return
        }
        files.forEach(::readJar)
    }

    /**
     * Hot-load a single jar (load + enable its modules now), replacing it first if already loaded.
     * The new jar's modules all `onLoad` before any of them `onEnable`, so an intra-jar service
     * dependency still resolves. Returns the ids that reached `ENABLED`.
     */
    fun loadJar(source: File): List<String> {
        if (isLoaded(source)) unloadByKey(key(source))
        val ids = manager.order(readJar(source) ?: return emptyList())
        val loaded = ids.filter { manager.load(it, context) }
        loaded.forEach(manager::enable)
        loaded.forEach(manager::postLoad)
        return loaded.filter { manager.state(it) == ModuleState.ENABLED }
    }

    /** Hot-load every jar in `modules/` that isn't loaded yet. Returns newly enabled ids. */
    fun loadNew(): List<String> = jarFiles().filterNot(::isLoaded).flatMap(::loadJar)

    /** Hot-remove the jar that declares [id] (disables + unregisters all its modules). */
    fun unloadModule(id: String): List<String>? = moduleToJar[id]?.let(::unloadByKey)

    /** Hot-remove a jar by file (the watcher's delete path; the file may already be gone). */
    fun unloadJar(source: File): List<String>? = unloadByKey(key(source))

    fun isLoaded(source: File): Boolean = jars.containsKey(key(source))

    /** Jar files sitting in `modules/` that aren't loaded yet, for the `/cryon load` suggester. */
    fun loadableJarNames(): List<String> = jarFiles().filterNot(::isLoaded).map(File::getName)

    /** Close every loader (modules before the shared parent) and clear the cache. For extension shutdown. */
    fun close() {
        worker.shutdownNow()
        jars.values.forEach { jar ->
            runCatching { context.services.unregisterByClassLoader(jar.loader) }
            runCatching { jar.loader.close() }
        }
        jars = LinkedHashMap()
        moduleToJar.clear()
        apiLoader?.let { runCatching { it.close() } }
        apiLoader = null
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Copy [source] into the cache, class-load it, register its [Module]s and lang bundle, and track
     * the jar. Returns the registered ids, or null if the jar is broken or declares nothing. Every
     * partial step is undone on failure, so a bad jar leaves no loader, copy or registration behind.
     */
    private fun readJar(source: File): List<String>? {
        val cache = File(cacheDir, source.name)
        var loader: URLClassLoader? = null
        var lang: MessageSource? = null
        val ids = ArrayList<String>()
        val sourceKey = key(source)
        try {
            source.copyTo(cache, overwrite = true)
            loader = URLClassLoader(arrayOf(cache.toURI().toURL()), moduleParent)

            val modules = ServiceLoader.load(Module::class.java, loader).toList()
            if (modules.isEmpty()) {
                log.warn("No Module service declared in {}", source.name)
                throw IllegalStateException("no modules")
            }
            for (module in modules) {
                for (id in manager.register(module)) {
                    ids.add(id)
                    moduleToJar[id] = sourceKey
                }
            }
            if (ids.isEmpty()) throw IllegalStateException("all module ids were duplicates")

            lang = LangScanner.fromJar(cache)?.also {
                messages()?.addSource(it)
                log.info("Registered lang bundle from {}", source.name)
            }

            jars = LinkedHashMap(jars).apply { put(sourceKey, LoadedJar(source, cache, loader, ids, lang)) }
            log.info("Discovered {} module(s) in {}", ids.size, source.name)
            return ids
        } catch (e: Throwable) {
            // Isolate a broken jar (ServiceConfigurationError is an Error, not an Exception).
            if (e !is IllegalStateException) log.error("Failed to read feature jar {}", source.name, e)
            ids.forEach { manager.unregister(it); moduleToJar.remove(it) }
            lang?.let { messages()?.removeSource(it) }
            loader?.let { runCatching { it.close() } }
            cache.delete()
            return null
        }
    }

    private fun unloadByKey(jarKey: String): List<String>? {
        val jar = jars[jarKey] ?: return null
        jars = LinkedHashMap(jars).apply { remove(jarKey) }
        for (id in jar.moduleIds.reversed()) {
            manager.disable(id) // no-op if already disabled
            manager.unregister(id)
            moduleToJar.remove(id)
        }
        // Drop services this jar published before closing its loader, so a reload re-registers cleanly
        // and peers can't resolve an impl from a now-dead loader.
        context.services.unregisterByClassLoader(jar.loader)
        jar.lang?.let { messages()?.removeSource(it) }
        runCatching { jar.loader.close() }
        jar.cache.delete()
        log.info("Unloaded {} module(s) from {}", jar.moduleIds.size, jar.source.name)
        return jar.moduleIds
    }

    private fun messages(): MessageService? = context.services.find<MessageService>()

    private fun jarFiles(): List<File> =
        modulesDir.listFiles { f: File -> f.isFile && f.name.endsWith(".jar") }
            ?.sortedBy(File::getName)
            ?: emptyList()

    private fun key(file: File): String = file.toPath().toAbsolutePath().normalize().toString()
}
