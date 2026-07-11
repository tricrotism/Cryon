package com.tricrotism.cryon.network.agones

import org.slf4j.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Thin REST client for the local Agones sidecar, using only the JDK [HttpClient] (no gRPC/protobuf on
 * the classpath). Present only inside an Agones-managed pod, where the sidecar injects
 * `AGONES_SDK_HTTP_PORT` and listens on `localhost`. Every call is best-effort: a failure is logged,
 * never thrown, so orchestration hiccups can't disturb gameplay.
 */
class AgonesClient private constructor(private val baseUrl: String, private val logger: Logger) {

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    fun ready(): CompletableFuture<Void> = post("/ready", EMPTY)
    fun health(): CompletableFuture<Void> = post("/health", EMPTY)
    fun shutdown(): CompletableFuture<Void> = post("/shutdown", EMPTY)
    fun allocate(): CompletableFuture<Void> = post("/allocate", EMPTY)
    fun reserve(seconds: Long): CompletableFuture<Void> = post("/reserve", """{"seconds":$seconds}""")
    fun setLabel(key: String, value: String): CompletableFuture<Void> = put("/metadata/label", meta(key, value))
    fun setAnnotation(key: String, value: String): CompletableFuture<Void> =
        put("/metadata/annotation", meta(key, value))

    private fun meta(key: String, value: String): String = """{"key":"${esc(key)}","value":"${esc(value)}"}"""
    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun post(path: String, body: String) = send("POST", path, body)
    private fun put(path: String, body: String) = send("PUT", path, body)

    private fun send(method: String, path: String, body: String): CompletableFuture<Void> {
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .build()
        return http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
            .thenAccept { response ->
                if (response.statusCode() !in 200..299) {
                    logger.warn("Agones {} {} returned HTTP {}", method, path, response.statusCode())
                }
            }
            .exceptionally { logger.warn("Agones {} {} failed: {}", method, path, it.message); null }
    }

    companion object {
        private const val EMPTY = "{}"

        /** An [AgonesClient] if this process runs under an Agones sidecar, else null. */
        fun detect(logger: Logger, env: (String) -> String? = System::getenv): AgonesClient? {
            val port = env("AGONES_SDK_HTTP_PORT")?.toIntOrNull() ?: return null
            logger.info("Agones sidecar detected on port {}", port)
            return AgonesClient("http://localhost:$port", logger)
        }
    }
}
