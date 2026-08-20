package com.tricrotism.cryon.geyser.motd

import com.tricrotism.cryon.common.text.Mini
import com.tricrotism.cryon.geyser.api.toGeyserString
import com.tricrotism.cryon.geyser.config.GeyserConfig
import java.nio.file.Path

/**
 * The Bedrock server-list MOTD, read from the same `motd.*` block the proxy uses and reloadable at
 * runtime with `/motd reload`.
 *
 * **This is not a port of the proxy's `Motd`.** That class anchors three MiniMessage segments
 * left/center/right by measuring their pixel width with `FontWidth` and padding with spaces, because
 * the Java server list renders one wide free-form component. A Bedrock ping is two short plain
 * strings with no font metrics and no layout to align against, so the padding math would only insert
 * runs of spaces nobody asked for. What is shared is the configuration and the MiniMessage source;
 * what is dropped is the alignment. Each line's non-empty segments are simply joined with a space
 * and rendered to the legacy string Bedrock reads.
 *
 * The second line is only shown by some Bedrock clients (it is the "world name" slot in the ping),
 * so keep the identity on the first.
 */
class BedrockMotd(private val configFile: Path) {

    @Volatile
    private var lines: Pair<String, String>? = null

    fun reload() {
        val config = GeyserConfig.load(configFile)
        lines = if (!config.boolean("motd.enabled", false)) null else {
            line(config, "top") to line(config, "bottom")
        }
    }

    /** The primary and secondary MOTD, or `null` when disabled (the caller leaves the ping alone). */
    fun render(): Pair<String, String>? = lines

    private fun line(config: GeyserConfig, section: String): String {
        val segments = listOf("left", "center", "right")
            .map { config.string("motd.$section.$it", "") }
            .filter { it.isNotEmpty() }
        if (segments.isEmpty()) return ""
        return Mini.format(segments.joinToString(" ")).toGeyserString()
    }
}
