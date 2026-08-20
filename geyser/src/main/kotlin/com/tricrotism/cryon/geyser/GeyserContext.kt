package com.tricrotism.cryon.geyser

import com.tricrotism.cryon.common.module.ServiceRegistry
import com.tricrotism.cryon.geyser.api.GeyserModuleContext
import org.geysermc.geyser.api.GeyserApi
import org.geysermc.geyser.api.extension.Extension
import org.slf4j.Logger
import java.nio.file.Path

/** The concrete [GeyserModuleContext] the loader hands to every Geyser module. */
class GeyserContext(
    override val geyser: GeyserApi,
    override val extension: Extension,
    override val logger: Logger,
    override val services: ServiceRegistry,
    override val dataDirectory: Path,
) : GeyserModuleContext
