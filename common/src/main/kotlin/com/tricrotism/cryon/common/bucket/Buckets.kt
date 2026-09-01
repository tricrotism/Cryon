package com.tricrotism.cryon.common.bucket

import java.util.concurrent.ConcurrentHashMap

/**
 * Factory for [Bucket]s. Pick the backing set to match your concurrency needs.
 */
object Buckets {

    /**
     * Backed by [ConcurrentHashMap]-based sets: any thread may read, iterate and cycle while another
     * mutates.
     *
     * **Mutation itself is not concurrent.** An add or a remove touches two sets, the membership set
     * and one partition, and the pair is not atomic: an add racing a remove of the same element can
     * leave it in a partition that [Bucket.contains] and [Bucket.size] both deny. Confine writes to
     * one thread (fill it, then share it), which is what the concurrent sets are here to make safe.
     */
    fun <E> concurrent(partitionCount: Int, strategy: PartitioningStrategy<E>): Bucket<E> =
        PartitionedBucket(partitionCount, strategy) { ConcurrentHashMap.newKeySet() }

    /**
     * Backed by plain [HashSet]s. Single-threaded use only.
     */
    fun <E> hashSet(partitionCount: Int, strategy: PartitioningStrategy<E>): Bucket<E> =
        PartitionedBucket(partitionCount, strategy) { HashSet() }
}

