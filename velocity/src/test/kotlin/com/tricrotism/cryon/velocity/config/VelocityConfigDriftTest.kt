package com.tricrotism.cryon.velocity.config

import com.tricrotism.cryon.common.config.ConfigDrift
import com.tricrotism.cryon.common.config.CoreKeys
import com.tricrotism.cryon.common.deploy.DeployKeys
import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The proxy's twin of `PaperConfigDriftTest`. Same failure it guards against: a key declared without a
 * template entry still works everywhere it is read, so the option simply never reaches an operator.
 */
class VelocityConfigDriftTest {

    private fun template(): Map<String, Any?> {
        val text = javaClass.classLoader.getResourceAsStream("config.yml")
            ?.bufferedReader()
            ?.readText()
            ?: error("config.yml is not on the test classpath")

        @Suppress("UNCHECKED_CAST")
        return Yaml().load<Map<String, Any?>>(text) ?: emptyMap()
    }

    @Test
    fun `every declared key appears in the shipped template with the same default`() {
        val complaints = ConfigDrift.check(
            keys = CoreKeys.keys + DeployKeys.keys + VelocityKeys.keys,
            template = template(),
            commented = CoreKeys.COMPUTED_FALLBACK,
            exempt = PLACEHOLDERS + SHIPPED_EXAMPLE,
        )

        assertEquals(
            emptyList(),
            complaints,
            "config.yml has drifted from the declared keys:\n" + complaints.joinToString("\n") { "  - $it" },
        )
    }

    private companion object {

        /** The template carries a worked example where the code default is empty; see the Paper twin. */
        val PLACEHOLDERS = setOf("deploy.refs-url", "deploy.archive-url")

        /**
         * The MOTD ships switched on with branded example segments, where the code default is off and
         * empty. Deliberate: an empty server list entry teaches an operator nothing about what the
         * three segments do, and the code default has to stay empty for a proxy that never sets one.
         */
        val SHIPPED_EXAMPLE = setOf(
            "motd.enabled",
            "motd.top.left", "motd.top.center", "motd.top.right",
            "motd.bottom.left", "motd.bottom.center", "motd.bottom.right",
        )
    }
}
