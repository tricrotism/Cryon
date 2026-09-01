package com.tricrotism.cryon.common.currency

import org.slf4j.Logger
import java.io.*
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.*

/**
 * Deposits that could not reach the database, held on local disk until they can.
 *
 * Only credits queue here. A deposit reads nothing to decide and commutes, so it can be replayed; a
 * withdraw needs an authoritative read, so deferring it would mean authorising a spend nobody can
 * verify. Debits are answered [WithdrawResult.UNAVAILABLE] instead.
 *
 * Records are appended rather than rewritten whole, because deposits do not collapse to one row per
 * player the way a repository's dirty set does. Each carries its own length, so a process killed
 * part-way through an append leaves a short final record that the read stops at, keeping the rest.
 * Every append is flushed to the device before it returns: the point of the record is to survive the
 * process that wrote it.
 */
class CurrencyJournal(private val file: Path, private val logger: Logger) {

    private val claimed: Path = file.resolveSibling(file.fileName.toString() + ".draining")

    val hasPending: Boolean
        get() = listOf(file, claimed).any { path ->
            Files.isRegularFile(path) && runCatching { Files.size(path) > 0 }.getOrDefault(false)
        }

    /**
     * Add [credit] to the queue, durably.
     *
     * @throws java.io.IOException when it cannot be written, which the caller must surface: at that
     *   point the deposit exists nowhere at all
     */
    fun append(credit: PendingCredit) {
        Files.createDirectories(file.parent)
        val payload = encode(credit)

        DataOutputStream(
            Files.newOutputStream(
                file,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE,
                StandardOpenOption.DSYNC,
            )
        ).use { output ->
            output.writeInt(payload.size)
            output.write(payload)
        }
    }

    /**
     * @return everything queued, oldest first, stopping at the first incomplete record. Never throws:
     *   a journal that cannot be read at all is moved aside, because failing startup over a recovery
     *   file turns a partial loss into a total outage
     */
    fun read(): List<PendingCredit> = readFrom(file)

    private fun readFrom(source: Path): List<PendingCredit> {
        if (!Files.isRegularFile(source)) return emptyList()

        val credits = ArrayList<PendingCredit>()

        try {
            DataInputStream(BufferedInputStream(Files.newInputStream(source))).use { input ->
                while (true) {
                    val length = try {
                        input.readInt()
                    } catch (e: EOFException) {
                        break
                    }

                    if (length <= 0 || length > MAX_RECORD_BYTES) break

                    val payload = ByteArray(length)
                    try {
                        input.readFully(payload)
                    } catch (e: EOFException) {
                        logger.warn(
                            "The currency journal ends mid-record; {} credit(s) before it are intact",
                            credits.size
                        )
                        break
                    }

                    credits += decode(payload)
                }
            }
        } catch (e: Exception) {
            quarantine(e)
            return emptyList()
        }

        return credits
    }

    /**
     * Claim everything queued, leaving the live journal empty for deposits still arriving.
     *
     * The live file is renamed aside rather than read and later truncated. Reading then truncating
     * destroys every credit appended while the drain was working, and during an outage deposits
     * append continuously, so that is a loss on the exact path built to prevent one. Renaming also
     * survives a crash: the claimed file is still on disk and the next [takeAll] picks it up before
     * looking at the live one.
     *
     * Pair with [finish], which puts back whatever the database refused.
     *
     * @return the claimed credits, oldest first
     */
    @Synchronized
    fun takeAll(): List<PendingCredit> {
        if (!Files.isRegularFile(claimed)) {
            if (!Files.isRegularFile(file)) return emptyList()
            runCatching { Files.move(file, claimed, StandardCopyOption.ATOMIC_MOVE) }
                .onFailure {
                    logger.warn("Could not claim the currency journal for draining", it)
                    return emptyList()
                }
        }

        return readFrom(claimed)
    }

    /**
     * Finish a [takeAll]: put [unapplied] back at the head of the live journal and drop the claim.
     *
     * Appended rather than written over, so credits that arrived during the drain keep theirs.
     */
    @Synchronized
    fun finish(unapplied: List<PendingCredit>) {
        for (credit in unapplied) {
            runCatching { append(credit) }
                .onFailure { logger.error("Could not return a queued {} deposit to the journal", credit.currency, it) }
        }

        runCatching { Files.deleteIfExists(claimed) }
            .onFailure { logger.error("Could not drop the claimed currency journal {}", claimed, it) }
    }

    fun clear() {
        runCatching { Files.deleteIfExists(file) }
            .onFailure { logger.warn("Could not delete the currency journal {}", file, it) }
    }

    private fun quarantine(cause: Exception) {
        val aside = file.resolveSibling(file.fileName.toString() + ".corrupt-" + System.currentTimeMillis())
        val moved = runCatching { Files.move(file, aside) }.isSuccess

        logger.error(
            "Could not read the currency journal {}, so queued deposits are lost. {}",
            file,
            if (moved) "It was kept at $aside" else "It could not be moved aside either",
            cause,
        )
    }

    private fun encode(credit: PendingCredit): ByteArray {
        val out = ByteArrayOutputStream()

        DataOutputStream(out).use { data ->
            writeText(data, credit.opId)
            writeText(data, credit.scope)
            writeText(data, credit.currency)
            data.writeLong(credit.player.mostSignificantBits)
            data.writeLong(credit.player.leastSignificantBits)
            writeText(data, credit.amount.toPlainString())
            writeText(data, credit.starting.toPlainString())
            writeText(data, credit.reason)
            data.writeLong(credit.at)
        }

        return out.toByteArray()
    }

    private fun decode(payload: ByteArray): PendingCredit =
        DataInputStream(payload.inputStream()).use { data ->
            PendingCredit(
                opId = readText(data),
                scope = readText(data),
                currency = readText(data),
                player = UUID(data.readLong(), data.readLong()),
                amount = BigDecimal(readText(data)),
                starting = BigDecimal(readText(data)),
                reason = readText(data),
                at = data.readLong(),
            )
        }

    // length-prefixed UTF-8, because writeUTF caps one string at 64KB
    private fun writeText(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readText(input: DataInputStream): String {
        val bytes = ByteArray(input.readInt())
        input.readFully(bytes)

        return String(bytes, StandardCharsets.UTF_8)
    }

    private companion object {
        // rejects a garbage length before it becomes a huge allocation
        const val MAX_RECORD_BYTES = 1 shl 20
    }
}
