package com.tricrotism.cryon.paper.api.command

/**
 * Annotation command model registered onto Paper's native Brigadier via [AnnotationCommands], a
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
 *
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Command(val name: String, val description: String = "", vararg val aliases: String)


