package com.tricrotism.cryon.velocity.bedrock

import com.tricrotism.cryon.velocity.api.bedrock.BedrockInput
import com.tricrotism.cryon.velocity.api.bedrock.BedrockService
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import org.slf4j.Logger
import java.util.*

/**
 * Picks the [BedrockService] implementation at proxy init. The twin of the Paper core's bridge, and
 * the same rule: a service is **always** registered, so a feature calls `services.get<BedrockService>()`
 * unconditionally. The no-Floodgate one reports every player as Java.
 */
object BedrockBridge {

    fun create(proxy: ProxyServer, logger: Logger): BedrockService {
        if (proxy.pluginManager.getPlugin("floodgate").isEmpty) return NoBedrock
        return try {
            FloodgateBedrockService(logger).also { logger.info("Floodgate detected. Bedrock identity available") }
        } catch (t: Throwable) {
            logger.warn("Floodgate is present but the Bedrock bridge could not be created", t)
            NoBedrock
        }
    }

    /**
     * Everyone is a Java player. Names no Floodgate type, so it always loads.
     */
    private object NoBedrock : BedrockService {
        override val usernamePrefix: String = ""
        override fun isBedrock(player: Player) = false
        override fun inputMode(player: Player) = BedrockInput.UNKNOWN
        override fun xuid(player: Player): String? = null
        override fun gamertag(player: Player): String? = null
        override fun linkedJavaId(player: Player): UUID? = null
    }
}
