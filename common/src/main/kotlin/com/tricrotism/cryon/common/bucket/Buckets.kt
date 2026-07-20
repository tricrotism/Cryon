package com.tricrotism.cryon.common.bucket

import java.util.concurrent.ConcurrentHashMap

/** Factory for [Bucket]s — pick the backing set to match your concurrency needs. */
object Buckets {

    /** Backed by [ConcurrentHashMap]-based sets; safe to add/remove from any thread while cycling. */
    fun <E> concurrent(partitionCount: Int, strategy: PartitioningStrategy<E>): Bucket<E> =
        PartitionedBucket(partitionCount, strategy) { ConcurrentHashMap.newKeySet() }

    /** Backed by plain [HashSet]s — single-threaded use only. */
    fun <E> hashSet(partitionCount: Int, strategy: PartitioningStrategy<E>): Bucket<E> =
        PartitionedBucket(partitionCount, strategy) { HashSet() }
}

private class PartitionedBucket<E>(
    override val partitionCount: Int,
    private val strategy: PartitioningStrategy<E>,
    setSupplier: () -> MutableSet<E>,
) : AbstractMutableSet<E>(), Bucket<E> {

    init {
        require(partitionCount >= 1) { "A bucket needs at least one partition." }
    }

    private val content: MutableSet<E> = setSupplier()
    private val backing: List<MutableSet<E>> = List(partitionCount) { setSupplier() }
    private val views: List<BucketPartition<E>> = backing.mapIndexed { i, set -> PartitionView(set, i) }
    private val partitionCycle: Cycle<BucketPartition<E>> = CycleImpl(views)

    override val size: Int get() = content.size
    override val partitions: List<BucketPartition<E>> get() = views
    override fun partition(index: Int): BucketPartition<E> = views[index]
    override fun cycle(): Cycle<BucketPartition<E>> = partitionCycle

    override fun add(element: E): Boolean {
        if (!content.add(element)) return false
        backing[strategy.allocate(element, this)].add(element)
        return true
    }

    override fun remove(element: E): Boolean {
        if (!content.remove(element)) return false
        backing.forEach { it.remove(element) }
        return true
    }

    override fun contains(element: E): Boolean = content.contains(element)

    override fun clear() {
        content.clear()
        backing.forEach { it.clear() }
    }

    override fun iterator(): MutableIterator<E> = object : MutableIterator<E> {
        private val delegate = content.iterator()
        private var current: E? = null

        override fun hasNext(): Boolean = delegate.hasNext()
        override fun next(): E = delegate.next().also { current = it }

        override fun remove() {
            delegate.remove()
            current?.let { removed -> backing.forEach { it.remove(removed) } }
        }
    }

    private class PartitionView<E>(
        private val set: MutableSet<E>,
        override val index: Int,
    ) : AbstractSet<E>(), BucketPartition<E> {
        override val size: Int get() = set.size
        override fun contains(element: E): Boolean = set.contains(element)
        override fun iterator(): Iterator<E> = set.iterator()
    }
}
