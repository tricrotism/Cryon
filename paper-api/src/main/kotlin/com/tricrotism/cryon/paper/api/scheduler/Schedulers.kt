package com.tricrotism.cryon.paper.api.scheduler

import com.tricrotism.cryon.paper.api.CryonPaper
import com.tricrotism.cryon.paper.api.scheduler.Schedulers.async
import com.tricrotism.cryon.paper.api.scheduler.Schedulers.entity
import com.tricrotism.cryon.paper.api.scheduler.Schedulers.global
import com.tricrotism.cryon.paper.api.scheduler.Schedulers.region
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import java.util.concurrent.TimeUnit

/**
 * Folia-aware scheduling over Paper's threaded-region schedulers. Pick the scope that owns the data
 * you touch — never the single global main thread for world/entity work:
 *
 * - [global] — server-wide main work with no world context.
 * - [region] — work scoped to a `Location`'s region (blocks, world state there).
 * - [entity] — work that follows an entity across region threads (teleports, inventory).
 * - [async] — off the main thread (I/O, network); no Bukkit API.
 *
 * One-shot callbacks take `() -> Unit`; repeating callbacks take `(ScheduledTask) -> Unit` so they
 * can cancel themselves. Tick delays must be `>= 1`. Returns the `ScheduledTask` (nullable for
 * entity tasks, which fail if the entity has been removed).
 */
object Schedulers {

    private val plugin get() = CryonPaper.plugin

    fun global(task: () -> Unit): ScheduledTask =
        Bukkit.getGlobalRegionScheduler().run(plugin) { task() }

    fun globalLater(delayTicks: Long, task: () -> Unit): ScheduledTask =
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { task() }, delayTicks)

    fun globalTimer(delayTicks: Long, periodTicks: Long, task: (ScheduledTask) -> Unit): ScheduledTask =
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { task(it) }, delayTicks, periodTicks)

    fun region(location: Location, task: () -> Unit): ScheduledTask =
        Bukkit.getRegionScheduler().run(plugin, location) { task() }

    fun regionLater(location: Location, delayTicks: Long, task: () -> Unit): ScheduledTask =
        Bukkit.getRegionScheduler().runDelayed(plugin, location, { task() }, delayTicks)

    fun regionTimer(
        location: Location,
        delayTicks: Long,
        periodTicks: Long,
        task: (ScheduledTask) -> Unit
    ): ScheduledTask =
        Bukkit.getRegionScheduler().runAtFixedRate(plugin, location, { task(it) }, delayTicks, periodTicks)

    fun entity(entity: Entity, retired: Runnable? = null, task: () -> Unit): ScheduledTask? =
        entity.scheduler.run(plugin, { task() }, retired)

    fun entityLater(entity: Entity, delayTicks: Long, retired: Runnable? = null, task: () -> Unit): ScheduledTask? =
        entity.scheduler.runDelayed(plugin, { task() }, retired, delayTicks)

    fun entityTimer(
        entity: Entity,
        delayTicks: Long,
        periodTicks: Long,
        retired: Runnable? = null,
        task: (ScheduledTask) -> Unit,
    ): ScheduledTask? =
        entity.scheduler.runAtFixedRate(plugin, { task(it) }, retired, delayTicks, periodTicks)

    fun async(task: () -> Unit): ScheduledTask =
        Bukkit.getAsyncScheduler().runNow(plugin) { task() }

    fun asyncLater(delay: Long, unit: TimeUnit, task: () -> Unit): ScheduledTask =
        Bukkit.getAsyncScheduler().runDelayed(plugin, { task() }, delay, unit)

    fun asyncTimer(initialDelay: Long, period: Long, unit: TimeUnit, task: (ScheduledTask) -> Unit): ScheduledTask =
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, { task(it) }, initialDelay, period, unit)
}
