package com.tricrotism.cryon.inventory

import com.tricrotism.cryon.common.bucket.Bucket
import com.tricrotism.cryon.common.bucket.Buckets
import com.tricrotism.cryon.common.bucket.PartitioningStrategies
import com.tricrotism.cryon.inventory.DefaultInventorySearch.Companion.PARTITIONS
import com.tricrotism.cryon.paper.api.inventory.InventorySearch
import com.tricrotism.cryon.paper.api.inventory.InventorySearch.Match
import com.tricrotism.cryon.paper.api.scheduler.Schedulers
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Default [InventorySearch]. Snapshots the online players on the global thread into a partitioned
 * [Bucket], then walks one partition per tick via its [Bucket.cycle] — the same load-amortization
 * the ChatEmojis anti-dupe sweep gets from its 20-partition bucket, so any search covers the whole
 * network within [PARTITIONS] ticks regardless of how many players are online.
 *
 * Each player's inventory is read on that player's own entity scheduler, which keeps it correct on
 * Folia (inventories are region/entity state). A per-search [AtomicInteger] counts the scheduled
 * scans down: every player resolves exactly once — the scan ran, or the player logged off first —
 * and the instant the last one lands, [onComplete] fires once on the global thread. `remaining`
 * starts at the full count, so an early scan finishing can never trip completion before the later
 * partitions are even swept.
 */
class DefaultInventorySearch : InventorySearch {

    override fun search(matcher: (ItemStack) -> Boolean, onComplete: (List<Match>) -> Unit) {
        Schedulers.global {
            val online = Bukkit.getOnlinePlayers().map { it.uniqueId }
            if (online.isEmpty()) {
                onComplete(emptyList())
                return@global
            }

            val bucket: Bucket<UUID> = Buckets.concurrent(PARTITIONS, PartitioningStrategies.lowestSize())
            bucket.addAll(online)

            val results = ConcurrentLinkedQueue<Match>()
            val remaining = AtomicInteger(bucket.size)
            val finish = { if (remaining.decrementAndGet() == 0) Schedulers.global { onComplete(results.toList()) } }

            val cycle = bucket.cycle()
            var swept = 0

            Schedulers.globalTimer(1, 1) { task ->
                cycle.next().forEach { uuid ->
                    val player = Bukkit.getPlayer(uuid)
                    if (player == null) {
                        finish() // logged off between snapshot and sweep
                        return@forEach
                    }
                    val scheduled = Schedulers.entity(player, retired = Runnable { finish() }) {
                        scan(player, matcher, results)
                        finish()
                    }
                    if (scheduled == null) finish() // retired the moment we tried to schedule
                }
                if (++swept >= bucket.partitionCount) task.cancel()
            }
        }
    }

    private fun scan(player: Player, matcher: (ItemStack) -> Boolean, out: ConcurrentLinkedQueue<Match>) {
        val contents = player.inventory.contents
        for (slot in contents.indices) {
            val item = contents[slot] ?: continue
            if (item.isEmpty || !matcher(item)) continue
            out.add(Match(player.uniqueId, player.name, slot, item.amount))
        }
    }

    private companion object {
        const val PARTITIONS = 20
    }
}
