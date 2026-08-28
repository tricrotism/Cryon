package com.tricrotism.cryon.common.signal

import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * The one [Signals] implementation. In-process by definition. See the note on [Signals].
 *
 * **Subscriptions are indexed by exact type and the assignable set is cached per dispatched type.**
 * The obvious implementation walks every subscription on every dispatch asking `isInstance`, which
 * is fine for ten subscribers and quietly terrible on a sell path at drop frequency. Resolving the
 * matching chain once per concrete signal type and reusing it makes a dispatch a map lookup plus a
 * walk of the handlers that actually apply.
 */
class LocalSignals(private val logger: Logger) : Signals {

    private class Entry(
        val type: Class<*>,
        val priority: Int,
        /** Ties within a priority resolve by registration order, and this is what records it. */
        val sequence: Long,
        val handler: suspend (Nothing) -> Unit,
    )

    private val byType = ConcurrentHashMap<Class<*>, CopyOnWriteArrayList<Entry>>()

    /**
     * Concrete signal type -> every handler that applies to it, already ordered.
     *
     * Invalidated wholesale on any subscribe or close rather than surgically: subscriptions happen
     * at module enable and dispatches happen at gameplay frequency, so the cheap thing to get right
     * is the read path. A wholesale clear costs one rebuild per signal type actually in use.
     */
    private val resolved = ConcurrentHashMap<Class<*>, List<Entry>>()

    private val sequence = AtomicLong()

    override fun <T : Signal> on(
        type: Class<T>,
        priority: Int,
        handler: suspend (T) -> Unit,
    ): Signals.SignalSubscription {
        @Suppress("UNCHECKED_CAST")
        val entry = Entry(type, priority, sequence.getAndIncrement(), handler as suspend (Nothing) -> Unit)
        byType.computeIfAbsent(type) { CopyOnWriteArrayList() } += entry
        resolved.clear()
        return Signals.SignalSubscription {
            byType[type]?.remove(entry)
            resolved.clear()
        }
    }

    override suspend fun <T : Signal> dispatch(signal: T): T {
        val handlers = resolved.computeIfAbsent(signal.javaClass, ::chainFor)
        if (handlers.isEmpty()) return signal
        for (entry in handlers) {
            try {
                @Suppress("UNCHECKED_CAST")
                (entry.handler as suspend (T) -> Unit)(signal)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Logged and skipped: one broken module must not stop the others modifying the value,
                // and must not fail the operation that emitted it.
                logger.error(
                    "A signal handler for {} failed",
                    signal.javaClass.simpleName,
                    e,
                )
            }
        }
        return signal
    }

    /**
     * Every handler registered against [type] or any of its supertypes, in dispatch order.
     *
     * Walks the registered keys rather than the class hierarchy, because the set of subscribed types
     * is small and known while the hierarchy of a signal is not, and an interface a signal picks up
     * three levels down would be missed by a naive superclass walk.
     */
    private fun chainFor(type: Class<*>): List<Entry> =
        byType.entries
            .filter { it.key.isAssignableFrom(type) }
            .flatMap { it.value }
            .sortedWith(compareBy({ it.priority }, { it.sequence }))
}
