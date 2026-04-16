package se.soderbjorn.termtastic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import se.soderbjorn.termtastic.persistence.SettingsRepository
import java.util.concurrent.atomic.AtomicLong

// The @Serializable data classes (WindowConfig, TabConfig, PaneNode, LeafNode,
// LeafContent + subclasses, SplitNode, SplitOrientation, SplitDirection,
// FloatingPane, PoppedOutPane) now live in the :clientServer KMP module so
// the web and android clients can deserialize the same wire types the server
// produces. See clientServer/src/commonMain/kotlin/se/soderbjorn/termtastic/.
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
    fun strip(node: PaneNode): PaneNode = when (node) {
        is LeafNode -> {
            val newContent = blankContent(node.content)
            if (node.sessionId.isEmpty() && newContent === node.content) node
            else node.copy(sessionId = "", content = newContent)
        }
        is SplitNode -> node.copy(first = strip(node.first), second = strip(node.second))
    }
    fun stripLeaf(leaf: LeafNode): LeafNode {
        val newContent = blankContent(leaf.content)
        return if (leaf.sessionId.isEmpty() && newContent === leaf.content) leaf
        else leaf.copy(sessionId = "", content = newContent)
    }
    return copy(
        tabs = tabs.map { tab ->
            tab.copy(
                root = tab.root?.let(::strip),
                floating = tab.floating.map { fp -> fp.copy(leaf = stripLeaf(fp.leaf)) },
                poppedOut = tab.poppedOut.map { po -> po.copy(leaf = stripLeaf(po.leaf)) },
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
     * freshly minted [TerminalSessions]. Tab order, titles, split orientations
     * and ratios are preserved verbatim.
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
                tab.root?.let { collectLeafIds(it, live) }
                tab.floating.forEach { live.add(it.leaf.id) }
                tab.poppedOut.forEach { live.add(it.leaf.id) }
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
     *     subsequent splits/tabs don't collide.
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

        fun rebuild(node: PaneNode): PaneNode = when (node) {
            is LeafNode -> rebuildLeaf(node)
            is SplitNode -> {
                trackNodeId(node.id)
                node.copy(
                    first = rebuild(node.first),
                    second = rebuild(node.second)
                )
            }
        }

        val rebuiltTabs = loaded.tabs.map { tab ->
            tab.id.removePrefix("t").toLongOrNull()?.let { if (it > maxTabId) maxTabId = it }
            // Popped-out panes dock back into the tree on rehydrate: the
            // Electron windows that displayed them don't survive a server
            // restart, so leaving them in `poppedOut` would orphan the PTY.
            // Each popped-out leaf becomes either the new root (if empty) or
            // the right child of a horizontal split wrapping the current root
            // — same rule as toggleFloating's dock path and movePaneToTab.
            var mergedRoot: PaneNode? = tab.root?.let(::rebuild)
            for (po in tab.poppedOut) {
                val leaf = rebuildLeaf(po.leaf)
                mergedRoot = when (val r = mergedRoot) {
                    null -> leaf
                    else -> {
                        maxNodeId += 1
                        SplitNode(
                            id = "n$maxNodeId",
                            orientation = SplitOrientation.Horizontal,
                            ratio = 0.5,
                            first = r,
                            second = leaf,
                        )
                    }
                }
            }
            tab.copy(
                root = mergedRoot,
                floating = tab.floating.map { fp -> fp.copy(leaf = rebuildLeaf(fp.leaf)) },
                poppedOut = emptyList(),
            )
        }

        // Counters use incrementAndGet, so set them to the current max — the
        // next call returns max+1.
        nodeIdCounter.set(maxNodeId)
        tabIdCounter.set(maxTabId)

        // Validate the persisted activeTabId / focusedPaneId references —
        // anything that no longer exists in the rebuilt tree gets cleared so
        // the client falls back cleanly to the first tab / first pane.
        val tabIdSet = rebuiltTabs.map { it.id }.toSet()
        val validatedActive = loaded.activeTabId?.takeIf { it in tabIdSet }
        val sanitizedTabs = rebuiltTabs.map { tab ->
            val livePaneIds = HashSet<String>()
            tab.root?.let { collectLeafIds(it, livePaneIds) }
            tab.floating.forEach { livePaneIds.add(it.leaf.id) }
            tab.poppedOut.forEach { livePaneIds.add(it.leaf.id) }
            val keepFocus = tab.focusedPaneId?.takeIf { it in livePaneIds }
            if (keepFocus == tab.focusedPaneId) tab else tab.copy(focusedPaneId = keepFocus)
        }
        return WindowConfig(tabs = sanitizedTabs, activeTabId = validatedActive)
    }

    private fun collectLeafIds(node: PaneNode, out: HashSet<String>) {
        when (node) {
            is LeafNode -> out.add(node.id)
            is SplitNode -> {
                collectLeafIds(node.first, out)
                collectLeafIds(node.second, out)
            }
        }
    }


    private fun buildDefault(): WindowConfig {
        // A fresh window starts with a single tab containing a single pane.
        // Users can split panes and add/remove tabs from the UI.
        val s1 = TerminalSessions.create()
        val tab1 = TabConfig(
            id = newTabId(),
            title = "Tab 1",
            root = LeafNode(
                id = newNodeId(),
                sessionId = s1,
                title = "Session ${s1.removePrefix("s")}",
                content = TerminalContent(s1),
            )
        )
        return WindowConfig(listOf(tab1))
    }

    fun addTab() = synchronized(this) {
        val cfg = _config.value
        val sessionId = TerminalSessions.create()
        val nextNumber = cfg.tabs.size + 1
        val newTab = TabConfig(
            id = newTabId(),
            title = "Tab $nextNumber",
            root = LeafNode(
                id = newNodeId(),
                sessionId = sessionId,
                title = "Session ${sessionId.removePrefix("s")}",
                content = TerminalContent(sessionId),
            )
        )
        _config.value = cfg.copy(tabs = cfg.tabs + newTab)
    }

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
        tab.root?.let { collectLeafIds(it, livePanes) }
        tab.floating.forEach { livePanes.add(it.leaf.id) }
        tab.poppedOut.forEach { livePanes.add(it.leaf.id) }
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
     * Split [paneId] and create a new terminal pane in the given [direction].
     * The new pane inherits the cwd of the anchor pane and gets a fresh PTY.
     * Returns the newly created [LeafNode], or null if [paneId] wasn't found.
     */
    fun splitTerminal(paneId: String, direction: SplitDirection): LeafNode? = synchronized(this) {
        val cfg = _config.value
        val orientation = directionToOrientation(direction)
        val newLeafFirst = direction == SplitDirection.Left || direction == SplitDirection.Up
        var created: LeafNode? = null
        val newCfg = cfg.copy(
            tabs = cfg.tabs.map { tab ->
                val root = tab.root ?: return@map tab
                tab.copy(root = transformLeaf(root, paneId) { leaf ->
                    val inheritedCwd = leaf.cwd
                    val newSession = TerminalSessions.create(initialCwd = inheritedCwd)
                    val fallbackTitle = "Session ${newSession.removePrefix("s")}"
                    val newLeaf = LeafNode(
                        id = newNodeId(),
                        sessionId = newSession,
                        cwd = inheritedCwd,
                        title = computeLeafTitle(null, inheritedCwd, fallbackTitle),
                        content = TerminalContent(newSession),
                    )
                    created = newLeaf
                    SplitNode(
                        id = newNodeId(),
                        orientation = orientation,
                        ratio = 0.5,
                        first = if (newLeafFirst) newLeaf else leaf,
                        second = if (newLeafFirst) leaf else newLeaf
                    )
                })
            }
        )
        if (created != null) _config.value = newCfg
        created
    }

    /**
     * Split [paneId] and create a new file-browser pane in the given [direction].
     * Returns the newly created [LeafNode], or null if [paneId] wasn't found.
     */
    fun splitFileBrowser(paneId: String, direction: SplitDirection): LeafNode? = synchronized(this) {
        val cfg = _config.value
        val orientation = directionToOrientation(direction)
        val newLeafFirst = direction == SplitDirection.Left || direction == SplitDirection.Up
        var created: LeafNode? = null
        val newCfg = cfg.copy(
            tabs = cfg.tabs.map { tab ->
                val root = tab.root ?: return@map tab
                tab.copy(root = transformLeaf(root, paneId) { leaf ->
                    val inheritedCwd = leaf.cwd
                    val newLeaf = LeafNode(
                        id = newNodeId(),
                        sessionId = "",
                        cwd = inheritedCwd,
                        title = computeLeafTitle(null, inheritedCwd, "Files"),
                        content = FileBrowserContent(),
                    )
                    created = newLeaf
                    SplitNode(
                        id = newNodeId(),
                        orientation = orientation,
                        ratio = 0.5,
                        first = if (newLeafFirst) newLeaf else leaf,
                        second = if (newLeafFirst) leaf else newLeaf
                    )
                })
            }
        )
        if (created != null) _config.value = newCfg
        created
    }

    /**
     * Split [paneId] and create a linked terminal pane pointing at
     * [targetSessionId] in the given [direction]. The linked pane shares the
     * same PTY session — no new process is spawned. Returns the newly created
     * [LeafNode], or null if [paneId] or [targetSessionId] wasn't found.
     */
    fun splitLink(paneId: String, direction: SplitDirection, targetSessionId: String): LeafNode? = synchronized(this) {
        if (TerminalSessions.get(targetSessionId) == null) return@synchronized null
        val cfg = _config.value
        val orientation = directionToOrientation(direction)
        val newLeafFirst = direction == SplitDirection.Left || direction == SplitDirection.Up

        // Find the source pane's title for the link label.
        val sourceTitle = findLeafBySession(cfg, targetSessionId)?.title ?: "Terminal"

        var created: LeafNode? = null
        val newCfg = cfg.copy(
            tabs = cfg.tabs.map { tab ->
                val root = tab.root ?: return@map tab
                tab.copy(root = transformLeaf(root, paneId) { leaf ->
                    val newLeaf = LeafNode(
                        id = newNodeId(),
                        sessionId = targetSessionId,
                        cwd = leaf.cwd,
                        title = sourceTitle,
                        content = TerminalContent(targetSessionId),
                        isLink = true,
                    )
                    created = newLeaf
                    SplitNode(
                        id = newNodeId(),
                        orientation = orientation,
                        ratio = 0.5,
                        first = if (newLeafFirst) newLeaf else leaf,
                        second = if (newLeafFirst) leaf else newLeaf
                    )
                })
            }
        )
        if (created != null) _config.value = newCfg
        created
    }

    private fun directionToOrientation(direction: SplitDirection) = when (direction) {
        SplitDirection.Left, SplitDirection.Right -> SplitOrientation.Horizontal
        SplitDirection.Up, SplitDirection.Down -> SplitOrientation.Vertical
    }

    /** Find the first leaf that references [sessionId], across all tabs. */
    private fun findLeafBySession(cfg: WindowConfig, sessionId: String): LeafNode? {
        fun walk(node: PaneNode): LeafNode? = when (node) {
            is LeafNode -> if (node.sessionId == sessionId) node else null
            is SplitNode -> walk(node.first) ?: walk(node.second)
        }
        for (tab in cfg.tabs) {
            tab.root?.let { walk(it) }?.let { return it }
            tab.floating.firstOrNull { it.leaf.sessionId == sessionId }?.let { return it.leaf }
            tab.poppedOut.firstOrNull { it.leaf.sessionId == sessionId }?.let { return it.leaf }
        }
        return null
    }

    /**
     * Find a leaf by id across docked, floating and popped-out positions.
     * Returns null if no leaf with [paneId] exists. Used by file-browser
     * command handlers that need a leaf's cwd or current [FileBrowserContent].
     */
    fun findLeaf(paneId: String): LeafNode? = synchronized(this) {
        val cfg = _config.value
        for (tab in cfg.tabs) {
            tab.root?.let { root ->
                findLeafIn(root, paneId)?.let { return@synchronized it }
            }
            tab.floating.firstOrNull { it.leaf.id == paneId }?.let { return@synchronized it.leaf }
            tab.poppedOut.firstOrNull { it.leaf.id == paneId }?.let { return@synchronized it.leaf }
        }
        null
    }

    private fun findLeafIn(node: PaneNode, paneId: String): LeafNode? = when (node) {
        is LeafNode -> if (node.id == paneId) node else null
        is SplitNode -> findLeafIn(node.first, paneId) ?: findLeafIn(node.second, paneId)
    }

    /**
     * Apply [transform] to the [FileBrowserContent] of pane [paneId], if it
     * exists and is currently a file-browser pane. Used by the small fleet of
     * setFileBrowser* commands so each handler doesn't have to walk the tree
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
                        root = tab.root?.let { transformAllLeaves(it, ::mutate) },
                        floating = tab.floating.map { fp -> fp.copy(leaf = mutate(fp.leaf)) },
                        poppedOut = tab.poppedOut.map { po -> po.copy(leaf = mutate(po.leaf)) },
                    )
                }
            )
            if (newState != null) _config.value = newCfg
            return newState
        }
    }

    fun setFileBrowserSelected(paneId: String, relPath: String?): FileBrowserContent? =
        updateFileBrowserContent(paneId) { it.copy(selectedRelPath = relPath) }

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
                        root = tab.root?.let { transformAllLeaves(it, ::mutate) },
                        floating = tab.floating.map { fp -> fp.copy(leaf = mutate(fp.leaf)) },
                        poppedOut = tab.poppedOut.map { po -> po.copy(leaf = mutate(po.leaf)) },
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

    /**
     * Split [paneId] and create a new git pane in the given [direction].
     * Returns the newly created [LeafNode], or null if [paneId] wasn't found.
     */
    fun splitGit(paneId: String, direction: SplitDirection): LeafNode? = synchronized(this) {
        val cfg = _config.value
        val orientation = directionToOrientation(direction)
        val newLeafFirst = direction == SplitDirection.Left || direction == SplitDirection.Up
        var created: LeafNode? = null
        val newCfg = cfg.copy(
            tabs = cfg.tabs.map { tab ->
                val root = tab.root ?: return@map tab
                tab.copy(root = transformLeaf(root, paneId) { leaf ->
                    val inheritedCwd = leaf.cwd
                    val newLeaf = LeafNode(
                        id = newNodeId(),
                        sessionId = "",
                        cwd = inheritedCwd,
                        title = computeLeafTitle(null, inheritedCwd, "Git"),
                        content = GitContent(),
                    )
                    created = newLeaf
                    SplitNode(
                        id = newNodeId(),
                        orientation = orientation,
                        ratio = 0.5,
                        first = if (newLeafFirst) newLeaf else leaf,
                        second = if (newLeafFirst) leaf else newLeaf
                    )
                })
            }
        )
        if (created != null) _config.value = newCfg
        created
    }

    /** Add a git pane as the sole pane in an empty tab. */
    fun addGitToEmptyTab(tabId: String): LeafNode? = synchronized(this) {
        val cfg = _config.value
        val idx = cfg.tabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return@synchronized null
        val tab = cfg.tabs[idx]
        if (tab.root != null || tab.floating.isNotEmpty()) return@synchronized null
        val leaf = LeafNode(
            id = newNodeId(),
            sessionId = "",
            title = "Git",
            content = GitContent(),
        )
        val newTabs = cfg.tabs.toMutableList()
        newTabs[idx] = tab.copy(root = leaf)
        _config.value = cfg.copy(tabs = newTabs)
        leaf
    }

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
                        root = tab.root?.let { transformAllLeaves(it, ::mutate) },
                        floating = tab.floating.map { fp -> fp.copy(leaf = mutate(fp.leaf)) },
                        poppedOut = tab.poppedOut.map { po -> po.copy(leaf = mutate(po.leaf)) },
                    )
                }
            )
            if (newState != null) _config.value = newCfg
            return newState
        }
    }


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

    fun closePane(paneId: String) = synchronized(this) {
        val cfg = _config.value
        val before = collectSessionIds(cfg)
        // Tabs are *not* dropped here even if they end up with no panes. The
        // empty-state placeholder lets the user create a new pane in-place;
        // tab removal is now exclusively the job of [closeTab].
        val newTabs = cfg.tabs.map { tab ->
            val newRoot = tab.root?.let { removeLeaf(it, paneId) }
            val newFloating = tab.floating.filterNot { it.leaf.id == paneId }
            val newPoppedOut = tab.poppedOut.filterNot { it.leaf.id == paneId }
            // Drop the tab's saved focus if it pointed at the pane we're
            // killing — otherwise the next render would chase a ghost.
            val newFocus = if (tab.focusedPaneId == paneId) null else tab.focusedPaneId
            if (newRoot === tab.root &&
                newFloating.size == tab.floating.size &&
                newPoppedOut.size == tab.poppedOut.size &&
                newFocus == tab.focusedPaneId
            ) tab
            else tab.copy(root = newRoot, floating = newFloating, poppedOut = newPoppedOut, focusedPaneId = newFocus)
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

        fun removeBySession(node: PaneNode): PaneNode? = when (node) {
            is LeafNode -> if (node.sessionId == sessionId) null else node
            is SplitNode -> {
                val newFirst = removeBySession(node.first)
                val newSecond = removeBySession(node.second)
                when {
                    newFirst == null && newSecond == null -> null
                    newFirst == null -> newSecond
                    newSecond == null -> newFirst
                    else -> node.copy(first = newFirst, second = newSecond)
                }
            }
        }

        val newTabs = cfg.tabs.map { tab ->
            val newRoot = tab.root?.let { removeBySession(it) }
            val newFloating = tab.floating.filterNot { it.leaf.sessionId == sessionId }
            val newPoppedOut = tab.poppedOut.filterNot { it.leaf.sessionId == sessionId }
            val liveIds = HashSet<String>()
            newRoot?.let { collectLeafIds(it, liveIds) }
            newFloating.forEach { liveIds.add(it.leaf.id) }
            newPoppedOut.forEach { liveIds.add(it.leaf.id) }
            val newFocus = tab.focusedPaneId?.takeIf { it in liveIds }
            tab.copy(root = newRoot, floating = newFloating, poppedOut = newPoppedOut, focusedPaneId = newFocus)
        }
        val newCfg = cfg.copy(tabs = newTabs)
        if (newCfg == cfg) return@synchronized
        _config.value = newCfg
        val after = collectSessionIds(newCfg)
        (before - after).forEach { TerminalSessions.destroy(it) }
    }

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
                    root = tab.root?.let { transformLeaf(it, paneId, ::renameLeaf) },
                    floating = tab.floating.map { fp -> fp.copy(leaf = renameLeaf(fp.leaf)) },
                    poppedOut = tab.poppedOut.map { po -> po.copy(leaf = renameLeaf(po.leaf)) },
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
                    root = tab.root?.let { transformAllLeaves(it, ::maybeUpdate) },
                    floating = tab.floating.map { fp -> fp.copy(leaf = maybeUpdate(fp.leaf)) },
                    poppedOut = tab.poppedOut.map { po -> po.copy(leaf = maybeUpdate(po.leaf)) },
                )
            }
        )
        if (changed) _config.value = newCfg
    }

    private fun transformAllLeaves(node: PaneNode, f: (LeafNode) -> LeafNode): PaneNode = when (node) {
        is LeafNode -> f(node)
        is SplitNode -> node.copy(
            first = transformAllLeaves(node.first, f),
            second = transformAllLeaves(node.second, f)
        )
    }

    /**
     * Adjust the size of [paneId] by [delta] (additive on the parent split's
     * ratio, in (-1, 1)). If the leaf is the `first` child of its parent,
     * the parent's ratio moves by `+delta`; if it's the `second` child, by
     * `-delta`. Pane has no parent (i.e. it is the tab root) → no-op.
     */
    fun resizePane(paneId: String, delta: Double) = synchronized(this) {
        val cfg = _config.value
        for (tab in cfg.tabs) {
            val root = tab.root ?: continue
            val parent = findParentSplit(root, paneId) ?: continue
            val (split, isFirst) = parent
            val newRatio = if (isFirst) split.ratio + delta else split.ratio - delta
            setSplitRatio(split.id, newRatio)
            return@synchronized
        }
    }

    /**
     * Walk [node] looking for the leaf with [paneId]; if found, return its
     * direct parent SplitNode and whether the leaf is the parent's `first`
     * child. Returns null if not found or if the pane has no parent split.
     */
    private fun findParentSplit(node: PaneNode, paneId: String): Pair<SplitNode, Boolean>? {
        if (node !is SplitNode) return null
        if (node.first is LeafNode && node.first.id == paneId) return node to true
        if (node.second is LeafNode && node.second.id == paneId) return node to false
        return findParentSplit(node.first, paneId) ?: findParentSplit(node.second, paneId)
    }

    fun setSplitRatio(splitId: String, ratio: Double) = synchronized(this) {
        val clamped = ratio.coerceIn(0.05, 0.95)
        val cfg = _config.value
        var changed = false
        val newCfg = cfg.copy(
            tabs = cfg.tabs.map { tab ->
                val root = tab.root ?: return@map tab
                tab.copy(root = transformSplit(root, splitId) { split ->
                    if (split.ratio == clamped) split else {
                        changed = true
                        split.copy(ratio = clamped)
                    }
                })
            }
        )
        if (changed) _config.value = newCfg
    }

    /**
     * Spawn a fresh shell as the sole pane in [tabId]. Used by the empty-tab
     * placeholder's "New pane" button. No-op if the tab already has any panes
     * (docked or floating) — clients should only surface the button when both
     * `tab.root` and `tab.floating` are empty.
     */
    fun addPaneToEmptyTab(tabId: String) = synchronized(this) {
        val cfg = _config.value
        val idx = cfg.tabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return@synchronized
        val tab = cfg.tabs[idx]
        if (tab.root != null || tab.floating.isNotEmpty()) return@synchronized
        val sessionId = TerminalSessions.create()
        val leaf = LeafNode(
            id = newNodeId(),
            sessionId = sessionId,
            title = "Session ${sessionId.removePrefix("s")}",
            content = TerminalContent(sessionId),
        )
        val newTabs = cfg.tabs.toMutableList()
        newTabs[idx] = tab.copy(root = leaf)
        _config.value = cfg.copy(tabs = newTabs)
    }

    /** Add a file-browser pane as the sole pane in an empty tab. */
    fun addFileBrowserToEmptyTab(tabId: String): LeafNode? = synchronized(this) {
        val cfg = _config.value
        val idx = cfg.tabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return@synchronized null
        val tab = cfg.tabs[idx]
        if (tab.root != null || tab.floating.isNotEmpty()) return@synchronized null
        val leaf = LeafNode(
            id = newNodeId(),
            sessionId = "",
            title = "Files",
            content = FileBrowserContent(),
        )
        val newTabs = cfg.tabs.toMutableList()
        newTabs[idx] = tab.copy(root = leaf)
        _config.value = cfg.copy(tabs = newTabs)
        leaf
    }

    /** Add a linked terminal pane as the sole pane in an empty tab. */
    fun addLinkToEmptyTab(tabId: String, targetSessionId: String) = synchronized(this) {
        if (TerminalSessions.get(targetSessionId) == null) return@synchronized
        val cfg = _config.value
        val idx = cfg.tabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return@synchronized
        val tab = cfg.tabs[idx]
        if (tab.root != null || tab.floating.isNotEmpty()) return@synchronized
        val sourceTitle = findLeafBySession(cfg, targetSessionId)?.title ?: "Terminal"
        val leaf = LeafNode(
            id = newNodeId(),
            sessionId = targetSessionId,
            title = sourceTitle,
            content = TerminalContent(targetSessionId),
            isLink = true,
        )
        val newTabs = cfg.tabs.toMutableList()
        newTabs[idx] = tab.copy(root = leaf)
        _config.value = cfg.copy(tabs = newTabs)
    }

    /**
     * Detach the pane [paneId] from its current location and put it back the
     * other way: a pane in the split tree becomes floating; a floating pane
     * gets re-docked. Re-docking follows the same rule as [movePaneToTab]:
     * if the destination tree is empty, the leaf becomes the root; otherwise
     * the existing root is wrapped in a new horizontal split with the leaf
     * as its right child.
     */
    fun toggleFloating(paneId: String) = synchronized(this) {
        val cfg = _config.value
        for ((tabIdx, tab) in cfg.tabs.withIndex()) {
            // Case 1: pane is currently floating → re-dock it.
            val floatingIdx = tab.floating.indexOfFirst { it.leaf.id == paneId }
            if (floatingIdx >= 0) {
                val leaf = tab.floating[floatingIdx].leaf
                val newFloating = tab.floating.toMutableList().also { it.removeAt(floatingIdx) }
                val newRoot: PaneNode = when (val root = tab.root) {
                    null -> leaf
                    else -> SplitNode(
                        id = newNodeId(),
                        orientation = SplitOrientation.Horizontal,
                        ratio = 0.5,
                        first = root,
                        second = leaf,
                    )
                }
                val newTabs = cfg.tabs.toMutableList()
                newTabs[tabIdx] = tab.copy(root = newRoot, floating = newFloating)
                _config.value = cfg.copy(tabs = newTabs)
                return@synchronized
            }
            // Case 2: pane lives in the split tree → detach into floating.
            val root = tab.root ?: continue
            val detached = detachLeaf(root, paneId) ?: continue
            val (leaf, newRoot) = detached
            val nextZ = (tab.floating.maxOfOrNull { it.z } ?: 0L) + 1L
            val newFloater = FloatingPane(
                leaf = leaf,
                x = 0.2,
                y = 0.2,
                width = 0.4,
                height = 0.4,
                z = nextZ,
            )
            val newTabs = cfg.tabs.toMutableList()
            newTabs[tabIdx] = tab.copy(root = newRoot, floating = tab.floating + newFloater)
            _config.value = cfg.copy(tabs = newTabs)
            return@synchronized
        }
    }

    /**
     * Update the relative geometry of a floating pane. All inputs are clamped
     * to keep the pane visible inside the tab area and at least 5% of each
     * dimension (mirrors the split-ratio clamp). No-op if [paneId] isn't
     * floating in any tab.
     */
    fun setFloatingGeometry(
        paneId: String,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
    ) = synchronized(this) {
        val w = width.coerceIn(0.05, 1.0)
        val h = height.coerceIn(0.05, 1.0)
        val nx = x.coerceIn(0.0, (1.0 - w).coerceAtLeast(0.0))
        val ny = y.coerceIn(0.0, (1.0 - h).coerceAtLeast(0.0))
        val cfg = _config.value
        var changed = false
        val newTabs = cfg.tabs.map { tab ->
            val idx = tab.floating.indexOfFirst { it.leaf.id == paneId }
            if (idx < 0) return@map tab
            val current = tab.floating[idx]
            if (current.x == nx && current.y == ny && current.width == w && current.height == h) {
                return@map tab
            }
            changed = true
            val newFloating = tab.floating.toMutableList()
            newFloating[idx] = current.copy(x = nx, y = ny, width = w, height = h)
            tab.copy(floating = newFloating)
        }
        if (changed) _config.value = cfg.copy(tabs = newTabs)
    }

    /**
     * Detach [paneId] from its tab's split tree or floating layer and move it
     * into the tab's `poppedOut` list. The caller (Electron main process)
     * is responsible for opening a new BrowserWindow that renders just this
     * pane. No-op if the pane isn't found. If the pane is already popped out,
     * this is a no-op too — re-popping a popped-out pane makes no sense.
     */
    fun popOutPane(paneId: String) = synchronized(this) {
        val cfg = _config.value
        for ((tabIdx, tab) in cfg.tabs.withIndex()) {
            // Already popped out → nothing to do.
            if (tab.poppedOut.any { it.leaf.id == paneId }) return@synchronized

            // Case 1: pane is currently floating → detach from floating layer.
            val floatingIdx = tab.floating.indexOfFirst { it.leaf.id == paneId }
            if (floatingIdx >= 0) {
                val leaf = tab.floating[floatingIdx].leaf
                val newFloating = tab.floating.toMutableList().also { it.removeAt(floatingIdx) }
                val newTabs = cfg.tabs.toMutableList()
                newTabs[tabIdx] = tab.copy(
                    floating = newFloating,
                    poppedOut = tab.poppedOut + PoppedOutPane(leaf),
                )
                _config.value = cfg.copy(tabs = newTabs)
                return@synchronized
            }
            // Case 2: pane lives in the split tree → detach from tree.
            val root = tab.root ?: continue
            val detached = detachLeaf(root, paneId) ?: continue
            val (leaf, newRoot) = detached
            val newTabs = cfg.tabs.toMutableList()
            // If the popped pane was the focused pane, clear it — focus
            // follows the pane into the new window.
            val newFocus = if (tab.focusedPaneId == paneId) null else tab.focusedPaneId
            newTabs[tabIdx] = tab.copy(
                root = newRoot,
                poppedOut = tab.poppedOut + PoppedOutPane(leaf),
                focusedPaneId = newFocus,
            )
            _config.value = cfg.copy(tabs = newTabs)
            return@synchronized
        }
    }

    /**
     * Re-dock a popped-out pane back into its tab's split tree. Called when
     * the user clicks "Dock" in the popout window, or when the popout window
     * is closed via the OS close button. Same re-docking rule as
     * [toggleFloating]: become the root if the tree is empty, otherwise wrap
     * the current root in a horizontal split.
     */
    fun dockPoppedOut(paneId: String) = synchronized(this) {
        val cfg = _config.value
        for ((tabIdx, tab) in cfg.tabs.withIndex()) {
            val idx = tab.poppedOut.indexOfFirst { it.leaf.id == paneId }
            if (idx < 0) continue
            val leaf = tab.poppedOut[idx].leaf
            val newPoppedOut = tab.poppedOut.toMutableList().also { it.removeAt(idx) }
            val newRoot: PaneNode = when (val root = tab.root) {
                null -> leaf
                else -> SplitNode(
                    id = newNodeId(),
                    orientation = SplitOrientation.Horizontal,
                    ratio = 0.5,
                    first = root,
                    second = leaf,
                )
            }
            val newTabs = cfg.tabs.toMutableList()
            newTabs[tabIdx] = tab.copy(root = newRoot, poppedOut = newPoppedOut)
            _config.value = cfg.copy(tabs = newTabs)
            return@synchronized
        }
    }

    /**
     * Bring the floating pane [paneId] to the front of its tab's stacking
     * order. No-op if it isn't floating or is already on top.
     */
    fun raiseFloating(paneId: String) = synchronized(this) {
        val cfg = _config.value
        var changed = false
        val newTabs = cfg.tabs.map { tab ->
            val idx = tab.floating.indexOfFirst { it.leaf.id == paneId }
            if (idx < 0) return@map tab
            val current = tab.floating[idx]
            val maxZ = tab.floating.maxOf { it.z }
            if (current.z == maxZ && tab.floating.count { it.z == maxZ } == 1) return@map tab
            changed = true
            val newFloating = tab.floating.toMutableList()
            newFloating[idx] = current.copy(z = maxZ + 1)
            tab.copy(floating = newFloating)
        }
        if (changed) _config.value = cfg.copy(tabs = newTabs)
    }

    /**
     * Move the pane [paneId] from whichever tab currently holds it (in either
     * the split tree or the floating layer) into [targetTabId], where it
     * lands as a docked pane. If the target tab is empty, the pane becomes
     * its root; otherwise the existing root is wrapped in a new horizontal
     * split with the moved pane as the right child.
     *
     * Floating-ness is intentionally dropped on transfer: the user explicitly
     * asked for "Move to tab" as a docking operation.
     */
    fun movePaneToTab(paneId: String, targetTabId: String) = synchronized(this) {
        if (paneId.isEmpty() || targetTabId.isEmpty()) return@synchronized
        val cfg = _config.value
        val targetIdx = cfg.tabs.indexOfFirst { it.id == targetTabId }
        if (targetIdx < 0) return@synchronized

        // Locate the source: search every tab's tree and floating layer.
        var sourceIdx = -1
        var movedLeaf: LeafNode? = null
        var newSourceRoot: PaneNode? = null
        var newSourceFloating: List<FloatingPane>? = null

        for ((idx, tab) in cfg.tabs.withIndex()) {
            val floatingIdx = tab.floating.indexOfFirst { it.leaf.id == paneId }
            if (floatingIdx >= 0) {
                sourceIdx = idx
                movedLeaf = tab.floating[floatingIdx].leaf
                newSourceRoot = tab.root
                newSourceFloating = tab.floating.toMutableList().also { it.removeAt(floatingIdx) }
                break
            }
            val root = tab.root ?: continue
            val detached = detachLeaf(root, paneId) ?: continue
            sourceIdx = idx
            movedLeaf = detached.first
            newSourceRoot = detached.second
            newSourceFloating = tab.floating
            break
        }
        if (sourceIdx < 0 || movedLeaf == null) return@synchronized
        if (sourceIdx == targetIdx) return@synchronized

        val newTabs = cfg.tabs.toMutableList()
        // Clear the source tab's saved focus if it pointed at the moving
        // pane — that pane no longer lives in the source tab.
        val sourceFocus = cfg.tabs[sourceIdx].focusedPaneId
        val newSourceFocus = if (sourceFocus == paneId) null else sourceFocus
        newTabs[sourceIdx] = cfg.tabs[sourceIdx].copy(
            root = newSourceRoot,
            floating = newSourceFloating ?: cfg.tabs[sourceIdx].floating,
            focusedPaneId = newSourceFocus,
        )
        val targetTab = newTabs[targetIdx]
        val newTargetRoot: PaneNode = when (val root = targetTab.root) {
            null -> movedLeaf
            else -> SplitNode(
                id = newNodeId(),
                orientation = SplitOrientation.Horizontal,
                ratio = 0.5,
                first = root,
                second = movedLeaf,
            )
        }
        newTabs[targetIdx] = targetTab.copy(root = newTargetRoot)
        _config.value = cfg.copy(tabs = newTabs)
    }

    /**
     * Like [removeLeaf] but also returns the removed [LeafNode] and the new
     * (possibly null) root. Used by [toggleFloating] and [movePaneToTab] to
     * detach a leaf without losing its identity. Returns null if [paneId]
     * isn't found in [node].
     */
    private fun detachLeaf(node: PaneNode, paneId: String): Pair<LeafNode, PaneNode?>? {
        when (node) {
            is LeafNode -> return if (node.id == paneId) node to null else null
            is SplitNode -> {
                val fromFirst = detachLeaf(node.first, paneId)
                if (fromFirst != null) {
                    val (leaf, newFirst) = fromFirst
                    val newRoot: PaneNode = newFirst?.let {
                        node.copy(first = it)
                    } ?: node.second
                    return leaf to newRoot
                }
                val fromSecond = detachLeaf(node.second, paneId)
                if (fromSecond != null) {
                    val (leaf, newSecond) = fromSecond
                    val newRoot: PaneNode = newSecond?.let {
                        node.copy(second = it)
                    } ?: node.first
                    return leaf to newRoot
                }
                return null
            }
        }
    }

    /** True iff [sessionId] is referenced by some leaf in the current config. */
    fun hasSession(sessionId: String): Boolean =
        collectSessionIds(_config.value).contains(sessionId)

    private fun transformLeaf(
        node: PaneNode,
        paneId: String,
        f: (LeafNode) -> PaneNode
    ): PaneNode = when (node) {
        is LeafNode -> if (node.id == paneId) f(node) else node
        is SplitNode -> node.copy(
            first = transformLeaf(node.first, paneId, f),
            second = transformLeaf(node.second, paneId, f)
        )
    }

    private fun transformSplit(
        node: PaneNode,
        splitId: String,
        f: (SplitNode) -> SplitNode
    ): PaneNode = when (node) {
        is LeafNode -> node
        is SplitNode -> {
            val maybeReplaced = if (node.id == splitId) f(node) else node
            maybeReplaced.copy(
                first = transformSplit(maybeReplaced.first, splitId, f),
                second = transformSplit(maybeReplaced.second, splitId, f)
            )
        }
    }

    /** Returns null if removing the leaf empties the entire subtree. */
    private fun removeLeaf(node: PaneNode, paneId: String): PaneNode? = when (node) {
        is LeafNode -> if (node.id == paneId) null else node
        is SplitNode -> {
            val newFirst = removeLeaf(node.first, paneId)
            val newSecond = removeLeaf(node.second, paneId)
            when {
                newFirst == null && newSecond == null -> null
                newFirst == null -> newSecond
                newSecond == null -> newFirst
                else -> node.copy(first = newFirst, second = newSecond)
            }
        }
    }

    private fun collectSessionIds(cfg: WindowConfig): Set<String> {
        val out = HashSet<String>()
        fun add(leaf: LeafNode) {
            // Skip non-terminal leaves: they have no PTY to track. Even for
            // terminal leaves we guard against the empty string, which only
            // appears in transient states (e.g. just-blanked persisted blobs).
            if (leaf.sessionId.isNotEmpty()) out.add(leaf.sessionId)
        }
        fun walk(n: PaneNode) {
            when (n) {
                is LeafNode -> add(n)
                is SplitNode -> { walk(n.first); walk(n.second) }
            }
        }
        cfg.tabs.forEach { tab ->
            tab.root?.let(::walk)
            tab.floating.forEach { add(it.leaf) }
            tab.poppedOut.forEach { add(it.leaf) }
        }
        return out
    }
}
