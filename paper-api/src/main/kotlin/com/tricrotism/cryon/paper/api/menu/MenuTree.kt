package com.tricrotism.cryon.paper.api.menu

import com.tricrotism.cryon.paper.api.extension.toItem
import com.tricrotism.cryon.paper.api.scheduler.CryonDispatchers
import com.tricrotism.cryon.paper.api.scheduler.Schedulers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.invui.item.Item
import xyz.xenondevs.invui.window.Window
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

/**
 * One entry in a menu tree: either a [MenuBranch] that opens another page, or a [MenuLeaf] that does
 * something.
 *
 * Icons are resolved **per viewer** rather than stored, because the interesting menus are the ones
 * where the icon is not the same for everyone, a price that depends on a discount, a lock that
 * depends on a permission, a count that depends on the player's inventory.
 */
sealed interface MenuNode {

    // Stable within a parent. Used for lookups and breadcrumbs, never shown to players
    val id: String

    /**
     * What [viewer] sees in the slot. Called on their own thread, each time the page is drawn.
     */
    fun icon(viewer: Player): ItemStack

    /**
     * @return whether [viewer] sees this at all. Hidden nodes never occupy a slot.
     */
    fun visibleTo(viewer: Player): Boolean
}

/**
 * Where a branch's entries come from, one window at a time.
 *
 * **The window, not the whole list.** A branch backed by a `List` can afford to be filtered and
 * sliced per draw; a shop with ten thousand entries, an auction house or a leaderboard cannot. The
 * nodes would all have to exist before the first page could be shown, and every redraw would walk
 * them again. Asking for `[offset, offset + limit)` lets the source do the work its own way: a list
 * scans once with an early exit, a database adds `LIMIT`/`OFFSET`.
 *
 * **There is deliberately no `size`.** The menu asks for one entry more than it can display and uses
 * the overflow to decide whether the "next" arrow lights up, so a source never has to answer a
 * `COUNT`, the query that gets expensive on exactly the tables this exists for.
 *
 * Suspending, so a page may be a query. It runs on the viewer's own dispatcher, so hop to
 * `CryonDispatchers.Async` inside for I/O; whatever it returns is rendered back on their thread.
 */
fun interface MenuContent {

    suspend fun page(viewer: Player, offset: Int, limit: Int): List<MenuNode>

    companion object {

        /**
         * A fixed list, filtered by visibility as it is walked.
         *
         * One pass with an early exit and one allocation the size of the window, where the obvious
         * `filter { }.drop(n).take(m)` builds two intermediate lists per draw and visits every node
         * even to render the first page.
         */
        fun of(nodes: List<MenuNode>): MenuContent = MenuContent { viewer, offset, limit ->
            val out = ArrayList<MenuNode>(limit)
            var skipped = 0
            for (node in nodes) {
                if (!node.visibleTo(viewer)) continue
                if (skipped < offset) {
                    skipped++
                    continue
                }
                out += node
                if (out.size == limit) break
            }
            out
        }
    }
}

/**
 * A page. Its entries may themselves be branches, which is what makes the nesting unbounded.
 *
 * [pinned] is for the entries whose position is part of the design rather than a consequence of how
 * many siblings they have: a switch that governs the page, a notice, a filter that applies to the
 * list. A node in the flow moves every time the list above it grows and lands on a different page
 * when the list is long enough to page, which is exactly what you do not want from a control.
 */
