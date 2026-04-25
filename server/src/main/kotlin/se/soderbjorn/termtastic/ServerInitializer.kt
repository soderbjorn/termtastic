/**
 * Bootstrap helpers wired up by [main] in [Application]: SQLite restore,
 * scrollback saver, session-state poller, and the Claude usage monitor.
 *
 * Each function is small and stateless — `main()` composes them in order
 * and registers the shutdown hook.
 *
 * @see WindowState
 * @see SettingsRepository
 * @see ClaudeUsageMonitor
 */
package se.soderbjorn.termtastic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import se.soderbjorn.darkness.store.Closeable
import se.soderbjorn.darkness.store.readUiSettingsRaw
import se.soderbjorn.darkness.store.watchUiSettings
import se.soderbjorn.termtastic.persistence.SettingsRepository
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * Snapshot of the per-leaf "last bytes saved" map plus the closure that
 * walks the live config and saves only those leaves whose ring contents
 * have advanced since the previous save.
 *
 * Returned by [installScrollbackSaver] so [main]'s shutdown hook can call
 * the flush function with `force = true` for a final write.
 */
internal class ScrollbackSaver(
    private val repo: SettingsRepository,
    private val lastSavedBytes: ConcurrentHashMap<String, Long>,
) {
    /**
     * Iterate over every leaf in the live config and persist its ring buffer
     * snapshot when [force] is `true` or when the leaf's [TerminalSession.bytesWritten]
     * has advanced since the previous save.
     */
    suspend fun saveAll(force: Boolean) {
        fun collect(leaf: LeafNode, out: MutableList<Pair<String, String>>) {
            val content = leaf.content
            val sid = when (content) {
                is TerminalContent -> content.sessionId
                null -> leaf.sessionId.takeIf { it.isNotEmpty() }
                else -> null
            }
            if (sid != null && sid.isNotEmpty()) out.add(leaf.id to sid)
        }
        val pairs = mutableListOf<Pair<String, String>>()
        for (tab in WindowState.config.value.tabs) {
            tab.panes.forEach { collect(it.leaf, pairs) }
        }
        for ((leafId, sessionId) in pairs) {
            val session = TerminalSessions.get(sessionId) ?: continue
            val current = session.bytesWritten()
            if (!force && lastSavedBytes[leafId] == current) continue
            val snapshot = session.snapshot()
            runCatching { repo.saveScrollback(leafId, snapshot) }
                .onSuccess { lastSavedBytes[leafId] = current }
                .onFailure { LoggerFactory.getLogger("ScrollbackPersistence").warn("Failed to save scrollback for $leafId", it) }
        }
    }
}

/**
 * Launch the debounced window-config persistence coroutine. Bursts of
 * mutations coalesce into a single SQLite write at 2 s after the burst
 * settles. `drop(1)` skips the initial value StateFlow replays so we don't
 * immediately rewrite the row we just loaded from.
 */
@OptIn(FlowPreview::class)
internal fun installWindowConfigPersister(scope: CoroutineScope, repo: SettingsRepository) {
    scope.launch {
        WindowState.config
            .drop(1)
            .debounce(2_000.milliseconds)
            .collectLatest { cfg ->
                runCatching { repo.saveWindowConfig(cfg.withBlankSessionIds()) }
                    .onFailure { LoggerFactory.getLogger("WindowPersistence").warn("Failed to persist window config", it) }
            }
    }
}

/**
 * Build a [ScrollbackSaver] and launch the periodic save loop. 10 s
 * cadence keeps the DB warm without thrashing on busy shells; crashes
 * lose at most that window.
 */
internal fun installScrollbackSaver(scope: CoroutineScope, repo: SettingsRepository): ScrollbackSaver {
    val lastSavedBytes = ConcurrentHashMap<String, Long>()
    val saver = ScrollbackSaver(repo, lastSavedBytes)
    scope.launch {
        while (true) {
            delay(10_000)
            runCatching { saver.saveAll(force = false) }
                .onFailure { LoggerFactory.getLogger("ScrollbackPersistence").warn("Periodic scrollback save failed", it) }
        }
    }
    return saver
}

/**
 * Subscribe to the toolkit-owned shared themes file at [SettingsRepository.sharedThemesPath]
 * and re-publish any external mutations into [SettingsRepository.publishExternalUiSettings],
 * which fans out through the existing `/window` UiSettings socket push to
 * every connected client. The watcher itself takes care of suppressing
 * events that this very process triggered, so saves originating from
 * `mergeUiSettings` won't loop.
 *
 * @param repo the settings repository whose `_uiSettings` flow gets the update
 * @return a [Closeable] handle the shutdown hook can close to stop watching
 */
internal fun installSharedThemesWatcher(repo: SettingsRepository): Closeable {
    val path = repo.sharedThemesPath()
    return runCatching {
        watchUiSettings(path) { _ ->
            // Re-read the raw bytes so any app-private keys round-trip.
            val raw = readUiSettingsRaw(path) ?: return@watchUiSettings
            val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: return@watchUiSettings
            repo.publishExternalUiSettings(obj)
        }
    }.getOrElse { t ->
        LoggerFactory.getLogger("SharedThemesWatcher")
            .warn("Failed to install shared themes watcher on {}; live theme propagation disabled", path, t)
        Closeable { /* no-op fallback */ }
    }
}

/**
 * Launch the periodic AI-state poller. Polls every 3 s and broadcasts to
 * connected clients via the returned shared flow. Replay is 1 so newly
 * connected clients get the latest snapshot immediately.
 */
internal fun installSessionStatePoller(scope: CoroutineScope): MutableSharedFlow<Map<String, String?>> {
    val sessionStates = MutableSharedFlow<Map<String, String?>>(replay = 1)
    scope.launch {
        while (true) {
            delay(3_000)
            sessionStates.emit(TerminalSessions.resolveStates())
        }
    }
    return sessionStates
}
