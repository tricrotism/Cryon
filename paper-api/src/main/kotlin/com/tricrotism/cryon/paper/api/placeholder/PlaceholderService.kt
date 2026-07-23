package com.tricrotism.cryon.paper.api.placeholder

/**
 * Core service that bridges [PlaceholderProvider]s to PlaceholderAPI. Registered once by the core into
 * the module `ServiceRegistry`; a module resolves it and publishes its provider (usually via
 * `PaperModule.registerPlaceholders`). Registration still succeeds when PlaceholderAPI is not installed —
 * it simply installs nothing — so features never branch on its presence.
 */
interface PlaceholderService {

    /**
     * Publish [provider] under its [PlaceholderProvider.identifier]. Returns an [AutoCloseable] that
     * unregisters it again (the module lifecycle closes it on disable); closing more than once is safe.
     */
    fun register(provider: PlaceholderProvider): AutoCloseable
}
