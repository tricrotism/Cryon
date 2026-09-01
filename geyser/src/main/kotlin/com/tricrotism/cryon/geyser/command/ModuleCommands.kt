package com.tricrotism.cryon.geyser.command

import com.tricrotism.cryon.common.module.ModuleManager
import com.tricrotism.cryon.geyser.GeyserModuleLoader
import com.tricrotism.cryon.geyser.api.command.Arg
import com.tricrotism.cryon.geyser.api.command.Command
import com.tricrotism.cryon.geyser.api.command.Permission
import com.tricrotism.cryon.geyser.api.command.Subcommand
import com.tricrotism.cryon.geyser.api.sendLocalized
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.geysermc.geyser.api.command.CommandSource
import java.io.File

/**
 * `/cryon modules ...`: the module surface Paper has, for the modules Geyser runs. Same verbs, same
 * meanings, so an operator is not learning a third vocabulary. Geyser namespaces every extension
 * command under the extension root, so the root here is `modules` and the verbs hang off it.
 *
 * Every verb that mutates module state runs through [GeyserModuleLoader.submit]: command dispatch
 * and the hot-reload watcher are two different threads, and the loader and manager are single-writer.
 *
 * `load`, `scan` and `reload-api` bring a hot-loaded module's services and listeners up but not its
 * commands, which Geyser can only accept during startup (see [GeyserModuleLoader]).
 */
@Command("modules", "Cryon module manager")
@Permission("cryon.admin")
class ModuleCommands(
    private val modules: ModuleManager,
    private val loader: GeyserModuleLoader,
) {

    @Subcommand
    fun usage(source: CommandSource) = source.sendLocalized("cryon.modules.usage")

    @Subcommand("list")
    fun list(source: CommandSource) {
        val states = modules.states()
        source.sendLocalized("cryon.modules.list.header", Placeholder.unparsed("count", states.size.toString()))
        if (states.isEmpty()) {
            source.sendLocalized("cryon.modules.list.empty")
            return
        }
        for ((id, state) in states) {
            source.sendLocalized(
                "cryon.modules.list.entry",
                Placeholder.unparsed("indent", if (modules.parentOf(id) == null) "" else "  "),
                Placeholder.unparsed("id", id),
                Placeholder.unparsed("state", state.name),
            )
        }
    }

    @Subcommand("info")
    fun info(source: CommandSource, @Arg("id", suggests = "moduleIds") id: String) {
        val state = modules.state(id) ?: return unknown(source, id)
        source.sendLocalized(
            "cryon.modules.info",
            Placeholder.unparsed("id", id),
            Placeholder.unparsed("state", state.name),
            Placeholder.unparsed("parent", modules.parentOf(id) ?: "-"),
        )
        val declared = modules.dependenciesOf(id)
        if (declared.isEmpty()) return
        source.sendLocalized(
            "cryon.modules.info.dependencies",
            Placeholder.unparsed(
                "dependencies",
                declared.joinToString(", ") { "${it.description}${if (it.hard) "" else " (soft)"}" },
            ),
        )
    }

    @Subcommand("enable")
    fun enable(source: CommandSource, @Arg("id", suggests = "moduleIds") id: String) = loader.submit {
        if (!modules.has(id)) return@submit unknown(source, id)
        if (modules.enable(id)) {
            modules.postLoad(id)
            source.sendLocalized("cryon.modules.enabled", Placeholder.unparsed("id", id))
        } else {
            source.sendLocalized("cryon.modules.failed", Placeholder.unparsed("id", id))
        }
    }

    @Subcommand("disable")
    fun disable(source: CommandSource, @Arg("id", suggests = "moduleIds") id: String) = loader.submit {
        if (!modules.has(id)) return@submit unknown(source, id)
        val key = if (modules.disable(id)) "cryon.modules.disabled" else "cryon.modules.failed"
        source.sendLocalized(key, Placeholder.unparsed("id", id))
    }

    @Subcommand("reload")
    fun reload(source: CommandSource, @Arg("id", suggests = "moduleIds") id: String) = loader.submit {
        if (!modules.has(id)) return@submit unknown(source, id)
        val key = if (modules.reload(id)) "cryon.modules.reloaded" else "cryon.modules.failed"
        source.sendLocalized(key, Placeholder.unparsed("id", id))
    }

    @Subcommand("load")
    fun load(source: CommandSource, @Arg("jar", suggests = "loadableJars") jar: String) = loader.submit {
        val enabled = loader.loadJar(File(loader.modulesDir, jar))
        source.sendLocalized(
            "cryon.modules.loaded",
            Placeholder.unparsed("jar", jar),
            Placeholder.unparsed("count", enabled.size.toString()),
        )
    }

    @Subcommand("unload")
    fun unload(source: CommandSource, @Arg("id", suggests = "moduleIds") id: String) = loader.submit {
        val removed = loader.unloadModule(id) ?: return@submit unknown(source, id)
        source.sendLocalized(
            "cryon.modules.unloaded",
            Placeholder.unparsed("id", id),
            Placeholder.unparsed("count", removed.size.toString()),
        )
    }

    @Subcommand("scan")
    fun scan(source: CommandSource) = loader.submit {
        val enabled = loader.loadNew()
        source.sendLocalized("cryon.modules.scanned", Placeholder.unparsed("count", enabled.size.toString()))
    }

    @Subcommand("reload-api")
    fun reloadApi(source: CommandSource) = loader.submit {
        val enabled = loader.reloadApi()
        source.sendLocalized("cryon.modules.api_reloaded", Placeholder.unparsed("count", enabled.size.toString()))
    }

    /**
     * Suggester for module-id arguments.
     */
    @Suppress("unused")
    fun moduleIds(): Collection<String> = modules.ids()

    /**
     * Suggester for `/cryon load`: jars sitting in modules/ that aren't loaded yet.
     */
    @Suppress("unused")
    fun loadableJars(): Collection<String> = loader.loadableJarNames()

    private fun unknown(source: CommandSource, id: String) =
        source.sendLocalized("cryon.modules.unknown", Placeholder.unparsed("id", id))
}
