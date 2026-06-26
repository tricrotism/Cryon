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
 * Platform-neutral: no Bukkit/Velocity types here, so the same contract serves a future `:velocity`
 * loader. Paper modules build on the `PaperModule` base in `:paper-api`.
 */
interface Module {

    /** Stable identifier used in logs and diagnostics. */
    val id: String

    /** Register services and read config. Called for all modules before any is enabled. */
    fun onLoad(context: ModuleContext) {}

    /** Wire listeners/tasks and consume peer services. Called after every module has loaded. */
    fun onEnable() {}

    /** Called in reverse enable order on shutdown. Undo everything [onEnable] set up. */
    fun onDisable() {}
}
