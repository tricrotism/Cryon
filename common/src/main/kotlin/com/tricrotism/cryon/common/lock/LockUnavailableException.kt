package com.tricrotism.cryon.common.lock

import kotlin.time.Duration

/**
 * The lock was still held by somebody else when the wait ran out. Nothing was run.
 */
class LockUnavailableException(namespace: String, key: String, wait: Duration) :
    RuntimeException("Could not acquire '$namespace:$key' within $wait")
