package com.tricrotism.cryon.common.bucket

/**
 * One partition of a [Bucket]. Read-only: elements enter and leave through the parent bucket.
 */
interface BucketPartition<E> : Set<E> {

    /** This partition's index within its bucket. */
    val index: Int
}
