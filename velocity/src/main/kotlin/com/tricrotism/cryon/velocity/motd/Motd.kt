package com.tricrotism.cryon.velocity.motd

import com.tricrotism.cryon.common.config.Config
import com.tricrotism.cryon.common.config.CoreKeys
import com.tricrotism.cryon.common.config.YamlConfigSource
import com.tricrotism.cryon.common.text.FontWidth
import com.tricrotism.cryon.common.text.Mini
import com.tricrotism.cryon.velocity.config.VelocityKeys
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.nio.file.Path
import kotlin.math.roundToInt

/**
 * The server-list MOTD, composed of a top and bottom line, each built from three MiniMessage segments
 * anchored **left**, **center**, and **right**. Segments are positioned by measuring their pixel width
 * ([FontWidth]) and padding with spaces, so the center segment sits centered and the right segment
 * ends at [MotdSettings.width]. Read from the proxy `config.yml` and reloadable at runtime. [reload]
 * re-parses the file, so `/motd reload` picks up edits without a proxy restart.
 */
class Motd(private val configFile: Path) {

    @Volatile
    private var settings = MotdSettings.EMPTY

    @Volatile
    private var rendered: Component? = null

    fun reload() {
        val s = MotdSettings.from(Config(YamlConfigSource.load(configFile)))
        settings = s
        rendered = if (!s.enabled) null else {
            val top = renderLine(s.topLeft, s.topCenter, s.topRight, s.width)
            val bottom = renderLine(s.bottomLeft, s.bottomCenter, s.bottomRight, s.width)
            Component.text().append(top).append(Component.newline()).append(bottom).build()
        }
    }

    fun isEnabled(): Boolean = settings.enabled

    /**
     * The two-line description, or `null` when disabled (so the caller leaves the ping untouched).
     */
    fun render(): Component? = rendered

    private fun renderLine(left: String, center: String, right: String, width: Int): Component {
        val segments = buildList {
            if (left.isNotEmpty()) add(anchor(left, 0) { 0 })
            if (center.isNotEmpty()) add(anchor(center, width) { w -> (width - w) / 2 })
            if (right.isNotEmpty()) add(anchor(right, width) { w -> width - w })
        }.sortedBy { it.start }
        if (segments.isEmpty()) return Component.empty()

        val line = Component.text()
        var cursor = 0
        segments.forEachIndexed { index, seg ->
            var spaces = ((seg.start - cursor).toFloat() / FontWidth.SPACE).roundToInt()
            if (index > 0) spaces = spaces.coerceAtLeast(1) // never let two segments run together
            if (spaces > 0) {
                line.append(Component.text(" ".repeat(spaces)))
                cursor += spaces * FontWidth.SPACE
            }
            line.append(seg.component)
            cursor += seg.width
        }
        return line.build()
    }

    private fun anchor(mini: String, width: Int, start: (Int) -> Int): Segment {
        val component = Mini.format(mini)
        val w = FontWidth.of(PLAIN.serialize(component))
        return Segment(component, w, start(w).coerceIn(0, width))
    }

    private data class Segment(val component: Component, val width: Int, val start: Int)

    private data class MotdSettings(
        val enabled: Boolean,
        val width: Int,
        val topLeft: String,
        val topCenter: String,
        val topRight: String,
        val bottomLeft: String,
        val bottomCenter: String,
        val bottomRight: String,
    ) {
        companion object {
            val EMPTY = MotdSettings(false, DEFAULT_WIDTH, "", "", "", "", "", "")

            fun from(config: Config) = MotdSettings(
                enabled = config[CoreKeys.MOTD_ENABLED],
                width = config[VelocityKeys.MOTD_WIDTH],
                topLeft = config[CoreKeys.MOTD_TOP_LEFT],
                topCenter = config[CoreKeys.MOTD_TOP_CENTER],
                topRight = config[CoreKeys.MOTD_TOP_RIGHT],
                bottomLeft = config[CoreKeys.MOTD_BOTTOM_LEFT],
                bottomCenter = config[CoreKeys.MOTD_BOTTOM_CENTER],
                bottomRight = config[CoreKeys.MOTD_BOTTOM_RIGHT],
            )
        }
    }

    private companion object {
        // Rough visible width of the server-list MOTD in default-font pixels; tune via motd.width.
        private const val DEFAULT_WIDTH = 256
        private val PLAIN = PlainTextComponentSerializer.plainText()
    }
}
