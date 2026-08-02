package com.tricrotism.cryon.bedrock

import com.tricrotism.cryon.paper.api.bedrock.*
import com.tricrotism.cryon.paper.api.event.Events
import com.tricrotism.cryon.paper.api.scheduler.Schedulers
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerQuitEvent
import org.geysermc.cumulus.form.CustomForm
import org.geysermc.cumulus.form.ModalForm
import org.geysermc.cumulus.form.SimpleForm
import org.geysermc.cumulus.form.util.FormBuilder
import org.geysermc.floodgate.api.FloodgateApi
import org.slf4j.Logger
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The real Bedrock bridge, used when Floodgate is installed. **This class names Floodgate and Cumulus
 * types, so it must only ever be classloaded after the plugin-presence check in [BedrockBridge].**
 *
 * Form text is legacy section-coded: that is what Bedrock form UIs colour, and MiniMessage/Adventure
 * markup means nothing to them.
 */
internal class FloodgateBedrockService(private val logger: Logger) : BedrockService {

    private val sessions = ConcurrentHashMap<UUID, FormSession>()

    init {
        Events.subscribe<PlayerQuitEvent>(EventPriority.MONITOR)
            .handler { event -> sessions[event.player.uniqueId]?.cancel() }
    }

    override fun isBedrock(player: Player): Boolean =
        FloodgateApi.getInstance().isFloodgatePlayer(player.uniqueId)

    /**
     * Floodgate declares `getInputMode()` in its API jar but ships the `InputMode` enum in its core, so
     * the type isn't on our compile classpath. The value is only ever used as a name, so read it
     * reflectively rather than dragging in the whole core artifact.
     */
    override fun inputMode(player: Player): BedrockInput {
        val fgPlayer = FloodgateApi.getInstance().getPlayer(player.uniqueId) ?: return BedrockInput.UNKNOWN
        return try {
            val mode = fgPlayer.javaClass.getMethod("getInputMode").invoke(fgPlayer) as? Enum<*>
            when (mode?.name?.uppercase()) {
                "MOUSE", "KEYBOARD_MOUSE" -> BedrockInput.KEYBOARD_MOUSE
                "TOUCH" -> BedrockInput.TOUCH
                "CONTROLLER" -> BedrockInput.CONTROLLER
                else -> BedrockInput.UNKNOWN
            }
        } catch (t: Throwable) {
            logger.debug("Could not read the Floodgate input mode for {}", player.name, t)
            BedrockInput.UNKNOWN
        }
    }

    override fun sendSimpleForm(
        player: Player,
        title: Component,
        content: Component,
        buttons: List<FormButton>,
        onClose: () -> Unit,
    ): Boolean {
        if (!isBedrock(player)) return false
        val session = begin(player, onClose)
        val form = SimpleForm.builder()
            .title(legacy(title))
            .content(legacy(content))
            .apply { buttons.forEach { button -> button(legacy(button.label)) } }
            .validResultHandler { response ->
                val tapped = buttons.getOrNull(response.clickedButtonId())
                if (tapped == null) session.cancel() else session.deliver(tapped.onTap)
            }
            .closedOrInvalidResultHandler(Runnable { session.cancel() })
        return send(player, session, form)
    }

    override fun sendModalForm(
        player: Player,
        title: Component,
        content: Component,
        confirmLabel: Component,
        cancelLabel: Component,
        onResult: (Boolean) -> Unit,
    ): Boolean {
        if (!isBedrock(player)) return false
        val session = begin(player) { onResult(false) }
        val form = ModalForm.builder()
            .title(legacy(title))
            .content(legacy(content))
            .button1(legacy(confirmLabel))
            .button2(legacy(cancelLabel))
            .validResultHandler { response -> session.deliver { onResult(response.clickedFirst()) } }
            .closedOrInvalidResultHandler(Runnable { session.cancel() })
        return send(player, session, form)
    }

