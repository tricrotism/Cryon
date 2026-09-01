package com.tricrotism.cryon.common.bucket

/**
 * A thread-safe rotating cursor over a fixed list, used to walk a [Bucket]'s partitions
 * deterministically over time, each [next] advances one step and wraps at the end.
 */
interface Cycle<E> {

    // The element under the cursor without moving it
    val current: E

    /**
     * Advance one step (wrapping) and return the new current element.
     */
    fun next(): E

    /**
     * Step back one (wrapping) and return the new current element.
     */
    fun previous(): E

    /**
     * The element [next] would land on, without moving the cursor.
     */
    fun peekNext(): E
}

