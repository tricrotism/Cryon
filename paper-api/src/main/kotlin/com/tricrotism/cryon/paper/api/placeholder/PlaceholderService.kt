package com.tricrotism.cryon.paper.api.placeholder

/**
 * Core service that bridges [PlaceholderProvider]s to PlaceholderAPI. Registered once by the core into
 * the module `ServiceRegistry`; a module resolves it and publishes its provider (usually via
 * `PaperModule.registerPlaceholders`, which passes the module id as the [owner]). Registration still
 * succeeds when PlaceholderAPI is not installed — it simply installs nothing — so features never branch
 * on its presence.
 */
interface PlaceholderService {

    /**
     * Publish [provider] under [owner] (the module id). Returns an [AutoCloseable] that unregisters it
     * again (the module lifecycle closes it on disable); closing more than once is safe.
     */
    fun register(owner: String, provider: PlaceholderProvider): AutoCloseable

    /** The `%…%` namespaces [owner] currently provides — what `/cryon info <id>` lists for a module. */
    fun identifiers(owner: String): Collection<String>
}
