package com.tricrotism.cryon.geyser.api.command

import com.tricrotism.cryon.common.locale.Messages
import com.tricrotism.cryon.common.text.CommonMessages
import com.tricrotism.cryon.geyser.api.resolvedLocale
import com.tricrotism.cryon.geyser.api.sendMessage
import com.tricrotism.cryon.geyser.api.sendNoPermission
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.geysermc.geyser.api.command.CommandExecutor
import org.geysermc.geyser.api.command.CommandSource
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCommandsEvent
import org.geysermc.geyser.api.extension.Extension
import java.lang.reflect.Method
import java.lang.reflect.Parameter
import org.geysermc.geyser.api.command.Command as GeyserCommand

/**
 * Builds [Command]-annotated classes into Geyser commands by reflection. The Geyser twin of the Paper
 * and Velocity `AnnotationCommands`, with one structural difference that shapes the whole class:
 *
 * **Geyser's command model is flat and is not Brigadier.** A command is a name and one executor
 * handed the raw `Array<String>`; there is no argument parser, no node tree and no per-argument
 * completion hook. So this layer does the work Brigadier does on the other two platforms: it matches
 * the leading `@Subcommand` literals against the incoming args, binds what is left to the method's
 * `@Arg` parameters by declared type, and answers a mismatch with a usage line of its own.
 *
 * `Command.Builder.subCommands(…)` is deliberately not called: on 2.11.2 it is deprecated for removal
 * and documented as having no effect, so feeding it the paths would buy a compile warning and nothing
 * else. The only completion surface that reaches a player is therefore the candidate list this class
 * prints on a mismatch, and that list is filtered against what was typed.
 *
 * Longer paths are matched first, so `@Subcommand("flag", "status")` wins over `@Subcommand("flag")`
 * with a trailing argument. An optional trailing argument is still two same-path methods (one with
 * the `@Arg`, one without): the arity check picks whichever fits the arg count.
 *
 * Every message it emits goes through [Messages], so a bundle can translate it; the fallbacks below
 * are the reference English.
 */
object AnnotationCommands {

    private const val KEY_USAGE = "cryon.command.usage"
    private const val KEY_UNKNOWN = "cryon.command.unknown_subcommand"
    private const val KEY_INVALID = "cryon.command.invalid_argument"
    private const val KEY_CHOICES = "cryon.command.choices"

    private const val USAGE_FALLBACK = "<off_white>Usage: <highlight><usage></highlight>"
    private const val UNKNOWN_FALLBACK = "<off_white>Unknown subcommand. Did you mean: <highlight><choices></highlight>"
    private const val INVALID_FALLBACK =
        "<off_white>Invalid value for <highlight><arg></highlight>: <highlight><value></highlight>"
    private const val CHOICES_FALLBACK = "<off_white>Expected one of: <highlight><choices></highlight>"

    /**
     * Build [handlers] and register each with the Geyser command lifecycle event.
     */
    fun register(event: GeyserDefineCommandsEvent, extension: Extension, vararg handlers: Any) =
        handlers.forEach { event.register(build(extension, it)) }

    /**
     * Build one `@Command`-annotated [handler] into a Geyser command owned by [extension]. Separate
     * from [register] because the loader may want the built command before the lifecycle event fires.
     */
    fun build(extension: Extension, handler: Any): GeyserCommand {
        val type = handler.javaClass
        val annotation = type.getAnnotation(Command::class.java) ?: error("${type.name} is missing @Command")
        val rootPermission = type.getAnnotation(Permission::class.java)?.value

        val routes = type.methods.mapNotNull { method ->
            method.getAnnotation(Subcommand::class.java)?.let { route(method, it.value, rootPermission) }
        }.sortedByDescending { it.path.size }
        require(routes.isNotEmpty()) { "${type.name} declares no @Subcommand methods" }

        val builder = GeyserCommand.builder<CommandSource>(extension)
            .source(CommandSource::class.java)
            .name(annotation.name)
            .description(annotation.description)
            .aliases(annotation.aliases.toList())
            .executor(Dispatcher(annotation.name, handler, routes))
        rootPermission?.let { builder.permission(it) }
        return builder.build()
    }

    private fun route(method: Method, path: Array<out String>, rootPermission: String?): Route {
        method.isAccessible = true
        val args = method.parameters.filter { it.isAnnotationPresent(Arg::class.java) }
        return Route(
            method = method,
            path = path.toList(),
            permission = method.getAnnotation(Permission::class.java)?.value ?: rootPermission,
            args = args,
            greedy = args.lastOrNull()?.isAnnotationPresent(Greedy::class.java) == true,
        )
    }