class MenuBranch(
    override val id: String,
    val title: Component,
    private val iconProvider: (Player) -> ItemStack,
    val content: MenuContent,
    private val visible: (Player) -> Boolean = { true },
    val pinned: Map<Int, MenuNode> = emptyMap(),
) : MenuNode {

    /**
     * A branch over a fixed list of children, the shape the `branch { }` DSL builds.
     */
    constructor(
        id: String,
        title: Component,
        iconProvider: (Player) -> ItemStack,
        children: List<MenuNode>,
        visible: (Player) -> Boolean = { true },
        pinned: Map<Int, MenuNode> = emptyMap(),
    ) : this(id, title, iconProvider, MenuContent.of(children), visible, pinned)

    /**
     * The slots the flow may use, which is every content slot no pinned node is sitting in.
     *
     * Held rather than derived per draw because it also sets the page size: a page that pinned two of
     * its content slots shows two fewer entries, and the paging arrows have to agree with that or the
     * last entries of a list become unreachable.
     */
    internal val contentSlots: IntArray =
        if (pinned.isEmpty()) MenuTree.CONTENT_SLOTS
        else MenuTree.CONTENT_SLOTS.filter { it !in pinned }.toIntArray()

    init {
        pinned.keys.firstOrNull { it !in 0 until MenuTree.SLOT_COUNT || it in MenuTree.RESERVED_SLOTS }?.let {
            throw IllegalArgumentException(
                "Menu '$id' pins slot $it, which is out of range or one of the navigation slots " +
                        "${MenuTree.RESERVED_SLOTS.sorted()}"
            )
        }
    }

    override fun icon(viewer: Player): ItemStack = iconProvider(viewer)
    override fun visibleTo(viewer: Player): Boolean = visible(viewer)
}

/**
 * A leaf. [onClick] runs on the clicking player's own thread and may open another menu.
 */
class MenuLeaf(
    override val id: String,
    private val iconProvider: (Player) -> ItemStack,
    private val visible: (Player) -> Boolean = { true },
    val onClick: (Player) -> Unit,
) : MenuNode {
    override fun icon(viewer: Player): ItemStack = iconProvider(viewer)
    override fun visibleTo(viewer: Player): Boolean = visible(viewer)
}

/**
 * Build a [MenuBranch] whose children are declared in the block, the form the tree reads best in.
 *
 * ```
 * branch("shop", Mini.format("<gold>Shop"), Material.EMERALD) {
 *     leaf("dirt", Material.DIRT, "<off_white>Dirt") { player -> buy(player, Material.DIRT) }
 * }
 * ```
 */
fun branch(
    id: String,
    title: Component,
    icon: Material,
    name: Component = title,
    visible: (Player) -> Boolean = { true },
    children: MenuChildren.() -> Unit,
): MenuBranch {
    val declared = MenuChildren().apply(children)

    return MenuBranch(
        id,
        title,
        { icon.toItem().name(name).build() },
        declared.build(),
        visible,
        declared.pinned(),
    )
}

/**
 * Collects the children of a [branch]. Order is the order they were declared in.
 */
class MenuChildren internal constructor() {

    private val nodes = ArrayList<MenuNode>()
    private val pins = HashMap<Int, MenuNode>()

    fun add(node: MenuNode) {
        nodes += node
    }

    /**
     * Place [node] at a fixed inventory slot instead of in the flow, as [MenuBranch.pinned] describes.
     *
     * The slot is a raw index into the window, so anything outside the content area is fair game and
     * the border is the usual home for a control. The navigation slots are not: pinning one is refused
     * when the branch is built.
     */
    fun pin(slot: Int, node: MenuNode) {
        // a map would take the second quietly and the first control would simply not be there
        pins[slot]?.let {
            throw IllegalArgumentException("Slot $slot is pinned by both '${it.id}' and '${node.id}'")
        }

        pins[slot] = node
    }

    fun branch(
        id: String,
        title: Component,
        icon: Material,
        name: Component = title,
        visible: (Player) -> Boolean = { true },
        children: MenuChildren.() -> Unit,
    ) {
        add(com.tricrotism.cryon.paper.api.menu.branch(id, title, icon, name, visible, children))
    }

    fun leaf(
        id: String,
        icon: Material,
        name: Component,
        lore: List<Component> = emptyList(),
        visible: (Player) -> Boolean = { true },
        slot: Int? = null,
        onClick: (Player) -> Unit,
    ) = place(slot, MenuLeaf(id, { icon.toItem().name(name).lore(lore).build() }, visible, onClick))

    /**
     * For a leaf whose icon depends on the viewer or on live state.
     */
    fun leaf(
        id: String,
        icon: (Player) -> ItemStack,
        visible: (Player) -> Boolean = { true },
        slot: Int? = null,
        onClick: (Player) -> Unit,
    ) = place(slot, MenuLeaf(id, icon, visible, onClick))

