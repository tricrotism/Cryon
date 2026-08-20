package com.tricrotism.cryon.velocity.bedrock

import com.tricrotism.cryon.velocity.api.bedrock.BedrockInput
import com.tricrotism.cryon.velocity.api.bedrock.BedrockService
import com.velocitypowered.api.proxy.Player
import org.geysermc.floodgate.api.FloodgateApi
import org.geysermc.floodgate.api.player.FloodgatePlayer
import org.slf4j.Logger
import java.util.*

/**
 * The real proxy-side Bedrock bridge, used when Floodgate is installed. **This class names Floodgate
 * types, so it must only ever be classloaded after the plugin-presence check in [BedrockBridge].**
 *
 * Every lookup goes through `FloodgateApi.getInstance()` rather than a field captured at
 * construction: Velocity initializes plugins in dependency order, but the API holder is filled by
 * Floodgate's own init, so a handle taken too early would be null for the proxy's whole life.
 */
internal class FloodgateBedrockService(private val logger: Logger) : BedrockService {

    override val usernamePrefix: String
        get() = FloodgateApi.getInstance()?.playerPrefix ?: ""

    override fun isBedrock(player: Player): Boolean =
        FloodgateApi.getInstance()?.isFloodgatePlayer(player.uniqueId) == true

    /**
     * Floodgate declares `getInputMode()` in its API jar but ships the `InputMode` enum in its core,
     * so the type isn't on our compile classpath. The value is only ever used as a name, so read it
     * reflectively rather than dragging in the whole core artifact.
     */
    override fun inputMode(player: Player): BedrockInput {
        val bedrockPlayer = floodgatePlayer(player) ?: return BedrockInput.UNKNOWN
        return try {
            val mode = bedrockPlayer.javaClass.getMethod("getInputMode").invoke(bedrockPlayer) as? Enum<*>
            when (mode?.name?.uppercase()) {
                "MOUSE", "KEYBOARD_MOUSE" -> BedrockInput.KEYBOARD_MOUSE
                "TOUCH" -> BedrockInput.TOUCH
                "CONTROLLER" -> BedrockInput.CONTROLLER
                else -> BedrockInput.UNKNOWN
            }
        } catch (t: Throwable) {
            logger.debug("Could not read the Floodgate input mode for {}", player.username, t)
            BedrockInput.UNKNOWN
        }
    }

    override fun xuid(player: Player): String? = floodgatePlayer(player)?.xuid

    override fun gamertag(player: Player): String? = floodgatePlayer(player)?.username

    /**
     * `getCorrectUniqueId` is the Java account's id precisely when the player is linked, and the
     * Floodgate-generated one otherwise, so the link check is what makes the answer meaningful. Taking
     * it this way keeps `LinkedPlayer` (another core-only type) off the compile classpath.
     */
    override fun linkedJavaId(player: Player): UUID? =
        floodgatePlayer(player)?.takeIf { it.isLinked }?.correctUniqueId

    private fun floodgatePlayer(player: Player): FloodgatePlayer? =
        FloodgateApi.getInstance()?.getPlayer(player.uniqueId)
}
