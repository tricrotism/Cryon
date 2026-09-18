package com.tricrotism.cryon.common.locale

import net.kyori.adventure.text.Component
import java.util.*

/**
 * Notified when a localized message is **displayed** to a player.
 *
 * The core fires this and does not consume it. What it exists for is the question nobody can answer
 * from a log: which of the things we tell players actually get read and acted on, and whether the way
 * a message is worded or coloured changes that. A feature that wants to know registers one observer
 * with [MessageObservers] and every message send on the server reaches it, including the ones written
 * next year.
 *
 * `rendered` is the finished component, so an observer can recover which palette colours were used
 * (see [com.tricrotism.cryon.common.text.CryonPalette.tags]) without the send site having to say.
 * Walking it costs something, so an observer on a busy server should memoize by key rather than
 * re-walking an unchanged template per send.
 *
 * **Called on the sending thread**, which is whatever thread decided to message the player. Do no
 * blocking work here and touch no owned state: record and return.
 */
fun interface MessageObserver {

    fun shown(player: UUID, key: String, surface: String, rendered: Component)
}
