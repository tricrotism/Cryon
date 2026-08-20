package com.tricrotism.cryon.geyser.api

import com.tricrotism.cryon.common.module.ModuleContext
import org.geysermc.geyser.api.GeyserApi
import org.geysermc.geyser.api.extension.Extension
import java.nio.file.Path

/**
 * The [ModuleContext] handed to Geyser modules. Adds the [geyser] handle and the owning [extension],
 * which is the Geyser twin of Velocity's plugin instance: the event bus and `Command.builder` are
 * both scoped to an [Extension], so anything a feature registers is registered against this one.
 *
 * The core supplies the concrete instance.
 */
interface GeyserModuleContext : ModuleContext {
    val geyser: GeyserApi

    /** The loader extension, passed to `Command.builder(...)` and event-bus registrations. */
    val extension: Extension

    /** The loader's own directory. A module's folder is [GeyserModule.dataFolder] under it. */
    val dataDirectory: Path
}
