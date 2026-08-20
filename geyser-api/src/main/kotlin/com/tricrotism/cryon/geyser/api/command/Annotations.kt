package com.tricrotism.cryon.geyser.api.command

/**
 * Annotation command model for Geyser extensions, registered via [AnnotationCommands]. Mirrors the
 * Paper `paper.api.command` and Velocity `velocity.api.command` models so a command reads the same
 * on all three platforms.
 *
 * **The annotations are duplicated across the three platforms, not shared**, for the same reason
 * Paper's and Velocity's already are: each platform's command source and registration type differ
 * (`CommandSourceStack`, Velocity's `CommandSource`, Geyser's `CommandSource`), and no module may
 * depend on another platform's artifact. Keep the three models in step by hand.
 *
 * Geyser's command model is flat and is not Brigadier, so [AnnotationCommands] parses the path
 * literals and the arguments itself. See its KDoc for what that changes.
 *
 * ```
 * @Command("cryon", "Module manager")
 * @Permission("cryon.admin")
 * class GeyserCommands(private val modules: ModuleManager) {
 *     @Subcommand("reload") fun reload(source: CommandSource) { … }   // /cryon reload
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

/** Permission gate, on the class (whole command) or a single method. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Permission(val value: String)

/**
 * A command argument. [value] is the display name used in usage lines (required, Java doesn't retain
 * parameter names). [suggests], if set, names a public no-arg method returning `Collection<String>`;
 * Geyser offers no per-argument completion hook, so those values are used to name the accepted
 * choices when an argument is rejected. Supported parameter types: `String`, `Int`, `Boolean`.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Arg(val value: String, val suggests: String = "")

/** Marks a trailing `String` argument as greedy (consumes the rest of the input). */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Greedy
