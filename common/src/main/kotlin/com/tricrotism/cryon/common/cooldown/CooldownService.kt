package com.tricrotism.cryon.common.cooldown

import java.util.*
import kotlin.time.Duration

/**
 * Per-subject rate limits, shared so that every feature needing one stops writing its own.
 *
 * **[trigger] is the whole API.** It answers whether the caller may act *and* starts the next
 * cooldown, in one indivisible step. The obvious alternative — ask [remaining], then mark — is a
 * check-then-act race, and on Folia it is not a theoretical one: two of a player's actions can be
 * handled on different region threads at the same instant, both read zero, and both proceed. Every
 * gate therefore reads:
 *
 * ```
 * if (!cooldowns.trigger(player.uniqueId, "kit.daily", 24.hours)) {
 *     player.sendError("You must wait …")
 *     return
 * }
 * ```
 *
 * [remaining] exists only to *describe* a refusal that [trigger] already made — never to decide one.
 * That is the same split the currency layer draws between `withdraw` and `cachedBalance`, for the
 * same reason.
 *
 * **Scope is one process, and deliberately not the network.** A cooldown is nearly always about
 * pacing one player's interaction with what is in front of them, which is local by nature, and
 * making every check a Redis round trip would put network latency on a click. Where a limit really
 * must hold network-wide — a daily reward, a global claim — that is a *claim* rather than a
 * cooldown, and `KeyValueStore.delete`'s atomic boolean or a `DistributedLock` is the honest tool.
 *
 * State does not survive a restart. That is the right trade for pacing and the wrong one for a
 * 24-hour reward gate; back those with persisted state and use this only to stop the button being
 * mashed.
 *
 * Thread-safe. Registered by the core, so `services.get<CooldownService>()` always resolves.
 */
interface CooldownService {

    /**
     * Claim the next use of [id] for [subject], answering whether it was granted.
     *
     * True means the caller now holds the cooldown and may act. False means it is still running and
     * **nothing was changed** — a refused attempt does not extend the wait, so mashing a button
     * cannot lock someone out longer than the original [duration].
     *
     * A non-positive [duration] always grants and records nothing.
     */
    fun trigger(subject: UUID, id: String, duration: Duration): Boolean

    /**
     * How long until [id] is available to [subject] again; [Duration.ZERO] when it is available now.
     *
     * **For messages, not decisions.** By the time a caller acts on this the answer may be stale;
     * [trigger] is the only thing that both decides and records.
     */
    fun remaining(subject: UUID, id: String): Duration

    /** Whether [id] is currently running for [subject]. Same caveat as [remaining]. */
    fun isActive(subject: UUID, id: String): Boolean = remaining(subject, id) > Duration.ZERO

    /** End [id] early for [subject], so the next [trigger] grants. */
    fun clear(subject: UUID, id: String)

    /** End every cooldown for [subject]. */
    fun clearAll(subject: UUID)
}
