package com.tricrotism.cryon.paper.api.dialog

import com.tricrotism.cryon.paper.api.CryonPaper
import com.tricrotism.cryon.paper.api.scheduler.CryonDispatchers
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Native client dialogs, the way to ask a player for a *value* rather than a click.
 *
 * Paper's dialog API is the right tool where Cryon previously had nothing: an anvil rename is a hack
 * with a durability cost and one field, and a chat prompt hijacks the chat box and loses the player's
 * message if they forget they are in one. A dialog is a real client-rendered form with typed inputs,
 * it cannot be confused with chat, and it works on Bedrock through Geyser without a separate path.
 *
 * **Each of these suspends and returns the answer**, which is the whole reason they read like
 * ordinary code rather than callbacks:
 *
 * ```
 * val name = Dialogs.text(player, "<gold>Name your home".mm(), "Name".mm()) ?: return   // cancelled
 * val warp = Dialogs.choose(player, "<gold>Warp".mm(), warps.map { Choice(it.display, it) })
 * Dialogs.notice(player, "<red>Banned".mm(), reason)
 * ```
 *
 * **Exactly one outcome, always.** The discipline `BedrockService`'s `FormSession` established, for
 * the same reason: anything escrowed behind an unanswered prompt hangs forever. There are three ways
 * out: a button, a disconnect, or the caller being canceled. They collapse onto one latch, so a
 * caller resumes exactly once no matter which happens first.
 *
 * **Escape is disabled and every exit is a button.** Paper fires no event when a player dismisses a
 * dialog, so an escape-closable prompt is one the server cannot tell apart from a player still
 * thinking about it. It would need a timeout, and a timeout on a prompt is either too short to type
 * into or too long to leave state pending. Making every exit a button removes the ambiguity.
 *
 * These run on the player's own thread and return there, so the result can be used with the Bukkit
 * API directly.
 */
object Dialogs {

    /**
     * Ask [player] for a line of text. Null if they canceled.
     *
     * [maxLength] bounds what the client will send. Bound it to what the value is for. This is
     * player input and it arrives exactly as typed.
     */
    suspend fun text(
        player: Player,
        title: Component,
        label: Component,
        body: Component? = null,
        initial: String = "",
        maxLength: Int = 64,
        submitLabel: Component = CONFIRM,
        cancelLabel: Component = CANCEL,
    ): String? = form(
        player, title,
        listOf(textInput(SINGLE, label, initial, maxLength)),
        body, submitLabel, cancelLabel,
    )?.getText(SINGLE)

    /**
     * Ask [player] a yes/no question.
     *
     * False covers both "no" and a disconnect, so a caller can treat it as "do not proceed" without
     * a third case to handle.
     */
    suspend fun confirm(
        player: Player,
        title: Component,
        body: Component? = null,
        confirmLabel: Component = CONFIRM,
        cancelLabel: Component = CANCEL,
    ): Boolean = ask(player, title, body, emptyList()) { buttons ->
        DialogType.confirmation(
            buttons.submit(confirmLabel) { true },
            buttons.cancel(cancelLabel),
        )
    } ?: false

    /**
     * Ask [player] to fill several [inputs] at once, returning the raw response to read them from,
     * or null if they canceled.
     *
     * Build the inputs with [textInput]/[boolInput]/[numberInput]/[optionInput] and read them back
     * by the same keys.
     */
    suspend fun form(
        player: Player,
        title: Component,
        inputs: List<DialogInput>,
        body: Component? = null,
        submitLabel: Component = CONFIRM,
        cancelLabel: Component = CANCEL,
    ): DialogResponseView? = ask(player, title, body, inputs) { buttons ->
        DialogType.confirmation(
            buttons.submit(submitLabel) { it },
            buttons.cancel(cancelLabel),
        )
    }

