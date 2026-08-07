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

/** Resolve a required peer service: sugar for `services.get<T>()`. */
inline fun <reified T : Any> ModuleContext.service(): T = services.get()

/** Resolve an optional peer service, or null: sugar for `services.find<T>()`. */
inline fun <reified T : Any> ModuleContext.serviceOrNull(): T? = services.find()
