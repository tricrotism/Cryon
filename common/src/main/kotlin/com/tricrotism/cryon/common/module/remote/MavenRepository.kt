package com.tricrotism.cryon.common.module.remote

import java.net.URI
import java.net.http.HttpRequest
import java.util.*

/**
 * One Maven repository the module poller may pull from.
 *
 * **Credentials are env-first and have no default.** `CRYON_MAVEN_<NAME>_USERNAME` and
 * `CRYON_MAVEN_<NAME>_PASSWORD` (the repository name uppercased, every non-alphanumeric character
 * replaced with `_`) win over anything in `config.yml`, so a container is configured without baking
 * a secret into a file that gets copied between deployments.
 *
 * A repository that resolves to a blank username sends no `Authorization` header at all. That is
 * correct for a public repository, and for a private one it produces an honest 401 in the log
 * rather than a silent fall back to whatever account happened to be configured elsewhere.
 */
data class MavenRepository(
    val name: String,
    val url: String,
    val username: String?,
    val password: String?,
) {

    private val base: String = url.trimEnd('/')

    private val authorization: String? = username
        ?.takeIf { it.isNotBlank() }
        ?.let { "Basic " + Base64.getEncoder().encodeToString("$it:${password.orEmpty()}".toByteArray()) }

    val requiresAuth: Boolean get() = authorization != null

    fun request(path: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create("$base/$path")).also { builder ->
            authorization?.let { builder.header("Authorization", it) }
        }

    override fun toString(): String = "$name ($base)"

    companion object {

        /**
         * Build a repository, letting the environment override the configured credentials.
         *
         * Both halves are resolved independently so a deployment can keep a shared read-only
         * username in `config.yml` and inject only the password.
         */
        fun of(
            name: String,
            url: String,
            configUsername: String?,
            configPassword: String?,
        ): MavenRepository = MavenRepository(
            name = name,
            url = url,
            username = env(name, "USERNAME") ?: configUsername,
            password = env(name, "PASSWORD") ?: configPassword,
        )

        fun envKey(name: String, suffix: String): String =
            "CRYON_MAVEN_${name.uppercase().replace(NON_ALPHANUMERIC, "_")}_$suffix"

        private fun env(name: String, suffix: String): String? =
            System.getenv(envKey(name, suffix))?.takeIf { it.isNotBlank() }

        private val NON_ALPHANUMERIC = Regex("[^A-Za-z0-9]")
    }
}