    private class Route(
        val method: Method,
        val path: List<String>,
        val permission: String?,
        val args: List<Parameter>,
        val greedy: Boolean,
    ) {
        fun matchesPath(input: Array<String>): Boolean =
            input.size >= path.size && path.indices.all { path[it].equals(input[it], ignoreCase = true) }

        fun matchesArity(input: Array<String>): Boolean {
            val rest = input.size - path.size
            return if (greedy) rest >= args.size else rest == args.size
        }

        fun usage(command: String): String = buildString {
            append('/').append(command)
            path.forEach { append(' ').append(it) }
            args.forEach { param ->
                val name = param.getAnnotation(Arg::class.java).value
                append(" <").append(name).append(if (param.isAnnotationPresent(Greedy::class.java)) "…>" else ">")
            }
        }
    }

    private class Dispatcher(
        private val command: String,
        private val handler: Any,
        private val routes: List<Route>,
    ) : CommandExecutor<CommandSource> {

        override fun execute(source: CommandSource, command: GeyserCommand, args: Array<String>) {
            val route = routes.firstOrNull { it.matchesPath(args) && it.matchesArity(args) }
            if (route == null) {
                reportUsage(source, args)
                return
            }
            if (route.permission != null && !source.hasPermission(route.permission)) {
                source.sendNoPermission()
                return
            }

            val rest = args.drop(route.path.size)
            val bound = HashMap<Parameter, Any>(route.args.size)
            route.args.forEachIndexed { index, param ->
                val raw = if (route.greedy && index == route.args.size - 1) {
                    rest.subList(index, rest.size).joinToString(" ")
                } else {
                    rest[index]
                }
                bound[param] = coerce(param, raw) ?: run {
                    reportInvalid(source, param, raw)
                    return
                }
            }

            val call = route.method.parameters.map { param ->
                if (param.isAnnotationPresent(Arg::class.java)) bound[param] else source
            }
            route.method.invoke(handler, *call.toTypedArray())
        }

        private fun coerce(param: Parameter, raw: String): Any? = when (param.type) {
            Int::class.javaPrimitiveType, Int::class.javaObjectType -> raw.toIntOrNull()
            Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> raw.toBooleanStrictOrNull()
            else -> raw
        }

        private fun reportInvalid(source: CommandSource, param: Parameter, raw: String) {
            val arg = param.getAnnotation(Arg::class.java)
            source.sendMessage(
                CommonMessages.message(
                    Messages.getOr(
                        source.resolvedLocale(), KEY_INVALID, INVALID_FALLBACK,
                        Placeholder.unparsed("arg", arg.value),
                        Placeholder.unparsed("value", raw),
                    )
                )
            )
            val choices = suggestions(arg).filter { it.startsWith(raw, ignoreCase = true) }
            if (choices.isNotEmpty()) source.sendMessage(choices(source, choices))
        }

        /**
         * Answer a mismatch with the literals that would have matched, **filtered by what was
         * actually typed**. Geyser hands us the raw args, so an unfiltered candidate list would put
         * every subcommand on screen no matter how much of one the player had already written.
         */
        private fun reportUsage(source: CommandSource, args: Array<String>) {
            val permitted = routes.filter { it.permission == null || source.hasPermission(it.permission) }
            val depth = permitted.maxOfOrNull { route ->
                route.path.indices.takeWhile { it < args.size && route.path[it].equals(args[it], ignoreCase = true) }
                    .count()
            } ?: 0

            val typed = args.getOrNull(depth).orEmpty()
            val next = permitted
                .filter { it.path.size > depth && it.path.take(depth).matchesPrefix(args) }
                .map { it.path[depth] }
                .distinct()
                .filter { it.startsWith(typed, ignoreCase = true) }

            if (next.isNotEmpty() && typed.isNotEmpty()) {
                source.sendMessage(
                    CommonMessages.message(
                        Messages.getOr(
                            source.resolvedLocale(), KEY_UNKNOWN, UNKNOWN_FALLBACK,
                            Placeholder.unparsed("choices", next.joinToString(", ")),
                        )
                    )
                )
                return
            }

            val relevant = permitted.filter { it.path.take(depth).matchesPrefix(args) }.ifEmpty { permitted }
            relevant.sortedBy { it.path.size }.forEach { route ->
                source.sendMessage(
                    CommonMessages.message(
                        Messages.getOr(
                            source.resolvedLocale(), KEY_USAGE, USAGE_FALLBACK,
                            Placeholder.unparsed("usage", route.usage(command)),
                        )
                    )
                )
            }
        }

        private fun choices(source: CommandSource, choices: List<String>) =
            CommonMessages.message(
                Messages.getOr(
                    source.resolvedLocale(), KEY_CHOICES, CHOICES_FALLBACK,
                    Placeholder.unparsed("choices", choices.joinToString(", ")),
                )
            )

        @Suppress("UNCHECKED_CAST")
        private fun suggestions(arg: Arg): Collection<String> {
            if (arg.suggests.isEmpty()) return emptyList()
            return runCatching {
                handler.javaClass.getMethod(arg.suggests).invoke(handler) as Collection<String>
            }.getOrDefault(emptyList())
        }

        private fun List<String>.matchesPrefix(args: Array<String>): Boolean =
            indices.all { it < args.size && this[it].equals(args[it], ignoreCase = true) }
    }
}
