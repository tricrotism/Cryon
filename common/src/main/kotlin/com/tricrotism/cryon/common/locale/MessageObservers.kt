package com.tricrotism.cryon.common.locale

import net.kyori.adventure.text.Component
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Where [MessageObserver]s register, and where the core fires them.
 *
 * **Only sites that genuinely displayed something call [shown].** Rendering a key is not showing it:
 * `MessageService.render` builds text that may end up in a menu icon, an action bar, a log line, or
 * nowhere at all, and counting those inflates the denominator of every "did anyone act on this" figure
 * with messages no player ever saw. So the rule is to instrument the display, never the render - and
 * the display is also the only site that knows which surface it is.
 */
object MessageObservers {

    // Sent to chat, through the shared prefix
    const val SURFACE_CHAT: String = "chat"

    // The title or lore of a menu icon
    const val SURFACE_MENU: String = "menu"

    private val logger = LoggerFactory.getLogger(MessageObservers::class.java)

    // Copy-on-write: read on every message send, written twice in a server's life
    private val observers = CopyOnWriteArrayList<MessageObserver>()

    // False on a server with nothing observing, the normal case, which must stay free. Check it before
    // doing any work to build the arguments for [shown]
    val active: Boolean
        get() = observers.isNotEmpty()

    /**
     * Registers [observer] until the handle is closed. Registering twice registers twice.
     *
     * @return the handle that stops it
     */
    fun observe(observer: MessageObserver): AutoCloseable {
        observers += observer
        return AutoCloseable { observers -= observer }
    }

    /**
     * Fans one display out to every observer. Cheap when nothing is observing.
     *
     * An observer that throws is a bug in the observer, and must not cost a player the message they
     * were being sent, so it is caught and the remaining observers still run.
     */
    fun shown(player: UUID, key: String, surface: String, rendered: Component) {
        if (observers.isEmpty()) return

        observers.forEach { observer ->
            try {
                observer.shown(player, key, surface, rendered)
            } catch (e: Exception) {
                logger.warn("Message observer failed for key {}", key, e)
            }
        }
    }
}
