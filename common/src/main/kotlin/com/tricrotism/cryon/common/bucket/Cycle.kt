package com.tricrotism.cryon.common.bucket

import java.util.concurrent.atomic.AtomicInteger

/**
 * A thread-safe rotating cursor over a fixed list, used to walk a [Bucket]'s partitions
 * deterministically over time — each [next] advances one step and wraps at the end.
 */
interface Cycle<E> {

    /** The element under the cursor without moving it. */
    val current: E

    /** Advance one step (wrapping) and return the new current element. */
    fun next(): E

    /** Step back one (wrapping) and return the new current element. */
    fun previous(): E

    /** The element [next] would land on, without moving the cursor. */
    fun peekNext(): E
}

internal class CycleImpl<E>(objects: List<E>) : Cycle<E> {

    private val objects: List<E> = objects.toList()
    private val size: Int = this.objects.size
    private val cursor = AtomicInteger(0)

    init {
        require(this.objects.isNotEmpty()) { "A cycle cannot be empty." }
    }

    override val current: E get() = objects[cursor.get()]

    override fun next(): E = objects[cursor.updateAndGet { if (it + 1 >= size) 0 else it + 1 }]

    override fun previous(): E = objects[cursor.updateAndGet { if (it == 0) size - 1 else it - 1 }]

    override fun peekNext(): E {
        val i = cursor.get()
        return objects[if (i + 1 >= size) 0 else i + 1]
    }
}
