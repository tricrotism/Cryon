package com.tricrotism.cryon.velocity.network

import com.tricrotism.cryon.common.net.Messenger
import com.tricrotism.cryon.common.net.MessengerSubscription
import com.tricrotism.cryon.common.server.TransferRequest
import com.velocitypowered.api.proxy.ProxyServer
import org.slf4j.Logger

/**
 * Receives routing requests broadcast on [TransferRequest.CHANNEL] and connects the player, if this
 * proxy is the one they are on. Every proxy receives the broadcast; only the owner has the player, so
 * the rest no-op. The target backend is looked up by instance id in this proxy's live server list
 * (kept current by [BackendSynchronizer]).
 */
class TransferListener(
    private val proxy: ProxyServer,
    private val messenger: Messenger,
    private val logger: Logger,
) {
    private var subscription: MessengerSubscription? = null

    fun start() {
        subscription = messenger.subscribe(TransferRequest.CHANNEL) { message ->
            val (player, instanceId) = TransferRequest.decode(message) ?: return@subscribe
            val target = proxy.getServer(instanceId).orElse(null) ?: return@subscribe
            proxy.getPlayer(player).ifPresent { connecting ->
                connecting.createConnectionRequest(target).connect().whenComplete { result, error ->
                    if (error != null || result?.isSuccessful == false) {
                        logger.warn("Failed to route {} to {}", player, instanceId)
                    }
                }
            }
        }
    }

    fun stop() {
        subscription?.unsubscribe()
        subscription = null
    }
}
