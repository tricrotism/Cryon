@file:OptIn(InternalCoroutinesApi::class)

package com.tricrotism.cryon.paper.api.scheduler

import com.tricrotism.cryon.common.concurrent.CryonIO
import com.tricrotism.cryon.paper.api.CryonPaper
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kotlinx.coroutines.*
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import java.util.concurrent.CancellationException
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine dispatchers over Paper's threaded-region schedulers, the suspending counterpart to
 * [Schedulers]. Pick the scope that owns the data you touch, exactly as you would there:
 *
 * - [Global] for server-wide work with no world context.
 * - [region] for work scoped to a `Location`'s region.
 * - [entity] for work following an entity across region threads.
 * - [Async] for work off the server threads entirely, I/O and network. No Bukkit API.
 *
 * **Each one elides the hop when it is already on the right thread.** A dispatcher whose
 * `isDispatchNeeded` answers false resumes the coroutine inline, in the caller's own frame, instead
 * of scheduling it. That matters more than it looks: `Schedulers.global` *always* defers to a later
 * tick, so a `withContext(Global)` that blindly scheduled would cost a tick per hop even when the
 * caller was already on the global thread, and a read-then-write pair split across two ticks is the
 * check-then-act race the core's currency layer exists to avoid. Here the common case costs nothing
 * and the ordering is the caller's own.
 *
 * **They also implement [Delay]**, so `delay(…)` and `withTimeout(…)` schedule on the owning region
 * rather than parking on the coroutines default executor and hopping back afterwards. One scheduled
 * task instead of a thread plus a dispatch, and the timeout is cancelled with the task.
 */
object CryonDispatchers {

    private val plugin get() = CryonPaper.plugin

    /** The global region thread: server-wide state with no world context. */
    val Global: CoroutineDispatcher = GlobalRegionDispatcher

    /**
     * Off-server work: blocking I/O, Redis, HTTP. **No Bukkit API.**
     *
     * The Paper-side name for [CryonIO.dispatcher], so features reach one shared virtual-thread pool
     * through the same object as the region dispatchers rather than having to know about `:common`'s
     * plumbing. See [CryonIO] for why virtual threads and why SQL is the exception.
     */
    val Async: CoroutineDispatcher get() = CryonIO.dispatcher

    /** The region owning [location]: blocks and world state there. */
    fun region(location: Location): CoroutineDispatcher = RegionDispatcher(location)

    /**
     * The region owning [entity], following it as it moves between regions.
     *
     * If the entity is gone, already removed or retired before the task ran, the coroutine is
     * **cancelled** rather than left suspended forever. See [EntityDispatcher].
     */
    fun entity(entity: Entity): CoroutineDispatcher = EntityDispatcher(entity)

    /** Milliseconds to whole ticks, rounding up: a sub-tick delay must still wait a tick. */
    internal fun ticks(millis: Long): Long = ((millis + MILLIS_PER_TICK - 1) / MILLIS_PER_TICK).coerceAtLeast(1)

    private const val MILLIS_PER_TICK = 50L

    /**
     * Shared [Delay] wiring: a dispatcher supplies how it schedules a delayed task, and gets
     * `delay`/`withTimeout` support that stays on its own thread.
     */
    private abstract class RegionAwareDispatcher : CoroutineDispatcher(), Delay {

        /**
         * Schedule [task] after [delayTicks], or return null if it cannot be scheduled.
         *
         * [onRetired] must be invoked if the scope dies before [task] runs. Only the entity scheduler
         * can retire, but the parameter is on the shared signature because forgetting it is exactly
         * the bug it exists to prevent: a dropped delayed task leaves its continuation suspended for
         * the rest of the process's life, holding everything the coroutine captured.
         */
        protected abstract fun scheduleDelayed(
            delayTicks: Long,
            onRetired: () -> Unit,
            task: () -> Unit,
        ): ScheduledTask?

        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            val retired: () -> Unit = {
                continuation.cancel(CancellationException("The scope retired during a delay"))
            }
            val scheduled = scheduleDelayed(ticks(timeMillis), retired) {
                with(continuation) { resumeUndispatched(Unit) }
            }
            if (scheduled == null) {
                retired()
                return
            }
            continuation.invokeOnCancellation { runCatching { scheduled.cancel() } }
        }

        override fun invokeOnTimeout(
            timeMillis: Long,
            block: Runnable,
            context: CoroutineContext,
        ): DisposableHandle {
            // A retired scope needs no timeout fired: the dispatch path has already cancelled the
            // coroutine this timeout was guarding.
            val scheduled = scheduleDelayed(ticks(timeMillis), onRetired = {}) { block.run() }
                ?: return DisposableHandle { }
            return DisposableHandle { runCatching { scheduled.cancel() } }
        }
    }

    private object GlobalRegionDispatcher : RegionAwareDispatcher() {

        override fun isDispatchNeeded(context: CoroutineContext): Boolean = !Bukkit.isGlobalTickThread()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            Bukkit.getGlobalRegionScheduler().run(plugin) { block.run() }
        }

        override fun scheduleDelayed(delayTicks: Long, onRetired: () -> Unit, task: () -> Unit): ScheduledTask =
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { task() }, delayTicks)

        override fun toString(): String = "Cryon.Global"
    }

    private class RegionDispatcher(private val location: Location) : RegionAwareDispatcher() {

        override fun isDispatchNeeded(context: CoroutineContext): Boolean =
            !Bukkit.isOwnedByCurrentRegion(location)

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            Bukkit.getRegionScheduler().run(plugin, location) { block.run() }
        }

        override fun scheduleDelayed(delayTicks: Long, onRetired: () -> Unit, task: () -> Unit): ScheduledTask =
            Bukkit.getRegionScheduler().runDelayed(plugin, location, { task() }, delayTicks)

        override fun toString(): String =
            "Cryon.Region(${location.world?.name}, ${location.blockX shr 4}, ${location.blockZ shr 4})"
    }

    /**
     * Follows one entity's owning region.
     *
     * **A retired entity cancels the coroutine instead of stranding it.** Paper's entity scheduler
     * refuses work for an entity that has been removed. It answers null, or invokes the `retired`
     * callback, and a dispatcher that simply dropped the block there would leave the coroutine
     * suspended for the rest of the process's life, holding whatever it captured. That is the leak
     * shape this whole migration is meant to remove, so the dispatcher takes the other option:
     * cancel the job, then run the block anyway so the machinery observes the cancellation and
     * unwinds its `finally` blocks rather than never waking.
     *
     * The unwind runs on [Async], not on a server thread, because by then there is no region that
     * owns this entity to run it on. A `finally` on this path must therefore be cleanup, not Bukkit
     * work, which is the same rule that already applies to any cleanup racing a player's logout.
     */
    private class EntityDispatcher(private val entity: Entity) : RegionAwareDispatcher() {

        override fun isDispatchNeeded(context: CoroutineContext): Boolean =
            !Bukkit.isOwnedByCurrentRegion(entity)

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val scheduled = entity.scheduler.run(plugin, { block.run() }, { retire(context, block) })
            if (scheduled == null) retire(context, block)
        }

        override fun scheduleDelayed(delayTicks: Long, onRetired: () -> Unit, task: () -> Unit): ScheduledTask? =
            entity.scheduler.runDelayed(plugin, { task() }, { onRetired() }, delayTicks)

        private fun retire(context: CoroutineContext, block: Runnable) {
            context.cancel(CancellationException("Entity ${entity.uniqueId} was retired"))
            CryonIO.dispatcher.dispatch(context, block)
        }

        override fun toString(): String = "Cryon.Entity(${entity.uniqueId})"
    }
}
