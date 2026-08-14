package com.tricrotism.cryon.bedrock

import com.tricrotism.cryon.paper.api.bedrock.*
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.slf4j.Logger

/**
 * Picks the [BedrockService] implementation at startup.
 *
 * A service is **always** registered. The no-Floodgate one reports every player as Java and no-ops
 * every send, so a feature calls `services.get<BedrockService>()` unconditionally and never
 * branches on whether Geyser is installed. Same shape as `Messenger`/`KeyValueStore`.
 */
object BedrockBridge {

    fun create(logger: Logger): BedrockService {
        if (Bukkit.getPluginManager().getPlugin("floodgate") == null) return NoBedrock
        return try {
            FloodgateBedrockService(logger).also { logger.info("Floodgate detected. Bedrock forms enabled") }
        } catch (t: Throwable) {
            logger.warn("Floodgate is present but the Bedrock bridge could not be created", t)
            NoBedrock
        }
    }

    /**
     * Everyone is a Java player and nothing is sent. Names no Floodgate type, so it always loads.
     */
    private object NoBedrock : BedrockService {
        override fun isBedrock(player: Player) = false
        override fun inputMode(player: Player) = BedrockInput.UNKNOWN
        override fun sendSimpleForm(
            player: Player,
            title: Component,
            content: Component,
            buttons: List<FormButton>,
            onClose: () -> Unit,
        ) = false

        override fun sendModalForm(
            player: Player,
            title: Component,
            content: Component,
            confirmLabel: Component,
            cancelLabel: Component,
            onResult: (Boolean) -> Unit,
        ) = false

        override fun sendCustomForm(
            player: Player,
            title: Component,
            fields: List<FormField>,
            onSubmit: (FormResponse) -> Unit,
            onClose: () -> Unit,
        ) = false

        override fun closeForm(player: Player) = false
    }
}
