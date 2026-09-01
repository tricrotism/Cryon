package com.tricrotism.cryon.common.bucket

/**
 * Decides which partition a new element lands in when added to a [Bucket].
 */
fun interface PartitioningStrategy<E> {
    fun allocate(element: E, bucket: Bucket<E>): Int
}
