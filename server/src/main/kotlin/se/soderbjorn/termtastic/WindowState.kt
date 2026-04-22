/**
 * Window, tab, and pane state management for Termtastic.
 *
 * This file contains the [WindowState] singleton, which is the authoritative
 * source of truth for the entire window layout: tabs, the free-form list of
 * panes in each tab, and the per-pane file-browser and git state. Every
 * mutation flows through this object so the resulting [WindowConfig]
 * StateFlow is the single stream clients subscribe to.
 *
 * Also contains helper functions:
 *  - [prettifyPath] -- collapses `$HOME` to `~` for display titles.
 *  - [computeLeafTitle] -- resolves the display title for a pane.
 *  - [WindowConfig.withBlankSessionIds] -- strips live PTY ids before
 *    persisting to SQLite.
 *
 * Mutations are synchronized and emit new [WindowConfig] snapshots that the
 * `/window` WebSocket pushes to all connected clients. The debounced
 * persistence writer in [Application.main] picks up changes and writes them
 * to SQLite.
 *
 * @see WindowConfig
 * @see TabConfig
 * @see Pane
 * @see PaneGeometry the snap/clamp utility every geometry mutation routes through
 * @see TerminalSessions
 * @see SettingsRepository
 */
package se.soderbjorn.termtastic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import se.soderbjorn.termtastic.persistence.SettingsRepository
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

// The @Serializable data classes (WindowConfig, TabConfig, Pane, LeafNode,
// LeafContent + subclasses) live in the :clientServer KMP
// module so the web and android clients can deserialize the same wire types
// the server produces. See clientServer/src/commonMain/kotlin/se/soderbjorn/termtastic/.
//
// The mutation logic (the WindowState object below) stays server-side since
// it touches TerminalSessions, the SQLite persistence layer, and live PTYs.


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
 * Process-wide window state. All mutations funnel through here so the
 * resulting [config] flow is the only source clients need to subscribe to.
 *
 * Mutations are guarded by `synchronized(this)` because the cost of contention
 * is negligible (commands are infrequent and human-driven) and the alternative
 * — reasoning about reentrant locks while we also create/destroy PTYs — isn't
 * worth it for the volume.
 */
object WindowState {
    private val log = LoggerFactory.getLogger(WindowState::class.java)

    private val nodeIdCounter = AtomicLong(0)
    private val tabIdCounter = AtomicLong(0)

    private fun newNodeId(): String = "n${nodeIdCounter.incrementAndGet()}"
    private fun newTabId(): String = "t${tabIdCounter.incrementAndGet()}"

    // Starts empty; [initialize] populates it from disk or [buildDefault] on
    // first boot. Keeping the flow empty until initialize() runs avoids
    // creating throwaway PTYs that the loaded config would immediately replace.
    private val _config: MutableStateFlow<WindowConfig> = MutableStateFlow(WindowConfig(emptyList()))
    val config: StateFlow<WindowConfig> = _config.asStateFlow()

    @Volatile
    private var initialized: Boolean = false

    /**
     * One-shot bootstrap: try to restore the persisted window config, otherwise
     * fall back to a fresh default. Must be called from `main()` exactly once,
     * before any other access to [config].
     *
     * On a successful restore, persisted leaf [LeafNode.sessionId]s are
     * discarded (they refer to PTYs from a previous process) and replaced with
     * freshly minted [TerminalSessions]. Tab order, titles, and pane geometry
     * are preserved verbatim.
     */
    @Synchronized
    fun initialize(repo: SettingsRepository) {
        if (initialized) return
        initialized = true

        val loaded = repo.loadWindowConfig()
        val cfg = if (loaded != null && loaded.tabs.isNotEmpty()) {
            try {
                rehydrate(loaded, repo)
            } catch (t: Throwable) {
                log.warn("Failed to rehydrate persisted window config; using default", t)
                buildDefault()
            }
        } else {
            buildDefault()
        }
        _config.value = cfg

        // GC stored scrollback for leaves that no longer exist in the tree
        // (closed panes from previous sessions). Done after cfg is committed
        // so we GC against the authoritative post-rehydrate leaf set.
        runCatching {
            val live = HashSet<String>()
            for (tab in cfg.tabs) {
                tab.panes.forEach { live.add(it.leaf.id) }
            }
            for (stale in repo.allScrollbackLeafIds() - live) {
                repo.deleteScrollback(stale)
            }
        }.onFailure { log.warn("Scrollback GC failed", it) }
    }

