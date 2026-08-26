package com.tricrotism.cryon.common.config

import org.slf4j.Logger
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

/**
 * Brings an operator's `config.yml` back up to date with the one shipped in the jar, so a key added
 * this release appears in their file instead of only in the source.
 *
 * Every platform used to write the bundled template only when the file was missing, which meant a
 * new key was invisible forever to anyone who already had a config. Behaviour was still correct, the
 * read sites carry defaults, but an option nobody can see is an option nobody can turn on.
 *
 * **The merge is template-driven and additive.** The output is the bundled template's text, with the
 * operator's value substituted wherever they already have one, plus anything of theirs the template
 * has no place for. Three consequences worth knowing:
 *
 * - New keys arrive with the comments explaining them, so improving that guidance in source is how
 *   it reaches deployments.
 * - Nothing is ever removed. A key dropped from the template survives in their file as a line
 *   nothing reads, which is a smaller problem than deleting a value an operator meant to keep, and
 *   is the reason this does not implement the usual drop-what-the-template-lost pass. Sections keyed
 *   by operator-chosen names (`remote.repositories`) and lists they curate (`remote.artifacts`) only
 *   work at all because of that rule.
 * - Comments the operator wrote next to a key they had already set are dropped, since their *value*
 *   is what is carried onto the template's line. Comments they wrote around keys the template does
 *   not have survive with those keys.
 *
 * A file that will not parse is left exactly as it is: it is far likelier to be half-edited than
 * abandoned, and rewriting it would take the operator's work with it.
 *
 * **Renamed keys need declaring**, or this quietly undoes them. Writing a template key an operator
 * has not set is normally harmless, since the value is the default they were already getting. It is
 * not harmless for a key that has been renamed and whose old name is still honoured: the new name
 * arrives holding the shipped default, the reader finds it set, and the value the operator actually
 * declared under the old name stops being consulted. Passing `aliases` makes the new key inherit
 * from the old one instead, which is what completing a rename means.
 */
object ConfigMigrator {

    /**
     * Write [template] to [target], carrying over every value already there.
     *
     * Returns true when the file changed, so the caller can say so once at boot rather than on every
     * start. Creating the file for the first time counts as a change.
     */
    fun migrate(
        template: String,
        target: Path,
        logger: Logger,
        aliases: Map<String, String> = emptyMap(),
    ): Boolean {
        if (!Files.exists(target)) {
            Files.createDirectories(target.parent)
            Files.writeString(target, template)
            return true
        }

        val existing = try {
            Files.readString(target)
        } catch (e: Exception) {
            logger.warn("Could not read {}, leaving it alone", target, e)
            return false
        }

        val user = try {
            @Suppress("UNCHECKED_CAST")
            Yaml().load<Any?>(existing) as? Map<String, Any?> ?: emptyMap()
        } catch (e: Exception) {
            logger.warn("{} is not valid YAML, so it was left untouched. Fix it to pick up new keys", target, e)
            return false
        }

        val merged = merge(template, user, aliases)
        if (merged == existing) return false
        Files.writeString(target, merged)
        return true
    }

    internal fun merge(template: String, user: Map<*, *>, aliases: Map<String, String> = emptyMap()): String {
        val out = StringBuilder()
        mergeBlock(Cursor(template.lines()), 0, "", user, Aliases(aliases, user), out)
        return out.toString()
    }

    /** Resolves a renamed key's former value out of the operator's file, by full dotted path. */
    private class Aliases(private val renames: Map<String, String>, private val root: Map<*, *>) {
        fun former(path: String): Any? {
            val from = renames[path] ?: return null
            var node: Any? = root
            for (part in from.split('.')) node = (node as? Map<*, *>)?.get(part) ?: return null
            return node.takeUnless { it is String && it.isBlank() }
        }
    }

    private class Cursor(val lines: List<String>) {
        var index = 0
        fun peek(): String? = lines.getOrNull(index)
    }

