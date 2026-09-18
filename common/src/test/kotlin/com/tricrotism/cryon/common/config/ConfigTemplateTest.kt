package com.tricrotism.cryon.common.config

import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The template is generated into the shipped jar at build time, so a malformed one is not caught by
 * anything at runtime: `ConfigMigrator` leaves a file it cannot parse exactly as it is, deliberately,
 * because half-edited is likelier than abandoned. That makes "it still parses, and to the values the
 * keys declared" the one property worth proving here.
 */
class ConfigTemplateTest {

    private fun parse(text: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return Yaml().load<Map<String, Any?>>(text) ?: emptyMap()
    }

    private fun Map<String, Any?>.at(path: String): Any? =
        path.split('.').fold(this as Any?) { node, name -> (node as? Map<*, *>)?.get(name) }

    @Test
    fun `renders every declared type back to its own value`() {
        val keys = listOf(
            ConfigKeys.boolean("production", true),
            ConfigKeys.long("remote.poll-seconds", 300L, 30L..86400L),
            ConfigKeys.int("network.port", 25565, 0..65535),
            ConfigKeys.double("worlds.scale", 1.5),
            ConfigKeys.string("database.type", "postgresql"),
            ConfigKeys.strings("network.restricted-servers", listOf("prison", "skyblock")),
        )

        val parsed = parse(ConfigTemplate.render(keys))

        assertEquals(true, parsed.at("production"))
        assertEquals(300, (parsed.at("remote.poll-seconds") as Number).toInt())
        assertEquals(25565, parsed.at("network.port"))
        assertEquals(1.5, parsed.at("worlds.scale"))
        assertEquals("postgresql", parsed.at("database.type"))
        assertEquals(listOf("prison", "skyblock"), parsed.at("network.restricted-servers"))
    }

    @Test
    fun `nests dotted paths and keeps declaration order`() {
        val keys = listOf(
            ConfigKeys.boolean("network.agones.shutdown-when-empty", false),
            ConfigKeys.long("network.agones.health-seconds", 5L),
            ConfigKeys.string("network.server", "prison"),
        )

        val text = ConfigTemplate.render(keys)
        val parsed = parse(text)

        assertEquals(false, parsed.at("network.agones.shutdown-when-empty"))
        assertEquals(5, (parsed.at("network.agones.health-seconds") as Number).toInt())
        assertEquals("prison", parsed.at("network.server"))
        assertTrue(
            text.indexOf("shutdown-when-empty") < text.indexOf("health-seconds"),
            "declaration order decides layout",
        )
    }

    @Test
    fun `quotes values that would otherwise parse as another type`() {
        val keys = listOf(
            ConfigKeys.string("a.yes", "yes"),
            ConfigKeys.string("a.number", "1.0"),
            ConfigKeys.string("a.empty", ""),
            ConfigKeys.string("a.colon", "host:5432"),
            ConfigKeys.string("a.plain", "postgresql"),
        )

        val parsed = parse(ConfigTemplate.render(keys))

        assertEquals("yes", parsed.at("a.yes"))
        assertEquals("1.0", parsed.at("a.number"))
        assertEquals("", parsed.at("a.empty"))
        assertEquals("host:5432", parsed.at("a.colon"))
        assertEquals("postgresql", parsed.at("a.plain"))
    }

    @Test
    fun `renders docs as comments without disturbing the value`() {
        val keys = listOf(
            ConfigKeys.long(
                "currency.drain-seconds", 30L, 5L..3600L,
                doc = "How often to retry deposits queued while the database was unreachable. " +
                    "Costs nothing when the queue is empty; the check is a file-size read.",
            ),
        )

        val text = ConfigTemplate.render(keys, sections = mapOf("currency" to "The currency ledger."))

        assertTrue("# How often to retry deposits" in text, "key doc is rendered")
        assertTrue("# The currency ledger." in text, "section doc is rendered")
        assertEquals(30, (parse(text).at("currency.drain-seconds") as Number).toInt())
    }

    @Test
    fun `a required key renders empty and says so`() {
        val keys = listOf(ConfigKeys.string("database.password"))

        val text = ConfigTemplate.render(keys)

        assertTrue("Required." in text, "a key with no default says it is required")
        assertEquals("", parse(text).at("database.password"))
    }

    @Test
    fun `a key with a computed fallback ships commented out, not marked required`() {
        val keys = listOf(
            ConfigKeys.boolean("modules.auto-reload", doc = "Defaults to the opposite of production."),
            ConfigKeys.string("database.password"),
        )

        val text = ConfigTemplate.render(keys, commented = mapOf("modules.auto-reload" to true))
        val parsed = parse(text)

        assertTrue("#auto-reload: true" in text, "it ships commented out with a sample to uncomment")
        assertEquals(null, parsed.at("modules.auto-reload"), "commented out means YAML does not set it")
        assertTrue(
            "Required." !in text.substringBefore("database"),
            "a computed fallback is not a required key",
        )
        assertTrue("Required." in text, "one with no fallback at all still says so")
    }

    @Test
    fun `an empty list renders as an empty list rather than null`() {
        val keys = listOf(ConfigKeys.strings("network.restricted-servers"))

        assertEquals(emptyList<String>(), parse(ConfigTemplate.render(keys)).at("network.restricted-servers"))
    }
}