    /**
     * Walk a freshly-loaded config and:
     *  1. mint a new [TerminalSessions] for every leaf, replacing the stale id;
     *  2. retarget the node/tab id counters past the highest persisted ids so
     *     subsequent panes/tabs don't collide.
     */
    private fun rehydrate(loaded: WindowConfig, repo: SettingsRepository): WindowConfig {
        var maxNodeId = 0L
        var maxTabId = 0L

        fun trackNodeId(id: String) {
            id.removePrefix("n").toLongOrNull()?.let { if (it > maxNodeId) maxNodeId = it }
        }

        // Walking a persisted leaf: terminal leaves get a freshly spawned PTY
        // (the persisted sessionId is dead — the previous process owned it).
        // Legacy leaves with `content == null` are treated as terminal so old
        // blobs round-trip unchanged.
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

        // Counters use incrementAndGet, so set them to the current max — the
        // next call returns max+1.
        nodeIdCounter.set(maxNodeId)
        tabIdCounter.set(maxTabId)

        // Validate the persisted activeTabId / focusedPaneId references —
        // anything that no longer exists gets cleared so the client falls back
        // cleanly to the first tab / first pane.
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
        // A fresh window starts with a single tab containing a single pane at
        // a snap-aligned medium position/size. Users can create more panes from
        // the new-window icon in the header.
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

    /**
     * Pick a random `(x, y)` origin on the 10% grid for a new pane of [size]
     * so the pane stays fully inside the tab area. Used by every "add pane"
     * entry point (new-window icon, empty-tab placeholder, cross-tab move).
     */
    private fun randomSnappedOrigin(size: Double = PaneGeometry.DEFAULT_SIZE): Pair<Double, Double> {
        val maxSteps = ((1.0 - size) / PaneGeometry.SNAP).toInt().coerceAtLeast(0)
        val sx = Random.nextInt(0, maxSteps + 1) * PaneGeometry.SNAP
        val sy = Random.nextInt(0, maxSteps + 1) * PaneGeometry.SNAP
        return sx to sy
    }

    /** Next available z in [tab]'s stacking order. */
    private fun nextZ(tab: TabConfig): Long =
        (tab.panes.maxOfOrNull { it.z } ?: 0L) + 1L

    /**
     * Return [tab] with every pane's `maximized` flag cleared. Adding a new
     * pane to a tab that already has one maximized would otherwise leave
     * the new pane hidden behind the full-screen sibling; demoting first
     * makes the new pane immediately visible.
     */
    private fun demoteMaximized(tab: TabConfig): TabConfig {
        if (tab.panes.none { it.maximized }) return tab
        return tab.copy(panes = tab.panes.map { if (it.maximized) it.copy(maximized = false) else it })
    }

    /**
     * Create a new tab with a single terminal pane and append it to the tab list.
     * The new pane gets a fresh PTY session.
     */
    fun addTab() = synchronized(this) {
        val cfg = _config.value
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
        _config.value = cfg.copy(tabs = cfg.tabs + newTab)
    }

    /**
     * Close the tab with [tabId], destroying any PTY sessions that are no
     * longer referenced by any remaining pane. No-op if there is only one
     * tab left (the UI has no way to recover from zero tabs).
     *
     * @param tabId the id of the tab to close
     */
    fun closeTab(tabId: String) = synchronized(this) {
        val cfg = _config.value
        // Never leave the user with zero tabs — the UI has no way to recover.
        if (cfg.tabs.size <= 1) return@synchronized
        if (cfg.tabs.none { it.id == tabId }) return@synchronized
        val before = collectSessionIds(cfg)
        val newTabs = cfg.tabs.filterNot { it.id == tabId }
        // If the closed tab was active, fall back to the tab that took its
        // visual slot (same index, clamped) so the user lands on a neighbour
        // instead of being teleported back to tab 1.
        val newActive = if (cfg.activeTabId == tabId) {
            val oldIdx = cfg.tabs.indexOfFirst { it.id == tabId }
            newTabs.getOrNull(oldIdx.coerceAtMost(newTabs.size - 1))?.id
        } else {
            cfg.activeTabId
        }
        val newCfg = cfg.copy(tabs = newTabs, activeTabId = newActive)
        _config.value = newCfg
        val after = collectSessionIds(newCfg)
        (before - after).forEach { TerminalSessions.destroy(it) }
    }

    /**
     * Mark [tabId] as the currently-selected tab. No-op if the id is unknown
     * or already active. Persisted via the StateFlow → debounced save flow.
     */
    fun setActiveTab(tabId: String) = synchronized(this) {
        val cfg = _config.value
        if (cfg.activeTabId == tabId) return@synchronized
        if (cfg.tabs.none { it.id == tabId }) return@synchronized
        _config.value = cfg.copy(activeTabId = tabId)
    }

    /**
     * Record that the user just focused [paneId] in [tabId]. The pane must
     * actually live in that tab; otherwise the call is ignored. Used by the
     * client to remember "where I was typing in this tab" so a tab switch
     * can restore focus to the right pane (and survive a server restart).
     */
    fun setFocusedPane(tabId: String, paneId: String) = synchronized(this) {
        val cfg = _config.value
        val tabIdx = cfg.tabs.indexOfFirst { it.id == tabId }
        if (tabIdx < 0) return@synchronized
        val tab = cfg.tabs[tabIdx]
        // Verify the pane still belongs to this tab — otherwise we'd persist
        // a stale reference that rehydrate() would have to clean up later.
        val livePanes = HashSet<String>()
        tab.panes.forEach { livePanes.add(it.leaf.id) }
        if (paneId !in livePanes) return@synchronized
        if (tab.focusedPaneId == paneId) return@synchronized
        val newTabs = cfg.tabs.toMutableList()
        newTabs[tabIdx] = tab.copy(focusedPaneId = paneId)
        _config.value = cfg.copy(tabs = newTabs)
    }

    /**
     * Move [tabId] so that it sits immediately before or after [targetTabId]
     * (depending on [before]). No-ops if either id is unknown, or if the tab
     * is already in the requested slot.
     */
    fun moveTab(tabId: String, targetTabId: String, before: Boolean) = synchronized(this) {
        if (tabId == targetTabId) return@synchronized
        val cfg = _config.value
        val srcIdx = cfg.tabs.indexOfFirst { it.id == tabId }
        val tgtIdx = cfg.tabs.indexOfFirst { it.id == targetTabId }
        if (srcIdx < 0 || tgtIdx < 0) return@synchronized

        val moving = cfg.tabs[srcIdx]
        // Remove first, then compute the insertion index against the
        // shortened list so "before/after target" stays correct regardless of
        // direction.
        val without = cfg.tabs.toMutableList().also { it.removeAt(srcIdx) }
        val newTargetIdx = without.indexOfFirst { it.id == targetTabId }
        val insertAt = if (before) newTargetIdx else newTargetIdx + 1
        // Skip no-op moves so we don't churn the config flow.
        if (insertAt == srcIdx) return@synchronized
        without.add(insertAt, moving)
        _config.value = cfg.copy(tabs = without)
    }

    /**
     * Set the display title of [tabId] to [title] (trimmed, max 80 chars).
     * No-op if the title is empty or unchanged.
     *
     * @param tabId the id of the tab to rename
     * @param title the new title text
     */
    fun renameTab(tabId: String, title: String) = synchronized(this) {
        val sanitized = title.trim().take(80)
        if (sanitized.isEmpty()) return@synchronized
        val cfg = _config.value
        var changed = false
        val newTabs = cfg.tabs.map { tab ->
            if (tab.id == tabId && tab.title != sanitized) {
                changed = true
                tab.copy(title = sanitized)
            } else tab
        }
        if (changed) _config.value = cfg.copy(tabs = newTabs)
    }

    /**
     * Find a leaf by id across all panes. Returns null if no leaf with
     * [paneId] exists. Used by file-browser / git command handlers that need
     * a leaf's cwd or current content state.
     */
    fun findLeaf(paneId: String): LeafNode? = synchronized(this) {
        val cfg = _config.value
        for (tab in cfg.tabs) {
            tab.panes.firstOrNull { it.leaf.id == paneId }?.let { return@synchronized it.leaf }
        }
        null
    }

    /**
     * Return the id of the tab that contains [paneId], or null if the pane
     * isn't found. Used by flows that want to add a sibling pane to the same
     * tab as an anchor (worktree creation, for instance).
     */
    fun tabIdOfPane(paneId: String): String? = synchronized(this) {
        val cfg = _config.value
        for (tab in cfg.tabs) {
            if (tab.panes.any { it.leaf.id == paneId }) return@synchronized tab.id
        }
        null
    }

    /** Find the first leaf that references [sessionId], across all tabs. */
    private fun findLeafBySession(cfg: WindowConfig, sessionId: String): LeafNode? {
        for (tab in cfg.tabs) {
            tab.panes.firstOrNull { it.leaf.sessionId == sessionId }?.let { return it.leaf }
        }
        return null
    }

    /**
     * Apply [transform] to the [FileBrowserContent] of pane [paneId], if it
     * exists and is currently a file-browser pane. Used by the small fleet of
     * setFileBrowser* commands so each handler doesn't have to walk the list
     * itself.
     */
    private fun updateFileBrowserContent(
        paneId: String,
        transform: (FileBrowserContent) -> FileBrowserContent,
    ): FileBrowserContent? {
        synchronized(this) {
            val cfg = _config.value
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
            if (newState != null) _config.value = newCfg
            return newState
        }
    }

    /**
     * Set the currently-selected file in the file-browser pane [paneId].
     *
     * @param paneId the leaf pane id
     * @param relPath relative path of the selected file, or null to clear selection
     * @return the updated [FileBrowserContent], or null if the pane was not found
     */
    fun setFileBrowserSelected(paneId: String, relPath: String?): FileBrowserContent? =
        updateFileBrowserContent(paneId) { it.copy(selectedRelPath = relPath) }

    /**
     * Toggle the expanded state of a directory in the file-browser tree.
     *
     * @param paneId the leaf pane id
     * @param dirRelPath relative path of the directory to expand/collapse
     * @param expanded true to expand, false to collapse
     * @return the updated [FileBrowserContent], or null if the pane was not found
     */
    fun setFileBrowserExpanded(paneId: String, dirRelPath: String, expanded: Boolean): FileBrowserContent? =
        updateFileBrowserContent(paneId) {
            val next = if (expanded) it.expandedDirs + dirRelPath else it.expandedDirs - dirRelPath
            if (next == it.expandedDirs) it else it.copy(expandedDirs = next)
        }

    fun setFileBrowserLeftWidth(paneId: String, px: Int): FileBrowserContent? {
        val clamped = px.coerceIn(0, 640)
        return updateFileBrowserContent(paneId) { it.copy(leftColumnWidthPx = clamped) }
    }

    fun setFileBrowserAutoRefresh(paneId: String, enabled: Boolean): FileBrowserContent? =
        updateFileBrowserContent(paneId) { it.copy(autoRefresh = enabled) }

    fun setFileBrowserFilter(paneId: String, filter: String): FileBrowserContent? {
        val normalized = filter.trim().ifEmpty { null }
        return updateFileBrowserContent(paneId) { it.copy(fileFilter = normalized) }
    }

    fun setFileBrowserSort(paneId: String, sort: FileBrowserSort): FileBrowserContent? =
        updateFileBrowserContent(paneId) {
            if (it.sortBy == sort) it else it.copy(sortBy = sort)
        }

    fun setFileBrowserExpandedAll(paneId: String, dirs: Set<String>): FileBrowserContent? =
        updateFileBrowserContent(paneId) {
            val merged = it.expandedDirs + dirs
            if (merged == it.expandedDirs) it else it.copy(expandedDirs = merged)
        }

    fun clearFileBrowserExpanded(paneId: String): FileBrowserContent? =
        updateFileBrowserContent(paneId) {
            if (it.expandedDirs.isEmpty()) it else it.copy(expandedDirs = emptySet())
        }

    fun setFileBrowserFontSize(paneId: String, size: Int): FileBrowserContent? {
        val clamped = size.coerceIn(8, 24)
        return updateFileBrowserContent(paneId) { it.copy(fontSize = clamped) }
    }

    // ---- Terminal pane mutations --------------------------------------------

    private fun updateTerminalContent(
        paneId: String,
        transform: (TerminalContent) -> TerminalContent,
    ): TerminalContent? {
        synchronized(this) {
            val cfg = _config.value
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
            if (newState != null) _config.value = newCfg
            return newState
        }
    }

    fun setTerminalFontSize(paneId: String, size: Int): TerminalContent? {
        val clamped = size.coerceIn(8, 24)
        return updateTerminalContent(paneId) { it.copy(fontSize = clamped) }
    }

    // ---- Git pane mutations ------------------------------------------------

    private fun updateGitContent(
        paneId: String,
        transform: (GitContent) -> GitContent,
    ): GitContent? {
        synchronized(this) {
            val cfg = _config.value
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
            if (newState != null) _config.value = newCfg
            return newState
        }
    }


    /**
     * Set the currently-selected file in the git pane [paneId].
     *
     * @param paneId the leaf pane id
     * @param filePath the selected file path, or null to clear selection
     * @return the updated [GitContent], or null if the pane was not found
     */
    fun setGitSelected(paneId: String, filePath: String?): GitContent? =
        updateGitContent(paneId) { it.copy(selectedFilePath = filePath) }

    fun setGitLeftWidth(paneId: String, px: Int): GitContent? {
        val clamped = px.coerceIn(0, 640)
        return updateGitContent(paneId) { it.copy(leftColumnWidthPx = clamped) }
    }

    fun setGitDiffMode(paneId: String, mode: GitDiffMode): GitContent? =
        updateGitContent(paneId) { it.copy(diffMode = mode) }

    fun setGitGraphicalDiff(paneId: String, enabled: Boolean): GitContent? =
        updateGitContent(paneId) { it.copy(graphicalDiff = enabled) }

    fun setGitDiffFontSize(paneId: String, size: Int): GitContent? {
        val clamped = size.coerceIn(8, 24)
        return updateGitContent(paneId) { it.copy(diffFontSize = clamped) }
    }

    fun setGitAutoRefresh(paneId: String, enabled: Boolean): GitContent? =
        updateGitContent(paneId) { it.copy(autoRefresh = enabled) }

    /**
     * Remove the pane [paneId] from its tab. Destroys any PTY session that is
     * no longer referenced by any remaining pane. Tabs are not removed even
     * if they become empty — the empty-state placeholder lets the user create
     * a new pane in-place.
     *
     * @param paneId the id of the pane to close
     */
    fun closePane(paneId: String) = synchronized(this) {
        val cfg = _config.value
        val before = collectSessionIds(cfg)
        val newTabs = cfg.tabs.map { tab ->
            val newPanes = tab.panes.filterNot { it.leaf.id == paneId }
            // Drop the tab's saved focus if it pointed at the pane we're
            // killing — otherwise the next render would chase a ghost.
            val newFocus = if (tab.focusedPaneId == paneId) null else tab.focusedPaneId
            if (newPanes.size == tab.panes.size &&
                newFocus == tab.focusedPaneId
            ) tab
            else tab.copy(panes = newPanes, focusedPaneId = newFocus)
        }
        val newCfg = cfg.copy(tabs = newTabs)
        if (newCfg == cfg) return@synchronized
        _config.value = newCfg
        val after = collectSessionIds(newCfg)
        (before - after).forEach { TerminalSessions.destroy(it) }
    }

    /**
     * Close every pane that references [sessionId]. Used when the user
     * confirms closing a terminal that has linked panes — all views of the
     * session are removed and the PTY is destroyed.
     */
    fun closeSession(sessionId: String) = synchronized(this) {
        if (sessionId.isEmpty()) return@synchronized
        val cfg = _config.value
        val before = collectSessionIds(cfg)

        val newTabs = cfg.tabs.map { tab ->
            val newPanes = tab.panes.filterNot { it.leaf.sessionId == sessionId }
            val liveIds = HashSet<String>()
            newPanes.forEach { liveIds.add(it.leaf.id) }
            val newFocus = tab.focusedPaneId?.takeIf { it in liveIds }
            tab.copy(panes = newPanes, focusedPaneId = newFocus)
        }
        val newCfg = cfg.copy(tabs = newTabs)
        if (newCfg == cfg) return@synchronized
        _config.value = newCfg
        val after = collectSessionIds(newCfg)
        (before - after).forEach { TerminalSessions.destroy(it) }
    }

    /**
     * Set or clear the custom display name for [paneId]. An empty [title]
     * clears the custom name and lets the cwd-based title take over.
     *
     * @param paneId the id of the pane to rename
     * @param title the new custom name, or empty to clear
     */
    fun renamePane(paneId: String, title: String) = synchronized(this) {
        val sanitized = title.trim().take(80)
        // Empty input clears the custom name and lets the cwd-based title take
        // over (today's web client suppresses empty submissions, so this is
        // forward-compat for an "unname" affordance).
        val newCustomName: String? = sanitized.ifEmpty { null }
        val cfg = _config.value
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
        if (changed) _config.value = newCfg
    }

    /**
     * Push a freshly-detected working directory for the pane backed by
     * [sessionId]. No-ops if the cwd hasn't changed; recomputes [LeafNode.title]
     * so a pane that has no custom name reflects the new directory.
     */
    fun updatePaneCwd(sessionId: String, cwd: String) = synchronized(this) {
        if (cwd.isBlank()) return@synchronized
        val cfg = _config.value
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
        if (changed) _config.value = newCfg
    }

    /**
     * Update the position and size of a pane after the user drags or resizes
     * it. Inputs are snapped and clamped via [PaneGeometry.normalize] so the
     * pane always lands on the 10% grid and stays fully inside the tab area.
     * No-op if [paneId] isn't found.
     */
    fun setPaneGeometry(
        paneId: String,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
    ) = synchronized(this) {
        val box = PaneGeometry.normalize(x, y, width, height)
        val cfg = _config.value
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
        if (changed) _config.value = cfg.copy(tabs = newTabs)
    }

    /**
     * Override or clear the per-pane color-scheme assignment.
     *
     * @param paneId the pane whose [Pane.colorScheme] to set
     * @param scheme the scheme name, or `null` to clear the override
     * @see WindowCommand.SetPaneColorScheme
     * @see setPaneGeometry for the sibling pane-level mutator pattern
     */
    fun setPaneColorScheme(paneId: String, scheme: String?) = synchronized(this) {
        val cfg = _config.value
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
        if (changed) _config.value = cfg.copy(tabs = newTabs)
    }

    /**
     * Bring the pane [paneId] to the top of its tab's stacking order by
     * setting its z to `max + 1`. No-op if the pane isn't found or is
     * already strictly on top.
     */
    fun raisePane(paneId: String) = synchronized(this) {
        val cfg = _config.value
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
        if (changed) _config.value = cfg.copy(tabs = newTabs)
    }

    /**
     * Toggle the maximized flag on [paneId]. When becoming maximized, any
     * other maximized pane in the same tab is demoted and this pane's z is
     * bumped to the top. When restoring, the pane's stored geometry is left
     * untouched so it returns to its prior size/position.
     */
    fun toggleMaximized(paneId: String) = synchronized(this) {
        val cfg = _config.value
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
        if (changed) _config.value = cfg.copy(tabs = newTabs)
    }

    /**
     * Arrange every pane in [tabId] using one of the predefined layout
     * algorithms. The pane with [primaryPaneId] (or the first pane if that
     * is null/invalid) always wins slot 0 — the biggest slot. The remaining
     * panes are assigned to the other slots ordered by **descending current
     * area** (width × height), so the user's manual sizing is preserved as a
     * priority signal: whatever they've grown becomes second-biggest, and so
     * on. Tab order is only consulted to break ties between equal-area panes.
     * Also clears any maximized flag so the layout takes effect visually.
     */
    fun applyLayout(tabId: String, layout: String, primaryPaneId: String?) = synchronized(this) {
        val cfg = _config.value
        val tabIdx = cfg.tabs.indexOfFirst { it.id == tabId }
        if (tabIdx < 0) return@synchronized
        val tab = cfg.tabs[tabIdx]
        if (tab.panes.isEmpty()) return@synchronized

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
        _config.value = cfg.copy(tabs = newTabs)
    }

    /**
     * Compute the list of pane boxes for [layout] with [n] total panes. Index
     * 0 is always the slot the caller should assign to the focused (biggest)
     * pane; subsequent indices are the remaining, uniformly-sized sibling
     * slots so the caller can assign area-ranked panes in order.
     *
     * Every layout is designed to produce at most three distinct size classes
     * (primary, secondary, rest-equal), with the smallest slot's dominant
     * dimension never falling below ~25% at common pane counts. There is no
     * cascade family here: strips are always filled with equal-sized slots,
     * so no pane ends up as a super-narrow sliver.
     *
     * Layout families:
     *  - **grid / columns / rows** — size-neutral resets (1 size class).
     *  - **hero-** — 65/35 split, primary big, siblings in equal-size strip.
     *  - **split-** — 50/50 split, primary half, siblings in equal-size strip.
     *  - **sidebar-** — 75/25 split with a narrow strip of equal sibling cells.
     *  - **t-shape / t-shape-inv** — 70/30 two-cell bar + equal-size strip.
     *  - **l-shape / l-shape-tr / l-shape-bl / l-shape-br** — corner hero +
     *    full-edge sibling + equal strip along the primary's remaining edge.
     *  - **big-2-stack / -right / -bottom** — wide primary + one medium +
     *    stacked equal siblings in the remaining quadrant.
     *
     * Unknown [layout] keys fall back to [grid] for robustness.
     *
     * @param layout the layout key (must match one in `LayoutMenu.kt`)
     * @param n the number of panes to place; must be ≥ 0
     * @return a list of [PaneBox] of size [n] in rank order
     * @see equalStrip
     */
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
                // n=2 has no bottom row to fill, so degrade to the hero-left
                // shape at 70/30 so the layout still feels like itself.
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
                // "grid" (default): even tiling with primary at top-left.
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

    /**
     * Fill a rectangular strip with [count] equally-sized slots along [axis].
     * The strip's top-left is at (`ox`, `oy`) and it spans (`sx`, `sy`) of
     * the tab area. For `"horizontal"` the slots share `sy` as height and
     * split `sx` into equal widths; for `"vertical"` they share `sx` as
     * width and split `sy` into equal heights.
     *
     * Equal sizing keeps every layout at ≤ 3 size classes (primary +
     * optional secondary + rest-equal) and avoids cascade-produced slivers.
     *
     * @param count number of slots to produce; 0 returns an empty list
     * @param ox strip origin x (fraction of tab area)
     * @param oy strip origin y (fraction of tab area)
     * @param sx strip width (fraction of tab area)
     * @param sy strip height (fraction of tab area)
     * @param axis `"horizontal"` or `"vertical"`
     * @return list of [PaneBox] of identical size
     */
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

    /**
     * Tile the full tab area into [n] equal-width columns, primary at left.
     * Used by the `columns` layout.
     */
    private fun equalColumns(n: Int): List<PaneBox> = (0 until n).map { i ->
        val w = 1.0 / n
        PaneBox(i * w, 0.0, w, 1.0)
    }

    /**
     * Tile the full tab area into [n] equal-height rows, primary at top.
     * Used by the `rows` layout.
     */
    private fun equalRows(n: Int): List<PaneBox> = (0 until n).map { i ->
        val h = 1.0 / n
        PaneBox(0.0, i * h, 1.0, h)
    }

    /**
     * Spawn a fresh shell as a new pane in [tabId]. Used by the new-window
     * icon and by the empty-tab placeholder's "New pane" button. If
     * [initialCwd] is provided the new shell starts there; otherwise the
     * new PTY inherits the user's home.
     */
    fun addPaneToTab(tabId: String, initialCwd: String? = null): LeafNode? = synchronized(this) {
        val cfg = _config.value
        val idx = cfg.tabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return@synchronized null
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
        _config.value = cfg.copy(tabs = newTabs)
        leaf
    }

    /** Add a file-browser pane to [tabId]. */
    fun addFileBrowserToTab(tabId: String, initialCwd: String? = null): LeafNode? = synchronized(this) {
        val cfg = _config.value
        val idx = cfg.tabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return@synchronized null
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
        _config.value = cfg.copy(tabs = newTabs)
        leaf
    }

    /** Add a git overview pane to [tabId]. */
    fun addGitToTab(tabId: String, initialCwd: String? = null): LeafNode? = synchronized(this) {
        val cfg = _config.value
        val idx = cfg.tabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return@synchronized null
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
        _config.value = cfg.copy(tabs = newTabs)
        leaf
    }

    /**
     * Add a linked terminal pane to [tabId] that shares the PTY session
     * [targetSessionId]. No new process is spawned.
     */
    fun addLinkToTab(tabId: String, targetSessionId: String): LeafNode? = synchronized(this) {
        if (TerminalSessions.get(targetSessionId) == null) return@synchronized null
        val cfg = _config.value
        val idx = cfg.tabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return@synchronized null
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
        _config.value = cfg.copy(tabs = newTabs)
        leaf
    }

    /**
     * Move the pane [paneId] from whichever tab currently holds it into
     * [targetTabId]. The pane lands at a random snapped origin on top of
     * any existing panes in the target.
     */
    fun movePaneToTab(paneId: String, targetTabId: String) = synchronized(this) {
        if (paneId.isEmpty() || targetTabId.isEmpty()) return@synchronized
        val cfg = _config.value
        val targetIdx = cfg.tabs.indexOfFirst { it.id == targetTabId }
        if (targetIdx < 0) return@synchronized

        // Locate the source.
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
        if (sourceIdx < 0 || movedLeaf == null || newSourcePanes == null) return@synchronized
        if (sourceIdx == targetIdx) return@synchronized

        val newTabs = cfg.tabs.toMutableList()
        // Clear the source tab's saved focus if it pointed at the moving
        // pane — that pane no longer lives in the source tab. Remember
        // whether the moving pane was the source's focused one so we can
        // carry that focus into the target tab; otherwise a cross-tab move
        // would silently deactivate the pane.
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
        _config.value = cfg.copy(tabs = newTabs)
    }

    /**
     * Swap the positions and sizes of two panes that share a tab. Pane A
     * (the drag source) inherits B's x/y/width/height and is raised to the
     * top of the stacking order so the user's dragged pane stays visually
     * on top; pane B takes A's former geometry and keeps its existing z.
     * No-op if either id is missing, if they are the same pane, or if they
     * live in different tabs — cross-tab moves go through [movePaneToTab].
     *
     * Dispatched from the web client when the user drops a pane's header
     * icon onto another pane in the same tab.
     */
    fun swapPanes(aId: String, bId: String) = synchronized(this) {
        if (aId.isEmpty() || bId.isEmpty() || aId == bId) return@synchronized
        val cfg = _config.value
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
            _config.value = cfg.copy(tabs = newTabs)
            return@synchronized
        }
    }

    /**
     * Check whether [sessionId] is referenced by any leaf in the current config.
     *
     * @param sessionId the terminal session id to look for
     * @return true if at least one leaf references this session
     */
    fun hasSession(sessionId: String): Boolean =
        collectSessionIds(_config.value).contains(sessionId)

    private fun collectSessionIds(cfg: WindowConfig): Set<String> {
        val out = HashSet<String>()
        fun add(leaf: LeafNode) {
            // Skip non-terminal leaves: they have no PTY to track. Even for
            // terminal leaves we guard against the empty string, which only
            // appears in transient states (e.g. just-blanked persisted blobs).
            if (leaf.sessionId.isNotEmpty()) out.add(leaf.sessionId)
        }
        cfg.tabs.forEach { tab ->
            tab.panes.forEach { add(it.leaf) }
        }
        return out
    }
}