    /**
     * Emit the template block whose keys sit at [indent], substituting from [user].
     *
     * Returns with the cursor on the first line belonging to an enclosing block, so a parent can
     * append its own leftovers in the right place rather than after the child's.
     */
    private fun mergeBlock(
        cursor: Cursor,
        indent: Int,
        path: String,
        user: Map<*, *>,
        aliases: Aliases,
        out: StringBuilder,
    ) {
        val seen = HashSet<String>()

        while (true) {
            val line = cursor.peek() ?: break
            val trimmed = line.trim()

            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                // Blank lines and comments belong to whichever key follows them. Handing them to
                // this block when that key is the parent's would append our leftovers below a
                // comment introducing something else.
                if (indentOfNextContent(cursor) < indent) break
                out.append(line).append('\n')
                cursor.index++
                continue
            }

            val at = indentOf(line)
            if (at < indent) break
            val key = keyOf(trimmed)
            if (at > indent || key == null) {
                out.append(line).append('\n')
                cursor.index++
                continue
            }

            seen += key
            val here = if (path.isEmpty()) key else "$path.$key"
            val rest = trimmed.substringAfter(':')
            val set = user.containsKey(key)
            val value = if (set) user[key] else aliases.former(here)
            val hasValue = set || value != null
            cursor.index++

            when {
                !hasValue -> emitTemplateSubtree(cursor, indent, line, out)

                rest.isBlank() && value is Map<*, *> -> {
                    out.append(line).append('\n')
                    val childIndent = indentOfNextContent(cursor).takeIf { it > indent } ?: (indent + STEP)
                    mergeBlock(cursor, childIndent, here, value, aliases, out)
                }

                else -> {
                    emit(key, value, indent, out)
                    skipSubtree(cursor, indent)
                }
            }
        }

        for ((key, value) in user) {
            val name = key?.toString() ?: continue
            if (name in seen) continue
            emit(name, value, indent, out)
        }
    }

    /** Copy a template key and everything nested under it, for a key the operator has not set. */
    private fun emitTemplateSubtree(cursor: Cursor, indent: Int, header: String, out: StringBuilder) {
        out.append(header).append('\n')
        while (true) {
            val line = cursor.peek() ?: return
            if (line.isBlank()) {
                if (indentOfNextContent(cursor) <= indent) return
                out.append(line).append('\n')
                cursor.index++
                continue
            }
            if (indentOf(line) <= indent) return
            out.append(line).append('\n')
            cursor.index++
        }
    }

    /** Drop the template's nested lines, the operator's value having replaced the whole subtree. */
    private fun skipSubtree(cursor: Cursor, indent: Int) {
        while (true) {
            val line = cursor.peek() ?: return
            if (line.isBlank()) {
                if (indentOfNextContent(cursor) <= indent) return
                cursor.index++
                continue
            }
            if (indentOf(line) <= indent) return
            cursor.index++
        }
    }

    private fun emit(key: String, value: Any?, indent: Int, out: StringBuilder) {
        val inline = inlineScalar(value)
        val pad = " ".repeat(indent)
        if (inline != null) {
            out.append(pad).append(key).append(": ").append(inline).append('\n')
            return
        }
        BLOCK.dump(mapOf(key to value)).trimEnd().lineSequence().forEach {
            out.append(pad).append(it).append('\n')
        }
    }

    /** The value as it would sit after `key: `, or null when it needs lines of its own. */
    private fun inlineScalar(value: Any?): String? {
        if (value is Map<*, *> && value.isNotEmpty()) return null
        if (value is Collection<*> && value.isNotEmpty()) return null
        val dumped = FLOW.dump(value).trimEnd()
        return dumped.takeIf { '\n' !in it }
    }

    private fun keyOf(trimmed: String): String? {
        if (trimmed.startsWith("-")) return null
        return trimmed.substringBefore(':', missingDelimiterValue = "").takeIf { it.isNotEmpty() }
    }

    private fun indentOf(line: String): Int = line.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: 0

    /** The indent of the next line carrying anything, so blank runs and comments can be attributed. */
    private fun indentOfNextContent(cursor: Cursor): Int {
        var i = cursor.index
        while (i < cursor.lines.size) {
            val line = cursor.lines[i]
            if (line.isNotBlank() && !line.trim().startsWith("#")) return indentOf(line)
            i++
        }
        return 0
    }

    private const val STEP = 4

    private val FLOW = Yaml(DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.FLOW })

    private val BLOCK = Yaml(DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        indent = STEP
    })
}
