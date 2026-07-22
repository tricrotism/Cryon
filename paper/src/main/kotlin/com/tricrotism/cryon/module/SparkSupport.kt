package com.tricrotism.cryon.module

import org.bukkit.Server
import org.slf4j.Logger
import java.lang.reflect.*

/**
 * Teaches the spark profiler to attribute CPU samples to individual feature modules instead of
 * losing them (or lumping them under the core Cryon plugin).
 *
 * spark's attribution pipeline for a sampled stack frame is two-step: a `ClassFinder` resolves the
 * frame's class *name* back to a `Class`, then a `ClassSourceLookup` maps that class to a source
 * name by walking its classloader chain. Both steps are blind to our feature jars: Paper's finder
 * (`Class.forName` + the Paper plugin classloader group) can't see into our isolated module
 * `URLClassLoader`s, so module frames never even reach the lookup; and the lookup only recognises
 * Bukkit/Paper plugin loaders anyway. spark exposes no API to extend either, so we splice in
 * reflectively: `SparkPlatform` delegates to its `SparkPlugin` (an interface) live at export time,
 * so we replace that field with a [Proxy] overriding the three attribution methods —
 *
 *  - `createClassFinder()` — spark's real finder first, then each module classloader, so module
 *    classes become findable at all;
 *  - `createClassSourceLookup()` — module classloaders resolve via [ModuleLoader.sourceName]
 *    (falling back to spark's real lookup, e.g. core classes -> Cryon);
 *  - `getKnownSources()` — the real plugin list plus one entry per loaded feature jar, so the
 *    viewer's sources view lists modules with their versions.
 *
 * We reach the `SparkPlatform` through spark's registered API (`me.lucko.spark.api.Spark`, which holds
 * the platform), so this works whether spark is a standalone plugin **or bundled into Paper** (Paper
 * 26.x ships spark built-in as a library, so it isn't a Bukkit plugin and can't be found via
 * `getPlugin`). spark's internal types (`SparkPlugin`, `ClassSourceLookup`, `ClassFinder`,
 * `SourceMetadata`) are derived from reflection metadata rather than named, since Paper relocates
 * spark's packages when it bundles them (`me.lucko.spark.paper.…`).
 *
 * Entirely best-effort: if spark is absent or its internals have shifted, we log a warning and leave
 * spark's default behaviour untouched. Nothing here is on a hot path; the wrapped methods run only
 * while a profiler is exporting.
 */
object SparkSupport {

    /** [author] is shown on the viewer's sources view for every module — pass the core plugin's authors. */
    fun install(server: Server, loader: ModuleLoader, author: String, log: Logger) {
        val api = findSparkApi(server)
        if (api == null) {
            log.warn("spark not available; feature modules will show under Cryon on profiles, not split out")
            return
        }
        try {
            splice(api, loader, author)
            log.info("spark hooked; feature modules now appear as separate sources on profiles")
        } catch (t: Throwable) {
            log.warn(
                "Could not hook spark for per-module profiling (its internals may have changed); modules stay under Cryon",
                t
            )
        }
    }

    /**
     * The live `me.lucko.spark.api.Spark` impl, or null if spark isn't loaded. Tries the universal
     * `SparkProvider.get()` first; falls back to the services registry (which yields the API even when
     * spark's api package isn't visible to our classloader, e.g. Paper's bundled spark).
     */
    private fun findSparkApi(server: Server): Any? {
        runCatching {
            Class.forName("me.lucko.spark.api.SparkProvider").getMethod("get").invoke(null)?.let { return it }
        }
        runCatching {
            val sparkClass = server.servicesManager.knownServices.firstOrNull { it.name == "me.lucko.spark.api.Spark" }
            if (sparkClass != null) return server.servicesManager.getRegistration(sparkClass)?.provider
        }
        return null
    }

