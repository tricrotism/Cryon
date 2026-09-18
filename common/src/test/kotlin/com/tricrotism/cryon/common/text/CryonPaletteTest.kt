package com.tricrotism.cryon.common.text

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The palette is two structures derived from one map, and both failures are silent.
 *
 * A tag missing from [CryonPalette.RESOLVER] does not throw: MiniMessage leaves `<scarlet>` in the
 * output as literal text, which reaches a player looking like a typo in the copy rather than a bug in
 * the palette. A colour missing from the inverse does not throw either: [CryonPalette.tag] answers
 * null and whatever was counting colours simply stops seeing that one.
 *
 * So the properties worth pinning are that every declared name parses, that parsing a name and naming
 * the parsed colour is a round trip, and that the aliases collapse the way the ordering promises.
 */
class CryonPaletteTest {

    private val mini = MiniMessage.builder().tags(CryonPalette.RESOLVER).build()

    @Test
    fun `every declared tag resolves to its colour`() {
        CryonPalette.TAGS.forEach { (name, expected) ->
            val rendered = mini.deserialize("<$name>x")
            assertEquals(
                expected,
                rendered.color(),
                "<$name> did not resolve to its palette colour - it is missing from RESOLVER",
            )
        }
    }

    @Test
    fun `naming a parsed colour round trips to a tag of the same colour`() {
        CryonPalette.TAGS.forEach { (name, color) ->
            val named = assertNotNull(CryonPalette.tag(color), "no name for the colour behind <$name>")
            assertEquals(
                color,
                CryonPalette.TAGS[named],
                "<$name> named itself <$named>, which is a different colour",
            )
        }
    }

    @Test
    fun `aliases collapse onto the first name declared`() {
        assertEquals("error", CryonPalette.tag(CryonPalette.RED))
        assertEquals("success", CryonPalette.tag(CryonPalette.GREEN))
        assertEquals("info", CryonPalette.tag(CryonPalette.BLUE))
        assertEquals("highlight_blue", CryonPalette.tag(CryonPalette.HIGHLIGHT))
        assertEquals("white", CryonPalette.tag(CryonPalette.OFF_WHITE))
    }

    @Test
    fun `the only colours sharing a hex are the five declared aliases`() {
        val shared = CryonPalette.TAGS.entries
            .groupBy({ it.value.value() }, { it.key })
            .values
            .filter { it.size > 1 }
            .map { it.toSet() }
            .toSet()

        assertEquals(
            setOf(
                setOf("error", "red"),
                setOf("success", "green"),
                setOf("info", "blue"),
                setOf("highlight_blue", "highlight"),
                setOf("white", "off_white"),
            ),
            shared,
            "two palette names share a hex without being one of the known aliases. A new colour that " +
                "duplicates an existing one does not fail anywhere: it silently becomes an alias, and " +
                "everything counting colours folds the two names into whichever was declared first",
        )
    }

    @Test
    fun `a colour outside the palette has no name`() {
        assertNull(CryonPalette.tag(TextColor.color(1, 2, 3)))
    }

    @Test
    fun `tags walks children and reports each palette colour once`() {
        val message = mini.deserialize("<error>bad <scarlet>very bad <error>still bad")
        assertEquals(setOf("error", "scarlet"), CryonPalette.tags(message))
    }

    @Test
    fun `tags ignores colours that are not ours`() {
        val message = Component.text("x").color(TextColor.color(1, 2, 3))
            .append(Component.text("y").color(CryonPalette.EMERALD))
        assertEquals(setOf("emerald"), CryonPalette.tags(message))
    }

    @Test
    fun `an uncoloured message reports nothing`() {
        assertTrue(CryonPalette.tags(Component.text("plain")).isEmpty())
    }
}
