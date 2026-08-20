package com.tricrotism.cryon.velocity.api.bedrock

import com.velocitypowered.api.proxy.Player
import java.util.*

/**
 * How a Bedrock client is being driven. Mirrors Floodgate's input mode without exposing its types.
 *
 * **Duplicated from `com.tricrotism.cryon.paper.api.bedrock.BedrockInput`, deliberately**, exactly
 * like the `@Command` model: `:paper-api` carries Bukkit types and `:velocity` must stay Bukkit-free,
 * so the two copies are kept in step by hand rather than shared.
 */
enum class BedrockInput {
    KEYBOARD_MOUSE,
    TOUCH,
    CONTROLLER,
    UNKNOWN;

    /**
     * Touch and unknown are the layouts worth special-casing: a touch player has no hotbar keys and no
     * hover tooltips, so anything that depends on either needs a different presentation.
     */
    val isTouchLike: Boolean get() = this == TOUCH || this == UNKNOWN
}

/**
 * Bedrock-client identity on the proxy, bridged to Geyser through Floodgate by the core.
 *
 * **Always registered** into the module `ServiceRegistry`, exactly like `Messenger` and
 * `KeyValueStore`: when Floodgate is absent every player reports as Java and every accessor answers
 * null or [BedrockInput.UNKNOWN], so a feature calls `services.get<BedrockService>()` unconditionally
 * and never branches on whether Geyser is installed.
 *
 * **Duplicated from the Paper `BedrockService` for the same reason [BedrockInput] is**, and
 * deliberately narrower: this is identity only. The Cumulus form machinery lives on Paper because
 * that is where a menu is, and nothing on the proxy asks a player a question, so porting it here
 * would be speculative.
 *
 * A Bedrock player reaches the proxy as an ordinary Java connection through a standalone Geyser, so
 * [Player.getUsername] carries Floodgate's [usernamePrefix] and [Player.getUniqueId] is the
 * Floodgate-generated id unless the account is linked. Use [gamertag] and [linkedJavaId] rather than
 * stripping or guessing.
 */
interface BedrockService {

    /**
     * The prefix Floodgate puts in front of a Bedrock player's Java username (typically `.`). Empty
     * when Floodgate is absent, and empty when it is configured with no prefix.
     */
    val usernamePrefix: String

    /**
     * Whether this is a Bedrock player connected through Geyser. False for everyone when Floodgate is
     * absent.
     */
    fun isBedrock(player: Player): Boolean

    /**
     * How [player]'s client is being driven; [BedrockInput.UNKNOWN] for Java players.
     */
    fun inputMode(player: Player): BedrockInput

    /**
     * [player]'s Xbox Live id, or null for a Java player. The one identifier that survives a gamertag
     * change, so it is what a Bedrock account should be keyed by.
     */
    fun xuid(player: Player): String?

    /**
     * [player]'s real Bedrock gamertag, with no [usernamePrefix] and none of the shortening or space
     * replacement Floodgate applies to make it a legal Java username. Null for a Java player.
     */
    fun gamertag(player: Player): String?

    /**
     * The Java account [player] has linked, or null when they are a Java player or an unlinked Bedrock
     * one. When this is non-null it is also [Player.getUniqueId], because the server sees a linked
     * player as their Java account; the point of asking is to learn that it *is* one.
     */
    fun linkedJavaId(player: Player): UUID?
}
