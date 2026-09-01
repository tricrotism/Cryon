package com.tricrotism.cryon.common.data

import org.slf4j.Logger
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.sql.Timestamp
import java.time.Instant
import java.util.*

/**
 * The local landing ground for rows a checkpoint could not write.
 *
 * [SqlRepository] keeps staged values in memory and writes them in batches, so a database that
 * refuses a checkpoint costs nothing while the process keeps running: the dirty marks stay set and
 * the next checkpoint retries. What it does not survive is the process ending while the database is
 * still down, which is the common shape of the incident since restarting servers is a normal
 * response to an outage. A failed checkpoint therefore writes its rows here first, and a later
 * checkpoint that succeeds writes them through and clears the file.
 *
 * Only the write-behind path spills. `CurrencyService` must not, because its writes are
 * compare-and-set: replaying one after an outage applies a debit against a balance that has since
 * moved. That is the split [Repository] already draws between single-owner state, where a replay is
 * idempotent, and state several nodes write at once.
 *
 * The file is rewritten whole rather than appended to. Rows are keyed by id, so a rewrite is bounded
 * by the dirty set for free where an append log would need compaction. It costs a full write per
 * failed checkpoint, on a path that is already broken. The rewrite goes to a sibling temporary file
 * and is moved over atomically, so a process killed mid-write leaves one spill or the other.
 */
class SpillStore(private val file: Path, private val logger: Logger) {

    /**
     * Every row the last failed checkpoint left behind, or empty when there is nothing to recover.
     *
     * Never throws. A spill that cannot be decoded is moved aside rather than read, because failing
     * a module's startup over an unreadable recovery file would turn a partial loss into a total
     * outage. The file is kept so it can be looked at.
     */
    fun read(): List<SpilledRow> {
        if (!Files.isRegularFile(file)) return emptyList()
        return try {
            DataInputStream(BufferedInputStream(Files.newInputStream(file))).use { input ->
                require(input.readInt() == MAGIC) { "not a Cryon spill file" }
                require(input.readByte() == FORMAT) { "unsupported spill format" }
                val rows = ArrayList<SpilledRow>()
                repeat(input.readInt()) { rows.add(readRow(input)) }
                rows
            }
        } catch (e: Exception) {
            quarantine(e)
            emptyList()
        }
    }

