package com.tricrotism.cryon.module

import com.tricrotism.cryon.common.module.remote.MavenRepository
import com.tricrotism.cryon.common.module.remote.RemoteArtifact
import com.tricrotism.cryon.common.module.remote.RemoteModules
import org.bukkit.configuration.file.FileConfiguration
import org.slf4j.Logger
import java.io.File

/**
 * Reads the `remote:` block into a [RemoteModules], or answers null when the feature is off or
 * configured into something that could not work.
 *
 * Every rejection is logged with the entry that caused it and the rest of the list still loads, so
 * one fat-fingered coordinate costs that artifact and nothing else. Returning null for an empty
 * result is what keeps the poller from starting, which matters because a timer that can only ever
 * find nothing is worse than no timer: it looks like the feature is working.
 */
object RemoteModuleConfig {

    fun build(config: FileConfiguration, modulesDir: File, dataFolder: File, log: Logger): RemoteModules? {
        if (!config.getBoolean("remote.enabled", false)) return null

        val repositories = repositories(config, log)
        if (repositories.isEmpty()) {
            log.warn("remote.enabled is true but no usable repository is configured; not polling")
            return null
        }

        val artifacts = artifacts(config, repositories.keys, log)
        if (artifacts.isEmpty()) {
            log.warn("remote.enabled is true but no usable artifact is configured; not polling")
            return null
        }

        log.info(
            "Remote modules: {} artifact(s) across {} repositor(ies)",
            artifacts.size,
            repositories.size,
        )
        return RemoteModules(repositories, artifacts, modulesDir, File(dataFolder, STATE_FILE), log)
    }

    private fun repositories(config: FileConfiguration, log: Logger): Map<String, MavenRepository> {
        val section = config.getConfigurationSection("remote.repositories") ?: return emptyMap()
        return section.getKeys(false).mapNotNull { name ->
            val url = section.getString("$name.url").orEmpty()
            if (url.isBlank()) {
                log.warn("Remote repository '{}' has no url; skipped", name)
                return@mapNotNull null
            }
            val repository = MavenRepository.of(
                name = name,
                url = url,
                configUsername = section.getString("$name.username"),
                configPassword = section.getString("$name.password"),
            )
            if (!repository.requiresAuth) {
                log.info(
                    "Remote repository '{}' has no credentials; set {} and {} if it is private",
                    name,
                    MavenRepository.envKey(name, "USERNAME"),
                    MavenRepository.envKey(name, "PASSWORD"),
                )
            }
            name to repository
        }.toMap()
    }

    private fun artifacts(
        config: FileConfiguration,
        known: Set<String>,
        log: Logger,
    ): List<RemoteArtifact> = config.getMapList("remote.artifacts").mapIndexedNotNull { index, raw ->
        fun value(key: String): String = raw[key]?.toString().orEmpty().trim()

        val coords = value("coords")
        val parts = coords.split(':')
        if (parts.size != 2 || parts.any { it.isBlank() }) {
            log.warn("remote.artifacts[{}] coords '{}' is not group:artifact; skipped", index, coords)
            return@mapIndexedNotNull null
        }

        val repository = value("repository")
        if (repository !in known) {
            log.warn(
                "remote.artifacts[{}] names repository '{}', which is not configured; skipped",
                index,
                repository,
            )
            return@mapIndexedNotNull null
        }

        val version = value("version")
        if (version.isBlank()) {
            log.warn("remote.artifacts[{}] ({}) has no version; skipped", index, coords)
            return@mapIndexedNotNull null
        }

        RemoteArtifact(
            repository = repository,
            group = parts[0],
            artifact = parts[1],
            version = version,
            branch = value("branch"),
        )
    }

    private const val STATE_FILE = "remote-modules.properties"
}
