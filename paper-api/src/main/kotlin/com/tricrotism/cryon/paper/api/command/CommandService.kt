package com.tricrotism.cryon.paper.api.command

/**
 * The core-owned command registry, shared through the `ServiceRegistry`. Modules contribute their
 * `@Command` handlers here (via [com.tricrotism.cryon.paper.api.PaperModule.registerCommands]); the
 * core registers everything through a single `COMMANDS` lifecycle handler at boot, and splices
 * anything contributed *after* that window straight into the live command dispatcher. That removes
 * the old runtime caveat: a module hot-loaded, `/cryon load`ed, or `reload-api`'d gets its commands
 * without a server restart.
 *
 * It also keeps the reflected [describe] view of every owner's commands, so `/cryon info <id>` can
 * list a module's commands, aliases, and usages.
 *
 * Registered once by the core; resolve with `services.get`/`find(CommandService::class)`. Main-thread.
 */
interface CommandService {

    /**
     * Contribute [handlers] (each an `@Command`-annotated object) under [owner] (a module id, or the
     * core), gated on [available] so the tree is hidden while the owner is disabled and reappears when
     * re-enabled. Registered at the next boot flush if the server is still starting, else spliced into
     * the live dispatcher immediately.
     */
    fun register(owner: String, available: () -> Boolean, handlers: List<Any>)

    /**
     * Contribute [handlers] as **branches of a shared root** rather than as roots of their own.
     *
     * [register] gives an owner exclusive title to a root literal: registering `foo` drops whatever
     * `foo` was there, and [unregister] takes the whole root away. That's right for a module's own
     * command, and wrong for a namespace several modules are meant to live under — the second
     * contributor would silently evict the first.
     *
     * Here the root literal is shared. Each handler's `@Command` name picks the root to hang under,
     * its `@Subcommand` methods become branches of it, and the root is created on first use and
     * removed once its last branch goes. So a module declaring `@Command("int")` with
     * `@Subcommand("mymodule", "dothing")` contributes `/int mymodule dothing` and disturbs nothing
     * else under `/int`.
     *
     * Two constraints follow from the root being shared:
     * - **A root `@Subcommand` (empty path) is ignored.** The bare `/int` belongs to no single
     *   contributor, so nobody gets to define what it does.
     * - **Aliases are ignored**, for the same reason.
     *
     * Permission still comes from each handler's own `@Permission`, so contributors don't have to
     * agree on one gate. The shared root itself is visible to everyone; Brigadier hides the branches
     * a sender can't run.
     */
    fun registerBranch(owner: String, available: () -> Boolean, handlers: List<Any>)

    /**
     * Remove every command [owner] contributed from the live dispatcher (used when a jar unloads) —
     * both whole roots from [register] and individual branches from [registerBranch].
     */
    fun unregister(owner: String)

    /** Re-push command trees to online players (after an enable/disable changes what's visible). */
    fun refresh()

    /** The reflected commands [owner] contributed, for admin listings. Empty if it has none. */
    fun describe(owner: String): List<CommandDescriptor>
}

/** A reflected, display-ready view of one `@Command` class. */
data class CommandDescriptor(
    val name: String,
    val description: String,
    val aliases: List<String>,
    val permission: String?,
    /** One line per handler method, e.g. `/f`, `/f create <name>`, `/f claim`. */
    val usages: List<String>,
)
