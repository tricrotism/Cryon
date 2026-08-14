package com.tricrotism.cryon.paper.api.menu

import com.tricrotism.cryon.common.text.Mini
import com.tricrotism.cryon.paper.api.bedrock.BedrockService
import com.tricrotism.cryon.paper.api.extension.toItem
import com.tricrotism.cryon.paper.api.scheduler.Schedulers
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.invui.item.Item
import xyz.xenondevs.invui.window.Window
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A yes/no dialog, the one menu every feature eventually needs, so it lives here rather than being
 * rebuilt per module.
 *
 * **Cross-platform by construction.** Java players get a three-row InvUI window; Bedrock players get a
 * native Cumulus modal, which is what a confirmation actually is on a touchscreen. Callers don't
 * choose: [open] asks [BedrockService] and picks. Closing the dialog counts as declining on both
 * sides, so [onResult] always fires exactly once and nothing is left hanging, including when the
 * player disconnects mid-dialog, or a later menu opens over this one.
 *
 * **Callable from any thread.** [open] hops to the player itself, and [onResult] comes back on the
 * player's own scheduler, so a handler may touch the Bukkit API and may open another menu. Both cost
 * a tick: the callback never runs inside the caller's frame, which is deliberate, since InvUI forbids
 * opening a window from inside the close handler this dialog answers from.
 */
object ConfirmMenu {

    private val STRUCTURE = arrayOf(
        "0 0 0 0 0 0 0 0 0",
        "0 0 y 0 0 0 n 0 0",
        "0 0 0 0 0 0 0 0 0",
    )

    /**
     * Ask [player] to confirm. [onResult] receives true only if they actively accepted.
     *
     * [title] is the window/form title, [question] the body. Shown as the confirm button's lore on
     * Java (a chest menu has nowhere else to put it) and as the form content on Bedrock.
     *
     * Returns the [Dialog] so the opener can take it down again; a module **must** close its open
     * dialogs in `onDisable`.
     */
    fun open(
        player: Player,
        bedrock: BedrockService,
        title: Component,
        question: Component,
        confirmLabel: Component = Mini.format("<green>Confirm"),
        cancelLabel: Component = Mini.format("<red>Cancel"),
        onResult: (Boolean) -> Unit,
    ): Dialog {
        val dialog = Dialog(player, bedrock)
        val scheduled = Schedulers.entity(player) {
            if (dialog.isClosed) return@entity onResult(false)
            if (bedrock.sendModalForm(player, title, question, confirmLabel, cancelLabel, onResult)) return@entity
            dialog.attach(openWindow(player, title, question, confirmLabel, cancelLabel, onResult))
        }

        if (scheduled == null) onResult(false)
        return dialog
    }

    /**
     * The opener's handle on a live dialog. [close] takes it down and answers false, exactly as the
     * player dismissing it would, so the exactly-once guarantee holds either way.
     *
     * It exists because a dialog outlives the frame that opened it and holds that frame's code:
     * InvUI keeps a strong reference to the `Item`s, the Bedrock form registry keeps the session, and
     * both close over the result callback, which in a feature is the module's own class. A dialog left
     * open through a module hot-unload therefore strands that module's classloader and leaves clicks
     * dispatching into code that is gone. Track what you open and close it in `onDisable`.
     *
     * Not a `Window`: a Bedrock player never has one. Safe to close from any thread and at any point,
     * including before the dialog appears, after it has been answered, or twice.
     */
    class Dialog internal constructor(private val player: Player, private val bedrock: BedrockService) : AutoCloseable {

        @Volatile
        private var window: Window? = null

        @Volatile
        internal var isClosed: Boolean = false
            private set

        internal fun attach(opened: Window) {
            window = opened
            if (isClosed) opened.close()
        }

        override fun close() {
            isClosed = true
            bedrock.closeForm(player)
            val opened = window ?: return
            window = null
            Schedulers.entity(player) { opened.close() }
        }
    }

    private fun openWindow(
        player: Player,
        title: Component,
        question: Component,
        confirmLabel: Component,
        cancelLabel: Component,
        onResult: (Boolean) -> Unit,
    ): Window {
        val answered = AtomicBoolean()

        fun answer(result: Boolean, window: Window?) {
            if (!answered.compareAndSet(false, true)) return
            player.playSound(
                player.location,
                if (result) Sound.UI_BUTTON_CLICK else Sound.BLOCK_NOTE_BLOCK_BASS,
                1f,
                if (result) 1.5f else 0.7f,
            )
            window?.close()
            Schedulers.entity(player) { onResult(result) } ?: onResult(result)
        }

        lateinit var window: Window

        val yes = Item.builder()
            .setItemProvider(
                Material.GREEN_STAINED_GLASS_PANE.toItem()
                    .name(confirmLabel)
                    .lore(listOf(question))
                    .build()
            )
            .addClickHandler { _ -> answer(true, window) }
            .build()

        val no = Item.builder()
            .setItemProvider(Material.RED_STAINED_GLASS_PANE.toItem().name(cancelLabel).build())
            .addClickHandler { _ -> answer(false, window) }
            .build()

        val gui = Gui.builder()
            .setStructure(*STRUCTURE)
            .addIngredient('y', yes)
            .addIngredient('n', no)
            .build()

        window = Window.builder()
            .setViewer(player)
            .setTitle(title)
            .setUpperGui(gui)
            .addCloseHandler { answer(false, null) }
            .build()

        window.open()
        return window
    }
}
