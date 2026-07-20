package com.tricrotism.cryon.common.bucket

/**
 * A [MutableSet] whose elements are also split across a fixed number of partitions by a
 * [PartitioningStrategy]. Every element is held twice: once in the whole-set backing, and once in
 * the partition it was allocated to on insertion.
 *
 * The point is load amortization: instead of doing per-element work for the whole set at once, walk
 * the partitions over successive ticks ([cycle]) and process one slice at a time, so per-tick cost
 * is roughly `size / partitionCount`.
 */
interface Bucket<E> : MutableSet<E> {

    /** The number of partitions this bucket is split into. Fixed at creation. */
    val partitionCount: Int

    /** The partitions, indexed `0 until partitionCount`. Live views over the bucket's contents. */
    val partitions: List<BucketPartition<E>>

    /** The partition at [index]. */
    fun partition(index: Int): BucketPartition<E>

    /** A shared, thread-safe rotating cursor over [partitions] — `cycle().next()` each tick. */
    fun cycle(): Cycle<BucketPartition<E>>
}

/** One partition of a [Bucket]. Read-only: elements enter and leave through the parent bucket. */
interface BucketPartition<E> : Set<E> {

    /** This partition's index within its bucket. */
    val index: Int
}
