package com.tricrotism.cryon.common.module

/**
 * Whether a third-party plugin/extension is present on the host platform, the seam [ModuleManager]
 * checks [Dependency.OnPlugin] through. `:common` carries no platform types, so each loader supplies
 * its own (`Bukkit.getPluginManager()`, the proxy's plugin manager, Geyser's extension manager).
 *
 * A manager built without one cannot verify plugin dependencies and skips them rather than failing
 * modules over a question it is unable to answer.
 */
fun interface PluginPresence {
    fun isPresent(name: String): Boolean
}