    override fun sendCustomForm(
        player: Player,
        title: Component,
        fields: List<FormField>,
        onSubmit: (FormResponse) -> Unit,
        onClose: () -> Unit,
    ): Boolean {
        if (!isBedrock(player)) return false
        val session = begin(player, onClose)
        val builder = CustomForm.builder().title(legacy(title))
        fields.forEach { field ->
            when (field) {
                is FormField.Input -> builder.input(legacy(field.label), field.placeholder, field.default)
                is FormField.Toggle -> builder.toggle(legacy(field.label), field.default)
                is FormField.Dropdown ->
                    builder.dropdown(legacy(field.label), field.options, field.defaultIndex)

                is FormField.Slider ->
                    builder.slider(legacy(field.label), field.min, field.max, field.step.toFloat(), field.default)
            }
        }
        builder.validResultHandler { response ->
            val values = LinkedHashMap<String, Any?>(fields.size)
            fields.forEachIndexed { index, field ->
                values[field.id] = runCatching {
                    when (field) {
                        is FormField.Input -> response.asInput(index)
                        is FormField.Toggle -> response.asToggle(index)
                        is FormField.Dropdown -> field.options.getOrNull(response.asDropdown(index))
                        is FormField.Slider -> response.asSlider(index)
                    }
                }.getOrNull()
            }
            session.deliver { onSubmit(FormResponse(values)) }
        }
        builder.closedOrInvalidResultHandler(Runnable { session.cancel() })
        return send(player, session, builder)
    }

    /**
     * Opens a session for the form about to be sent, resolving whatever it displaces. A Bedrock client
     * happily stacks forms and `openInventory` can't see them, so replacing here is the only place two
     * live callbacks can be collapsed into one, the twin of InvUI evicting an open window.
     */
    private fun begin(player: Player, onCancel: () -> Unit): FormSession {
        val session = FormSession(player, onCancel)
        sessions.put(player.uniqueId, session)?.cancel()
        return session
    }

    /**
     * Bedrock will silently drop a form while the player has a real container open, so close it first
     * and let the client settle before sending. An empty crafting grid is the "nothing open" state and
     * needs no such dance.
     *
     * Returns true for any Bedrock player: whether the form actually reached them is reported through
     * [session], not through this boolean, so a caller never falls back to a Java menu for a client
     * that can't read one. Every path that fails to deliver cancels the session instead of going quiet.
     */
    private fun send(player: Player, session: FormSession, form: FormBuilder<*, *, *>): Boolean {
        val dispatch = Schedulers.entity(player) {
            val api = FloodgateApi.getInstance()
            if (player.openInventory.type == InventoryType.CRAFTING) {
                if (!api.sendForm(player.uniqueId, form)) session.cancel()
                return@entity
            }
            player.closeInventory()
            val delayed = Schedulers.entityLater(
                player,
                FORM_REOPEN_DELAY_TICKS,
                retired = Runnable { session.cancel() },
            ) {
                if (!api.sendForm(player.uniqueId, form)) session.cancel()
            }
            if (delayed == null) session.cancel()
        }
        if (dispatch == null) session.cancel()
        return true
    }

    /**
     * Form callbacks arrive on Floodgate's thread; handlers are allowed to touch the Bukkit API.
     *
     * A player who has already been retired has no entity scheduler left, and that is exactly the
     * disconnect case, so fall back to the global one rather than dropping the callback — the handler
     * is releasing state it already holds, and skipping it is how escrows go missing.
     */
    private fun run(player: Player, action: () -> Unit) {
        val guarded = {
            try {
                action()
            } catch (t: Throwable) {
                logger.error("Bedrock form handler failed for {}", player.name, t)
            }
        }
        Schedulers.entity(player) { guarded() } ?: Schedulers.global { guarded() }
    }

    /**
     * One form's outcome, settled exactly once. Floodgate reports a tap and a dismissal but says nothing
     * when the send never lands or the connection dies mid-form, and a replaced form is never reported
     * at all, so the missing outcomes are supplied here and the latch keeps the winner alone.
     */
    private inner class FormSession(private val player: Player, private val onCancel: () -> Unit) {

        private val settled = AtomicBoolean()

        fun deliver(action: () -> Unit) {
            if (!settled.compareAndSet(false, true)) return
            sessions.remove(player.uniqueId, this)
            run(player, action)
        }

        fun cancel() = deliver(onCancel)
    }

    private fun legacy(component: Component): String = LEGACY.serialize(component)

    private companion object {
        val LEGACY: LegacyComponentSerializer = LegacyComponentSerializer.legacySection()

        /**
         * Long enough for the close to reach the client before the form does.
         */
        const val FORM_REOPEN_DELAY_TICKS = 5L
    }
}
