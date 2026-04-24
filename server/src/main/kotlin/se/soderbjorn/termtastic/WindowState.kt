/**
 * Per-window tab/pane state management for Termtastic.
 *
 * This file contains the [WindowState] registry, which owns one [WindowSlot]
 * per Electron main BrowserWindow. Each slot has its own [WindowConfig]
 * [kotlinx.coroutines.flow.StateFlow] containing that window's tabs, panes,
 * file-browser and git state. Every mutation flows through a windowId-
 * addressed entry point so the `/window` WebSocket for a specific window only
 * observes the state of that one window.
 *
 * Also contains helper functions:
 *  - [prettifyPath] -- collapses `$HOME` to `~` for display titles.
 *  - [computeLeafTitle] -- resolves the display title for a pane.
 *  - [WindowConfig.withBlankSessionIds] -- strips live PTY ids before
 *    persisting to SQLite.
 *
 * PTY [TerminalSessions] are process-global, not window-scoped: a session can
 * be linked from panes in multiple windows, and is destroyed only when no
 * window references it anywhere. Pane and tab ids are also globally unique
 * across all windows (shared counters in this object) so an id can be
 * authoritatively traced back to exactly one window.
 *
 * Persistence layout (no backwards compat with the old single-window v3
 * blob — issue #18 explicitly drops that):
 *  - `windows.known` — JSON-encoded list of windowIds with persisted state.
 *  - `window.config.v4.<id>` — one [WindowConfig] blob per window id.
 *
 * @see WindowConfig
 * @see TabConfig
 * @see Pane
 * @see PaneGeometry the snap/clamp utility every geometry mutation routes through
 * @see TerminalSessions
 * @see SettingsRepository
 * @see WindowRegistry the sibling registry that tracks Electron BrowserWindow geometry
 */
package se.soderbjorn.termtastic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import se.soderbjorn.termtastic.persistence.SettingsRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

// The @Serializable data classes (WindowConfig, TabConfig, Pane, LeafNode,
// LeafContent + subclasses) live in the :clientServer KMP module so the web
// and android clients can deserialize the same wire types the server
// produces. See clientServer/src/commonMain/kotlin/se/soderbjorn/termtastic/.
//
// The mutation logic (the WindowState object below) stays server-side since
// it touches TerminalSessions, the SQLite persistence layer, and live PTYs.


/**
 * Default windowId used by clients that don't assert one. The plain-browser
 * client (no Electron wrapper) and the Android app both sit here. The
 * Electron renderer always supplies its own id via the `?window=<id>` URL
 * query param assigned by [electron/main.js], so this default only ever
 * covers the non-Electron cases.
 */
const val PRIMARY_WINDOW_ID: String = "primary"

/**
 * Collapse `$HOME` to `~` in [path] for display. Anything else is left intact —
 * shortening to basename is intentionally avoided so users can tell similarly
 * named directories apart at a glance.
 */
internal fun prettifyPath(path: String): String {
    val home = System.getProperty("user.home")
    if (home.isNullOrEmpty()) return path
    return when {
        path == home -> "~"
        path.startsWith("$home/") -> "~" + path.substring(home.length)
        else -> path
    }
}

/**
 * Resolve the display title for a pane:
 *  1. user-set [customName] wins;
 *  2. else the prettified [cwd];
 *  3. else [fallback] (typically the auto-generated "Session N" label).
 */
internal fun computeLeafTitle(customName: String?, cwd: String?, fallback: String): String =
    customName?.takeIf { it.isNotBlank() }
        ?: cwd?.takeIf { it.isNotBlank() }?.let(::prettifyPath)
        ?: fallback

/**
 * Returns a deep copy of this config with every [LeafNode.sessionId] blanked.
 * Persisted blobs use this so we never write live PTY ids to disk — they
 * become stale the moment the process exits.
 */
internal fun WindowConfig.withBlankSessionIds(): WindowConfig {
    fun blankContent(c: LeafContent?): LeafContent? = when (c) {
        is TerminalContent -> if (c.sessionId.isEmpty()) c else c.copy(sessionId = "")
        is FileBrowserContent, is GitContent, null -> c
    }
    fun stripLeaf(leaf: LeafNode): LeafNode {
        val newContent = blankContent(leaf.content)
        return if (leaf.sessionId.isEmpty() && newContent === leaf.content) leaf
        else leaf.copy(sessionId = "", content = newContent)
    }
    return copy(
        tabs = tabs.map { tab ->
            tab.copy(
                panes = tab.panes.map { p -> p.copy(leaf = stripLeaf(p.leaf)) },
            )
        }
    )
}

/**
 * Process-wide window state registry. All mutations funnel through here so
 * each window's [config] flow is the single source clients need to subscribe
 * to.
 *
 * Mutations are guarded per-window by `synchronized(slot)`. The volume of
 * window-state mutations is tiny (human-driven clicks and drags) so
 * contention is negligible; the alternative — reasoning about reentrant
 * locks while we also create/destroy PTYs — isn't worth it for the volume.
 *
 * PTY session destruction needs a **global** view across every window,
 * because a terminal pane in window A and a linked pane in window B may
 * reference the same session id. The guard against premature shutdown is
 * [isSessionReferencedAnywhere], consulted by every code path that would
 * otherwise call [TerminalSessions.destroy].
 */
object WindowState {
    private val log = LoggerFactory.getLogger(WindowState::class.java)

    // Counters are singleton — shared across every window — so pane/tab ids
    // are globally unique. That means `findLeaf(paneId)` can traverse a
    // single window's tabs (the one owning the pane) and we never risk two
    // different windows handing back the same id.
    private val nodeIdCounter = AtomicLong(0)
    private val tabIdCounter = AtomicLong(0)

    private fun newNodeId(): String = "n${nodeIdCounter.incrementAndGet()}"
    private fun newTabId(): String = "t${tabIdCounter.incrementAndGet()}"

    /**
     * Per-window state slot. Each Electron main BrowserWindow owns one of
     * these, keyed by windowId. The [config] flow is what the `/window`
     * WebSocket for that window subscribes to.
     */
    class WindowSlot internal constructor(val windowId: String, initial: WindowConfig) {
        internal val _config = MutableStateFlow(initial)
        /** Live config snapshot for this window. */
        val config: StateFlow<WindowConfig> = _config.asStateFlow()
    }

    private val json = Json { ignoreUnknownKeys = true }

    // The map is populated on [initialize]; mutated only by [getOrCreate]
    // / [remove], which synchronize on the object. Reads are wait-free.
    private val slots = ConcurrentHashMap<String, WindowSlot>()

    @Volatile
    private var repo: SettingsRepository? = null

    @Volatile
    private var initialized: Boolean = false

    private const val KNOWN_WINDOWS_KEY = "windows.known"
    private const val WINDOW_CONFIG_PREFIX = "window.config.v4."

    private fun knownWindowsKey() = KNOWN_WINDOWS_KEY
    private fun configKeyFor(id: String) = "$WINDOW_CONFIG_PREFIX$id"

