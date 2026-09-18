package com.tricrotism.cryon.common.currency

import org.slf4j.helpers.NOPLogger
import java.math.BigDecimal
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the one question a failed [CurrencyJournal.append] has to be able to answer: did the record
 * land anyway?
 *
 * The append writes with `DSYNC`, so the bytes reach the device before the stream is closed, and a
 * close that fails throws from an append whose credit is already queued. `Currencies.drainPending`
 * used to take that as "nothing was written" and hand the delta back to the shared ledger, leaving
 * the same money in two places, with the next drain giving it a fresh op id so the ledger's
 * exactly-once claim would not catch it.
 *
 * The I/O failure itself is not reproducible from a test, which is exactly why the guard is what gets
 * tested rather than the scenario.
 */
class CurrencyJournalTest {

    private fun credit(opId: String) = PendingCredit(
        opId = opId,
        scope = "server:local",
        currency = "coins",
        player = UUID.randomUUID(),
        amount = BigDecimal("100"),
        starting = BigDecimal.ZERO,
        reason = "offline",
        at = System.currentTimeMillis(),
    )

    @Test
    fun `a queued credit is found by its op id, and an unqueued one is not`() {
        val file = Files.createTempDirectory("cryon-journal").resolve("journal.bin")
        val journal = CurrencyJournal(file, NOPLogger.NOP_LOGGER)

        assertFalse(journal.contains("anything"), "an empty journal holds nothing")

        journal.append(credit("op-1"))

        assertTrue(journal.contains("op-1"), "a durable record is reported as present")
        assertFalse(journal.contains("op-2"), "and a credit never appended is not")
    }

    @Test
    fun `each appended credit is found, so a partial drain cannot hide one`() {
        val file = Files.createTempDirectory("cryon-journal").resolve("journal.bin")
        val journal = CurrencyJournal(file, NOPLogger.NOP_LOGGER)

        val ids = List(5) { "op-$it" }
        ids.forEach { journal.append(credit(it)) }

        for (id in ids) assertTrue(journal.contains(id), "$id survived the append")
        assertTrue(journal.read().size == ids.size, "and nothing was written twice")
    }
}
