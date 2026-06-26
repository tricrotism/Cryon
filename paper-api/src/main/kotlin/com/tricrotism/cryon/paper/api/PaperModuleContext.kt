package com.tricrotism.cryon.paper.api

import com.tricrotism.cryon.common.module.ModuleContext
import org.bukkit.Server
import org.bukkit.plugin.Plugin

/**
 * The [ModuleContext] handed to Paper modules. Adds the core [plugin] and [server] handles every
 * Bukkit feature needs. The core supplies the concrete instance.
 */
interface PaperModuleContext : ModuleContext {
    val plugin: Plugin
    val server: Server
}