    /**
     * One-shot bootstrap. Called from [main] exactly once before any HTTP
     * route is served so the first `/window` subscribe for any known
     * windowId immediately sees the persisted state.
     *
     * Discovers known windows from the `windows.known` index, then loads
     * each one's blob. If neither the index nor any per-window blob
     * exists, materialises a single [PRIMARY_WINDOW_ID] slot with a fresh
     * default config so the server is always usable on first boot.
     *
     * Note: this deliberately does not fall back to the old
     * `window.config.v3` key — issue #18 dropped backwards compatibility
     * with the single-window schema.
     *
     * @param repo the settings repository to read/write per-window blobs
     */
    @Synchronized
    fun initialize(repo: SettingsRepository) {
        if (initialized) return
        initialized = true
        this.repo = repo

        val knownIds = loadKnownWindowIds(repo)
        if (knownIds.isEmpty()) {
            // Fresh install (or a user who dropped their settings DB). Create
            // the primary window with a default config; the renderer that
            // connects first will flesh it out via commands.
            val primary = WindowSlot(PRIMARY_WINDOW_ID, buildDefault())
            slots[PRIMARY_WINDOW_ID] = primary
            persistKnownWindowIds()
            persist(primary)
        } else {
            for (id in knownIds) {
                val loaded = loadWindowConfig(repo, id)
                val cfg = if (loaded != null && loaded.tabs.isNotEmpty()) {
                    try {
                        rehydrate(loaded, repo)
                    } catch (t: Throwable) {
                        log.warn("Failed to rehydrate persisted config for window $id; using default", t)
                        buildDefault()
                    }
                } else {
                    buildDefault()
                }
                slots[id] = WindowSlot(id, cfg)
            }
        }

        // GC stored scrollback for leaves that no longer exist in ANY window
        // — a scrollback row keyed by a dead leaf id just wastes disk.
        runCatching {
            val live = HashSet<String>()
            for (slot in slots.values) {
                for (tab in slot._config.value.tabs) {
                    tab.panes.forEach { live.add(it.leaf.id) }
                }
            }
            for (stale in repo.allScrollbackLeafIds() - live) {
                repo.deleteScrollback(stale)
            }
        }.onFailure { log.warn("Scrollback GC failed", it) }
    }

    /**
     * Return (or lazily create) the [WindowSlot] for [windowId]. Creating a
     * slot persists it to [KNOWN_WINDOWS_KEY] and flushes a default config
     * for it so subsequent process restarts rediscover the window.
     *
     * Called by every public mutation/query entry point and by the
     * `/window` WebSocket handler when a renderer connects with a fresh
     * `?window=<id>` it hasn't reported before.
     *
     * @param windowId the client-assigned window id
     * @return the existing or newly-materialised slot
     */
    fun getOrCreateSlot(windowId: String): WindowSlot {
        val existing = slots[windowId]
        if (existing != null) return existing
        synchronized(this) {
            val again = slots[windowId]
            if (again != null) return again
            val slot = WindowSlot(windowId, buildDefault())
            slots[windowId] = slot
            persistKnownWindowIds()
            persist(slot)
            return slot
        }
    }

    /**
     * Return the current [WindowConfig] for [windowId]. Materialises the
     * slot lazily if it didn't already exist.
     */
    fun config(windowId: String): StateFlow<WindowConfig> = getOrCreateSlot(windowId).config

    /**
     * List every known windowId (slots currently alive in memory). Used by
     * the persistence saver to iterate across every window without caring
     * about the registry.
     */
    fun knownWindowIds(): List<String> = slots.keys.toList()

    /**
     * Remove a slot and delete its persisted blob. Called when the user
     * closes an Electron BrowserWindow (the renderer's
     * `DELETE /api/windows/{id}` path).
     *
     * Destroys PTY sessions that only that window referenced. Sessions
     * referenced by other windows keep running.
     *
     * @param windowId the window id being dropped
     * @return `true` if an entry existed and was removed
     */
    fun removeWindow(windowId: String): Boolean {
        val r = repo
        val removed = synchronized(this) {
            val slot = slots.remove(windowId) ?: return@synchronized null
            slot
        } ?: return false
        // Collect sessions that *were* referenced only by this window.
        val orphaned = collectSessionIds(removed._config.value) - collectSessionIdsAcrossLiveSlots()
        orphaned.forEach { TerminalSessions.destroy(it) }
        if (r != null) {
            runCatching { r.putString(configKeyFor(windowId), "") }
            persistKnownWindowIds()
        }
        return true
    }

    // ---- persistence helpers -------------------------------------------------