    /**
     * Tell [player] something and wait for them to acknowledge it.
     *
     * Its own call rather than a [confirm] with one button, because a notice that returns `Boolean`
     * invites callers to branch on an answer nobody was asked for.
     */
    suspend fun notice(
        player: Player,
        title: Component,
        body: Component,
        buttonLabel: Component = OK,
    ) {
        ask(player, title, body, emptyList()) { buttons ->
            DialogType.notice(buttons.submit(buttonLabel) { Unit })
        }
    }

    /**
     * Offer [player] a set of choices and return the one they picked, or null if they backed out.
     *
     * Each option carries its own value, so the caller gets back the thing it cares about rather than
     * an index into a list it then has to keep in step. Buttons are laid out in [columns].
     */
    suspend fun <T> choose(
        player: Player,
        title: Component,
        options: List<Choice<T>>,
        body: Component? = null,
        cancelLabel: Component = CANCEL,
        columns: Int = 2,
    ): T? {
        require(options.isNotEmpty()) { "A choice dialog needs at least one option" }
        return ask(player, title, body, emptyList()) { buttons ->
            DialogType.multiAction(
                options.map { choice ->
                    buttons.submit(choice.label, choice.tooltip) { choice.value }
                }
            )
                .exitAction(buttons.cancel(cancelLabel))
                .columns(columns.coerceAtLeast(1))
                .build()
        }
    }

    /** One option in a [choose], and the value it stands for. */
    data class Choice<T>(val label: Component, val value: T, val tooltip: Component? = null)

    /** A single-line text field. Read back with `getText(key)`. */
    fun textInput(key: String, label: Component, initial: String = "", maxLength: Int = 64): DialogInput =
        DialogInput.text(key, label).initial(initial).maxLength(maxLength).build()

    /** A checkbox. Read back with `getBoolean(key)`. */
    fun boolInput(key: String, label: Component, initial: Boolean = false): DialogInput =
        DialogInput.bool(key, label).initial(initial).build()

    /**
     * A slider over `[start, end]`. Read back with `getFloat(key)`.
     *
     * [step] is the granularity the client snaps to. Leave it null for a continuous slider; set it
     * to `1f` for anything the caller will round to an integer anyway, so the number the player
     * releases the slider on is the number they actually get.
     */
    fun numberInput(
        key: String,
        label: Component,
        start: Float,
        end: Float,
        initial: Float? = null,
        step: Float? = null,
    ): DialogInput = DialogInput.numberRange(key, label, start, end)
        .apply {
            initial?.let { initial(it) }
            step?.let { step(it) }
        }
        .build()

    /**
     * A dropdown. The selected entry's [Option.id] comes back through `getText(key)`.
     *
     * At most one option may be pre-selected, the client honours the first that is, so two would
     * quietly disagree with whatever the caller thought the default was.
     */
    fun optionInput(key: String, label: Component, options: List<Option>): DialogInput {
        require(options.isNotEmpty()) { "A dropdown needs at least one option" }
        require(options.count { it.selected } <= 1) { "Only one dropdown option may be pre-selected" }
        return DialogInput.singleOption(
            key,
            label,
            options.map { SingleOptionDialogInput.OptionEntry.create(it.id, it.display, it.selected) },
        ).build()
    }

    /** One entry of an [optionInput] dropdown. */
    data class Option(val id: String, val display: Component, val selected: Boolean = false)

