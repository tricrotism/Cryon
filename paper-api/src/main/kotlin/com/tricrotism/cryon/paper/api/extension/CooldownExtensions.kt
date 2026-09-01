package com.tricrotism.cryon.paper.api.extension

import com.tricrotism.cryon.common.cooldown.CooldownService
import com.tricrotism.cryon.common.extension.formatDuration
import com.tricrotism.cryon.common.locale.Messages
import com.tricrotism.cryon.common.text.CommonMessages
import com.tricrotism.cryon.common.text.Mini
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.entity.Player
import kotlin.time.Duration

/**
 * Claim the next use of [id] for [player], answering whether it was granted. The `Player` overload of
 * [CooldownService.trigger].
 */
fun CooldownService.trigger(player: Player, id: String, duration: Duration): Boolean =
    trigger(player.uniqueId, id, duration)

/**
 * How long [player] must still wait for [id].
 */
fun CooldownService.remaining(player: Player, id: String): Duration = remaining(player.uniqueId, id)

/**
 * The one-line gate for a command or interaction: claims the cooldown, or tells [player] how long is
 * left and answers false.
 *
 * The counterpart to `FeatureFlags.guard`, and used the same way, as the first line of a handler:
 *
 * ```
 * if (!cooldowns.guard(player, "kit.daily", 24.hours)) return
 * ```
 *
 * The message is deliberately built here rather than taken from the caller: a refusal that does not
 * say how long is left reads as a broken button, and every feature writing its own version of that
 * sentence is how the wording drifts. Pass [key] to localize it per feature; the default is the
 * shared `cryon.common.cooldown` line, which receives a `<time>` placeholder.
 */
fun CooldownService.guard(
    player: Player,
    id: String,
    duration: Duration,
    key: String = "cryon.common.cooldown",
): Boolean {
    if (trigger(player.uniqueId, id, duration)) return true
    val left = remaining(player.uniqueId, id).inWholeSeconds.coerceAtLeast(1)
    val template = Messages.rawOr(
        player.resolvedLocale(), key,
        "You must wait <time> before doing that again.",
    )
    player.sendMessage(
        CommonMessages.error(Mini.format(template, Placeholder.unparsed("time", left.formatDuration())))
    )
    return false
}
