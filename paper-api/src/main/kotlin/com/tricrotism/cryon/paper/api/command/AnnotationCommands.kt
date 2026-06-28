package com.tricrotism.cryon.paper.api.command

import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import java.lang.reflect.Method
import java.lang.reflect.Parameter
import java.util.function.Predicate
import com.mojang.brigadier.Command as BrigadierCommand

/**
 * Registers [Command]-annotated classes onto a Paper Brigadier [Commands] registrar via reflection.
 * Each `@Subcommand` method becomes a node; the `CommandSender` parameter is injected, `@Arg` params
 * become Brigadier arguments (named via the annotation), and `@Arg(suggests = "method")` wires a
 * no-arg `Collection<String>` suggester. Uses Java reflection only — no kotlin-reflect.
 */
object AnnotationCommands {

    fun register(registrar: Commands, vararg handlers: Any) = handlers.forEach { register(registrar, it) }

    /**
     * Register [handler]. [available] is AND-ed into every node's access check, so a feature can pass
     * its own enabled-state (`::isEnabled`) and have the whole command tree become unavailable while
     * the module is disabled, then reappear on re-enable — re-evaluated per dispatch, no re-register.
     *
     * `@JvmOverloads` keeps the `register(Commands, Any)` JVM signature so feature jars built before
     * `available` was added still link (this is a published API — preserve binary compatibility).
     */
    @JvmOverloads
    fun register(registrar: Commands, handler: Any, available: () -> Boolean = { true }) {
        val type = handler.javaClass
        val command = type.getAnnotation(Command::class.java) ?: error("${type.name} is missing @Command")

        val root = Commands.literal(command.name)
        root.requires(guard(available, type.getAnnotation(Permission::class.java)?.value))

        for (method in type.methods) {
            val sub = method.getAnnotation(Subcommand::class.java) ?: continue
            addMethod(root, handler, method, sub.value, method.getAnnotation(Permission::class.java)?.value, available)
        }

        registrar.register(root.build(), command.description.ifEmpty { null }, command.aliases.toList())
    }

    /** Brigadier access predicate: the module must be [available] and the sender hold any [permission]. */
    private fun guard(available: () -> Boolean, permission: String?): Predicate<CommandSourceStack> =
        Predicate { source -> available() && (permission == null || source.sender.hasPermission(permission)) }

    private fun addMethod(
        root: ArgumentBuilder<CommandSourceStack, *>,
        handler: Any,
        method: Method,
        path: Array<out String>,
        permission: String?,
        available: () -> Boolean,
    ) {
        val executor = BrigadierCommand<CommandSourceStack> { ctx ->
            invoke(handler, method, ctx)
            BrigadierCommand.SINGLE_SUCCESS
        }

        val elements = ArrayList<ArgumentBuilder<CommandSourceStack, *>>()
        path.forEach { elements.add(Commands.literal(it)) }
        method.parameters.filter { it.isAnnotationPresent(Arg::class.java) }
            .forEach { elements.add(argNode(handler, it)) }

        if (elements.isEmpty()) {
            root.executes(executor)
            permission?.let { perm -> root.requires(guard(available, perm)) }
            return
        }

        var current = elements.last()
        current.executes(executor)
        permission?.let { perm -> current.requires(guard(available, perm)) }
        for (i in elements.size - 2 downTo 0) {
            current = elements[i].then(current)
        }
        root.then(current)
    }

    private fun argNode(handler: Any, param: Parameter): ArgumentBuilder<CommandSourceStack, *> {
        val arg = param.getAnnotation(Arg::class.java)
        val node = Commands.argument(arg.value, argType(param))
        if (arg.suggests.isNotEmpty()) {
            val suggester = handler.javaClass.getMethod(arg.suggests)
            node.suggests { _, builder ->
                @Suppress("UNCHECKED_CAST")
                (suggester.invoke(handler) as Collection<String>).forEach { builder.suggest(it) }
                builder.buildFuture()
            }
        }
        return node
    }

    private fun argType(param: Parameter): ArgumentType<*> = when (param.type) {
        Int::class.javaPrimitiveType, Int::class.javaObjectType -> IntegerArgumentType.integer()
        Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> BoolArgumentType.bool()
        String::class.java ->
            if (param.isAnnotationPresent(Greedy::class.java)) StringArgumentType.greedyString() else StringArgumentType.word()

        else -> StringArgumentType.word()
    }

    private fun invoke(handler: Any, method: Method, ctx: CommandContext<CommandSourceStack>) {
        val args = method.parameters.map { param ->
            val arg = param.getAnnotation(Arg::class.java)
            if (arg != null) resolveArg(param, arg.value, ctx) else ctx.source.sender
        }
        method.invoke(handler, *args.toTypedArray())
    }

    private fun resolveArg(param: Parameter, name: String, ctx: CommandContext<CommandSourceStack>): Any =
        when (param.type) {
            Int::class.javaPrimitiveType, Int::class.javaObjectType -> IntegerArgumentType.getInteger(ctx, name)
            Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> BoolArgumentType.getBool(ctx, name)
            else -> StringArgumentType.getString(ctx, name)
        }
}