    /**
     * Build a dialog, show it, and suspend until exactly one outcome fires.
     *
     * [type] is handed a [Buttons] factory whose products *resolve* the dialog: clicking one answers
     * the coroutine with whatever its reader returns, and `cancel` answers null. That indirection is
     * what lets one implementation serve a confirmation, a notice and an N-way choice. Those shapes
     * differ only in how many resolving buttons they have and what each answers with, so the latch,
     * the quit listener and the cancellation handling are written once.
     */
    private suspend fun <T> ask(
        player: Player,
        title: Component,
        body: Component?,
        inputs: List<DialogInput>,
        type: (Buttons<T>) -> DialogType,
    ): T? = withContext(CryonDispatchers.entity(player)) {
        suspendCancellableCoroutine { continuation ->
            val answered = AtomicBoolean()
            lateinit var quitListener: Listener

            // Every path funnels through here, so the latch is the single point guaranteeing one
            // resume. A late click, a quit racing a click, and a cancelled caller all collapse.
            fun answer(result: T?) {
                if (!answered.compareAndSet(false, true)) return
                HandlerList.unregisterAll(quitListener)
                if (continuation.isActive) continuation.resume(result)
            }

            fun action(read: (DialogResponseView) -> T?): DialogAction = DialogAction.customClick(
                { response, _ ->
                    // `read` is caller-supplied and runs on Paper's dialog thread. Letting it throw
                    // there would escape into the server *and* leave this coroutine suspended
                    // forever, because the latch would never be set.
                    val value = runCatching { read(response) }
                        .onFailure { CryonPaper.plugin.slF4JLogger.error("A dialog response handler failed", it) }
                        .getOrNull()
                    answer(value)
                },
                ONE_SHOT,
            )

            val buttons = Buttons<T>(
                build = { label, tooltip, read ->
                    ActionButton.builder(label)
                        .apply { tooltip?.let { tooltip(it) } }
                        .action(action(read))
                        .build()
                },
                cancel = { label ->
                    ActionButton.builder(label).action(action { null }).build()
                },
            )

            quitListener = object : Listener {
                @EventHandler(priority = EventPriority.MONITOR)
                fun onQuit(event: PlayerQuitEvent) {
                    if (event.player.uniqueId == player.uniqueId) answer(null)
                }
            }
            CryonPaper.plugin.server.pluginManager.registerEvents(quitListener, CryonPaper.plugin)

            // A caller cancelled while the dialog is up must not leave the listener registered, and
            // the dialog itself comes down since nothing on screen would answer it any more.
            continuation.invokeOnCancellation {
                if (answered.compareAndSet(false, true)) {
                    HandlerList.unregisterAll(quitListener)
                    runCatching { (player as Audience).closeDialog() }
                }
            }

            val dialog = Dialog.create { factory ->
                factory.empty()
                    .base(
                        DialogBase.builder(title)
                            .canCloseWithEscape(false)
                            .pause(false)
                            .afterAction(DialogBase.DialogAfterAction.CLOSE)
                            .body(body?.let { listOf(DialogBody.plainMessage(it)) } ?: emptyList())
                            .inputs(inputs)
                            .build()
                    )
                    .type(type(buttons))
            }

            (player as Audience).showDialog(dialog)
        }
    }

    /**
     * Makes the buttons that end a dialog.
     *
     * `cancel` reads as `null`, which is why every asking method's "backed out" answer is null rather
     * than a sentinel: there is exactly one place that decides what backing out means.
     */
    private class Buttons<T>(
        private val build: (Component, Component?, (DialogResponseView) -> T?) -> ActionButton,
        val cancel: (Component) -> ActionButton,
    ) {
        fun submit(
            label: Component,
            tooltip: Component? = null,
            read: (DialogResponseView) -> T?,
        ): ActionButton = build(label, tooltip, read)
    }

    private val CONFIRM: Component = Component.text("Confirm")
    private val CANCEL: Component = Component.text("Cancel")
    private val OK: Component = Component.text("OK")

    /** The key [text] reads its single field back under. Private: nothing outside needs to name it. */
    private const val SINGLE = "value"

    /**
     * One use, and a lifetime that outlives any reasonable deliberation.
     *
     * `uses(1)` makes the client-side callback single-shot, belt to the latch's braces. The latch
     * already collapses duplicates; there is simply no reason to keep a live callback registered on
     * the server once it has fired. The lifetime reclaims one for a dialog nobody ever answered.
     */
    private val ONE_SHOT: ClickCallback.Options = ClickCallback.Options.builder()
        .uses(1)
        .lifetime(Duration.ofMinutes(10))
        .build()
}
