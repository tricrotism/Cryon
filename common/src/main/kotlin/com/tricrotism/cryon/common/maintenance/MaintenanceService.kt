package com.tricrotism.cryon.common.maintenance

import java.util.concurrent.CompletableFuture

/**
 * Network-wide maintenance toggle, shared across every proxy. When on, proxies show an "under
 * maintenance" server-list entry (with a protocol number no client matches) and deny non-bypass
 * logins. Backed by SQL (source of truth when a `Database` is present) and synced instantly across
 * proxies over Redis, mirroring `FeatureFlags`. Resolve via `services.find(MaintenanceService::class)`.
 */
interface MaintenanceService {

    fun isEnabled(): Boolean

    /** The message shown on the server list and in the kick screen. */
    fun message(): String

    /** Toggle maintenance and, optionally, update the message. Persists and broadcasts to every proxy. */
    fun set(enabled: Boolean, message: String? = null): CompletableFuture<Void>

    /** Observe changes (a proxy re-reads its state to refresh the ping). Close to stop. */
    fun onChange(listener: () -> Unit): AutoCloseable

    fun close()
}
