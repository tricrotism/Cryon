package com.tricrotism.cryon.common.net

/**
 * Handle to cancel a [Messenger.subscribe]/[Messenger.handle] registration.
 */
fun interface MessengerSubscription {
    fun unsubscribe()
}
