package com.tricrotism.cryon.common.experiment

import java.util.*

/**
 * A live A/B - or A/B/C, or A/B/C/D - test.
 *
 * There is no separate "number of arms" anywhere in this system: two arms and five arms are the same
 * code and the same storage, so the only difference between an A/B test and an A/B/C test is the
 * length of a list.
 *
 * [salt] is what makes assignment re-randomisable and independent. Two experiments running at once
 * must not put the same players in the same relative places - otherwise a result is really the two of
 * them entangled - and re-running a settled experiment needs a fresh split rather than the same players
 * falling into the same arms again. Generated per experiment; change it to reshuffle.
 *
 * [enabled] is the kill switch. A disabled experiment assigns nobody, and callers fall back to whatever
 * they do without an arm, which must always be a default worth shipping.
 */
data class Experiment(
    val id: String,
    val arms: List<Arm>,
    val salt: String = UUID.randomUUID().toString(),
    val enabled: Boolean = true,
) {

    init {
        require(id.isNotBlank()) { "an experiment needs an id" }
        require(arms.isNotEmpty()) { "experiment $id has no arms" }
        require(arms.distinctBy { it.id }.size == arms.size) { "experiment $id has two arms with one id" }
    }

    // The denominator every assignment is taken against. Declared after the checks above, so it is
    // never derived from a list that was about to be rejected
    val totalWeight: Int = arms.sumOf { it.weight }

    /**
     * **Changing the arms reshuffles the population, and that is not avoidable.** Weights are relative,
     * so appending a third even arm to an even pair moves every edge - the first arm's slice goes from
     * half the space to a third of it, and the players in the difference change arms. No ordering trick
     * avoids it; the only way to hold people still would be absolute slices, which would leave the space
     * partly unassigned. So treat any edit to the arms as starting a new experiment, and read a result
     * that straddles one with suspicion.
     *
     * The edge comes from *cumulative* weight rather than from adding truncated per-arm widths.
     * Accumulating the truncation would leave the last edge short of [BUCKETS] - three even arms give
     * 3333 each, summing to 9999 - and hand the remainder to whichever arm happened to be last.
     *
     * @return the arm [bucket] falls in, where `bucket` is in `0 until` [BUCKETS]
     */
    fun armAt(bucket: Int): Arm {
        var cumulative = 0L

        for (arm in arms) {
            cumulative += arm.weight
            if (bucket < cumulative * BUCKETS / totalWeight) return arm
        }

        return arms.last()
    }

    companion object {

        // Assignment resolution. A thousand would make a 0.1% canary unrepresentable; a million buys
        // nothing, because no Minecraft network has the players for the extra precision to mean anything
        const val BUCKETS: Int = 10_000

        private const val FNV_OFFSET = -0x340d631b7bdddcdbL
        private const val FNV_PRIME = 0x100000001b3L

        /**
         * **The whole of the assignment logic, and deliberately arithmetic rather than a digest.** It
         * runs wherever a variant is chosen, which is display code - a message being sent, a menu being
         * drawn - so it allocates nothing and takes no lock. A `MessageDigest` would be correct too and
         * roughly two hundred times slower for a property nothing here needs: this decides which wording
         * a player sees, not who can spend money.
         *
         * The mixer is SplitMix64's finalizer, applied twice so both halves of the UUID reach every
         * output bit. Documented rather than buried because assignment has to be reproducible: the same
         * player, experiment and salt must land in the same arm on every server, across restarts, for as
         * long as the experiment runs. An experiment whose population quietly reshuffles is not one.
         *
         * The shift before the modulo drops the sign bit rather than taking an absolute value, because
         * `Long.MIN_VALUE` has no absolute value and would stay negative.
         *
         * @return the bucket [subject] falls in for an experiment seeded with [seed]
         */
        fun bucket(seed: Long, subject: UUID): Int {
            val mixed = mix(mix(seed xor subject.mostSignificantBits) xor subject.leastSignificantBits)

            return ((mixed ushr 1) % BUCKETS).toInt()
        }

        /**
         * FNV-1a over the salt's UTF-16 code units, rather than `String.hashCode`, which is only 32 bits
         * - two salts colliding there would silently give two experiments the identical population
         * split, which is the one thing the salt exists to prevent.
         *
         * @return a stable 64-bit seed for [salt]
         */
        fun seed(salt: String): Long {
            var hash = FNV_OFFSET

            for (index in salt.indices) {
                val code = salt[index].code
                hash = (hash xor (code and 0xff).toLong()) * FNV_PRIME
                hash = (hash xor (code ushr 8).toLong()) * FNV_PRIME
            }

            return hash
        }

        /**
         * The arms as one column rather than a second table. They are always read whole and never
         * queried across, so a join would buy nothing and cost a table.
         *
         * @return [arms] as `a:50,b:50`
         */
        fun encodeArms(arms: List<Arm>): String = arms.joinToString(",") { "${it.id}:${it.weight}" }

        /**
         * @return the arms [encodeArms] wrote, or null when the text is empty or malformed, so a corrupt
         *   row disables an experiment rather than throwing on every server that reads it
         */
        fun decodeArms(encoded: String): List<Arm>? {
            val arms = encoded.split(',').mapNotNull { part ->
                val separator = part.lastIndexOf(':')
                if (separator <= 0) return@mapNotNull null

                val weight = part.substring(separator + 1).trim().toIntOrNull() ?: return@mapNotNull null
                if (weight <= 0) return@mapNotNull null

                Arm(part.substring(0, separator).trim(), weight)
            }

            return arms.ifEmpty { null }
        }

        private fun mix(value: Long): Long {
            var z = value + -0x61c8864680b583ebL
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L

            return z xor (z ushr 31)
        }
    }
}
