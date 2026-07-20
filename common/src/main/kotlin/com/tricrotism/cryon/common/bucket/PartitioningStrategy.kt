package com.tricrotism.cryon.common.bucket

import java.util.concurrent.ThreadLocalRandom

/** Decides which partition a new element lands in when added to a [Bucket]. */
fun interface PartitioningStrategy<E> {
    fun allocate(element: E, bucket: Bucket<E>): Int
}

/** Ready-made [PartitioningStrategy]s. */
object PartitioningStrategies {

    /** Spreads elements at random across the partitions. */
    fun <E> random(): PartitioningStrategy<E> =
        PartitioningStrategy { _, bucket -> ThreadLocalRandom.current().nextInt(bucket.partitionCount) }

    /** Keeps partitions balanced by placing each new element in whichever currently holds the fewest. */
    fun <E> lowestSize(): PartitioningStrategy<E> = PartitioningStrategy { _, bucket ->
        var index = 0
        var lowest = Int.MAX_VALUE
        for (partition in bucket.partitions) {
            val size = partition.size
            if (size < lowest) {
                lowest = size
                index = partition.index
            }
        }
        index
    }
}
