package com.tricrotism.cryon.velocity

import com.tricrotism.cryon.common.module.Module
import com.tricrotism.cryon.common.module.ModuleManager
import org.slf4j.Logger
import java.io.File
import java.net.URLClassLoader
import java.util.*

/**
 * The Velocity counterpart to the Paper `ModuleLoader`, over the same `:common` module system. Each
 * feature jar in `<data>/modules/` is copied into a private cache and loaded from the copy in its own
 * isolated [URLClassLoader], parented to the shared `<data>/api/` contract layer so cross-repo
 * contracts resolve to the same type. Phase 1 covers boot loading + teardown; runtime hot-swap parity
 * (matching Paper's watcher/`/cryon load`) comes later. Main-thread only.
 */
class VelocityModuleLoader(
    private val manager: ModuleManager,
    private val log: Logger,
    private val modulesDir: File,
    private val cacheDir: File,
    private val coreLoader: ClassLoader,
) {
    private val loaders = LinkedHashMap<String, URLClassLoader>()
    private var apiLoader: URLClassLoader? = null
    private var moduleParent: ClassLoader = coreLoader

    /** Load the shared `api/` contract layer into one loader that parents every feature loader. */
    fun loadSharedApi(dir: File) {
        val jars = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".jar") }?.sortedBy(File::getName)
            ?: emptyList()
        if (jars.isEmpty()) return
        val loader = URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), coreLoader)
        apiLoader = loader
        moduleParent = loader
        log.info("Loaded {} shared API jar(s) from {}", jars.size, dir.path)
    }

    /** Wipe any copies left behind by a previous run, then ensure the cache dir exists. */
    fun prepareCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
        cacheDir.mkdirs()
    }

    /** Read and register every jar in `modules/`; the caller then drives loadAll/enableAll. */
    fun registerAll() {
        val jars = modulesDir.listFiles { f: File -> f.isFile && f.name.endsWith(".jar") }?.sortedBy(File::getName)
            ?: emptyList()
        if (jars.isEmpty()) {
            log.info("No feature jars in {}", modulesDir.path)
            return
        }
        jars.forEach(::readJar)
    }

    private fun readJar(source: File) {
        val cache = File(cacheDir, source.name)
        var loader: URLClassLoader? = null
        try {
            source.copyTo(cache, overwrite = true)
            loader = URLClassLoader(arrayOf(cache.toURI().toURL()), moduleParent)
            val modules = ServiceLoader.load(Module::class.java, loader).toList()
            if (modules.isEmpty()) {
                log.warn("No Module service declared in {}", source.name)
                loader.close(); cache.delete(); return
            }
            val registered = modules.count { manager.register(it) }
            if (registered == 0) {
                loader.close(); cache.delete(); return
            }
            loaders[source.name] = loader
            log.info("Discovered {} module(s) in {}", registered, source.name)
        } catch (e: Throwable) {
            // Isolate a broken jar (ServiceConfigurationError is an Error, not an Exception).
            log.error("Failed to read feature jar {}", source.name, e)
            loader?.let { runCatching { it.close() } }
            cache.delete()
        }
    }

    /** Close every module loader and the shared api/ parent, then clear the cache. For plugin shutdown. */
    fun close() {
        loaders.values.forEach { runCatching { it.close() } }
        loaders.clear()
        apiLoader?.let { runCatching { it.close() } }
        apiLoader = null
        cacheDir.listFiles()?.forEach { it.delete() }
    }
}
