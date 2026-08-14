package com.tricrotism.cryon.menu

import com.tricrotism.cryon.common.flag.FeatureFlags
import com.tricrotism.cryon.common.module.ModuleManager
import com.tricrotism.cryon.common.module.ModuleState
import com.tricrotism.cryon.common.text.Mini
import com.tricrotism.cryon.network.NetworkStatus
import com.tricrotism.cryon.paper.api.bedrock.BedrockService
import com.tricrotism.cryon.paper.api.bedrock.FormButton
import com.tricrotism.cryon.paper.api.extension.toItem
import com.tricrotism.cryon.paper.api.menu.MenuTree
import com.tricrotism.cryon.paper.api.menu.branch
import com.tricrotism.cryon.paper.api.scheduler.Schedulers
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * `/cryon` as a menu: the module list, the feature flags and the network summary, clicked instead of
 * typed.
 *
 * Every action here has a command that does the same thing, and neither is the primary one. A menu
 * answers "which of these is off?" faster because the state *is* the layout; a command is faster once
 * you know the id, and it is the only one that works from console or a script. `commands.menu` decides
 * which of the two bare `/cryon` gives a player, and `/cryon menu` and `/cryon help` reach the other
 * one whichever way it is set.
 *
 * **State is read when the page opens and never refreshed in place.** An action re-opens the menu
 * rather than editing the slot it was clicked in, which costs a redraw and removes the whole class of
 * bug where a button still describes a module that has since failed.
 */
