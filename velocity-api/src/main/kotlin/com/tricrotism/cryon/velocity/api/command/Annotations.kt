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


