package com.tricrotism.cryon.geyser.config

import com.tricrotism.cryon.common.config.ConfigDrift
import com.tricrotism.cryon.common.config.CoreKeys
import com.tricrotism.cryon.common.deploy.DeployKeys
import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Geyser's twin of `PaperConfigDriftTest`. It declares no keys of its own: it reads `CoreKeys` and the
 * deploy block, and `motd.width` is the proxy's alone because a Bedrock ping is two plain strings with
 * no font metrics to align against.
 */
class GeyserConfigDriftTest {

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
            keys = CoreKeys.keys + DeployKeys.keys,
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

        /** The MOTD ships switched on with branded example segments; see the Velocity twin. */
        val SHIPPED_EXAMPLE = setOf(
            "motd.enabled",
            "motd.top.left", "motd.top.center", "motd.top.right",
            "motd.bottom.left", "motd.bottom.center", "motd.bottom.right",
        )
    }
}
