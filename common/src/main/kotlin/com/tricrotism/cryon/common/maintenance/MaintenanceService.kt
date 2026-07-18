package com.tricrotism.cryon.common.maintenance

import java.util.concurrent.CompletableFuture

/**
 * Network-wide maintenance toggle, shared across every proxy. When on, proxies show an "under
 * maintenance" server-list entry (with a protocol number no client matches) and deny non-bypass
 * logins. Backed by SQL (source of truth when a `Database` is present) and synced instantly across
 * proxies over the `Messenger`, mirroring `FeatureFlags`.
 *
 * **Proxy-side only**, and always registered there — resolve via `services.get(MaintenanceService::class)`
 * on Velocity. It is deliberately absent on Paper: maintenance is enforced where logins arrive.
 */
interface MaintenanceService {

    fun isEnabled(): Boolean

    /** The message shown on the server list and in the kick screen. */
    fun message(): String

    /** Toggle maintenance and, optionally, update the message. Persists and broadcasts to every proxy. */
    fun set(enabled: Boolean, message: String? = null): CompletableFuture<Void>

    /**
     * Player names (lowercased) allowed to join while maintenance is on, independent of the
     * `cryon.maintenance.bypass` permission. Managed by command and synced across every proxy.
     */
    fun allowlist(): Set<String>

    /** Whether [name] may bypass maintenance via the allowlist (case-insensitive). */
    fun isAllowed(name: String): Boolean

    /** Add [name] to the allowlist. Returns true if it was newly added. Persists and broadcasts. */
    fun allow(name: String): Boolean

    /** Remove [name] from the allowlist. Returns true if it was present. Persists and broadcasts. */
    fun disallow(name: String): Boolean

    /** Observe changes (a proxy re-reads its state to refresh the ping). Close to stop. */
    fun onChange(listener: () -> Unit): AutoCloseable

    fun close()
}
