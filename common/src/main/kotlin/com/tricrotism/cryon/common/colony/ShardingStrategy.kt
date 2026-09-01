package com.tricrotism.cryon.common.colony

/**
 * Which node a message for a sharded service should go to.
 *
 * Shard `0` is reserved and means **the queen**. That is how a caller says "whoever owns this",
 * without knowing who that is. Anything else selects a shard by `abs(shard) % shards.size`.
 */
fun interface ShardingStrategy {

    /**
     * Which shard this message belongs to. Called once per send, so a stateful strategy such as
     * [roundRobin] advances here rather than at construction.
     */
    fun shard(): Int

    companion object {

        /** Everything to the queen. The default, and what you want unless you know otherwise. */
        val Queen: ShardingStrategy = ShardingStrategy { 0 }

        /**
         * Pin to a shard by id, so the same subject always lands on the same node.
         *
         * The reason to prefer this over [RoundRobin] for anything stateful: a player's requests
         * reaching one node means that node can cache them. Coerced off zero because zero means the
         * queen, and a hash that happened to land there would silently reroute the whole shard.
         */
        fun byId(id: Any): ShardingStrategy = ShardingStrategy { id.hashCode().let { if (it == 0) 1 else it } }

        /**
         * Spread across shards in sequence.
         *
         * The counter is per-process, so each node balances only its own outbound traffic; the
         * cluster-wide spread is even only insofar as the senders are. Right for stateless fan-out,
         * wrong for anything a node would want to remember.
         */
        fun roundRobin(): ShardingStrategy {
            val counter = java.util.concurrent.atomic.AtomicInteger()
            return ShardingStrategy { counter.incrementAndGet().let { if (it == 0) 1 else it } }
        }
    }
}
