package com.tricrotism.cryon.common.diagnostic

import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.concurrent.ConcurrentHashMap

/**
 * Answers the one question a heap dump is normally needed for: **did that actually get collected?**
 *
 * Hand it something that ought to become garbage, a module's classloader, a closed session, a
 * cache entry, and it holds a phantom reference to it. Once the collector reclaims the object the
 * reference is enqueued, and [report] can say so. Nothing here keeps the object alive, so tracking
 * something can never be the reason it leaks.
 *
 * **Why this exists for Cryon in particular.** Hot-swapping module jars is the framework's headline
 * feature and a stranded classloader is its documented failure mode, a module that left a listener,
 * a task or a captured lambda behind keeps its whole jar's classes resident, and the symptom is a
 * slow metaspace climb across reloads that nothing reports until the server dies of it. External
 * tooling cannot see this: `LagFinder` attributes heap by package prefix, and every Cryon module is
 * `com.tricrotism.cryon.*`, so its histogram credits them all to the core plugin. This does not
 * attribute by name at all. It observes reclamation directly, which is the only evidence that
 * actually settles the question.
 *
 * **What a negative result means, precisely.** `live > 0` says the object was *not yet* reclaimed,
 * not that it leaked: nothing has necessarily run a collection since it was dropped. Read it after a
 * GC, and read a *rising* live count across repeated reloads as the real signal, one survivor is
 * noise, ten reloads leaving ten survivors is a leak. That is the same discipline LagFinder's old-gen
 * trend uses, applied to objects you can name instead of to the heap as a whole.
 *
 * Thread-safe, allocation-free to read, and cheap enough to leave on: a phantom reference per
 * tracked object and nothing per tick.
 */
class Retention {

    /**
     * A phantom rather than a weak reference.
     *
     * A weak reference is cleared *before* finalization and before the object is genuinely gone; a
     * phantom is enqueued only once the collector has finished with it, which is the fact being
     * measured. The key rides on the reference itself because by the time it is enqueued the
     * referent is unavailable by definition. There is nothing left to ask what it was.
     */
    private class Tracked(
        referent: Any,
        val key: String,
        queue: ReferenceQueue<Any>,
    ) : PhantomReference<Any>(referent, queue)

    private val queue = ReferenceQueue<Any>()

    /** key -> the references registered under it that have not yet been enqueued. */
    private val outstanding = ConcurrentHashMap<String, MutableSet<Tracked>>()

    /** key -> how many have been registered and how many the collector has since reclaimed. */
    private val totals = ConcurrentHashMap<String, Counters>()

    private class Counters {
        @Volatile
        var registered: Int = 0

        @Volatile
        var collected: Int = 0
    }

    /**
     * Watch [value] under [key] and report on it later.
     *
     * [key] is a bucket, not an identity. Track every reload of one module under the same key and
     * the count across reloads is what tells the story.
     */
    fun track(key: String, value: Any) {
        drain()
        val reference = Tracked(value, key, queue)
        outstanding.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() } += reference
        counters(key).let { synchronized(it) { it.registered++ } }
    }

    /**
     * What has been reclaimed and what has not, per key.
     *
     * Drains the queue first, so the answer reflects every collection that has happened up to this
     * call rather than up to the last [track].
     */
    fun report(): Map<String, Retained> {
        drain()
        return totals.entries.associate { (key, counters) ->
            val registered = counters.registered
            val collected = counters.collected
            key to Retained(registered, collected, (registered - collected).coerceAtLeast(0))
        }
    }

    fun report(key: String): Retained? = report()[key]

    /** Forget [key] entirely. The outstanding references are dropped, not resolved. */
    fun forget(key: String) {
        outstanding.remove(key)
        totals.remove(key)
    }

    fun clear() {
        outstanding.clear()
        totals.clear()
    }

    /**
     * Move whatever the collector has enqueued into the counters.
     *
     * Polled rather than drained on a thread of its own: the only moments the numbers matter are
     * when somebody asks and when something new is tracked, and a dedicated thread would be a
     * lifecycle to own for a queue that is empty almost always.
     */
    private fun drain() {
        while (true) {
            val reference = queue.poll() ?: return
            val tracked = reference as? Tracked ?: continue
            outstanding[tracked.key]?.remove(tracked)
            counters(tracked.key).let { synchronized(it) { it.collected++ } }
        }
    }

    private fun counters(key: String): Counters = totals.computeIfAbsent(key) { Counters() }

    /**
     * [live] is the count not yet reclaimed. Read it after a collection, and read its trend across
     * repeated reloads rather than any single value. See the note on [Retention].
     */
    data class Retained(val registered: Int, val collected: Int, val live: Int) {
        override fun toString(): String = "$live live / $registered tracked ($collected collected)"
    }
}
