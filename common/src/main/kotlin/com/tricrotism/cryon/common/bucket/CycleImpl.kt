package com.tricrotism.cryon.common.bucket

import java.util.concurrent.atomic.AtomicInteger

/**
 * The [Cycle] a [Bucket] hands out, backed by an atomic cursor so several threads may advance it.
 */
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
