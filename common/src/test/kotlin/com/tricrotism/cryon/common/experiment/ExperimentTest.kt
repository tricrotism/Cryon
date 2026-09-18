package com.tricrotism.cryon.common.experiment

import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Assignment is the part of this system that is silent when it is wrong.
 *
 * A skewed split still returns a valid arm for everybody, so the experiment runs, the dashboard fills
 * in, and the result is simply untrue - and a split that is not stable across restarts produces a
 * population that quietly reshuffles underneath a running test, which looks like noise rather than a
 * bug. Neither shows up as an error anywhere, so both are pinned here.
 *
 * Deterministic: a fixed list of UUIDs from a seeded [Random], never `randomUUID`, so a failure is
 * reproducible rather than something that happened once on somebody's machine.
 */
class ExperimentTest {

    private val population: List<UUID> = Random(20260912).let { random ->
        List(20_000) { UUID(random.nextLong(), random.nextLong()) }
    }

    private fun split(experiment: Experiment): Map<String, Int> {
        val seed = Experiment.seed(experiment.salt)
        return population
            .groupingBy { experiment.armAt(Experiment.bucket(seed, it)).id }
            .eachCount()
    }

    @Test
    fun `the same player always lands in the same arm`() {
        val experiment = Experiment("COPY", listOf(Arm("a", 1), Arm("b", 1)), salt = "fixed")
        val seed = Experiment.seed(experiment.salt)
        population.take(500).forEach { subject ->
            val first = experiment.armAt(Experiment.bucket(seed, subject)).id
            repeat(3) {
                assertEquals(first, experiment.armAt(Experiment.bucket(seed, subject)).id)
            }
        }
    }

    @Test
    fun `an even split is even`() {
        val counts = split(Experiment("COPY", listOf(Arm("a", 1), Arm("b", 1)), salt = "fixed"))
        counts.values.forEach { count ->
            assertTrue(count in 9_500..10_500, "an even split over 20k gave $counts")
        }
    }

    @Test
    fun `weights are honoured, including a canary`() {
        val counts = split(Experiment("ROLLOUT", listOf(Arm("control", 90), Arm("canary", 10)), salt = "fixed"))
        assertTrue(counts.getValue("control") in 17_600..18_400, "expected ~18000 control, got $counts")
        assertTrue(counts.getValue("canary") in 1_600..2_400, "expected ~2000 canary, got $counts")
    }

    @Test
    fun `three arms split three ways`() {
        val counts = split(
            Experiment("COPY3", listOf(Arm("a", 1), Arm("b", 1), Arm("c", 1)), salt = "fixed")
        )
        assertEquals(3, counts.size, "expected every arm to be used, got $counts")
        counts.values.forEach { count ->
            assertTrue(count in 6_300..7_100, "an even three-way split over 20k gave $counts")
        }
    }

    @Test
    fun `the bucket space is covered exactly, with no arm collecting the remainder`() {
        val experiment = Experiment("EDGES", listOf(Arm("a", 1), Arm("b", 1), Arm("c", 1)))
        val widths = (0 until Experiment.BUCKETS)
            .groupingBy { experiment.armAt(it).id }
            .eachCount()
        assertEquals(Experiment.BUCKETS, widths.values.sum())
        widths.values.forEach { width ->
            assertTrue(width in 3_333..3_334, "uneven bucket widths: $widths")
        }
    }

    @Test
    fun `different salts assign independently`() {
        val arms = listOf(Arm("a", 1), Arm("b", 1))
        val first = Experiment("ONE", arms, salt = "salt-one")
        val second = Experiment("TWO", arms, salt = "salt-two")
        val firstSeed = Experiment.seed(first.salt)
        val secondSeed = Experiment.seed(second.salt)

        val agree = population.count { subject ->
            first.armAt(Experiment.bucket(firstSeed, subject)).id ==
                second.armAt(Experiment.bucket(secondSeed, subject)).id
        }
        // Independent coins agree about half the time. Correlated ones agree nearly always.
        assertTrue(agree in 9_500..10_500, "two salts agreed $agree/20000 times, which is not independent")
    }

    @Test
    fun `reshuffling moves a large share of the population`() {
        val arms = listOf(Arm("a", 1), Arm("b", 1))
        val before = Experiment("RERUN", arms, salt = "first-run")
        val after = before.copy(salt = "second-run")
        val beforeSeed = Experiment.seed(before.salt)
        val afterSeed = Experiment.seed(after.salt)

        val moved = population.count { subject ->
            before.armAt(Experiment.bucket(beforeSeed, subject)).id !=
                after.armAt(Experiment.bucket(afterSeed, subject)).id
        }
        assertTrue(moved > 9_000, "a fresh salt only moved $moved/20000; it is not reshuffling")
    }

    @Test
    fun `a salt collision would need a full 64 bits, not a String hashCode`() {
        assertNotEquals(Experiment.seed("Aa"), Experiment.seed("BB"))
    }

    @Test
    fun `arms encode and decode back to themselves`() {
        val arms = listOf(Arm("control", 90), Arm("canary", 10))
        assertEquals("control:90,canary:10", Experiment.encodeArms(arms))
        assertEquals(arms, Experiment.decodeArms("control:90,canary:10"))
    }

    @Test
    fun `a malformed arm list decodes to null rather than a half experiment`() {
        assertEquals(null, Experiment.decodeArms(""))
        assertEquals(null, Experiment.decodeArms("nonsense"))
        assertEquals(null, Experiment.decodeArms("a:0"))
        assertEquals(null, Experiment.decodeArms("a:-5"))
    }

    @Test
    fun `an arm id that would corrupt the encoding is refused at construction`() {
        assertFailsWith<IllegalArgumentException> { Arm("has,comma", 1) }
        assertFailsWith<IllegalArgumentException> { Arm("has:colon", 1) }
        assertFailsWith<IllegalArgumentException> { Arm("has|pipe", 1) }
        assertFailsWith<IllegalArgumentException> { Arm("UPPER", 1) }
        assertFailsWith<IllegalArgumentException> { Arm("a", 0) }
    }

    @Test
    fun `an experiment with duplicate arm ids is refused`() {
        assertFailsWith<IllegalArgumentException> {
            Experiment("DUPE", listOf(Arm("a", 1), Arm("a", 1)))
        }
    }
}