class AdminMenu(
    private val modules: ModuleManager,
    private val flags: FeatureFlags,
    private val network: NetworkStatus,
    private val bedrock: BedrockService,
) : AutoCloseable {

    /**
     * One live session per viewer, replaced on re-open and dropped on [close].
     *
     * InvUI holds the click handlers, and those handlers are this plugin's code reaching services the
     * core owns. There is no module classloader to strand here, but a window left open across a
     * shutdown is still dispatching into things that have been torn down. Keyed by UUID rather than by
     * `Player`, so a stale entry cannot pin a disconnected player's object graph.
     */
    private val sessions = ConcurrentHashMap<UUID, MenuTree.Session>()

    /**
     * Open the admin menu for [player]: an InvUI window on Java, Cumulus forms on Bedrock.
     */
    fun open(player: Player) {
        if (bedrock.isBedrock(player)) return openRootForm(player)
        show(player, tree())
    }

    override fun close() {
        sessions.values.toList().forEach { runCatching { it.close() } }
        sessions.clear()
    }

    private fun show(player: Player, root: com.tricrotism.cryon.paper.api.menu.MenuBranch) {
        sessions.put(player.uniqueId, MenuTree.open(player, root))?.close()
    }

    private fun tree() = branch("cryon", title("Cryon"), Material.COMMAND_BLOCK) {
        branch("modules", title("Modules"), Material.CHEST, name = label("<gold>Modules", moduleSummary())) {
            for ((id, state) in modules.states()) {
                leaf(id, icon = { moduleIcon(id, state) }, onClick = { viewer -> showModule(viewer, id, state) })
            }
        }
        branch("flags", title("Feature flags"), Material.LEVER, name = label("<gold>Feature flags", flagSummary())) {
            for ((scope, entries) in flags.scopes()) {
                for ((feature, enabled) in entries) {
                    leaf(
                        "$scope|$feature",
                        icon = { flagIcon(scope, feature, enabled) },
                        onClick = { viewer -> act(viewer) { flags.set(scope, feature, !enabled) } },
                    )
                }
            }
        }
        leaf("network", Material.BEACON, label("<gold>Network", network.identity.serverId), networkLines()) { viewer ->
            viewer.playSound(viewer.location, Sound.UI_BUTTON_CLICK, 0.6f, 1.2f)
        }
    }

    /**
     * The per-module page: the state-appropriate toggle, plus reload.
     */
    private fun showModule(viewer: Player, id: String, state: ModuleState) {
        show(viewer, branch("module-$id", title(id), Material.CHEST) {
            if (state == ModuleState.ENABLED) {
                leaf("disable", Material.RED_DYE, label("<scarlet>Disable", id)) { player ->
                    act(player) { modules.disable(id) }
                }
            } else {
                leaf("enable", Material.LIME_DYE, label("<emerald>Enable", id)) { player ->
                    act(player) { if (modules.enable(id)) modules.postLoad(id) }
                }
            }
            leaf("reload", Material.CLOCK, label("<sky_blue>Reload", id)) { player ->
                act(player) { modules.reload(id) }
            }
        })
    }

    /**
     * Apply [mutation] on the global region thread, then re-open the menu on the viewer's own.
     *
     * The module graph and the command registry are single-writer by contract, and a click arrives on
     * the player's region thread, so the mutation cannot run inline. The re-open is scheduled *after*
     * it rather than beside it, so the redrawn page reads the state the click produced instead of the
     * one it replaced.
     */
    private fun act(viewer: Player, mutation: () -> Unit) {
        viewer.playSound(viewer.location, Sound.UI_BUTTON_CLICK, 0.7f, 1.4f)
        Schedulers.global {
            mutation()
            Schedulers.entity(viewer) { open(viewer) }
        }
    }

    private fun moduleIcon(id: String, state: ModuleState): ItemStack {
        val material = when (state) {
            ModuleState.ENABLED -> Material.LIME_DYE
            ModuleState.FAILED -> Material.REDSTONE
            ModuleState.DISABLED -> Material.GRAY_DYE
            else -> Material.YELLOW_DYE
        }
        return material.toItem()
            .name(label("<highlight>$id", state.name.lowercase()))
            .lore(listOf(Mini.format("<slate_gray>Click for actions")))
            .build()
    }

    private fun flagIcon(scope: String, feature: String, enabled: Boolean): ItemStack =
        (if (enabled) Material.LIME_DYE else Material.GRAY_DYE).toItem()
            .name(label("<highlight>$feature", if (enabled) "on" else "off"))
            .lore(
                listOf(
                    Mini.format("<slate_gray>Scope: <scope>", Placeholder.unparsed("scope", scope)),
                    Mini.format(if (enabled) "<slate_gray>Click to turn off" else "<slate_gray>Click to turn on"),
                )
            )
            .build()

    private fun networkLines(): List<Component> = buildList {
        add(field("Node", network.identity.nodeId))
        add(field("Expect", network.identity.expectation.name.lowercase().replace('_', '-')))
        add(field("Transport", network.transport))
        add(field("Live nodes", network.nodeCount().toString()))
        network.warnings().forEach { add(Mini.format("<error>! <w>", Placeholder.unparsed("w", it))) }
    }

    private fun field(label: String, value: String): Component = Mini.format(
        "<slate_gray><label>: </slate_gray><off_white><value>",
        Placeholder.unparsed("label", label),
        Placeholder.unparsed("value", value),
    )

    private fun moduleSummary(): String {
        val states = modules.states()
        return "${states.values.count { it == ModuleState.ENABLED }} of ${states.size} enabled"
    }

    private fun flagSummary(): String = "${flags.features().size} flag(s)"

    private fun title(text: String): Component = Mini.format("<gold><t>", Placeholder.unparsed("t", text))

    private fun label(mini: String, detail: String): Component =
        Mini.format("$mini <slate_gray>(<d>)", Placeholder.unparsed("d", detail))

    /**
     * Bedrock gets forms rather than the translated container. Geyser will happily render the window,
     * but a grid of dyes read by color carries nothing on a touchscreen, and a form scrolls, so the
     * module list needs no paging.
     */
    private fun openRootForm(player: Player) {
        bedrock.sendSimpleForm(
            player,
            title("Cryon"),
            Mini.format("<off_white>Pick a section."),
            listOf(
                FormButton(Mini.format("Modules (${moduleSummary()})")) { openModuleForm(player) },
                FormButton(Mini.format("Feature flags (${flagSummary()})")) { openFlagForm(player) },
                FormButton(Mini.format("Network")) { networkLines().forEach(player::sendMessage) },
            ),
        )
    }

    private fun openModuleForm(player: Player) {
        val states = modules.states()
        if (states.isEmpty()) {
            player.sendMessage(Mini.format("<off_white>No modules are loaded."))
            return
        }
        bedrock.sendSimpleForm(
            player,
            title("Modules"),
            Mini.format("<off_white>Tap a module to act on it."),
            states.map { (id, state) ->
                FormButton(Mini.format("$id - ${state.name.lowercase()}")) { moduleForm(player, id, state) }
            },
        )
    }

    private fun moduleForm(player: Player, id: String, state: ModuleState) {
        val toggle = if (state == ModuleState.ENABLED) {
            FormButton(Mini.format("Disable")) { act(player) { modules.disable(id) } }
        } else {
            FormButton(Mini.format("Enable")) { act(player) { if (modules.enable(id)) modules.postLoad(id) } }
        }
        bedrock.sendSimpleForm(
            player,
            title(id),
            Mini.format("<off_white>State: ${state.name.lowercase()}"),
            listOf(
                toggle,
                FormButton(Mini.format("Reload")) { act(player) { modules.reload(id) } },
                FormButton(Mini.format("Back")) { openModuleForm(player) },
            ),
        )
    }

    /**
     * A Bedrock flag toggle applies to the scope the flag is already listed under, exactly as the Java
     * page does. Choosing a *different* scope stays a command: it is the one action that would make a
     * form grow a dropdown for something an operator types once.
     */
    private fun openFlagForm(player: Player) {
        val rows = flags.scopes().flatMap { (scope, entries) -> entries.map { Triple(scope, it.key, it.value) } }
        if (rows.isEmpty()) {
            player.sendMessage(Mini.format("<off_white>No feature flags are registered."))
            return
        }
        bedrock.sendSimpleForm(
            player,
            title("Feature flags"),
            Mini.format("<off_white>Tap a flag to toggle it."),
            rows.map { (scope, feature, enabled) ->
                FormButton(Mini.format("$feature - ${if (enabled) "on" else "off"} ($scope)")) {
                    act(player) { flags.set(scope, feature, !enabled) }
                }
            },
        )
    }
}
