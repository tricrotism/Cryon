package com.tricrotism.cryon.velocity.api.command

/**
 * Annotation command model for Velocity, registered onto Velocity's native Brigadier via
 * [AnnotationCommands]. Mirrors the Paper `paper.api.command` model so commands read the same on both
 * platforms; the source type here is Velocity's `CommandSource` rather than Paper's sender.
 *
 * ```
 * @Command("motd", "MOTD control")
 * @Permission("cryon.motd")
 * class MotdCommand(private val motd: Motd) {
 *     @Subcommand("reload") fun reload(source: CommandSource) { motd.reload() }   // /motd reload
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
