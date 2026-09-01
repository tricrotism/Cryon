package com.tricrotism.cryon.command

import com.tricrotism.cryon.command.CommandUi.MAX_DISTANCE
import com.tricrotism.cryon.common.text.CommonMessages
import com.tricrotism.cryon.common.text.Mini
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.command.CommandSender
import kotlin.math.min

/**
 * The shared look of the core's command output: clickable buttons, usage lines, and the
 * did-you-mean reply.
 *
 * It exists because "unknown id" is the single most common thing an operator sees, and answering it
 * with only the rejection wastes the one moment we know both what they typed and what exists. Every
 * `no such module / currency / jar / flag` path routes through [unknown] instead, so the correction
 * is one click rather than a re-type, and the phrasing is the same wherever it appears.
 */
internal object CommandUi {

    /**
     * A bracketed label that runs [command] on click. For an action the sender is choosing to take.
     */
    fun button(label: String, tag: String, command: String, hover: Component): Component =
        Mini.format(bracket(tag), Placeholder.unparsed("label", label))
            .clickEvent(ClickEvent.runCommand(command))
            .hoverEvent(HoverEvent.showText(hover))

    /**
     * As [button], but only fills the chat box, for anything that still needs an argument typed.
     */
    fun suggestButton(label: String, tag: String, command: String, hover: Component): Component =
        Mini.format(bracket(tag), Placeholder.unparsed("label", label))
            .clickEvent(ClickEvent.suggestCommand(command))
            .hoverEvent(HoverEvent.showText(hover))

    /**
     * The bracketed shell both buttons share, with the label left as a placeholder.
     *
     * [tag] is a palette tag name and so is spliced in, but the label is data: a module or currency
     * id carrying something that reads as a tag would otherwise be parsed rather than shown.
     */
    private fun bracket(tag: String): String =
        "<slate_gray>[</slate_gray><$tag><label></$tag><slate_gray>]</slate_gray>"

    fun hover(tag: String, title: String, detail: String): Component = Mini.format(
        "<$tag><b><title></b></$tag><newline><slate_gray><detail>",
        Placeholder.unparsed("title", title),
        Placeholder.unparsed("detail", detail),
    )

    /**
     * Tell [sender] that [input] is not a known [noun], and offer the nearest [candidates] entry as a
     * clickable correction when one is close enough to be worth offering.
     *
     * [retry] builds the command that would have worked, so the correction runs rather than being
     * read. When nothing is close the reply is the plain rejection: a wrong guess costs more than no
     * guess, because it invites a click that fails a second time.
     */
    fun unknown(
        sender: CommandSender,
        noun: String,
        input: String,
        candidates: Collection<String>,
        retry: (String) -> String,
    ) {
        val rejection = Mini.format(
            "<off_white>No <noun> <highlight><input></highlight>.",
            Placeholder.unparsed("noun", noun),
            Placeholder.unparsed("input", input),
        )
        val nearest = closest(input, candidates)
        if (nearest == null) {
            sender.sendMessage(CommonMessages.error(rejection))
            return
        }
        sender.sendMessage(
            Component.textOfChildren(
                CommonMessages.error(rejection),
                Mini.format(" <slate_gray>Did you mean</slate_gray> "),
                button(
                    nearest, "sky_blue", retry(nearest),
                    hover("sky_blue", nearest, "Click to run this instead"),
                ),
            )
        )
    }

    /**
     * A `/cryon flag enable <feature>` style line that fills the chat box on click.
     */
    fun usage(path: String, description: String): Component = Component.textOfChildren(
        Mini.format(
            "  <sky_blue>/<path></sky_blue> <slate_gray>- <desc>",
            Placeholder.unparsed("path", path),
            Placeholder.unparsed("desc", description),
        ).clickEvent(ClickEvent.suggestCommand("/${path.substringBefore(" <")}"))
            .hoverEvent(HoverEvent.showText(hover("sky_blue", "/$path", description))),
    )

    /**
     * The entry in [candidates] closest to [input], or null when none is close enough to suggest.
     *
     * A prefix match wins outright, which is what a half-typed id is. Otherwise the edit distance has
     * to be within a third of the input's length (and never more than [MAX_DISTANCE]), so `coin` finds
     * `coins` while `xyz` finds nothing rather than the alphabetically unluckiest flag.
     */
    fun closest(input: String, candidates: Collection<String>): String? {
        if (input.isEmpty() || candidates.isEmpty()) return null
        val needle = input.lowercase()
        candidates.firstOrNull { it.lowercase().startsWith(needle) }?.let { return it }
        val budget = min(MAX_DISTANCE, maxOf(1, needle.length / 3))
        return candidates
            .map { it to distance(needle, it.lowercase(), budget) }
            .filter { it.second <= budget }
            .minByOrNull { it.second }
            ?.first
    }

    /**
     * Levenshtein, abandoned once every cell in a row exceeds [budget].
     */
    private fun distance(a: String, b: String, budget: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > budget) return budget + 1
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var best = current[0]
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), substitution)
                best = min(best, current[j])
            }
            if (best > budget) return budget + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private const val MAX_DISTANCE = 3
}
