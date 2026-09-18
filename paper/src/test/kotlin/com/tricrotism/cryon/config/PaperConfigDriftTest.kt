package com.tricrotism.cryon.config

import com.tricrotism.cryon.common.config.ConfigDrift
import com.tricrotism.cryon.common.config.CoreKeys
import com.tricrotism.cryon.common.deploy.DeployKeys
import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shipped `config.yml` and the keys declared in [PaperKeys] each carry every default, and until
 * this nothing made them agree.
 *
 * Both failures are silent at runtime, which is why they belong in a test rather than a boot check. A
 * key with no template entry still works everywhere it is read, so the server behaves correctly and
 * the option simply never reaches an operator. A default that disagrees is worse: the file documents
 * behaviour the server does not have, and the operator who reads it is the one who gets it wrong.
 */
class PaperConfigDriftTest {

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
        // Paper reads all three schemas, so all three are checked. Only checking PaperKeys would leave
        // the core and deploy blocks, which are most of the file, unguarded.
        val complaints = ConfigDrift.check(
            keys = CoreKeys.keys + DeployKeys.keys + PaperKeys.keys,
            template = template(),
            commented = CoreKeys.COMPUTED_FALLBACK,
            exempt = NOT_READ_BY_PAPER + PLACEHOLDERS,
        )

        assertEquals(
            emptyList(),
            complaints,
            "config.yml has drifted from the declared keys:\n" + complaints.joinToString("\n") { "  - $it" },
        )
    }

    private companion object {

        /**
         * `CoreKeys` holds what more than one platform reads, which is not the same as what all three
         * read. Maintenance is enforced where logins arrive, so nothing on Paper reads it, and the
         * MOTD belongs to the proxy and Geyser. Neither block is in Paper's template, correctly.
         */
        val NOT_READ_BY_PAPER = setOf(
            "maintenance.default-message",
            "maintenance.refresh-seconds",
            "motd.enabled",
            "motd.top.left", "motd.top.center", "motd.top.right",
            "motd.bottom.left", "motd.bottom.center", "motd.bottom.right",
        )

        /**
         * The template carries a worked example where the code default is empty, deliberately: an
         * operator needs to see the shape of a refs URL to write their own, and `GitDeploy` refuses
         * to poll while `deploy.enabled` is false anyway.
         */
        val PLACEHOLDERS = setOf("deploy.refs-url", "deploy.archive-url")
    }

    @Test
    fun `the schema actually declared its keys`() {
        assertTrue(PaperKeys.keys.size >= 15, "self-registration produced ${PaperKeys.keys.size} keys")
        assertTrue(
            PaperKeys.keys.none { it.path == "network.mode" },
            "a legacy alias is withdrawn with hidden() and must not be checked against the template",
        )
    }
}