    private fun loadKnownWindowIds(repo: SettingsRepository): List<String> {
        val raw = repo.getString(KNOWN_WINDOWS_KEY) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), raw)
        }.getOrElse {
            log.warn("Failed to decode $KNOWN_WINDOWS_KEY; starting empty", it)
            emptyList()
        }
    }

    private fun persistKnownWindowIds() {
        val r = repo ?: return
        val ids = slots.keys.sorted()
        val encoded = json.encodeToString(ListSerializer(String.serializer()), ids)
        runCatching { r.putString(KNOWN_WINDOWS_KEY, encoded) }
            .onFailure { log.warn("Failed to persist known windows index", it) }
    }

    private fun loadWindowConfig(repo: SettingsRepository, windowId: String): WindowConfig? {
        val raw = repo.getString(configKeyFor(windowId))?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { windowJson.decodeFromString(WindowConfig.serializer(), raw) }
            .getOrElse {
                log.warn("Failed to decode window config for $windowId; using default", it)
                null
            }
    }

    /**
     * Write the given slot's current (blanked) config to the k/v store.
     * Called on slot creation / removal and by [persistAll] from the
     * debounced saver in [Application.main].
     */
    internal fun persist(slot: WindowSlot) {
        val r = repo ?: return
        val blanked = slot._config.value.withBlankSessionIds()
        val encoded = windowJson.encodeToString(WindowConfig.serializer(), blanked)
        runCatching { r.putString(configKeyFor(slot.windowId), encoded) }
            .onFailure { log.warn("Failed to persist window config for ${slot.windowId}", it) }
    }

    /**
     * Persist every live window to SQLite. Triggered by the debounced
     * saver in [Application.main] whenever any window's config flow emits.
     */
    suspend fun persistAllBlocking() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        for (slot in slots.values) persist(slot)
    }

    /**
     * Rehydrate a freshly-loaded config:
     *  1. mint a new [TerminalSessions] for every terminal leaf, replacing
     *     the stale id (the persisted sessionId referred to a PTY from a
     *     previous JVM process);
     *  2. retarget the node/tab id counters past the highest persisted ids
     *     so subsequent panes/tabs across all windows don't collide.
     */
    private fun rehydrate(loaded: WindowConfig, repo: SettingsRepository): WindowConfig {
        var maxNodeId = nodeIdCounter.get()
        var maxTabId = tabIdCounter.get()

        fun trackNodeId(id: String) {
            id.removePrefix("n").toLongOrNull()?.let { if (it > maxNodeId) maxNodeId = it }
        }

        fun rebuildLeaf(leaf: LeafNode): LeafNode {
            trackNodeId(leaf.id)
            return when (leaf.content) {
                is FileBrowserContent, is GitContent -> leaf.copy(sessionId = "")
                is TerminalContent, null -> {
                    val priorScrollback = runCatching { repo.loadScrollback(leaf.id) }.getOrNull()
                    val freshSession = TerminalSessions.create(leaf.cwd, priorScrollback)
                    leaf.copy(sessionId = freshSession, content = TerminalContent(freshSession))
                }
            }
        }

        val rebuiltTabs = loaded.tabs.map { tab ->
            tab.id.removePrefix("t").toLongOrNull()?.let { if (it > maxTabId) maxTabId = it }

            val rebuiltPanes = tab.panes.map { p ->
                val box = PaneGeometry.normalize(p.x, p.y, p.width, p.height)
                p.copy(
                    leaf = rebuildLeaf(p.leaf),
                    x = box.x, y = box.y, width = box.width, height = box.height,
                )
            }
            tab.copy(panes = rebuiltPanes)
        }

        nodeIdCounter.set(maxNodeId)
        tabIdCounter.set(maxTabId)

        val tabIdSet = rebuiltTabs.map { it.id }.toSet()
        val validatedActive = loaded.activeTabId?.takeIf { it in tabIdSet }
        val sanitizedTabs = rebuiltTabs.map { tab ->
            val livePaneIds = tab.panes.mapTo(HashSet()) { it.leaf.id }
            val keepFocus = tab.focusedPaneId?.takeIf { it in livePaneIds }
            if (keepFocus == tab.focusedPaneId) tab else tab.copy(focusedPaneId = keepFocus)
        }
        return WindowConfig(tabs = sanitizedTabs, activeTabId = validatedActive)
    }

    private fun buildDefault(): WindowConfig {
        val s1 = TerminalSessions.create()
        val leaf = LeafNode(
            id = newNodeId(),
            sessionId = s1,
            title = "Session ${s1.removePrefix("s")}",
            content = TerminalContent(s1),
        )
        val (ox, oy) = randomSnappedOrigin()
        val tab1 = TabConfig(
            id = newTabId(),
            title = "Tab 1",
            panes = listOf(
                Pane(
                    leaf = leaf,
                    x = ox, y = oy,
                    width = PaneGeometry.DEFAULT_SIZE,
                    height = PaneGeometry.DEFAULT_SIZE,
                    z = 1L,
                )
            ),
        )
        return WindowConfig(listOf(tab1))
    }

    private fun randomSnappedOrigin(size: Double = PaneGeometry.DEFAULT_SIZE): Pair<Double, Double> {
        val maxSteps = ((1.0 - size) / PaneGeometry.SNAP).toInt().coerceAtLeast(0)
        val sx = Random.nextInt(0, maxSteps + 1) * PaneGeometry.SNAP
        val sy = Random.nextInt(0, maxSteps + 1) * PaneGeometry.SNAP
        return sx to sy
    }

    private fun nextZ(tab: TabConfig): Long =
        (tab.panes.maxOfOrNull { it.z } ?: 0L) + 1L

    private fun demoteMaximized(tab: TabConfig): TabConfig {
        if (tab.panes.none { it.maximized }) return tab
        return tab.copy(panes = tab.panes.map { if (it.maximized) it.copy(maximized = false) else it })
    }

    // ---- Windowed mutations -------------------------------------------------
    //
    // Each public method takes `windowId` as its first parameter and operates
    // on that window's slot only. Pane and tab ids are globally unique across
    // windows (via the shared counters above), so a caller can route a
    // `paneId` to the correct window by searching slots. For the common case
    // where the caller already knows the windowId (e.g. the /window
    // WebSocket handler), the windowed entry points are strictly cheaper.

    fun addTab(windowId: String) {
        val slot = getOrCreateSlot(windowId)
        synchronized(slot) {
            val cfg = slot._config.value
            val sessionId = TerminalSessions.create()
            val nextNumber = cfg.tabs.size + 1
            val leaf = LeafNode(
                id = newNodeId(),
                sessionId = sessionId,
                title = "Session ${sessionId.removePrefix("s")}",
                content = TerminalContent(sessionId),
            )
            val (ox, oy) = randomSnappedOrigin()
            val newTab = TabConfig(
                id = newTabId(),
                title = "Tab $nextNumber",
                panes = listOf(
                    Pane(
                        leaf = leaf,
                        x = ox, y = oy,
                        width = PaneGeometry.DEFAULT_SIZE,
                        height = PaneGeometry.DEFAULT_SIZE,
                        z = 1L,
                    )
                ),
            )
            slot._config.value = cfg.copy(tabs = cfg.tabs + newTab)
        }
    }

    fun closeTab(windowId: String, tabId: String) {
        val slot = getOrCreateSlot(windowId)
        synchronized(slot) {
            val cfg = slot._config.value
            if (cfg.tabs.size <= 1) return
            if (cfg.tabs.none { it.id == tabId }) return
            val before = collectSessionIds(cfg)
            val newTabs = cfg.tabs.filterNot { it.id == tabId }
            val newActive = if (cfg.activeTabId == tabId) {
                val oldIdx = cfg.tabs.indexOfFirst { it.id == tabId }
                newTabs.getOrNull(oldIdx.coerceAtMost(newTabs.size - 1))?.id
            } else {
                cfg.activeTabId
            }
            val newCfg = cfg.copy(tabs = newTabs, activeTabId = newActive)
            slot._config.value = newCfg
            destroyOrphanedSessions(beforeSlot = slot, removedFromSlot = before - collectSessionIds(newCfg))
        }
    }

    fun setActiveTab(windowId: String, tabId: String) {
        val slot = getOrCreateSlot(windowId)
        synchronized(slot) {
            val cfg = slot._config.value
            if (cfg.activeTabId == tabId) return
            if (cfg.tabs.none { it.id == tabId }) return
            slot._config.value = cfg.copy(activeTabId = tabId)
        }
    }

    fun setFocusedPane(windowId: String, tabId: String, paneId: String) {
        val slot = getOrCreateSlot(windowId)
        synchronized(slot) {
            val cfg = slot._config.value
            val tabIdx = cfg.tabs.indexOfFirst { it.id == tabId }
            if (tabIdx < 0) return
            val tab = cfg.tabs[tabIdx]
            val livePanes = HashSet<String>()
            tab.panes.forEach { livePanes.add(it.leaf.id) }
            if (paneId !in livePanes) return
            if (tab.focusedPaneId == paneId) return
            val newTabs = cfg.tabs.toMutableList()
            newTabs[tabIdx] = tab.copy(focusedPaneId = paneId)
            slot._config.value = cfg.copy(tabs = newTabs)
        }
    }

    fun moveTab(windowId: String, tabId: String, targetTabId: String, before: Boolean) {
        val slot = getOrCreateSlot(windowId)
        synchronized(slot) {
            if (tabId == targetTabId) return
            val cfg = slot._config.value
            val srcIdx = cfg.tabs.indexOfFirst { it.id == tabId }
            val tgtIdx = cfg.tabs.indexOfFirst { it.id == targetTabId }
            if (srcIdx < 0 || tgtIdx < 0) return

            val moving = cfg.tabs[srcIdx]
            val without = cfg.tabs.toMutableList().also { it.removeAt(srcIdx) }
            val newTargetIdx = without.indexOfFirst { it.id == targetTabId }
            val insertAt = if (before) newTargetIdx else newTargetIdx + 1
            if (insertAt == srcIdx) return
            without.add(insertAt, moving)
            slot._config.value = cfg.copy(tabs = without)
        }
    }

    fun setTabHidden(windowId: String, tabId: String, hidden: Boolean) {
        val slot = getOrCreateSlot(windowId)
        synchronized(slot) {
            val cfg = slot._config.value
            val idx = cfg.tabs.indexOfFirst { it.id == tabId }
            if (idx < 0) return
            val tab = cfg.tabs[idx]
            if (tab.isHidden == hidden) return
            val newTabs = cfg.tabs.toMutableList()
            newTabs[idx] = tab.copy(isHidden = hidden)
            slot._config.value = cfg.copy(tabs = newTabs)
        }
    }

    fun setTabHiddenFromSidebar(windowId: String, tabId: String, hidden: Boolean) {
        val slot = getOrCreateSlot(windowId)
        synchronized(slot) {
            val cfg = slot._config.value
            val idx = cfg.tabs.indexOfFirst { it.id == tabId }
            if (idx < 0) return
            val tab = cfg.tabs[idx]
            if (tab.isHiddenFromSidebar == hidden) return
            val newTabs = cfg.tabs.toMutableList()
            newTabs[idx] = tab.copy(isHiddenFromSidebar = hidden)
            slot._config.value = cfg.copy(tabs = newTabs)
        }
    }

    fun renameTab(windowId: String, tabId: String, title: String) {
        val slot = getOrCreateSlot(windowId)
        synchronized(slot) {
            val sanitized = title.trim().take(80)
            if (sanitized.isEmpty()) return
            val cfg = slot._config.value
            var changed = false
            val newTabs = cfg.tabs.map { tab ->
                if (tab.id == tabId && tab.title != sanitized) {
                    changed = true
                    tab.copy(title = sanitized)
                } else tab
            }
            if (changed) slot._config.value = cfg.copy(tabs = newTabs)
        }
    }

    /**
     * Find the leaf [paneId] in the window [windowId]. Returns null if the
     * pane doesn't live in that window (or doesn't exist at all).
     */
    fun findLeaf(windowId: String, paneId: String): LeafNode? {
        val slot = slots[windowId] ?: return null
        val cfg = slot._config.value
        for (tab in cfg.tabs) {
            tab.panes.firstOrNull { it.leaf.id == paneId }?.let { return it.leaf }
        }
        return null
    }

    /**
     * Global (any-window) pane lookup. Used by cross-window bookkeeping —
     * notably the PTY cwd watcher that pushes OSC 7 updates without knowing
     * which window a pane lives in.
     *
     * @return a pair of (windowId, leaf) if found, else null.
     */
    fun findLeafAnywhere(paneId: String): Pair<String, LeafNode>? {
        for ((id, slot) in slots) {
            val cfg = slot._config.value
            for (tab in cfg.tabs) {
                tab.panes.firstOrNull { it.leaf.id == paneId }?.let {
                    return id to it.leaf
                }
            }
        }
        return null
    }

    /**
     * Return the id of the tab inside [windowId] that contains [paneId], or
     * null if the pane isn't in that window.
     */
    fun tabIdOfPane(windowId: String, paneId: String): String? {
        val slot = slots[windowId] ?: return null
        val cfg = slot._config.value
        for (tab in cfg.tabs) {
            if (tab.panes.any { it.leaf.id == paneId }) return tab.id
        }
        return null
    }

    private fun findLeafBySession(cfg: WindowConfig, sessionId: String): LeafNode? {
        for (tab in cfg.tabs) {
            tab.panes.firstOrNull { it.leaf.sessionId == sessionId }?.let { return it.leaf }
        }
        return null
    }

    private fun updateFileBrowserContent(
        windowId: String,
        paneId: String,
        transform: (FileBrowserContent) -> FileBrowserContent,
    ): FileBrowserContent? {
        val slot = getOrCreateSlot(windowId)
        synchronized(slot) {
            val cfg = slot._config.value
            var newState: FileBrowserContent? = null
            fun mutate(leaf: LeafNode): LeafNode {
                if (leaf.id != paneId) return leaf
                val current = leaf.content as? FileBrowserContent ?: return leaf
                val next = transform(current)
                if (next == current) return leaf
                newState = next
                return leaf.copy(content = next)
            }
            val newCfg = cfg.copy(
                tabs = cfg.tabs.map { tab ->
                    tab.copy(
                        panes = tab.panes.map { p -> p.copy(leaf = mutate(p.leaf)) },
                    )
                }
            )
            if (newState != null) slot._config.value = newCfg
            return newState
        }
    }

    fun setFileBrowserSelected(windowId: String, paneId: String, relPath: String?): FileBrowserContent? =
        updateFileBrowserContent(windowId, paneId) { it.copy(selectedRelPath = relPath) }

    fun setFileBrowserExpanded(windowId: String, paneId: String, dirRelPath: String, expanded: Boolean): FileBrowserContent? =
        updateFileBrowserContent(windowId, paneId) {
            val next = if (expanded) it.expandedDirs + dirRelPath else it.expandedDirs - dirRelPath
            if (next == it.expandedDirs) it else it.copy(expandedDirs = next)
        }

    fun setFileBrowserLeftWidth(windowId: String, paneId: String, px: Int): FileBrowserContent? {
        val clamped = px.coerceIn(0, 640)
        return updateFileBrowserContent(windowId, paneId) { it.copy(leftColumnWidthPx = clamped) }
    }

    fun setFileBrowserAutoRefresh(windowId: String, paneId: String, enabled: Boolean): FileBrowserContent? =
        updateFileBrowserContent(windowId, paneId) { it.copy(autoRefresh = enabled) }

    fun setFileBrowserFilter(windowId: String, paneId: String, filter: String): FileBrowserContent? {
        val normalized = filter.trim().ifEmpty { null }
        return updateFileBrowserContent(windowId, paneId) { it.copy(fileFilter = normalized) }
    }

    fun setFileBrowserSort(windowId: String, paneId: String, sort: FileBrowserSort): FileBrowserContent? =
        updateFileBrowserContent(windowId, paneId) {
            if (it.sortBy == sort) it else it.copy(sortBy = sort)
        }

    fun setFileBrowserExpandedAll(windowId: String, paneId: String, dirs: Set<String>): FileBrowserContent? =
        updateFileBrowserContent(windowId, paneId) {
            val merged = it.expandedDirs + dirs
            if (merged == it.expandedDirs) it else it.copy(expandedDirs = merged)
        }

    fun clearFileBrowserExpanded(windowId: String, paneId: String): FileBrowserContent? =
        updateFileBrowserContent(windowId, paneId) {
            if (it.expandedDirs.isEmpty()) it else it.copy(expandedDirs = emptySet())
        }

    fun setFileBrowserFontSize(windowId: String, paneId: String, size: Int): FileBrowserContent? {
        val clamped = size.coerceIn(8, 24)
        return updateFileBrowserContent(windowId, paneId) { it.copy(fontSize = clamped) }
    }

    // ---- Terminal pane mutations --------------------------------------------

    private fun updateTerminalContent(
        windowId: String,
        paneId: String,
        transform: (TerminalContent) -> TerminalContent,
    ): TerminalContent? {
        val slot = getOrCreateSlot(windowId)
        synchronized(slot) {
            val cfg = slot._config.value
            var newState: TerminalContent? = null
            fun mutate(leaf: LeafNode): LeafNode {
                if (leaf.id != paneId) return leaf
                val current = leaf.content as? TerminalContent ?: return leaf
                val next = transform(current)
                if (next == current) return leaf
                newState = next
                return leaf.copy(content = next)
            }
            val newCfg = cfg.copy(
                tabs = cfg.tabs.map { tab ->
                    tab.copy(
                        panes = tab.panes.map { p -> p.copy(leaf = mutate(p.leaf)) },
                    )
                }
            )
            if (newState != null) slot._config.value = newCfg
            return newState
        }
    }

    fun setTerminalFontSize(windowId: String, paneId: String, size: Int): TerminalContent? {
        val clamped = size.coerceIn(8, 24)
        return updateTerminalContent(windowId, paneId) { it.copy(fontSize = clamped) }
    }

    // ---- Git pane mutations ------------------------------------------------

    private fun updateGitContent(
        windowId: String,
        paneId: String,
        transform: (GitContent) -> GitContent,
    ): GitContent? {
        val slot = getOrCreateSlot(windowId)
        synchronized(slot) {
            val cfg = slot._config.value
            var newState: GitContent? = null
            fun mutate(leaf: LeafNode): LeafNode {
                if (leaf.id != paneId) return leaf
                val current = leaf.content as? GitContent ?: return leaf
                val next = transform(current)
                if (next == current) return leaf
                newState = next
                return leaf.copy(content = next)
            }
            val newCfg = cfg.copy(
                tabs = cfg.tabs.map { tab ->
                    tab.copy(
                        panes = tab.panes.map { p -> p.copy(leaf = mutate(p.leaf)) },
                    )
                }
            )
            if (newState != null) slot._config.value = newCfg
            return newState
        }
    }

    fun setGitSelected(windowId: String, paneId: String, filePath: String?): GitContent? =
        updateGitContent(windowId, paneId) { it.copy(selectedFilePath = filePath) }

    fun setGitLeftWidth(windowId: String, paneId: String, px: Int): GitContent? {
        val clamped = px.coerceIn(0, 640)
        return updateGitContent(windowId, paneId) { it.copy(leftColumnWidthPx = clamped) }
    }

    fun setGitDiffMode(windowId: String, paneId: String, mode: GitDiffMode): GitContent? =
        updateGitContent(windowId, paneId) { it.copy(diffMode = mode) }

    fun setGitGraphicalDiff(windowId: String, paneId: String, enabled: Boolean): GitContent? =
        updateGitContent(windowId, paneId) { it.copy(graphicalDiff = enabled) }

    fun setGitDiffFontSize(windowId: String, paneId: String, size: Int): GitContent? {
        val clamped = size.coerceIn(8, 24)
        return updateGitContent(windowId, paneId) { it.copy(diffFontSize = clamped) }
    }

    fun setGitAutoRefresh(windowId: String, paneId: String, enabled: Boolean): GitContent? =
        updateGitContent(windowId, paneId) { it.copy(autoRefresh = enabled) }

    fun closePane(windowId: String, paneId: String) {
        val slot = getOrCreateSlot(windowId)
        synchronized(slot) {
            val cfg = slot._config.value
            val before = collectSessionIds(cfg)
            val newTabs = cfg.tabs.map { tab ->
                val newPanes = tab.panes.filterNot { it.leaf.id == paneId }
                val newFocus = if (tab.focusedPaneId == paneId) null else tab.focusedPaneId
                if (newPanes.size == tab.panes.size && newFocus == tab.focusedPaneId) tab
                else tab.copy(panes = newPanes, focusedPaneId = newFocus)
            }
            val newCfg = cfg.copy(tabs = newTabs)
            if (newCfg == cfg) return
            slot._config.value = newCfg
            val after = collectSessionIds(newCfg)
            destroyOrphanedSessions(beforeSlot = slot, removedFromSlot = before - after)
        }
    }

    /**
     * Close every pane in [windowId] that references [sessionId], and — if
     * no other window still references it — destroy the PTY itself.
     *
     * For cross-window session closures the caller should iterate over
     * [knownWindowIds] and call this per window, or use [closeSessionEverywhere].
     */
    fun closeSession(windowId: String, sessionId: String) {
        if (sessionId.isEmpty()) return
        val slot = getOrCreateSlot(windowId)
        synchronized(slot) {
            val cfg = slot._config.value
            val before = collectSessionIds(cfg)

            val newTabs = cfg.tabs.map { tab ->
                val newPanes = tab.panes.filterNot { it.leaf.sessionId == sessionId }
                val liveIds = HashSet<String>()
                newPanes.forEach { liveIds.add(it.leaf.id) }
                val newFocus = tab.focusedPaneId?.takeIf { it in liveIds }
                tab.copy(panes = newPanes, focusedPaneId = newFocus)
            }
            val newCfg = cfg.copy(tabs = newTabs)
            if (newCfg == cfg) return
            slot._config.value = newCfg
            val after = collectSessionIds(newCfg)
            destroyOrphanedSessions(beforeSlot = slot, removedFromSlot = before - after)
        }
    }

    /**
     * Close [sessionId] in every known window and destroy the PTY. Used by
     * commands the user triggers explicitly to wipe a live terminal
     * globally rather than unlink it from the current window only.
     */
    fun closeSessionEverywhere(sessionId: String) {
        if (sessionId.isEmpty()) return
        for (id in knownWindowIds()) {
            closeSession(id, sessionId)
        }
        // Best-effort: if no slot still references it, ensure destruction
        // (the per-window closures already handle this, this is belt-and-
        // braces in case of a race).
        if (!isSessionReferencedAnywhere(sessionId)) {
            TerminalSessions.destroy(sessionId)
        }
    }

    fun renamePane(windowId: String, paneId: String, title: String) {
        val slot = getOrCreateSlot(windowId)
        synchronized(slot) {
            val sanitized = title.trim().take(80)
            val newCustomName: String? = sanitized.ifEmpty { null }
            val cfg = slot._config.value
            var changed = false
            fun renameLeaf(leaf: LeafNode): LeafNode {
                if (leaf.id != paneId) return leaf
                val newTitle = computeLeafTitle(newCustomName, leaf.cwd, leaf.title)
                if (leaf.customName == newCustomName && leaf.title == newTitle) return leaf
                changed = true
                return leaf.copy(customName = newCustomName, title = newTitle)
            }
            val newCfg = cfg.copy(
                tabs = cfg.tabs.map { tab ->
                    tab.copy(
                        panes = tab.panes.map { p -> p.copy(leaf = renameLeaf(p.leaf)) },
                    )
                }
            )
            if (changed) slot._config.value = newCfg
        }
    }

    /**
     * Push a freshly-detected working directory for every pane backed by
     * [sessionId] — in every window. A linked pane in one window should
     * update its title in lockstep with the originating pane in another.
     */
    fun updatePaneCwd(sessionId: String, cwd: String) {
        if (cwd.isBlank()) return
        for (slot in slots.values) {
            synchronized(slot) {
                val cfg = slot._config.value
                var changed = false
                fun maybeUpdate(leaf: LeafNode): LeafNode {
                    if (leaf.sessionId != sessionId || leaf.cwd == cwd) return leaf
                    changed = true
                    val newTitle = computeLeafTitle(leaf.customName, cwd, leaf.title)
                    return leaf.copy(cwd = cwd, title = newTitle)
                }
                val newCfg = cfg.copy(
                    tabs = cfg.tabs.map { tab ->
                        tab.copy(
                            panes = tab.panes.map { p -> p.copy(leaf = maybeUpdate(p.leaf)) },
                        )
                    }
                )
                if (changed) slot._config.value = newCfg
            }
        }
    }

    fun setPaneGeometry(
        windowId: String,
        paneId: String,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
    ) {
        val slot = getOrCreateSlot(windowId)
        synchronized(slot) {
            val box = PaneGeometry.normalize(x, y, width, height)
            val cfg = slot._config.value
            var changed = false
            val newTabs = cfg.tabs.map { tab ->
                val idx = tab.panes.indexOfFirst { it.leaf.id == paneId }
                if (idx < 0) return@map tab
                val current = tab.panes[idx]
                if (current.x == box.x && current.y == box.y &&
                    current.width == box.width && current.height == box.height
                ) return@map tab
                changed = true
                val newPanes = tab.panes.toMutableList()
                newPanes[idx] = current.copy(x = box.x, y = box.y, width = box.width, height = box.height)
                tab.copy(panes = newPanes)
            }
            if (changed) slot._config.value = cfg.copy(tabs = newTabs)
        }
    }

    fun setPaneColorScheme(windowId: String, paneId: String, scheme: String?) {
        val slot = getOrCreateSlot(windowId)
        synchronized(slot) {
            val cfg = slot._config.value
            var changed = false
            val newTabs = cfg.tabs.map { tab ->
                val idx = tab.panes.indexOfFirst { it.leaf.id == paneId }
                if (idx < 0) return@map tab
                val current = tab.panes[idx]
                if (current.colorScheme == scheme) return@map tab
                changed = true
                val newPanes = tab.panes.toMutableList()
                newPanes[idx] = current.copy(colorScheme = scheme)
                tab.copy(panes = newPanes)
            }
            if (changed) slot._config.value = cfg.copy(tabs = newTabs)
        }
    }

    fun raisePane(windowId: String, paneId: String) {
        val slot = getOrCreateSlot(windowId)
        synchronized(slot) {
            val cfg = slot._config.value
            var changed = false
            val newTabs = cfg.tabs.map { tab ->
                val idx = tab.panes.indexOfFirst { it.leaf.id == paneId }
                if (idx < 0) return@map tab
                val current = tab.panes[idx]
                val maxZ = tab.panes.maxOf { it.z }
                if (current.z == maxZ && tab.panes.count { it.z == maxZ } == 1) return@map tab
                changed = true
                val newPanes = tab.panes.toMutableList()
                newPanes[idx] = current.copy(z = maxZ + 1)
                tab.copy(panes = newPanes)
            }
            if (changed) slot._config.value = cfg.copy(tabs = newTabs)
        }
    }

    fun toggleMaximized(windowId: String, paneId: String) {
        val slot = getOrCreateSlot(windowId)
        synchronized(slot) {
            val cfg = slot._config.value
            var changed = false
            val newTabs = cfg.tabs.map { tab ->
                val idx = tab.panes.indexOfFirst { it.leaf.id == paneId }
                if (idx < 0) return@map tab
                val current = tab.panes[idx]
                val nowMax = !current.maximized
                val topZ = tab.panes.maxOf { it.z }
                changed = true
                val newPanes = tab.panes.mapIndexed { i, p ->
                    when {
                        i == idx -> p.copy(
                            maximized = nowMax,
                            z = if (nowMax) topZ + 1 else p.z,
                        )
                        nowMax && p.maximized -> p.copy(maximized = false)
                        else -> p
                    }
                }
                tab.copy(panes = newPanes)
            }
            if (changed) slot._config.value = cfg.copy(tabs = newTabs)
        }
    }

    fun applyLayout(windowId: String, tabId: String, layout: String, primaryPaneId: String?) {
        val slot = getOrCreateSlot(windowId)
        synchronized(slot) {
            val cfg = slot._config.value
            val tabIdx = cfg.tabs.indexOfFirst { it.id == tabId }
            if (tabIdx < 0) return
            val tab = cfg.tabs[tabIdx]
            if (tab.panes.isEmpty()) return

            val primary = tab.panes.firstOrNull { it.leaf.id == primaryPaneId } ?: tab.panes.first()
            val rest = tab.panes
                .filter { it.leaf.id != primary.leaf.id }
                .sortedWith(
                    compareByDescending<Pane> { it.width * it.height }
                        .thenBy { tab.panes.indexOf(it) }
                )
            val ordered = listOf(primary) + rest
            val boxes = computeLayout(layout, ordered.size)

            val boxById = ordered.withIndex().associate { (i, p) -> p.leaf.id to boxes[i] }
            val newPanes = tab.panes.map { p ->
                val b = boxById[p.leaf.id] ?: return@map p
                p.copy(x = b.x, y = b.y, width = b.width, height = b.height, maximized = false)
            }
            val newTabs = cfg.tabs.toMutableList()
            newTabs[tabIdx] = tab.copy(panes = newPanes)
            slot._config.value = cfg.copy(tabs = newTabs)
        }
    }

    private fun computeLayout(layout: String, n: Int): List<PaneBox> {
        if (n <= 0) return emptyList()
        if (n == 1) return listOf(PaneBox(0.0, 0.0, 1.0, 1.0))
        return when (layout) {
            "hero-left" -> listOf(PaneBox(0.0, 0.0, 0.65, 1.0)) +
                equalStrip(n - 1, ox = 0.65, oy = 0.0, sx = 0.35, sy = 1.0, axis = "vertical")
            "hero-right" -> listOf(PaneBox(0.35, 0.0, 0.65, 1.0)) +
                equalStrip(n - 1, ox = 0.0, oy = 0.0, sx = 0.35, sy = 1.0, axis = "vertical")
            "hero-top" -> listOf(PaneBox(0.0, 0.0, 1.0, 0.65)) +
                equalStrip(n - 1, ox = 0.0, oy = 0.65, sx = 1.0, sy = 0.35, axis = "horizontal")
            "hero-bottom" -> listOf(PaneBox(0.0, 0.35, 1.0, 0.65)) +
                equalStrip(n - 1, ox = 0.0, oy = 0.0, sx = 1.0, sy = 0.35, axis = "horizontal")

            "split-h" -> listOf(PaneBox(0.0, 0.0, 0.50, 1.0)) +
                equalStrip(n - 1, ox = 0.50, oy = 0.0, sx = 0.50, sy = 1.0, axis = "vertical")
            "split-v" -> listOf(PaneBox(0.0, 0.0, 1.0, 0.50)) +
                equalStrip(n - 1, ox = 0.0, oy = 0.50, sx = 1.0, sy = 0.50, axis = "horizontal")

            "sidebar-left" -> listOf(PaneBox(0.25, 0.0, 0.75, 1.0)) +
                equalStrip(n - 1, ox = 0.0, oy = 0.0, sx = 0.25, sy = 1.0, axis = "vertical")
            "sidebar-right" -> listOf(PaneBox(0.0, 0.0, 0.75, 1.0)) +
                equalStrip(n - 1, ox = 0.75, oy = 0.0, sx = 0.25, sy = 1.0, axis = "vertical")
            "sidebar-top" -> listOf(PaneBox(0.0, 0.25, 1.0, 0.75)) +
                equalStrip(n - 1, ox = 0.0, oy = 0.0, sx = 1.0, sy = 0.25, axis = "horizontal")
            "sidebar-bottom" -> listOf(PaneBox(0.0, 0.0, 1.0, 0.75)) +
                equalStrip(n - 1, ox = 0.0, oy = 0.75, sx = 1.0, sy = 0.25, axis = "horizontal")

            "t-shape" -> when (n) {
                2 -> listOf(
                    PaneBox(0.0, 0.0, 0.70, 1.0),
                    PaneBox(0.70, 0.0, 0.30, 1.0),
                )
                else -> buildList {
                    add(PaneBox(0.0, 0.0, 0.70, 0.70))
                    add(PaneBox(0.70, 0.0, 0.30, 0.70))
                    addAll(equalStrip(n - 2, ox = 0.0, oy = 0.70, sx = 1.0, sy = 0.30, axis = "horizontal"))
                }
            }
            "t-shape-inv" -> when (n) {
                2 -> listOf(
                    PaneBox(0.0, 0.0, 0.70, 1.0),
                    PaneBox(0.70, 0.0, 0.30, 1.0),
                )
                else -> buildList {
                    add(PaneBox(0.0, 0.30, 0.70, 0.70))
                    add(PaneBox(0.70, 0.30, 0.30, 0.70))
                    addAll(equalStrip(n - 2, ox = 0.0, oy = 0.0, sx = 1.0, sy = 0.30, axis = "horizontal"))
                }
            }

            "l-shape" -> when (n) {
                2 -> listOf(
                    PaneBox(0.0, 0.0, 0.65, 1.0),
                    PaneBox(0.65, 0.0, 0.35, 1.0),
                )
                else -> buildList {
                    add(PaneBox(0.0, 0.0, 0.65, 0.65))
                    add(PaneBox(0.65, 0.0, 0.35, 1.0))
                    addAll(equalStrip(n - 2, ox = 0.0, oy = 0.65, sx = 0.65, sy = 0.35, axis = "horizontal"))
                }
            }
            "l-shape-tr" -> when (n) {
                2 -> listOf(
                    PaneBox(0.35, 0.0, 0.65, 1.0),
                    PaneBox(0.0, 0.0, 0.35, 1.0),
                )
                else -> buildList {
                    add(PaneBox(0.35, 0.0, 0.65, 0.65))
                    add(PaneBox(0.0, 0.0, 0.35, 1.0))
                    addAll(equalStrip(n - 2, ox = 0.35, oy = 0.65, sx = 0.65, sy = 0.35, axis = "horizontal"))
                }
            }
            "l-shape-bl" -> when (n) {
                2 -> listOf(
                    PaneBox(0.0, 0.0, 0.65, 1.0),
                    PaneBox(0.65, 0.0, 0.35, 1.0),
                )
                else -> buildList {
                    add(PaneBox(0.0, 0.35, 0.65, 0.65))
                    add(PaneBox(0.65, 0.0, 0.35, 1.0))
                    addAll(equalStrip(n - 2, ox = 0.0, oy = 0.0, sx = 0.65, sy = 0.35, axis = "horizontal"))
                }
            }
            "l-shape-br" -> when (n) {
                2 -> listOf(
                    PaneBox(0.35, 0.0, 0.65, 1.0),
                    PaneBox(0.0, 0.0, 0.35, 1.0),
                )
                else -> buildList {
                    add(PaneBox(0.35, 0.35, 0.65, 0.65))
                    add(PaneBox(0.0, 0.0, 0.35, 1.0))
                    addAll(equalStrip(n - 2, ox = 0.35, oy = 0.0, sx = 0.65, sy = 0.35, axis = "horizontal"))
                }
            }

            "big-2-stack" -> when (n) {
                2 -> listOf(
                    PaneBox(0.0, 0.0, 0.60, 1.0),
                    PaneBox(0.60, 0.0, 0.40, 1.0),
                )
                else -> buildList {
                    add(PaneBox(0.0, 0.0, 0.60, 1.0))
                    add(PaneBox(0.60, 0.0, 0.40, 0.60))
                    addAll(equalStrip(n - 2, ox = 0.60, oy = 0.60, sx = 0.40, sy = 0.40, axis = "vertical"))
                }
            }
            "big-2-stack-right" -> when (n) {
                2 -> listOf(
                    PaneBox(0.40, 0.0, 0.60, 1.0),
                    PaneBox(0.0, 0.0, 0.40, 1.0),
                )
                else -> buildList {
                    add(PaneBox(0.40, 0.0, 0.60, 1.0))
                    add(PaneBox(0.0, 0.0, 0.40, 0.60))
                    addAll(equalStrip(n - 2, ox = 0.0, oy = 0.60, sx = 0.40, sy = 0.40, axis = "vertical"))
                }
            }
            "big-2-stack-bottom" -> when (n) {
                2 -> listOf(
                    PaneBox(0.0, 0.40, 1.0, 0.60),
                    PaneBox(0.0, 0.0, 1.0, 0.40),
                )
                else -> buildList {
                    add(PaneBox(0.0, 0.40, 1.0, 0.60))
                    add(PaneBox(0.0, 0.0, 0.60, 0.40))
                    addAll(equalStrip(n - 2, ox = 0.60, oy = 0.0, sx = 0.40, sy = 0.40, axis = "horizontal"))
                }
            }

            "columns" -> equalColumns(n)
            "rows" -> equalRows(n)
            else -> {
                val cols = kotlin.math.ceil(kotlin.math.sqrt(n.toDouble())).toInt().coerceAtLeast(1)
                val rows = kotlin.math.ceil(n.toDouble() / cols).toInt().coerceAtLeast(1)
                val w = 1.0 / cols
                val h = 1.0 / rows
                (0 until n).map { i ->
                    val r = i / cols
                    val c = i % cols
                    PaneBox(c * w, r * h, w, h)
                }
            }
        }
    }

    private fun equalStrip(
        count: Int,
        ox: Double,
        oy: Double,
        sx: Double,
        sy: Double,
        axis: String,
    ): List<PaneBox> {
        if (count <= 0) return emptyList()
        val out = ArrayList<PaneBox>(count)
        if (axis == "horizontal") {
            val bw = sx / count
            for (i in 0 until count) out.add(PaneBox(ox + i * bw, oy, bw, sy))
        } else {
            val bh = sy / count
            for (i in 0 until count) out.add(PaneBox(ox, oy + i * bh, sx, bh))
        }
        return out
    }

    private fun equalColumns(n: Int): List<PaneBox> = (0 until n).map { i ->
        val w = 1.0 / n
        PaneBox(i * w, 0.0, w, 1.0)
    }

    private fun equalRows(n: Int): List<PaneBox> = (0 until n).map { i ->
        val h = 1.0 / n
        PaneBox(0.0, i * h, 1.0, h)
    }

    fun addPaneToTab(windowId: String, tabId: String, initialCwd: String? = null): LeafNode? {
        val slot = getOrCreateSlot(windowId)
        synchronized(slot) {
            val cfg = slot._config.value
            val idx = cfg.tabs.indexOfFirst { it.id == tabId }
            if (idx < 0) return null
            val tab = cfg.tabs[idx]
            val sessionId = TerminalSessions.create(initialCwd = initialCwd)
            val fallbackTitle = "Session ${sessionId.removePrefix("s")}"
            val leaf = LeafNode(
                id = newNodeId(),
                sessionId = sessionId,
                cwd = initialCwd,
                title = computeLeafTitle(null, initialCwd, fallbackTitle),
                content = TerminalContent(sessionId),
            )
            val (ox, oy) = randomSnappedOrigin()
            val newPane = Pane(
                leaf = leaf,
                x = ox, y = oy,
                width = PaneGeometry.DEFAULT_SIZE,
                height = PaneGeometry.DEFAULT_SIZE,
                z = nextZ(tab),
            )
            val newTabs = cfg.tabs.toMutableList()
            val demoted = demoteMaximized(tab)
            newTabs[idx] = demoted.copy(panes = demoted.panes + newPane)
            slot._config.value = cfg.copy(tabs = newTabs)
            return leaf
        }
    }

    fun addFileBrowserToTab(windowId: String, tabId: String, initialCwd: String? = null): LeafNode? {
        val slot = getOrCreateSlot(windowId)
        synchronized(slot) {
            val cfg = slot._config.value
            val idx = cfg.tabs.indexOfFirst { it.id == tabId }
            if (idx < 0) return null
            val tab = cfg.tabs[idx]
            val leaf = LeafNode(
                id = newNodeId(),
                sessionId = "",
                cwd = initialCwd,
                title = computeLeafTitle(null, initialCwd, "Files"),
                content = FileBrowserContent(),
            )
            val (ox, oy) = randomSnappedOrigin()
            val newPane = Pane(
                leaf = leaf,
                x = ox, y = oy,
                width = PaneGeometry.DEFAULT_SIZE,
                height = PaneGeometry.DEFAULT_SIZE,
                z = nextZ(tab),
            )
            val newTabs = cfg.tabs.toMutableList()
            val demoted = demoteMaximized(tab)
            newTabs[idx] = demoted.copy(panes = demoted.panes + newPane)
            slot._config.value = cfg.copy(tabs = newTabs)
            return leaf
        }
    }

    fun addGitToTab(windowId: String, tabId: String, initialCwd: String? = null): LeafNode? {
        val slot = getOrCreateSlot(windowId)
        synchronized(slot) {
            val cfg = slot._config.value
            val idx = cfg.tabs.indexOfFirst { it.id == tabId }
            if (idx < 0) return null
            val tab = cfg.tabs[idx]
            val leaf = LeafNode(
                id = newNodeId(),
                sessionId = "",
                cwd = initialCwd,
                title = computeLeafTitle(null, initialCwd, "Git"),
                content = GitContent(),
            )
            val (ox, oy) = randomSnappedOrigin()
            val newPane = Pane(
                leaf = leaf,
                x = ox, y = oy,
                width = PaneGeometry.DEFAULT_SIZE,
                height = PaneGeometry.DEFAULT_SIZE,
                z = nextZ(tab),
            )
            val newTabs = cfg.tabs.toMutableList()
            val demoted = demoteMaximized(tab)
            newTabs[idx] = demoted.copy(panes = demoted.panes + newPane)
            slot._config.value = cfg.copy(tabs = newTabs)
            return leaf
        }
    }

    fun addLinkToTab(windowId: String, tabId: String, targetSessionId: String): LeafNode? {
        if (TerminalSessions.get(targetSessionId) == null) return null
        val slot = getOrCreateSlot(windowId)
        synchronized(slot) {
            val cfg = slot._config.value
            val idx = cfg.tabs.indexOfFirst { it.id == tabId }
            if (idx < 0) return null
            val tab = cfg.tabs[idx]
            val sourceTitle = findLeafBySession(cfg, targetSessionId)?.title ?: "Terminal"
            val leaf = LeafNode(
                id = newNodeId(),
                sessionId = targetSessionId,
                title = sourceTitle,
                content = TerminalContent(targetSessionId),
                isLink = true,
            )
            val (ox, oy) = randomSnappedOrigin()
            val newPane = Pane(
                leaf = leaf,
                x = ox, y = oy,
                width = PaneGeometry.DEFAULT_SIZE,
                height = PaneGeometry.DEFAULT_SIZE,
                z = nextZ(tab),
            )
            val newTabs = cfg.tabs.toMutableList()
            val demoted = demoteMaximized(tab)
            newTabs[idx] = demoted.copy(panes = demoted.panes + newPane)
            slot._config.value = cfg.copy(tabs = newTabs)
            return leaf
        }
    }

    fun movePaneToTab(windowId: String, paneId: String, targetTabId: String) {
        if (paneId.isEmpty() || targetTabId.isEmpty()) return
        val slot = getOrCreateSlot(windowId)
        synchronized(slot) {
            val cfg = slot._config.value
            val targetIdx = cfg.tabs.indexOfFirst { it.id == targetTabId }
            if (targetIdx < 0) return

            var sourceIdx = -1
            var movedLeaf: LeafNode? = null
            var newSourcePanes: List<Pane>? = null

            for ((idx, tab) in cfg.tabs.withIndex()) {
                val paneIdx = tab.panes.indexOfFirst { it.leaf.id == paneId }
                if (paneIdx >= 0) {
                    sourceIdx = idx
                    movedLeaf = tab.panes[paneIdx].leaf
                    newSourcePanes = tab.panes.toMutableList().also { it.removeAt(paneIdx) }
                    break
                }
            }
            if (sourceIdx < 0 || movedLeaf == null || newSourcePanes == null) return
            if (sourceIdx == targetIdx) return

            val newTabs = cfg.tabs.toMutableList()
            val sourceFocus = cfg.tabs[sourceIdx].focusedPaneId
            val wasFocusedInSource = sourceFocus == paneId
            val newSourceFocus = if (wasFocusedInSource) null else sourceFocus
            newTabs[sourceIdx] = cfg.tabs[sourceIdx].copy(
                panes = newSourcePanes,
                focusedPaneId = newSourceFocus,
            )
            val targetTab = newTabs[targetIdx]
            val (ox, oy) = randomSnappedOrigin()
            val newPane = Pane(
                leaf = movedLeaf,
                x = ox, y = oy,
                width = PaneGeometry.DEFAULT_SIZE,
                height = PaneGeometry.DEFAULT_SIZE,
                z = nextZ(targetTab),
            )
            val demotedTarget = demoteMaximized(targetTab)
            val newTargetFocus = if (wasFocusedInSource) paneId else demotedTarget.focusedPaneId
            newTabs[targetIdx] = demotedTarget.copy(
                panes = demotedTarget.panes + newPane,
                focusedPaneId = newTargetFocus,
            )
            slot._config.value = cfg.copy(tabs = newTabs)
        }
    }

    fun swapPanes(windowId: String, aId: String, bId: String) {
        if (aId.isEmpty() || bId.isEmpty() || aId == bId) return
        val slot = getOrCreateSlot(windowId)
        synchronized(slot) {
            val cfg = slot._config.value
            for ((tabIdx, tab) in cfg.tabs.withIndex()) {
                val aIdx = tab.panes.indexOfFirst { it.leaf.id == aId }
                val bIdx = tab.panes.indexOfFirst { it.leaf.id == bId }
                if (aIdx < 0 || bIdx < 0) continue
                val a = tab.panes[aIdx]
                val b = tab.panes[bIdx]
                val topZ = tab.panes.maxOf { it.z }
                val newPanes = tab.panes.toMutableList()
                newPanes[aIdx] = a.copy(
                    x = b.x, y = b.y, width = b.width, height = b.height,
                    z = topZ + 1,
                )
                newPanes[bIdx] = b.copy(x = a.x, y = a.y, width = a.width, height = a.height)
                val newTabs = cfg.tabs.toMutableList()
                newTabs[tabIdx] = tab.copy(panes = newPanes)
                slot._config.value = cfg.copy(tabs = newTabs)
                return
            }
        }
    }

    /**
     * Check whether [sessionId] is referenced by any leaf in any window's
     * current config. Used by session-destruction paths to make sure we
     * never kill a PTY a linked pane in a sibling window still needs.
     */
    fun isSessionReferencedAnywhere(sessionId: String): Boolean {
        if (sessionId.isEmpty()) return false
        for (slot in slots.values) {
            if (collectSessionIds(slot._config.value).contains(sessionId)) return true
        }
        return false
    }

    private fun collectSessionIds(cfg: WindowConfig): Set<String> {
        val out = HashSet<String>()
        fun add(leaf: LeafNode) {
            if (leaf.sessionId.isNotEmpty()) out.add(leaf.sessionId)
        }
        cfg.tabs.forEach { tab -> tab.panes.forEach { add(it.leaf) } }
        return out
    }

    private fun collectSessionIdsAcrossLiveSlots(): Set<String> {
        val out = HashSet<String>()
        for (slot in slots.values) out += collectSessionIds(slot._config.value)
        return out
    }

    /**
     * Destroy any PTY in [removedFromSlot] that isn't referenced by any
     * other slot's current config. Called after any mutation that drops
     * panes from [beforeSlot].
     */
    private fun destroyOrphanedSessions(beforeSlot: WindowSlot, removedFromSlot: Set<String>) {
        if (removedFromSlot.isEmpty()) return
        // `beforeSlot` is already synchronized by the caller.
        for (sid in removedFromSlot) {
            if (!isSessionReferencedAnywhere(sid)) {
                TerminalSessions.destroy(sid)
            }
        }
    }
}
