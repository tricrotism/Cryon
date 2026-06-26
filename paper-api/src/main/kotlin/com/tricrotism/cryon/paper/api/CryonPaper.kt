package com.tricrotism.cryon.paper.api

import com.tricrotism.cryon.paper.api.CryonPaper.init
import org.bukkit.plugin.Plugin

/**
 * Shared handle to the core plugin, used by the static utilities ([scheduler.Schedulers],
 * [event.Events]) that need a `Plugin` but have no context. The core calls [init] once in
 * `onEnable`. Loaded from the core's classloader, so this single instance is visible to every
 * feature jar.
 */
object CryonPaper {

    @Volatile
    private var pluginRef: Plugin? = null

    val plugin: Plugin
        get() = pluginRef ?: error("CryonPaper is not initialized! Call CryonPaper.init(plugin) in the core onEnable")

    fun init(plugin: Plugin) {
        pluginRef = plugin
    }
}
