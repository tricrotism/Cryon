package com.tricrotism.cryon.module

import com.tricrotism.cryon.common.locale.LangScanner
import com.tricrotism.cryon.common.locale.MessageService
import com.tricrotism.cryon.common.locale.MessageSource
import com.tricrotism.cryon.common.module.Module
import com.tricrotism.cryon.common.module.ModuleContext
import com.tricrotism.cryon.common.module.ModuleManager
import com.tricrotism.cryon.common.module.ModuleState
import com.tricrotism.cryon.paper.api.command.CommandService
import org.slf4j.Logger
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the jar ↔ classloader ↔ module mapping for the core and the runtime hot-swap operations the
 * boot scan, the `/cryon` command, and the [ModuleWatcher] all share.
 *
 * Each feature jar in `plugins/Cryon/modules/` is **copied into a private cache dir and loaded from
 * the copy**, so the original stays unlocked on Windows and can be deleted or replaced while the
 * feature is running — the basis for hot add/remove/reload. Closing a jar's [URLClassLoader] frees
 * its file handle and lets its lang bundle and listeners go; reclaiming the classes themselves still
 * depends on the module not leaking references of its own (same caveat as any plugin reload).
 *
 * Main-thread only — boot, the command, and the watcher's main-thread hops all drive it; not safe
 * for concurrent mutation.
 */
