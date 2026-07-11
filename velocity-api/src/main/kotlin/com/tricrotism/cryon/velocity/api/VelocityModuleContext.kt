package com.tricrotism.cryon.velocity.api

import com.tricrotism.cryon.common.module.ModuleContext
import com.velocitypowered.api.proxy.ProxyServer

/**
 * The [ModuleContext] handed to Velocity modules. Adds the [proxy] and the [plugin] instance every
 * proxy feature needs (Velocity's event/command managers register against the plugin object). The
 * core supplies the concrete instance.
 */
interface VelocityModuleContext : ModuleContext {
    val proxy: ProxyServer

    /** The Velocity plugin instance, passed to `eventManager`/`commandManager` registrations. */
    val plugin: Any
}
