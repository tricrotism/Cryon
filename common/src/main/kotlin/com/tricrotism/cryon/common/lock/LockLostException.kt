package com.tricrotism.cryon.common.lock

/**
 * The lease was lost while the body was running, so the body was canceled part-way.
 *
 * Whatever it was doing is in an unknown state. It stopped at a suspension point, not at a
 * boundary it chose. Treat it as a failed attempt and let whoever holds the lock now redo it, which
 * is why the work under a lock should be safe to repeat.
 */
class LockLostException(namespace: String, key: String) :
    RuntimeException("Lost the lease on '$namespace:$key' while holding it")
