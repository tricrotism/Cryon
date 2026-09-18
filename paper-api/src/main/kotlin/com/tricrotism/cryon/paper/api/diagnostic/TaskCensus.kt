package com.tricrotism.cryon.paper.api.diagnostic

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * What is still running, and which module owns it.
 *
 * `/cryon retention` answers whether an unloaded module's classloader was collected, which is the
 * question *after* the leak. This answers the one before it: a module that was disabled ten minutes
 * ago and still has nine repeating tasks and four listeners registered is the reason the classloader
 * is still reachable, and nothing else in the server will say so. spark cannot: it attributes sampled
 * stack frames, so it sees a task only while that task is on a CPU, and a leaked timer that ticks
 * cheaply is invisible in a profile.
 *
 * **Ownership comes from the classloader, not from a plugin field.** Every module runs in its own
 * `URLClassLoader`, so the lambda handed to [Schedulers][com.tricrotism.cryon.paper.api.scheduler.Schedulers]
 * is a class from the module that wrote it, and that is the whole identification. It matters because
 * the obvious alternative does not work here: Paper records the owning *plugin* on each task, and
 * every module's task belongs to the Cryon plugin, so asking Paper produces one row for the entire
 * server. It also means no reflection into Folia's scheduler internals, and nothing to re-verify on a
 * Paper bump, because this counts at the door every module already comes through rather than reading
 * the queues afterwards.
 *
 * **Only repeating work is counted.** A one-shot finishes on its own and is not what leaks, while
 * `Schedulers.entity` sits on genuinely hot paths where a map write per call would be a real cost for
 * a number nobody reads. Timers and listeners are created rarely and are exactly what outlives a
 * module that forgot to tear them down.
 *
 * Costs nothing when unused: entries are pruned on read rather than by a sweeper, so a server that
 * never runs `/cryon tasks` pays one weak reference per timer and nothing else.
 */
object TaskCensus {

    enum class Kind { TIMER, LISTENER }

    /**
     * Names a classloader. Installed by the core, which owns the module registry; until then every
     * owner reads as [UNKNOWN], which is honest rather than wrong.
     */
    @Volatile
    private var naming: (ClassLoader?) -> String? = { null }

    private class Entry(val kind: Kind, task: ScheduledTask?, cancelled: (() -> Boolean)?) {
        /**
         * Weak so a census can never be the thing keeping a module's task, and through it the
         * module's classloader, alive. That would make this the leak it exists to report.
         */
        private val task = task?.let(::WeakReference)
        private val cancelled = cancelled

        /** False once the work is gone, by any route: cancelled, finished, or collected. */
        fun live(): Boolean {
            cancelled?.let { return !it() }
            val handle = task?.get() ?: return false
            return !handle.isCancelled
        }
    }

    private val entries: MutableMap<String, MutableSet<Entry>> = ConcurrentHashMap()

    fun install(naming: (ClassLoader?) -> String?) {
        this.naming = naming
    }

    /** Record a repeating task against whoever wrote [owner]. Called by `Schedulers`. */
    fun recordTimer(owner: Any, task: ScheduledTask?) {
        if (task == null) return
        add(nameOf(owner), Entry(Kind.TIMER, task, null))
    }

    /** Record a live subscription against whoever wrote [owner]. Called by `Events`. */
    fun recordListener(owner: Any, cancelled: () -> Boolean) {
        add(nameOf(owner), Entry(Kind.LISTENER, null, cancelled))
    }

    /**
     * Live counts per owner, per kind. Prunes what has finished as it goes, which is why there is no
     * sweeper: the only caller that cares about accuracy is the one asking.
     */
    fun snapshot(): Map<String, Map<Kind, Int>> {
        val result = LinkedHashMap<String, Map<Kind, Int>>()

        for ((owner, set) in entries) {
            set.removeIf { !it.live() }
            if (set.isEmpty()) {
                entries.remove(owner, set)
                continue
            }
            val counts = java.util.EnumMap<Kind, Int>(Kind::class.java)
            for (entry in set) counts.merge(entry.kind, 1, Int::plus)
            result[owner] = counts
        }

        return result.toSortedMap()
    }

    // Deliberately no `forget(owner)`. Dropping a module's entries when its jar unloads would hide
    // precisely what this is for: a timer still ticking after the module that made it is gone. An
    // entry that really ended prunes itself on the next read.

    private fun add(owner: String, entry: Entry) {
        entries.computeIfAbsent(owner) { Collections.newSetFromMap(ConcurrentHashMap()) } += entry
    }

    private fun nameOf(owner: Any): String =
        naming(owner.javaClass.classLoader) ?: UNKNOWN

    const val UNKNOWN = "core"
}
