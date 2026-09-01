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

