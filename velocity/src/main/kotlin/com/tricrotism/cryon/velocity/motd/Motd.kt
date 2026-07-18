package com.tricrotism.cryon.velocity.motd

import com.tricrotism.cryon.common.text.Mini
import com.tricrotism.cryon.velocity.config.VelocityConfig
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.nio.file.Path
import kotlin.math.roundToInt

/**
 * The server-list MOTD, composed of a top and bottom line, each built from three MiniMessage segments
 * anchored **left**, **center**, and **right**. Segments are positioned by measuring their pixel width
 * ([FontWidth]) and padding with spaces, so the center segment sits centered and the right segment
 * ends at [MotdSettings.width]. Read from the proxy `config.yml` and reloadable at runtime — [reload]
 * re-parses the file, so `/motd reload` picks up edits without a proxy restart.
 */
class Motd(private val configFile: Path) {

    @Volatile
    private var settings = MotdSettings.EMPTY

    fun reload() {
        settings = MotdSettings.from(VelocityConfig.load(configFile))
    }

    fun isEnabled(): Boolean = settings.enabled

    /** The two-line description, or `null` when disabled (so the caller leaves the ping untouched). */
    fun render(): Component? {
        val s = settings
        if (!s.enabled) return null
        val top = renderLine(s.topLeft, s.topCenter, s.topRight, s.width)
        val bottom = renderLine(s.bottomLeft, s.bottomCenter, s.bottomRight, s.width)
        return Component.text().append(top).append(Component.newline()).append(bottom).build()
    }

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

            fun from(config: VelocityConfig) = MotdSettings(
                enabled = config.boolean("motd.enabled", false),
                width = config.int("motd.width", DEFAULT_WIDTH),
                topLeft = config.string("motd.top.left", ""),
                topCenter = config.string("motd.top.center", ""),
                topRight = config.string("motd.top.right", ""),
                bottomLeft = config.string("motd.bottom.left", ""),
                bottomCenter = config.string("motd.bottom.center", ""),
                bottomRight = config.string("motd.bottom.right", ""),
            )
        }
    }

    private companion object {
        // Rough visible width of the server-list MOTD in default-font pixels; tune via motd.width.
        private const val DEFAULT_WIDTH = 256
        private val PLAIN = PlainTextComponentSerializer.plainText()
    }
}