class ModuleLoader(
    private val manager: ModuleManager,
    private val messageService: MessageService,
    private val context: ModuleContext,
    private val log: Logger,
    /** `plugins/Cryon/modules/` — the originals admins add, replace, and delete. */
    val modulesDir: File,
    /** Private copies we actually class-load, so the originals never lock. */
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

    /**
     * A feature jar's [URLClassLoader], exposing `findLoadedClass` so [SparkSupport] can attribute an
     * already-loaded module class without triggering a fresh (parent-delegating, lock-contending) load.
     */
    private class ModuleClassLoader(urls: Array<URL>, parent: ClassLoader) : URLClassLoader(urls, parent) {
        fun loadedClass(name: String): Class<*>? = findLoadedClass(name)
    }

    /** A module jar as the profiler should present it: display [name] (its module ids) + [version]. */
    data class ModuleSource(val name: String, val version: String)

    private var apiLoader: URLClassLoader? = null
    private var apiDir: File? = null
    private var moduleParent: ClassLoader = coreLoader
    private val jars = LinkedHashMap<String, LoadedJar>() // key: source path; insertion = load order
    private val moduleToJar = HashMap<String, String>()   // module id -> jar key

    // Module classloader -> source info, for spark profiler attribution (see sourceName). Concurrent
    // because the profiler reads it off its own export thread while hot-swaps mutate it on main.
    private val loaderSources = ConcurrentHashMap<ClassLoader, ModuleSource>()

    /**
     * Load every jar in the shared `api/` contract layer into one [URLClassLoader] that parents all
     * feature loaders (so cross-repo contracts resolve to the same type). Unchanged when `api/` is
     * empty. Hot-swappable only via [reloadApi] (a full cascade) — never on its own, because every
     * loaded module is linked against these classes.
     */
    fun loadSharedApi(dir: File) {
        apiDir = dir
        val jars = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".jar") }
            ?.sortedBy(File::getName)
            ?: emptyList()
        if (jars.isEmpty()) return
        val loader = URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), coreLoader)
        apiLoader = loader
        moduleParent = loader
        log.info("Loaded {} shared API jar(s) from {}", jars.size, dir.path)
    }

    /**
     * Reload the shared `api/` layer. Because it parents every module loader, swapping it in isolation
     * would leave running modules linked to the old contract classes (`ClassCastException` through the
     * registry). So this is a **cascade**: unload every module → close + reload the `api/` loader →
     * reload exactly the modules that were loaded, in their original order, preserving the global
     * two-phase ordering (every `onLoad` before any `onEnable`). Briefly takes all features down.
     * Returns the ids brought back to `ENABLED`. Main-thread only.
     */
    fun reloadApi(): List<String> {
        val sources = jars.values.map { it.source } // insertion order
        jars.keys.toList().asReversed().forEach(::unloadByKey)

        apiLoader?.let { runCatching { it.close() } }
        apiLoader = null
        moduleParent = coreLoader
        apiDir?.let { loadSharedApi(it) }

        // Re-read all (register) before loading any, so cross-module services resolve in onEnable.
        val ids = sources.flatMap { readJar(it) ?: emptyList() }
        val loaded = ids.filter { manager.load(it, context) }
        loaded.forEach(manager::enable)
        context.services.find(CommandService::class)?.refresh() // reveal re-enabled commands
        val enabled = loaded.filter { manager.state(it) == ModuleState.ENABLED }
        log.info("Reloaded api/ and {} module(s) ({} re-enabled)", sources.size, enabled.size)
        return enabled
    }

    /** Wipe any copies left behind by a previous (possibly crashed) run, then ensure the dir exists. */
    fun prepareCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
        cacheDir.mkdirs()
    }

    /**
     * Boot scan: read and **register** every jar in `modules/` (no `onLoad`/`onEnable`). The caller
     * then runs [ModuleManager.loadAll]/[ModuleManager.enableAll] so the global two-phase ordering
     * (every module's `onLoad` before any `onEnable`) holds across all jars at boot.
     */
    fun registerAll() {
        val files = jarFiles()
        if (files.isEmpty()) {
            log.info("No feature jars in {}", modulesDir.path)
            return
        }
        files.forEach(::readJar)
    }

    /**
     * Hot-load a single jar (load + enable its modules now). Replaces it first if already loaded.
     * Returns the ids that reached `ENABLED`. The new jar's modules `onLoad` together before any of
     * them `onEnable`, so an intra-jar service dependency still resolves; pre-existing modules are
     * already enabled, so their services are available too.
     */
    fun loadJar(source: File): List<String> {
        if (isLoaded(source)) unloadByKey(key(source))
        val ids = readJar(source) ?: return emptyList()
        val loaded = ids.filter { manager.load(it, context) }
        loaded.forEach(manager::enable)
        context.services.find(CommandService::class)?.refresh() // reveal now-enabled commands
        return loaded.filter { manager.state(it) == ModuleState.ENABLED }
    }

    /** Hot-load every jar present in `modules/` that isn't loaded yet. Returns newly enabled ids. */
    fun loadNew(): List<String> = jarFiles().filterNot(::isLoaded).flatMap(::loadJar)

    /** Hot-remove the jar that declares [id] (disables + unregisters all its modules). */
    fun unloadModule(id: String): List<String>? = moduleToJar[id]?.let(::unloadByKey)

    /** Hot-remove a jar by file (used by the watcher on delete; the file may already be gone). */
    fun unloadJar(source: File): List<String>? = unloadByKey(key(source))

    fun isLoaded(source: File): Boolean = jars.containsKey(key(source))

    /** Jar files sitting in `modules/` that aren't loaded yet — for the `/cryon load` suggester. */
    fun loadableJarNames(): List<String> = jarFiles().filterNot(::isLoaded).map(File::getName)

    /**
     * Display name for a module's classloader, or null if [loader] isn't one of ours. Used by
     * [SparkSupport] so the profiler can credit feature modules instead of lumping them under the
     * core; null lets spark fall back to its normal lookup (e.g. core classes -> Cryon). Read off the
     * profiler's export thread.
     */
    fun sourceName(loader: ClassLoader?): String? = loader?.let { loaderSources[it]?.name }

    /**
     * [name] if any live module loader has already *defined* it, else null — [SparkSupport]'s
     * class-finder fallback. spark can only attribute a sampled class it can find, and Paper's own
     * lookup can't see into these isolated loaders. Uses `findLoadedClass` (a native class-table read:
     * no parent delegation, no fresh load) so it never blocks on Paper's classloader-group lock the way
     * a delegating `Class.forName` would; a sampled stack frame's class is always already loaded, so
     * this suffices. Safe to call off the profiler's export thread (concurrent map view).
     */
    fun findLoadedModuleClass(name: String): Class<*>? {
        for (loader in loaderSources.keys) {
            (loader as? ModuleClassLoader)?.loadedClass(name)?.let { return it }
        }
        return null
    }

    /** Snapshot of every loaded jar's source info — spark's "known sources" metadata. */
    fun sources(): Collection<ModuleSource> = loaderSources.values.toList()

    /** Close every loader (modules before the shared parent) and clear the cache. For plugin disable. */
    fun close() {
        jars.values.forEach { runCatching { it.loader.close() } }
        jars.clear()
        moduleToJar.clear()
        loaderSources.clear()
        apiLoader?.let { runCatching { it.close() } }
        apiLoader = null
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Copy [source] into the cache, class-load it, register its [Module]s and lang bundle, and track
     * the jar. Returns the registered ids, or null if the jar is broken or declares nothing — every
     * partial step is cleaned up so a failed read leaves no loader, copy, or registration behind.
     */
    private fun readJar(source: File): List<String>? {
        val cache = File(cacheDir, source.name)
        var loader: URLClassLoader? = null
        var lang: MessageSource? = null
        val ids = ArrayList<String>()
        val sourceKey = key(source)
        try {
            source.copyTo(cache, overwrite = true)
            loader = ModuleClassLoader(arrayOf(cache.toURI().toURL()), moduleParent)

            val modules = ServiceLoader.load(Module::class.java, loader).toList()
            if (modules.isEmpty()) {
                log.warn("No Module service declared in {}", source.name)
                throw IllegalStateException("no modules")
            }
            for (module in modules) {
                if (manager.register(module)) {
                    ids.add(module.id)
                    moduleToJar[module.id] = sourceKey
                }
            }
            if (ids.isEmpty()) throw IllegalStateException("all module ids were duplicates")

            // Read the lang bundle from the cache copy (the original may be replaced/deleted live).
            lang = LangScanner.fromJar(cache)?.also {
                messageService.addSource(it)
                log.info("Registered lang bundle from {}", source.name)
            }

            jars[sourceKey] = LoadedJar(source, cache, loader, ids, lang)
            loaderSources[loader] = ModuleSource(
                ids.joinToString(", ") { id -> "Cryon-Module-${id.replaceFirstChar(Char::uppercase)}" },
                jarVersion(source.name),
            )
            log.info("Discovered {} module(s) in {}", ids.size, source.name)
            return ids
        } catch (e: Throwable) {
            // Isolate a broken jar (ServiceConfigurationError is an Error, not an Exception).
            if (e !is IllegalStateException) log.error("Failed to read feature jar {}", source.name, e)
            ids.forEach { manager.unregister(it); moduleToJar.remove(it) }
            lang?.let(messageService::removeSource)
            loader?.let { runCatching { it.close() } }
            cache.delete()
            return null
        }
    }

    private fun unloadByKey(jarKey: String): List<String>? {
        val jar = jars.remove(jarKey) ?: return null
        loaderSources.remove(jar.loader)
        val commands = context.services.find(CommandService::class)
        for (id in jar.moduleIds.reversed()) {
            manager.disable(id) // no-op if already disabled
            manager.unregister(id)
            moduleToJar.remove(id)
            commands?.unregister(id) // drop its commands from the live dispatcher
        }
        // Drop services this jar published before closing its loader, so a reload re-registers cleanly
        // and peers can't resolve an impl from a now-dead loader.
        context.services.unregisterByClassLoader(jar.loader)
        jar.lang?.let(messageService::removeSource)
        runCatching { jar.loader.close() }
        jar.cache.delete()
        log.info("Unloaded {} module(s) from {}", jar.moduleIds.size, jar.source.name)
        return jar.moduleIds
    }

    private fun jarFiles(): List<File> =
        modulesDir.listFiles { f: File -> f.isFile && f.name.endsWith(".jar") }
            ?.sortedBy(File::getName)
            ?: emptyList()

    private fun key(file: File): String = file.toPath().toAbsolutePath().normalize().toString()

    /** `cryon-economy-1.0.0.jar` -> `1.0.0`; falls back to the whole base name for unversioned jars. */
    private fun jarVersion(fileName: String): String {
        val base = fileName.removeSuffix(".jar")
        return Regex("-(\\d[\\w.+-]*)$").find(base)?.groupValues?.get(1) ?: base
    }
}
