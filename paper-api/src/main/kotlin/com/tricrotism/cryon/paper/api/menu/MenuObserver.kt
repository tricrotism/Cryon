package com.tricrotism.cryon.paper.api.menu

import java.util.*

/**
 * Notified when a player opens a menu page or clicks something in one.
 *
 * Registered through [MenuTree.observe], and fired from the two places every menu in every feature
 * already passes through: the page draw and the click dispatch. That is the whole reason this is in
 * the core rather than in each menu - an observer here sees menus written by modules that know
 * nothing about it, including ones written later, and nobody has to remember to instrument a screen.
 *
 * What it answers: which menus get opened and abandoned without a click, and which entries nobody
 * ever presses. The second is the cheapest content decision available, because a button untouched
 * for a month costs nothing to remove and makes the menu easier to read for everyone else.
 *
 * `menu` is the breadcrumb path of node ids (`shop/blocks`), not the leaf id alone: [MenuNode.id] is
 * only unique within its parent, so two unrelated pages both called `page` would otherwise merge into
 * one row.
 *
 * **Called on the viewer's own thread.** Record and return: no blocking, no inventory work, nothing
 * that needs a different thread.
 */
interface MenuObserver {

    /**
     * A page was drawn and put on the viewer's screen. Paging counts, because it is a new page.
     */
    fun opened(player: UUID, menu: String, page: Int) {}

    /**
     * An entry was clicked. [node] is the entry's own id, within [menu].
     */
    fun clicked(player: UUID, menu: String, node: String) {}
}
