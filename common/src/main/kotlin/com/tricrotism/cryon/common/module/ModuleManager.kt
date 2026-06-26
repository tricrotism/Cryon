package com.tricrotism.cryon.common.module

import org.slf4j.Logger

/**
 * Owns module lifecycle and tracks each module's [ModuleState]. The loader [register]s every
 * discovered module, then runs [loadAll] (publish services) and [enableAll] (consume peers).
 * [disableAll] tears down in reverse order. [enable]/[disable]/[reload] drive a single module at
 * runtime (e.g. from the `/cryon` command) — re-enabling reuses the context captured at load.
 *
 * Main-thread only: boot and the module command both run there; not safe for concurrent mutation.
 */
class ModuleManager(private val logger: Logger) {

    private val modules = LinkedHashMap<String, Module>()
    private val states = LinkedHashMap<String, ModuleState>()

    fun register(module: Module) {
        if (modules.putIfAbsent(module.id, module) != null) {
            logger.warn("Duplicate module id '{}' — ignoring the duplicate", module.id)
            return
        }
        states[module.id] = ModuleState.REGISTERED
    }

    fun loadAll(context: ModuleContext) {
        for ((id, module) in modules) {
            try {
                module.onLoad(context)
                states[id] = ModuleState.LOADED
            } catch (e: Exception) {
                states[id] = ModuleState.FAILED
                logger.error("Failed to load module {}", id, e)
            }
        }
    }

    fun enableAll() {
        for ((id, module) in modules) {
            if (states[id] == ModuleState.LOADED) enableInternal(id, module)
        }
    }

    fun disableAll() {
        for ((id, module) in modules.entries.reversed()) {
            if (states[id] == ModuleState.ENABLED) disableInternal(id, module)
        }
    }

    /** Enable a single module at runtime. True if it transitioned to [ModuleState.ENABLED]. */
    fun enable(id: String): Boolean {
        val module = modules[id] ?: return false
        if (states[id] == ModuleState.ENABLED) return false
        return enableInternal(id, module)
    }

    /** Disable a single module at runtime. True if it transitioned to [ModuleState.DISABLED]. */
    fun disable(id: String): Boolean {
        val module = modules[id] ?: return false
        if (states[id] != ModuleState.ENABLED) return false
        return disableInternal(id, module)
    }

    /** Disable (if enabled) then re-enable a module. */
    fun reload(id: String): Boolean {
        if (!modules.containsKey(id)) return false
        if (states[id] == ModuleState.ENABLED && !disable(id)) return false
        return enable(id)
    }

    fun state(id: String): ModuleState? = states[id]
    fun has(id: String): Boolean = modules.containsKey(id)
    fun ids(): List<String> = modules.keys.toList()
    fun states(): Map<String, ModuleState> = LinkedHashMap(states)

    private fun enableInternal(id: String, module: Module): Boolean = try {
        module.onEnable()
        states[id] = ModuleState.ENABLED
        logger.info("Enabled module {}", id)
        true
    } catch (e: Exception) {
        states[id] = ModuleState.FAILED
        logger.error("Failed to enable module {}", id, e)
        false
    }

    private fun disableInternal(id: String, module: Module): Boolean = try {
        module.onDisable()
        states[id] = ModuleState.DISABLED
        logger.info("Disabled module {}", id)
        true
    } catch (e: Exception) {
        logger.error("Failed to disable module {}", id, e)
        false
    }
}