    private fun place(slot: Int?, leaf: MenuLeaf) {
        if (slot == null) add(leaf) else pin(slot, leaf)
    }

    internal fun build(): List<MenuNode> = nodes.toList()
    internal fun pinned(): Map<Int, MenuNode> = pins.toMap()
}

/**
 * Nested, paginated chest menus of arbitrary depth. The shape a shop, a warp list, a cosmetics
 * browser and a settings screen all turn out to share.
 *
 * A tree is **data**: [MenuBranch]es containing [MenuNode]s, built once and opened many times. The
 * navigation, the back button, the paging and the thread discipline live here so a feature module
 * only has to describe its own nodes. Nesting is unbounded because a branch's children may be
 * branches; the back button walks the path the viewer actually took rather than a parent pointer, so
 * the same subtree can be reached from two places and still return to the right one.
 *
 * ```
 * val shop = branch("shop", Mini.format("<gold>Shop"), Material.EMERALD) {
 *     branch("blocks", Mini.format("Blocks"), Material.DIRT) {
 *         leaf("dirt", Material.DIRT) { player -> buy(player, Material.DIRT) }
 *     }
 * }
 * MenuTree.open(player, shop)
 * ```
 *
 * **Every page is drawn fresh for its viewer**, so icons reflect the state at the moment it opened
 * rather than at the moment the tree was built. Nothing is cached per player, so nothing has to be
 * invalidated when a balance or a permission changes, and nothing leaks when they log out.
 *
 * **A branch's entries come from a [MenuContent], and only the visible window is ever asked for.**
 * `branch { }` builds one over a fixed list, which is right for a settings screen; a shop or a
 * leaderboard passes its own and pages in SQL instead of materializing every row.
 *
 * [open] is safe to call from any thread and returns a [Session] the opener must close on module
 * disable, for the reason spelled out on [ConfirmMenu.Dialog]: an open window holds this module's
 * click handlers, and one left open through a hot-unload strands the classloader behind them.
 */
object MenuTree {

    // Row layout. `#` filler, `x` content, `<`/`>` paging, `b` back.
    //
    // Parsed once by InvUI per page build; the array itself is shared and never mutated
    private val STRUCTURE = arrayOf(
        "# # # # # # # # #",
        "# x x x x x x x #",
        "# x x x x x x x #",
        "# x x x x x x x #",
        "# x x x x x x x #",
        "# < # # b # # > #",
    )

    // The `x` slots, as inventory indices.
    //
    // Content is placed by index after the page is built rather than bound to the `x` character:
    // binding an ingredient maps *every* occurrence of that character to the same item, so a loop
    // over the children would leave twenty-eight copies of whichever one bound last
    internal val CONTENT_SLOTS: IntArray =
        (1..4).flatMap { row -> (1..7).map { column -> row * 9 + column } }.toIntArray()

    // The structure with its spacing removed, one character per slot, which is how both of the
    // tables below read positions off it rather than repeating the layout as numbers
    private val KEYS: String = STRUCTURE.joinToString("") { row -> row.filterNot(Char::isWhitespace) }

    /**
     * How many slots a page has, so a caller taking a slot from an operator can bound the value it
     * accepts rather than hardcoding the window size.
     */
    val SLOT_COUNT: Int = KEYS.length

    /**
     * The paging and back slots, which [MenuBranch] refuses to pin over.
     *
     * Public because the caller taking a slot from a config file is the one that can do something
     * sensible about a bad one: fall back to its own default and carry on, where the branch can only
     * refuse to build. Replacing a navigation button would stand the viewer on the page for good.
     */
    val RESERVED_SLOTS: Set<Int> =
        KEYS.indices.filterTo(HashSet()) { KEYS[it] == '<' || KEYS[it] == '>' || KEYS[it] == 'b' }