    /**
     * Replace the spill with [rows], or delete it when there are none left to carry.
     *
     * Throws if the write fails. The caller is already handling a failed checkpoint and has to know
     * that the fallback failed too, since at that point the rows exist only in memory.
     */
    fun write(rows: Collection<SpilledRow>) {
        if (rows.isEmpty()) {
            clear()
            return
        }
        Files.createDirectories(file.parent)
        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        DataOutputStream(
            BufferedOutputStream(
                Files.newOutputStream(
                    temp,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.DSYNC,
                )
            )
        ).use { output ->
            output.writeInt(MAGIC)
            output.writeByte(FORMAT.toInt())
            output.writeInt(rows.size)
            for (row in rows) writeRow(output, row)
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * What this spill is holding, or null when there is no file.
     *
     * Reads the header only, so it neither decodes the rows nor quarantines a file it cannot parse.
     * A listing command has to be safe to run on a broken spill: moving the file aside is [read]'s
     * decision, made when something is actually going to recover from it.
     */
    fun summary(): SpillSummary? {
        if (!Files.isRegularFile(file)) return null
        val table = file.fileName.toString().removeSuffix(".bin")
        val rows = runCatching {
            DataInputStream(BufferedInputStream(Files.newInputStream(file))).use { input ->
                if (input.readInt() != MAGIC || input.readByte() != FORMAT) -1 else input.readInt()
            }
        }.getOrDefault(-1)
        return SpillSummary(table, rows, Files.size(file), Files.getLastModifiedTime(file).toInstant())
    }

    /** Drop the spill. Called once its rows have reached the database. */
    fun clear() {
        runCatching { Files.deleteIfExists(file) }
            .onFailure { logger.warn("Could not delete the spill file {}", file, it) }
    }

    private fun quarantine(cause: Exception) {
        val aside = file.resolveSibling(file.fileName.toString() + ".corrupt-" + System.currentTimeMillis())
        val moved = runCatching { Files.move(file, aside) }.isSuccess
        logger.error(
            "Could not read the spill file {}, so rows staged before the last restart are lost. {}",
            file,
            if (moved) "It was kept at $aside" else "It could not be moved aside either",
            cause,
        )
    }

    private fun writeRow(output: DataOutputStream, row: SpilledRow) {
        writeString(output, row.id)
        output.writeLong(row.version)
        output.writeInt(row.values.size)
        for (value in row.values) writeValue(output, value)
    }

    private fun readRow(input: DataInputStream): SpilledRow {
        val id = readString(input)
        val version = input.readLong()
        val values = arrayOfNulls<Any?>(input.readInt())
        for (i in values.indices) values[i] = readValue(input)
        return SpilledRow(id, values, version)
    }

    /**
     * Tagged so a value comes back as the type the driver was given rather than as a string every
     * codec would then have to parse. Only the types JDBC actually binds are covered; a codec
     * emitting anything else is a bug worth surfacing where the row is written, not where it is
     * recovered.
     */
    private fun writeValue(output: DataOutputStream, value: Any?) {
        when (value) {
            null -> output.writeByte(NULL)
            is String -> {
                output.writeByte(STRING); writeString(output, value)
            }

            is Boolean -> {
                output.writeByte(BOOLEAN); output.writeBoolean(value)
            }

            is Byte -> {
                output.writeByte(BYTE); output.writeByte(value.toInt())
            }

            is Short -> {
                output.writeByte(SHORT); output.writeShort(value.toInt())
            }

            is Int -> {
                output.writeByte(INT); output.writeInt(value)
            }

            is Long -> {
                output.writeByte(LONG); output.writeLong(value)
            }

            is Float -> {
                output.writeByte(FLOAT); output.writeFloat(value)
            }

            is Double -> {
                output.writeByte(DOUBLE); output.writeDouble(value)
            }

            is BigDecimal -> {
                output.writeByte(DECIMAL); writeString(output, value.toPlainString())
            }

            is ByteArray -> {
                output.writeByte(BYTES); output.writeInt(value.size); output.write(value)
            }

            is UUID -> {
                output.writeByte(UUID_VALUE)
                output.writeLong(value.mostSignificantBits)
                output.writeLong(value.leastSignificantBits)
            }

            is Timestamp -> {
                output.writeByte(TIMESTAMP); output.writeLong(value.time); output.writeInt(value.nanos)
            }

            is Instant -> {
                output.writeByte(INSTANT); output.writeLong(value.epochSecond); output.writeInt(value.nano)
            }

            else -> throw IllegalArgumentException(
                "A row codec produced ${value.javaClass.name}, which cannot be spilled to disk. " +
                        "Write it as one of the types JDBC binds directly."
            )
        }
    }

    private fun readValue(input: DataInputStream): Any? = when (val tag = input.readByte().toInt()) {
        NULL -> null
        STRING -> readString(input)
        BOOLEAN -> input.readBoolean()
        BYTE -> input.readByte()
        SHORT -> input.readShort()
        INT -> input.readInt()
        LONG -> input.readLong()
        FLOAT -> input.readFloat()
        DOUBLE -> input.readDouble()
        DECIMAL -> BigDecimal(readString(input))
        BYTES -> ByteArray(input.readInt()).also { input.readFully(it) }
        UUID_VALUE -> UUID(input.readLong(), input.readLong())
        TIMESTAMP -> Timestamp(input.readLong()).apply { nanos = input.readInt() }
        INSTANT -> Instant.ofEpochSecond(input.readLong(), input.readInt().toLong())
        else -> throw IllegalStateException("Unknown spill value tag $tag")
    }

    /**
     * Length-prefixed UTF-8 rather than [DataOutputStream.writeUTF], whose modified-UTF8 encoding
     * caps a single string at 64KB. A column holding a serialized blob passes that, and a checkpoint
     * failing to spill because a value is large is the one moment it must not.
     */
    private fun writeString(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val bytes = ByteArray(input.readInt())
        input.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    companion object {

        @Volatile
        private var root: Path? = null

        /**
         * Every table currently holding unwritten rows, newest first.
         *
         * The directory is the source rather than a registry of live repositories, deliberately: the
         * spill that matters most is the one whose module has since been removed, so nothing is left
         * to flush it and nothing would appear in a registry.
         */
        fun pending(logger: Logger): List<SpillSummary> {
            val directory = root ?: return emptyList()
            if (!Files.isDirectory(directory)) return emptyList()
            return runCatching {
                Files.list(directory).use { paths ->
                    paths.filter { it.fileName.toString().endsWith(".bin") }
                        .map { SpillStore(it, logger).summary() }
                        .toList()
                        .filterNotNull()
                        .sortedByDescending { it.modified }
                }
            }.onFailure { logger.warn("Could not list the spill directory {}", directory, it) }
                .getOrDefault(emptyList())
        }

        /**
         * Point every repository's spill file at [directory], or turn spilling off with null.
         *
         * Installed once by each loader at boot, the same shape `Locales.install` uses and for the
         * same reason: a repository is constructed by the feature that owns it, so handing the
         * directory in as a constructor argument would mean every feature repo passing a path it has
         * no opinion about, and any that forgot would silently lose the durability.
         */
        fun install(directory: Path?) {
            root = directory
        }

        /**
         * The spill for [table], or null when nothing is installed (no loader, or spilling off).
         *
         * A table name that cannot be a filename disables the spill for that repository rather than
         * failing it. Losing the fallback is worth a warning; losing the feature is not.
         */
        fun forTable(table: String, logger: Logger): SpillStore? {
            val directory = root ?: return null
            if (table.isEmpty() || !table.all { it.isLetterOrDigit() || it == '_' }) {
                logger.warn("Table '{}' cannot name a spill file, so its writes will not survive a restart", table)
                return null
            }
            return SpillStore(directory.resolve("$table.bin"), logger)
        }

        /** "CRYS", so a file that is not one of ours is rejected rather than misread. */
        private const val MAGIC = 0x43525953
        private const val FORMAT: Byte = 1

        private const val NULL = 0
        private const val STRING = 1
        private const val BOOLEAN = 2
        private const val BYTE = 3
        private const val SHORT = 4
        private const val INT = 5
        private const val LONG = 6
        private const val FLOAT = 7
        private const val DOUBLE = 8
        private const val DECIMAL = 9
        private const val BYTES = 10
        private const val UUID_VALUE = 11
        private const val TIMESTAMP = 12
        private const val INSTANT = 13
    }
}