    /** Swap `SparkPlatform.plugin` for a proxy overriding the three attribution methods. */
    private fun splice(api: Any, loader: ModuleLoader, author: String) {
        val platform = field(api.javaClass, "platform").get(api)
            ?: throw IllegalStateException("spark platform not initialised")

        val pluginField = field(platform.javaClass, "plugin")
        val realPlugin = pluginField.get(platform)

        val sparkPluginType = pluginField.type // the SparkPlugin interface
        val createLookup = sparkPluginType.getMethod("createClassSourceLookup")
        val lookupType = createLookup.returnType // the ClassSourceLookup interface
        val createFinder = sparkPluginType.getMethod("createClassFinder")
        val finderType = createFinder.returnType // the ClassFinder interface
        val knownSources = sparkPluginType.getMethod("getKnownSources")
        // getKnownSources(): Collection<SourceMetadata> — pull the element type out of the generics.
        val metadataType = (knownSources.genericReturnType as ParameterizedType).actualTypeArguments[0] as Class<*>
        val metadataCtor = metadataType.getConstructor(
            String::class.java, String::class.java, String::class.java, String::class.java
        )

        val proxy = Proxy.newProxyInstance(sparkPluginType.classLoader, arrayOf(sparkPluginType)) { _, method, args ->
            when {
                method.name == "createClassSourceLookup" && method.parameterCount == 0 ->
                    wrapLookup(lookupType, createLookup.invoke(realPlugin), loader)

                method.name == "createClassFinder" && method.parameterCount == 0 ->
                    wrapFinder(finderType, createFinder.invoke(realPlugin), loader)

                method.name == "getKnownSources" && method.parameterCount == 0 ->
                    appendModuleSources(knownSources.invoke(realPlugin), metadataCtor, loader, author)

                else -> delegate(method, realPlugin, args)
            }
        }

        pluginField.set(platform, proxy)
    }

    /** A [Proxy] over spark's lookup: our module classloaders first, spark's real lookup for the rest. */
    private fun wrapLookup(lookupType: Class<*>, realLookup: Any, loader: ModuleLoader): Any =
        Proxy.newProxyInstance(lookupType.classLoader, arrayOf(lookupType)) { _, method, args ->
            if (method.name == "identify" && args?.size == 1 && args[0] is Class<*>) {
                loader.sourceName((args[0] as Class<*>).classLoader)?.let { return@newProxyInstance it }
            }
            delegate(method, realLookup, args)
        }

    /**
     * A [Proxy] over spark's `ClassFinder`: the real finder first, then the module loaders. Without
     * this, module classes are unfindable (Paper only searches the server and plugin loaders), and
     * spark drops the frame before ever consulting the source lookup. The module fallback resolves via
     * `findLoadedClass` (see [ModuleLoader.findLoadedModuleClass]) rather than a delegating
     * `Class.forName`, so it never blocks on Paper's classloader-group lock while a profile exports.
     */
    private fun wrapFinder(finderType: Class<*>, realFinder: Any, loader: ModuleLoader): Any =
        Proxy.newProxyInstance(finderType.classLoader, arrayOf(finderType)) { _, method, args ->
            if (method.name == "findClass" && args?.size == 1 && args[0] is String) {
                delegate(method, realFinder, args) ?: loader.findLoadedModuleClass(args[0] as String)
            } else {
                delegate(method, realFinder, args)
            }
        }

    /** spark's real known sources plus a `SourceMetadata(name, version, author, description)` per jar. */
    private fun appendModuleSources(
        real: Any?,
        metadataCtor: Constructor<*>,
        loader: ModuleLoader,
        author: String,
    ): Any {
        val combined = ArrayList<Any?>(real as? Collection<Any?> ?: emptyList())
        for (source in loader.sources()) {
            combined.add(metadataCtor.newInstance(source.name, source.version, author, "Cryon feature module"))
        }
        return combined
    }

    /** [Class.getDeclaredField] walked up the hierarchy, made accessible. */
    private fun field(type: Class<*>, name: String): Field {
        var c: Class<*>? = type
        while (c != null) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        throw NoSuchFieldException("$name on ${type.name}")
    }

    /** Reflective delegate that unwraps [InvocationTargetException] so checked throws propagate cleanly. */
    private fun delegate(method: Method, target: Any, args: Array<out Any?>?): Any? =
        try {
            if (args == null) method.invoke(target) else method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
}
