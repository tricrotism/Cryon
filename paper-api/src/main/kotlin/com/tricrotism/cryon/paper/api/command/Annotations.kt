package com.tricrotism.cryon.paper.api.command

/**
 * Annotation command model registered onto Paper's native Brigadier via [AnnotationCommands] — a
 * thin, Cloud-free layer that works on bleeding-edge Paper.
 *
 * ```
 * @Command("cryon", "Module manager")
 * @Permission("cryon.admin")
 * class ModuleCommands(private val modules: ModuleManager) {
 *     @Subcommand fun overview(sender: CommandSender) = list(sender)          // /cryon
 *     @Subcommand("enable")
 *     fun enable(sender: CommandSender, @Arg("id", suggests = "ids") id: String) { … }  // /cryon enable <id>
 *     fun ids(): Collection<String> = modules.ids()                           // suggester
 * }
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Command(val name: String, val description: String = "", vararg val aliases: String)

/** A handler method. Empty path = the root command; otherwise nested literals. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Subcommand(vararg val value: String)

/** Permission gate — on the class (whole command) or a single method. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Permission(val value: String)

/**
 * A command argument. [value] is the Brigadier argument name (required — Java doesn't retain
 * parameter names). [suggests], if set, names a public no-arg method returning `Collection<String>`
 * used for tab completion. Supported parameter types: `String`, `Int`, `Boolean`.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Arg(val value: String, val suggests: String = "")

/** Marks a trailing `String` argument as greedy (consumes the rest of the input). */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Greedy