    /**
     * Open [root] for [player]. Safe from any thread; the window itself is built on their own
     * scheduler because that is where inventory work belongs.
     */
    fun open(player: Player, root: MenuBranch, scope: CoroutineScope): Session {
        val session = Session(player, scope)
        session.navigate(root, page = 0, pushPath = true)
        return session
    }

    // Copy-on-write: read on every page draw and every click, written twice in a server's life
    private val observers = CopyOnWriteArrayList<MenuObserver>()

    /**
     * Watches every menu in every feature. Nothing in the core consumes this; it exists so one
     * registration covers screens the observer has never heard of. A server with nothing registered
     * pays one field read per draw and per click.
     *
     * @return the handle that stops it
     */
    fun observe(observer: MenuObserver): AutoCloseable {
        observers += observer
        return AutoCloseable { observers -= observer }
    }

    /**
     * An observer that throws is a bug in the observer, and must not cost the player their menu, so it
     * is caught and the remaining observers still run. [what] names the hook for the log line.
     */
    private inline fun fire(what: String, block: (MenuObserver) -> Unit) {
        if (observers.isEmpty()) return

        observers.forEach { observer ->
            try {
                block(observer)
            } catch (e: Exception) {
                Bukkit.getLogger().log(Level.WARNING, "Menu observer failed on $what", e)
            }
        }
    }

