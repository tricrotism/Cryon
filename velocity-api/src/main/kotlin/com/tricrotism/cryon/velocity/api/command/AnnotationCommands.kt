package com.tricrotism.cryon.velocity.api.command

import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.command.CommandManager
import com.velocitypowered.api.command.CommandSource
import java.lang.reflect.Method
import java.lang.reflect.Parameter
import java.util.function.Predicate
import com.mojang.brigadier.Command as BrigadierExecutor

/**
 * Registers [Command]-annotated classes onto Velocity's native Brigadier via reflection — the proxy
 * twin of the Paper `AnnotationCommands`. Each `@Subcommand` method becomes a node; the `CommandSource`
 * parameter is injected, `@Arg` params become Brigadier arguments (named via the annotation), and
 * `@Arg(suggests = "method")` wires a no-arg `Collection<String>` suggester. Java reflection only.
 *
 * An optional trailing argument is expressed as two same-path methods (one with the arg, one without);
 * Brigadier merges the same-named literals, so both `/cmd on` and `/cmd on <message>` dispatch.
 */
object AnnotationCommands {

    fun register(manager: CommandManager, vararg handlers: Any) = handlers.forEach { register(manager, it) }

    fun register(manager: CommandManager, handler: Any) {
        val type = handler.javaClass
        val command = type.getAnnotation(Command::class.java) ?: error("${type.name} is missing @Command")

        val root = BrigadierCommand.literalArgumentBuilder(command.name)
        root.requires(guard(type.getAnnotation(Permission::class.java)?.value))
        for (method in type.methods) {
            val sub = method.getAnnotation(Subcommand::class.java) ?: continue
            addMethod(root, handler, method, sub.value, method.getAnnotation(Permission::class.java)?.value)
        }

        val brigadier = BrigadierCommand(root.build())
        val meta = manager.metaBuilder(brigadier).aliases(*command.aliases).build()
        manager.register(meta, brigadier)
    }

    private fun guard(permission: String?): Predicate<CommandSource> =
        Predicate { source -> permission == null || source.hasPermission(permission) }

    private fun addMethod(
        root: ArgumentBuilder<CommandSource, *>,
        handler: Any,
        method: Method,
        path: Array<out String>,
        permission: String?,
    ) {
        method.isAccessible = true
        val binders: Array<(CommandContext<CommandSource>) -> Any?> = method.parameters.map { param ->
            val arg = param.getAnnotation(Arg::class.java)
            if (arg != null) argBinder(param, arg.value) else { ctx -> ctx.source }
        }.toTypedArray()

        val executor = BrigadierExecutor<CommandSource> { ctx ->
            val args = arrayOfNulls<Any?>(binders.size)
            for (i in binders.indices) args[i] = binders[i](ctx)
            method.invoke(handler, *args)
            BrigadierExecutor.SINGLE_SUCCESS
        }

        val elements = ArrayList<ArgumentBuilder<CommandSource, *>>()
        path.forEach { elements.add(BrigadierCommand.literalArgumentBuilder(it)) }
        method.parameters.filter { it.isAnnotationPresent(Arg::class.java) }
            .forEach { elements.add(argNode(handler, it)) }

        if (elements.isEmpty()) {
            root.executes(executor)
            permission?.let { root.requires(guard(it)) }
            return
        }

        var current = elements.last()
        current.executes(executor)
        permission?.let { current.requires(guard(it)) }
        for (i in elements.size - 2 downTo 0) {
            current = elements[i].then(current)
        }
        root.then(current)
    }

    private fun argNode(handler: Any, param: Parameter): ArgumentBuilder<CommandSource, *> {
        val arg = param.getAnnotation(Arg::class.java)

        @Suppress("UNCHECKED_CAST")
        val node = BrigadierCommand.requiredArgumentBuilder(arg.value, argType(param) as ArgumentType<Any>)
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

    private fun argBinder(param: Parameter, name: String): (CommandContext<CommandSource>) -> Any? =
        when (param.type) {
            Int::class.javaPrimitiveType, Int::class.javaObjectType -> { ctx ->
                IntegerArgumentType.getInteger(
                    ctx,
                    name
                )
            }

            Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> { ctx ->
                BoolArgumentType.getBool(
                    ctx,
                    name
                )
            }

            else -> { ctx -> StringArgumentType.getString(ctx, name) }
        }
}
