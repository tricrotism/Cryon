package com.tricrotism.cryon.common.module

import org.slf4j.Logger

/**
 * Handed to every [Module] in [Module.onLoad]. Carries the cross-module [services] registry — the
 * intertwine seam — and a [logger]. Platform loaders extend this (see `PaperModuleContext`) to add
 * platform handles such as the plugin/server.
 */
interface ModuleContext {
    val logger: Logger
    val services: ServiceRegistry
}
