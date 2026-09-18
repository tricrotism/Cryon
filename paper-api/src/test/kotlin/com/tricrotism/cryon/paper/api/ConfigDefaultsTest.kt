package com.tricrotism.cryon.paper.api

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * This is a class
 */
class ConfigDefaultsTest {

    @Test
    fun `a key the release added is written in`() {
        val folded = ConfigDefaults.fold(
            yaml("a: 1"),
            yaml("a: 1\nb: 2"),
            offered = setOf("a"),
        )

        assertContentEquals(listOf("b"), folded.added)
        assertEquals(2, folded.result.getInt("b"))
    }

    @Test
    fun `a key the operator deleted stays deleted`() {
        val folded = ConfigDefaults.fold(
            yaml("a: 1\nc: 3"),
            yaml("a: 1\nb: 2\nc: 3"),
            offered = setOf("a", "b", "c"),
        )

        assertTrue(folded.added.isEmpty(), "a deleted key was offered again: ${folded.added}")
        assertFalse(folded.result.contains("b"))
    }

    @Test
    fun `a deleted key and a new key are told apart in the same pass`() {
        val folded = ConfigDefaults.fold(
            yaml("a: 1"),
            yaml("a: 1\nb: 2\nnewly: 9"),
            offered = setOf("a", "b"),
        )

        assertContentEquals(listOf("newly"), folded.added)
        assertFalse(folded.result.contains("b"), "the deleted key came back")
        assertEquals(9, folded.result.getInt("newly"))
    }

    /**
     * The upgrade boot. With no record nothing can be called deleted, so the old behaviour stands and
     * the operator deletes once more for it to stick.
     */
    @Test
    fun `without a record every missing default is offered once more`() {
        val folded = ConfigDefaults.fold(
            yaml("a: 1"),
            yaml("a: 1\nb: 2"),
            offered = null,
        )

        assertContentEquals(listOf("b"), folded.added)
        assertContentEquals(listOf("a", "b"), folded.offered.sorted())
    }

    @Test
    fun `deleting a whole section does not leave the heading behind`() {
        val folded = ConfigDefaults.fold(
            yaml("words:\n  toggle:\n    profanity:\n      list:\n      - damn"),
            yaml(
                """
                words:
                  toggle:
                    profanity:
                      list:
                      - damn
                    scams:
                      display: Scam words
                      list:
                      - Spawner
                added: true
                """.trimIndent()
            ),
            offered = setOf(
                "words.toggle.profanity.list",
                "words.toggle.scams.display",
                "words.toggle.scams.list",
            ),
        )

        assertContentEquals(listOf("added"), folded.added)
        assertFalse(folded.result.contains("words.toggle.scams.display"))
        assertFalse(folded.result.contains("words.toggle.scams"), "the emptied section was left behind")
        assertContentEquals(listOf("damn"), folded.result.getStringList("words.toggle.profanity.list"))
    }

    @Test
    fun `a customised value and a key the default never had both survive`() {
        val folded = ConfigDefaults.fold(
            yaml("a: mine\nours: kept"),
            yaml("a: theirs\nb: 2"),
            offered = setOf("a"),
        )

        assertEquals("mine", folded.result.getString("a"))
        assertEquals("kept", folded.result.getString("ours"))
        assertEquals(2, folded.result.getInt("b"))
    }

    @Test
    fun `a config already current is handed back untouched and still recorded`() {
        val existing = yaml("a: 1\nb: 2")
        val folded = ConfigDefaults.fold(existing, yaml("a: 1\nb: 2"), offered = setOf("a", "b"))

        assertTrue(folded.added.isEmpty())
        assertEquals(existing, folded.result)
        assertContentEquals(listOf("a", "b"), folded.offered.sorted())
    }

    private fun yaml(text: String): YamlConfiguration = YamlConfiguration().apply { loadFromString(text) }
}
