package com.tricrotism.cryon.common.module

/**
 * A self-contained feature, discovered from a jar via [java.util.ServiceLoader] and driven by
 * [ModuleManager]. Each feature jar declares its implementation(s) in
 * `META-INF/services/com.tricrotism.cryon.common.module.Module` and must have a no-arg constructor.
 *
 * Lifecycle is two-phase so modules can intertwine regardless of load order:
 *  1. [onLoad] runs for **every** module first — publish your services into [ModuleContext.services].
 *  2. [onEnable] runs after all modules have loaded — now every peer's services are available.
 *
 * [preLoad] sits ahead of both, in the host's own load phase, for the rare module that has to reach
 * a third-party registry before the rest of the server enables. [postLoad] closes the other end, for
 * the rare module that needs its peers *enabled* rather than merely loaded.
 *
 * Platform-neutral: no Bukkit/Velocity types here, so the same contract serves a future `:velocity`
 * loader. Paper modules build on the `PaperModule` base in `:paper-api`.
 */
interface Module {

    /** Stable identifier used in logs and diagnostics. */
    val id: String

    /**
     * Runs inside the host's own load phase, before **any** plugin on the server has enabled. The
     * only window in which a third-party registry that seals itself on enable — WorldGuard's flag
     * registry being the motivating case — can still be written to.
     *
     * Cryon's own infrastructure does not exist yet: [ModuleContext.services] is empty here, and
     * staying empty is the point. Touch nothing but the platform and the registry you came for.
     *
     * Does **not** run when a jar is hot-loaded at runtime — by then the same registries are shut,
     * so a module that needs this phase must be present at boot to work at all.
     */
    fun preLoad(context: ModuleContext) {}

    /** Register services and read config. Called for all modules before any is enabled. */
    fun onLoad(context: ModuleContext) {}

    /** Wire listeners/tasks and consume peer services. Called after every module has loaded. */
    fun onEnable() {}

    /**
     * Runs once every module has finished [onEnable] — the mirror of [preLoad] at the far end of the
     * lifecycle. Where [onEnable] can only rely on peers having *published* their services,
     * this can rely on them being live: their listeners registered, their tasks running, their
     * runtime state built.
     *
     * Takes no context for the same reason [onEnable] doesn't — [onLoad] has already run, so a
     * module holds its own. Unlike [preLoad] it does run on the hot-add path, after the new jar's
     * modules have all enabled, so a hot-swapped module reaches the same state a booted one does.
     *
     * Throwing here does not un-enable the module: [onEnable] already succeeded and its listeners
     * are live. The failure is logged and the module stays `ENABLED`.
     */
    fun postLoad() {}

    /** Called in reverse enable order on shutdown. Undo everything [onEnable] set up. */
    fun onDisable() {}
}
