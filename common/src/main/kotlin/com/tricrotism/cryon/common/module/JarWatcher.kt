package com.tricrotism.cryon.common.module

import org.slf4j.Logger
import java.io.File
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.WatchKey
import java.util.concurrent.TimeUnit

/**
 * Watches a feature-jar directory on a daemon thread and drives hot add/remove/replace: a new jar
 * fires [onChanged], a replaced jar fires [onChanged] (the loader reloads it), a deleted jar fires
 * [onDeleted]. Bursts are coalesced. File copies emit many `MODIFY` events, so after the first event
 * we keep draining for a short quiet window before dispatching one batch.
 *
 * Filesystem events arrive on the watcher's own thread, and module lifecycle is single-writer on
 * every platform, so both callbacks are handed to [dispatch] rather than invoked here: Paper funnels
 * them onto the global region thread, the proxy and Geyser onto their loader executor.
 *
 * Dev-only, gated behind config so production never runs it. Originals are never locked (loaders run
 * from cache copies), so admins can freely delete or replace jars underneath it.
 */
class JarWatcher(
    private val dir: File,
    private val log: Logger,
    private val dispatch: (() -> Unit) -> Unit,
    private val onChanged: (File) -> Unit,
    private val onDeleted: (File) -> Unit,
) {

    private val watchService = FileSystems.getDefault().newWatchService()

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        dir.toPath().register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
        running = true
        thread = Thread(::run, "Cryon-Module-Watcher").apply { isDaemon = true; start() }
        log.info("Module hot-reload watcher active on {}", dir.path)
    }

    private fun run() {
        while (running) {
            val first = try {
                watchService.take()
            } catch (e: InterruptedException) {
                break
            } catch (e: ClosedWatchServiceException) {
                break
            }

            val changed = LinkedHashSet<String>()
            val deleted = LinkedHashSet<String>()
            var key: WatchKey? = first
            // Drain follow-up events with a 500ms quiet window so a single copy/replace is one batch.
            while (key != null) {
                for (event in key.pollEvents()) {
                    val name = (event.context() as? Path)?.toString() ?: continue
                    if (!name.endsWith(".jar")) continue
                    if (event.kind() == ENTRY_DELETE) {
                        deleted.add(name); changed.remove(name)
                    } else {
                        changed.add(name); deleted.remove(name)
                    }
                }
                if (!key.reset()) break // dir vanished
                key = try {
                    watchService.poll(500, TimeUnit.MILLISECONDS)
                } catch (e: ClosedWatchServiceException) {
                    null
                }
            }

            if (changed.isEmpty() && deleted.isEmpty()) continue
            // Never let a dispatch failure kill the watcher thread (callbacks self-guard too).
            runCatching {
                dispatch {
                    deleted.forEach { onDeleted(File(dir, it)) }
                    changed.forEach { onChanged(File(dir, it)) }
                }
            }.onFailure { log.error("Module watcher dispatch failed", it) }
        }
    }

    fun close() {
        running = false
        runCatching { watchService.close() }
        thread?.interrupt()
    }
}
