package com.tricrotism.cryon.paper.api.bedrock

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player


/**
 * Bedrock-client support, bridged to Geyser through Floodgate by the core.
 *
 * **Always registered** into the module `ServiceRegistry`, exactly like `Messenger` and
 * `KeyValueStore`: when Floodgate is absent the registered implementation reports every player as Java
 * and every send is a no-op, so a feature calls this unconditionally and never branches on whether
 * Geyser is installed.
 *
 * Why it exists: a Java inventory menu is translated by Geyser into a Bedrock container, and while it
 * opens, everything the layout leans on (filler panes, control rows, hover lore) is meaningless on a
 * touchscreen. Native forms are the Bedrock idiom, and they scroll, so they need no paging.
 *
 * Every callback here is dispatched on the player's own scheduler, so handlers may touch the Bukkit
 * API directly. (Floodgate delivers responses on its own thread; the core hops for you.) A player who
 * has already left has no entity scheduler, so their callback runs on the global one instead. It
 * still has to fire, because releasing an escrow can't be skipped just because they logged off.
 *
 * **Exactly one callback per send.** A form can be answered, dismissed, replaced by a later send, or
 * die with the connection, and Floodgate reports only some of those; the core settles the rest, so a
 * caller can hold state behind a callback without it hanging. Sending a second form replaces the
 * first, resolving it as dismissed, which mirrors what InvUI does when a window opens over another.
 */
interface BedrockService {

    /**
     * Whether this is a Bedrock player connected through Geyser. False for everyone when absent.
     */
    fun isBedrock(player: Player): Boolean

    /**
     * How [player]'s client is being driven; [BedrockInput.UNKNOWN] for Java players.
     */
    fun inputMode(player: Player): BedrockInput

    /**
     * A scrolling list of buttons. Returns false if [player] isn't a Bedrock player, in which case the
     * caller should fall back to its Java menu.
     *
     * [onClose] covers every way the form ends without a tap, so exactly one of it or a button's
     * `onTap` always runs.
     */
    fun sendSimpleForm(
        player: Player,
        title: Component,
        content: Component,
        buttons: List<FormButton>,
        onClose: () -> Unit = {},
    ): Boolean

    /**
     * A two-button yes/no dialog. Returns false if [player] isn't a Bedrock player. Dismissing the form
     * reports false, so [onResult] always runs exactly once.
     */
    fun sendModalForm(
        player: Player,
        title: Component,
        content: Component,
        confirmLabel: Component,
        cancelLabel: Component,
        onResult: (confirmed: Boolean) -> Unit,
    ): Boolean

    /**
     * A form of typed fields. Returns false if [player] isn't a Bedrock player.
     *
     * [onClose] covers every way the form ends without a submission, so exactly one of it or [onSubmit]
     * always runs.
     */
    fun sendCustomForm(
        player: Player,
        title: Component,
        fields: List<FormField>,
        onSubmit: (FormResponse) -> Unit,
        onClose: () -> Unit = {},
    ): Boolean

    /**
     * Take down whatever form [player] currently has open, settling it as a dismissal. True if there
     * was one. The one-callback guarantee is unchanged: this is the same outcome as the player closing
     * it themselves, and a form already answered stays answered.
     *
     * The sender needs this to let go. Nothing else can see a Cumulus form (not even
     * `openInventory`), so a feature holding state behind a callback has no other way to release it
     * early, and a module being unloaded with a form on screen would otherwise leave that callback,
     * and its classloader, reachable from the form registry.
     */
    fun closeForm(player: Player): Boolean
}

