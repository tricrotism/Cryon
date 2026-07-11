package com.tricrotism.cryon.velocity

import com.tricrotism.cryon.common.module.ServiceRegistry
import com.tricrotism.cryon.velocity.api.VelocityModuleContext
import com.velocitypowered.api.proxy.ProxyServer
import org.slf4j.Logger

/** The concrete [VelocityModuleContext] the core hands to every Velocity module. */
class VelocityContext(
    override val proxy: ProxyServer,
    override val plugin: Any,
    override val logger: Logger,
    override val services: ServiceRegistry,
) : VelocityModuleContext