    /**
     * One player's walk through a tree.
     *
     * Holds the path taken rather than a pointer into the tree, which is what lets the back button be
     * correct when a subtree is reachable from more than one parent. The path is per-session state,
     * so two players browsing the same tree never see each other's position, and it dies with the
     * session rather than living in a map keyed by player, which would need a quit listener to avoid
     * becoming a leak.
     */
    class Session internal constructor(
        private val player: Player,
        private val scope: CoroutineScope,
    ) : AutoCloseable {

        private val path = ArrayDeque<MenuBranch>()

        @Volatile
        private var window: Window? = null
        // the page last drawn, so a refresh redraws that one rather than starting the branch over
        @Volatile
        private var shownPage = 0

        // Set while a navigation is replacing the window, so the close handler ignores that close
        private val navigating = AtomicBoolean(false)

        private val closed = AtomicBoolean(false)

        /**
         * Redraw the page the viewer is on, leaving their place in the tree alone.
         *
         * For the click that changes what its own page says: a settings toggle, a purchase that moves
         * a balance. Re-opening the tree instead is correct but costs the player their position every
         * time they change one of several things on the same page, and a menu that throws you back to
         * the front on every click is the thing players complain about.
         *
         * The path is untouched, so back still goes where it went before, and so is the page number,
         * so a toggle on page three stays on page three. Safe from any thread. A no-op on a closed
         * session, and on one whose window has not been drawn yet.
         */
        fun refresh() {
            if (closed.get()) return

            scope.launch(CryonDispatchers.entity(player)) {
                val current = path.lastOrNull() ?: return@launch

                show(current, shownPage, pushPath = false)
            }
        }

        /**
         * Draw [branch] on the viewer's own thread, resolving the page as it goes.
         *
         * Launched rather than awaited because every caller is a click handler or a scheduler
         * callback. The dispatcher is the viewer's, so the window is fetched and the InvUI window
         * built on the thread that owns them, and a suspending content source resumes back there.
         */
        internal fun navigate(branch: MenuBranch, page: Int, pushPath: Boolean) {
            if (closed.get()) return
            scope.launch(CryonDispatchers.entity(player)) { show(branch, page, pushPath) }
        }

        private suspend fun show(branch: MenuBranch, page: Int, pushPath: Boolean) {
            if (closed.get()) return
            if (pushPath) path.addLast(branch)

            val current = page.coerceAtLeast(0)
            val slots = branch.contentSlots
            val size = slots.size
            val fetched = branch.content.page(player, current * size, size + 1)
            val hasNext = fetched.size > size
            val shown = if (hasNext) fetched.subList(0, size) else fetched

            if (shown.isEmpty() && current > 0) return show(branch, current - 1, pushPath = false)

            shownPage = current

            val gui = Gui.builder()
                .setStructure(*STRUCTURE)
                .addIngredient('#', filler())
                .addIngredient('b', backButton())
                .addIngredient('<', pageButton(branch, current - 1, current > 0, PREVIOUS_LABEL))
                .addIngredient('>', pageButton(branch, current + 1, hasNext, NEXT_LABEL))
                .addIngredient('x', empty())
                .build()

            val breadcrumb = breadcrumb()
            shown.forEachIndexed { index, node ->
                gui.setItem(slots[index], nodeItem(breadcrumb, node))
            }

            // after the flow, so a pinned control is never the thing a long list paints over
            for ((slot, node) in branch.pinned) {
                if (node.visibleTo(player)) gui.setItem(slot, nodeItem(breadcrumb, node))
            }

            val previous = window
            navigating.set(true)
            val opened = Window.builder()
                .setViewer(player)
                .setTitle(branch.title)
                .setUpperGui(gui)
                .addCloseHandler { if (!navigating.getAndSet(false)) close() }
                .build()
            window = opened
            opened.open()
            previous?.close()
            navigating.set(false)
            if (closed.get()) opened.close()

            // After open(), not before: this reports what the player was actually shown, and the early
            // returns above are pages that were resolved and then abandoned
            fire("open") { it.opened(player.uniqueId, breadcrumb, current) }
        }

        /**
         * Built from the walked path rather than from the branch alone because [MenuNode.id] is only
         * unique within its parent, so two unrelated pages both named `page` would otherwise be one row
         * wherever these are counted.
         *
         * @return the path of node ids to the page being drawn, `shop/blocks`
         */
        private fun breadcrumb(): String = path.joinToString("/") { it.id }

        private fun nodeItem(menu: String, node: MenuNode): Item = Item.builder()
            .setItemProvider(node.icon(player))
            .addClickHandler { _ ->
                fire("click") { it.clicked(player.uniqueId, menu, node.id) }
                when (node) {
                    is MenuBranch -> {
                        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.6f, 1.4f)
                        navigate(node, page = 0, pushPath = true)
                    }

                    is MenuLeaf -> {
                        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.6f, 1.2f)
                        Schedulers.entity(player) { node.onClick(player) }
                    }
                }
            }
            .build()

        private fun backButton(): Item = Item.builder()
            .setItemProvider(
                Material.ARROW.toItem()
                    .name(if (path.size > 1) BACK_LABEL else CLOSE_LABEL)
                    .build()
            )
            .addClickHandler { _ ->
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.6f, 0.9f)
                path.removeLastOrNull()
                val parent = path.lastOrNull()
                if (parent == null) Schedulers.entity(player) { close() }
                else navigate(parent, page = 0, pushPath = false)
            }
            .build()

        private fun pageButton(
            branch: MenuBranch,
            target: Int,
            enabled: Boolean,
            label: Component,
        ): Item {
            val icon = if (enabled) Material.SPECTRAL_ARROW else Material.GRAY_STAINED_GLASS_PANE
            return Item.builder()
                .setItemProvider(icon.toItem().name(label).build())
                .addClickHandler { _ ->
                    if (!enabled) return@addClickHandler
                    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.6f, 1.1f)
                    navigate(branch, target, pushPath = false)
                }
                .build()
        }

        /**
         * Placeholder for an unused content slot; overwritten by [show] wherever a node lands.
         */
        private fun empty(): Item = Item.builder()
            .setItemProvider(ItemStack(Material.AIR))
            .build()

        private fun filler(): Item = Item.builder()
            .setItemProvider(Material.BLACK_STAINED_GLASS_PANE.toItem().name(Component.empty()).build())
            .build()

        /**
         * Take the menu down. Safe from any thread, before it opens, and more than once.
         */
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            val opened = window ?: return
            window = null
            Schedulers.entity(player) { opened.close() }
        }
    }

    private val PREVIOUS_LABEL: Component = Component.text("Previous")
    private val NEXT_LABEL: Component = Component.text("Next")
    private val BACK_LABEL: Component = Component.text("Back")
    private val CLOSE_LABEL: Component = Component.text("Close")
}
