package com.tricrotism.cryon.paper.api

import org.bukkit.configuration.file.YamlConfiguration

/**
 * Folding a module's bundled defaults into the copy on disk, without touching the file system.
 *
 * Split out of [PaperModule] because this is the part that is easy to get wrong and silent when it
 * is. A key missing from the operator's file is either one they deleted or one this release added,
 * and only the record of what has already been offered tells the two apart. Getting that backwards
 * means a shipped default nobody can refuse.
 */
internal object ConfigDefaults {

    /**
     * @param offered every default already written into this file, or null before any record existed
     */
    fun fold(existing: YamlConfiguration, bundled: YamlConfiguration, offered: Set<String>?): Folded {
        val leaves = leaves(bundled)
        val missing = leaves.filter { !existing.contains(it) }

        // Nothing recorded means nothing can be called deleted, so every missing default is offered
        // once more and a deletion sticks from the next boot on.
        val removed = if (offered == null) emptySet() else missing.filterTo(HashSet()) { it in offered }
        val added = missing.filterNot { it in removed }

        if (added.isEmpty()) return Folded(existing, emptyList(), leaves)

        for (path in leaves) {
            if (path in removed) bundled.set(path, null)
            else if (existing.contains(path)) bundled.set(path, existing.get(path))
        }

        for (path in existing.getKeys(true)) {
            if (existing.isConfigurationSection(path)) continue
            if (!bundled.contains(path)) bundled.set(path, existing.get(path))
        }

        prune(bundled, existing)

        return Folded(bundled, added, leaves)
    }

    fun leaves(config: YamlConfiguration): List<String> =
        config.getKeys(true).filter { !config.isConfigurationSection(it) }

    /**
     * Drop the sections the removals emptied, so a category the operator deleted does not return as a
     * bare heading with nothing under it, which reads exactly like the bug this exists to fix.
     *
     * Deepest first, so emptying a child can empty its parent in the same pass. A section [existing]
     * still carries is left alone: somebody who wrote an empty section meant it.
     */
    private fun prune(bundled: YamlConfiguration, existing: YamlConfiguration) {
        val sections = bundled.getKeys(true)
            .filter { bundled.isConfigurationSection(it) }
            .sortedByDescending { path -> path.count { it == '.' } }

        for (path in sections) {
            if (existing.contains(path)) continue
            if (bundled.getConfigurationSection(path)?.getKeys(false).isNullOrEmpty()) bundled.set(path, null)
        }
    }

    /**
     * [result] is what the module runs on, [added] the defaults written in, [offered] what to record.
     * An empty [added] means nothing needs writing to disk.
     */
    data class Folded(val result: YamlConfiguration, val added: List<String>, val offered: List<String>)
}
