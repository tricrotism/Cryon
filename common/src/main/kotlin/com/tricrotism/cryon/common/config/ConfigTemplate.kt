package com.tricrotism.cryon.common.config

/**
 * Renders declared [ConfigKey]s into the `config.yml` a platform ships.
 *
 * The template used to be written by hand beside the keys, which meant every default existed twice
 * and the prose explaining one could describe behaviour the code had stopped having. Nothing failed
 * when they disagreed, because the read sites carry the defaults and the file is only what a fresh
 * install starts from. Generating it removes the second copy: a key's default and its explanation are
 * the same declaration, and `ConfigMigrator` keeps doing exactly what it did, since it merges against
 * the template's text and does not care who wrote it.
 *
 * Order is the order keys are declared. A tree built from dotted paths cannot recover the grouping an
 * operator reads by, so the author's sequence is the layout, and a key inserted between two others
 * appears between them.
 */
object ConfigTemplate {

    private const val INDENT = "    "

    /** Where a comment has to wrap, counting its indent and the `# `. */
    private const val WIDTH = 110

    /**
     * @param keys every key this platform reads, in the order they should appear
     * @param sections prose for a whole block, keyed by its path (`"currency"`), emitted above it
     * @param header prose for the top of the file, above everything
     * @param commented paths to render commented out, mapped to the sample value to show
     */
    fun render(
        keys: List<ConfigKey<*>>,
        sections: Map<String, String> = emptyMap(),
        header: String = "",
        commented: Map<String, Any> = emptyMap(),
    ): String {
        val root = Node()

        for (key in keys) root.put(key.path.split('.'), key)

        return buildString {
            if (header.isNotBlank()) {
                appendComment(header, "")
                append('\n')
            }
            appendNode(root, "", "", sections, commented)
        }.trimEnd() + "\n"
    }

    private fun StringBuilder.appendNode(
        node: Node,
        indent: String,
        path: String,
        sections: Map<String, String>,
        commented: Map<String, Any>,
    ) {
        var first = true

        for ((name, child) in node.children) {
            val childPath = if (path.isEmpty()) name else "$path.$name"
            val leaf = child.key

            // A blank line between blocks, never before the first entry of one, so nesting does not
            // open with an empty line.
            if (!first) append('\n')
            first = false

            if (leaf != null) {
                val sample = commented[childPath]
                if (leaf.doc.isNotBlank()) appendComment(leaf.doc, indent)

                // A key with no constant default is one of two very different things, and saying the
                // wrong one is worse than saying nothing. Listed in `commented` it has a fallback the
                // code computes (auto-reload follows production), so it ships commented out with a
                // sample to uncomment. Otherwise nothing can supply it and the boot stops.
                when {
                    sample != null -> append(indent).append('#').append(scalar(name)).append(": ").append(scalar(sample))
                    else -> {
                        if (leaf.required) {
                            appendComment("Required. There is no default; an unset value stops the boot.", indent)
                        }
                        append(indent).append(scalar(name)).append(':')
                        appendValue(leaf.default, indent)
                    }
                }
                append('\n')
            } else {
                sections[childPath]?.let { appendComment(it, indent) }
                append(indent).append(scalar(name)).append(":\n")
                appendNode(child, indent + INDENT, childPath, sections, commented)
            }
        }
    }

    private fun StringBuilder.appendValue(value: Any?, indent: String) {
        when (value) {
            null -> append(' ').append("\"\"")
            is List<*> -> {
                if (value.isEmpty()) {
                    append(" []")
                } else {
                    append('\n')
                    for (entry in value) append(indent).append(INDENT).append("- ").append(scalar(entry)).append('\n')
                    setLength(length - 1)
                }
            }

            else -> append(' ').append(scalar(value))
        }
    }

    /**
     * YAML is forgiving enough that most scalars need no quoting, but one that parses as something
     * else does. `yes`, `1.0` and an empty string all come back as the wrong type unquoted, and a
     * colon or a leading indicator character changes the parse outright.
     *
     * **Key names go through this too, not just values.** A key literally named `on` or `yes` is read
     * by YAML 1.1 as a boolean key, so the entry is there under a key no lookup by name will ever
     * find, and nothing reports it.
     */
    private fun scalar(value: Any?): String = when (value) {
        null -> "\"\""
        is Boolean, is Number -> value.toString()
        else -> {
            val text = value.toString()
            val ambiguous = text.isEmpty() ||
                text != text.trim() ||
                text.toDoubleOrNull() != null ||
                text.lowercase() in RESERVED ||
                text.first() in "*&!%@`|>{}[]#-?," ||
                ":" in text
            if (ambiguous) '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"' else text
        }
    }

    private fun StringBuilder.appendComment(text: String, indent: String) {
        val room = (WIDTH - indent.length - 2).coerceAtLeast(20)

        for (paragraph in text.trimIndent().trim().lines()) {
            if (paragraph.isBlank()) {
                append(indent).append("#\n")
                continue
            }
            var line = StringBuilder()
            for (word in paragraph.split(' ')) {
                if (line.isNotEmpty() && line.length + 1 + word.length > room) {
                    append(indent).append("# ").append(line).append('\n')
                    line = StringBuilder()
                }
                if (line.isNotEmpty()) line.append(' ')
                line.append(word)
            }
            if (line.isNotEmpty()) append(indent).append("# ").append(line).append('\n')
        }
    }

    private val RESERVED = setOf("true", "false", "yes", "no", "on", "off", "null", "~")

    private class Node {
        val children = LinkedHashMap<String, Node>()
        var key: ConfigKey<*>? = null

        fun put(path: List<String>, key: ConfigKey<*>) {
            val child = children.getOrPut(path.first()) { Node() }
            if (path.size == 1) child.key = key else child.put(path.drop(1), key)
        }
    }
}
