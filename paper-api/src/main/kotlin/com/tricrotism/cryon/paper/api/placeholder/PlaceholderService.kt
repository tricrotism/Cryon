package com.tricrotism.cryon.paper.api.placeholder

/**
 * Core service that bridges [PlaceholderProvider]s to PlaceholderAPI. Registered once by the core into
 * the module `ServiceRegistry`; a module resolves it and publishes its provider (usually via
 * `PaperModule.registerPlaceholders`, which passes the module id as the [owner]). Registration still
 * succeeds when PlaceholderAPI is not installed; it simply installs nothing, so features never branch
 * on its presence.
 */
interface PlaceholderService {

    /**
     * Publish [provider] under [owner] (the module id). Returns an [AutoCloseable] that unregisters it
     * again (the module lifecycle closes it on disable); closing more than once is safe.
     */
    fun register(owner: String, provider: PlaceholderProvider): AutoCloseable

    /**
     * The `%…%` namespaces [owner] currently provides.
     */
    fun identifiers(owner: String): Collection<String>

    /**
     * Every placeholder [owner] provides, rendered ready to paste: `%warps_count%`, `%warps_list%`.
     *
     * What `/cryon info <id>` lists. A namespace whose provider declares no
     * [PlaceholderProvider.placeholders] contributes one `%warps_…%` line instead, so the answer is
     * always the whole truth about that namespace and never a partial set presented as complete.
     */
    fun placeholders(owner: String): List<String>
}
