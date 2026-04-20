/**
 * Server-side per-screen view state management for Termtastic's multi-window
 * support. Manages a list of [ScreenState] objects, each representing the
 * view-layer state for one OS window (screen).
 *
 * All mutations funnel through this object so the resulting [screens] flow
 * is the single source clients need to subscribe to. The debounced persistence
 * writer in [Application.main] picks up changes and writes them to SQLite.
 *
 * @see ScreenState
 * @see WindowState for the shared tab/pane tree
 */
package se.soderbjorn.termtastic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import se.soderbjorn.termtastic.persistence.SettingsRepository

/**
 * Process-wide screen state manager. All mutations are `synchronized(this)` to
 * avoid races between concurrent WebSocket commands. Contention is negligible
 * because commands are infrequent and human-driven.
 *
 * @see ScreenState
 */
object ScreenStateManager {
    private val log = LoggerFactory.getLogger(ScreenStateManager::class.java)

    private val _screens = MutableStateFlow<List<ScreenState>>(emptyList())

    /**
     * Hot flow of the complete list of screen states. The debounced persistence
     * writer and per-screen WebSocket push jobs collect from this.
     */
    val screens: StateFlow<List<ScreenState>> = _screens.asStateFlow()

    @Volatile
    private var initialized = false

    /**
     * One-shot bootstrap: load persisted screen states from SQLite, or migrate
     * from existing global settings if no screens have been persisted yet.
     *
     * Must be called from `main()` exactly once, after [WindowState.initialize].
     *
     * @param repo the settings repository for loading/migrating persisted state
     */
    @Synchronized
    fun initialize(repo: SettingsRepository) {
        if (initialized) return
        initialized = true

        val loaded = repo.loadScreenStates()
        if (loaded != null && loaded.isNotEmpty()) {
            _screens.value = loaded
            log.info("Loaded {} persisted screen state(s)", loaded.size)
        } else {
            // First boot or pre-multi-window install: migrate from the global
            // ui.settings.v1 blob and WindowConfig.activeTabId.
            val migrated = migrateFromGlobalSettings(repo)
            _screens.value = listOf(migrated)
            log.info("Migrated global settings into screen 0")
        }
    }

    /**
     * Build a screen 0 [ScreenState] from the existing global UI settings and
     * [WindowConfig.activeTabId]. This ensures zero behavior change for existing
     * single-window users.
     */
    private fun migrateFromGlobalSettings(repo: SettingsRepository): ScreenState {
        val ui = repo.getUiSettings()
        val cfg = WindowState.config.value
        fun str(key: String): String? =
            ui[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        fun bool(key: String): Boolean? =
            ui[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
        fun int(key: String): Int? =
            ui[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

        // Build per-tab focusedPaneIds from the existing TabConfig.focusedPaneId
        val focusedPaneIds = cfg.tabs
            .mapNotNull { tab -> tab.focusedPaneId?.let { tab.id to it } }
            .toMap()

        return ScreenState(
            screenIndex = 0,
            open = true,
            activeTabId = cfg.activeTabId,
            focusedPaneIds = focusedPaneIds,
            sidebarCollapsed = bool("sidebarCollapsed") ?: false,
            sidebarWidth = int("sidebarWidth"),
            appearance = str("appearance"),
            themeName = str("theme"),
            sidebarThemeName = str("theme.sidebar"),
            terminalThemeName = str("theme.terminal"),
            diffThemeName = str("theme.diff"),
            fileBrowserThemeName = str("theme.fileBrowser"),
            tabsThemeName = str("theme.tabs"),
            chromeThemeName = str("theme.chrome"),
            windowsThemeName = str("theme.windows"),
            activeThemeName = str("theme.active"),
            paneFontSize = int("paneFontSize"),
        )
    }

    /**
     * Return a flow that emits the [ScreenState] for [screenIndex] whenever
     * it changes. Used by the per-connection WebSocket push job.
     *
     * @param screenIndex the screen to observe
     * @return a flow of that screen's state (emits the latest value immediately)
     */
    fun screenStateFlow(screenIndex: Int) = _screens.map { list ->
        list.firstOrNull { it.screenIndex == screenIndex }
            ?: ScreenState(screenIndex = screenIndex)
    }

    /**
     * Return the current [ScreenState] for [screenIndex], or a default if
     * no state exists for that index.
     *
     * @param screenIndex the screen to look up
     * @return the screen's current state
     */
    fun getScreenState(screenIndex: Int): ScreenState = synchronized(this) {
        _screens.value.firstOrNull { it.screenIndex == screenIndex }
            ?: ScreenState(screenIndex = screenIndex)
    }

    /**
     * Return all current screen states. Used by the `GET /api/screen-states`
     * endpoint so Electron can decide how many windows to create on startup.
     *
     * @return the full list of screen states
     */
    fun allScreenStates(): List<ScreenState> = _screens.value

    // --- Mutations -----------------------------------------------------------

    /**
     * Set the active tab for [screenIndex].
     *
     * @param screenIndex the screen whose active tab should change
     * @param tabId the tab to activate
     */
    fun setActiveTab(screenIndex: Int, tabId: String) = synchronized(this) {
        mutate(screenIndex) { it.copy(activeTabId = tabId) }
    }

    /**
     * Record the focused pane for a tab within a screen.
     *
     * @param screenIndex the screen where focus changed
     * @param tabId the tab containing the pane
     * @param paneId the pane that received focus
     */
    fun setFocusedPane(screenIndex: Int, tabId: String, paneId: String) = synchronized(this) {
        mutate(screenIndex) { it.copy(focusedPaneIds = it.focusedPaneIds + (tabId to paneId)) }
    }

    /**
     * Update the sidebar collapsed/width state for a screen.
     *
     * @param screenIndex the screen to update
     * @param collapsed whether the sidebar is collapsed
     * @param width sidebar width in pixels, or null to keep current
     */
    fun setSidebar(screenIndex: Int, collapsed: Boolean, width: Int?) = synchronized(this) {
        mutate(screenIndex) {
            it.copy(
                sidebarCollapsed = collapsed,
                sidebarWidth = width ?: it.sidebarWidth,
            )
        }
    }

    /**
     * Persist the OS window bounds for a screen.
     *
     * @param screenIndex the screen whose bounds changed
     * @param bounds the new window geometry
     */
    fun setBounds(screenIndex: Int, bounds: ScreenBounds) = synchronized(this) {
        mutate(screenIndex) { it.copy(bounds = bounds) }
    }

    /**
     * Batch-update theme and appearance overrides for a screen from a JSON
     * object. Keys correspond to [ScreenState] field names; empty-string values
     * clear the override (set to null).
     *
     * @param screenIndex the screen to update
     * @param settings JSON patch with theme fields
     */
    fun setTheme(screenIndex: Int, settings: JsonObject) = synchronized(this) {
        fun str(key: String): String? =
            settings[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        fun optStr(key: String, current: String?): String? =
            if (settings.containsKey(key)) str(key) else current
        fun optInt(key: String, current: Int?): Int? =
            if (settings.containsKey(key)) settings[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull() else current

        mutate(screenIndex) {
            it.copy(
                appearance = optStr("appearance", it.appearance),
                themeName = optStr("themeName", it.themeName),
                sidebarThemeName = optStr("sidebarThemeName", it.sidebarThemeName),
                terminalThemeName = optStr("terminalThemeName", it.terminalThemeName),
                diffThemeName = optStr("diffThemeName", it.diffThemeName),
                fileBrowserThemeName = optStr("fileBrowserThemeName", it.fileBrowserThemeName),
                tabsThemeName = optStr("tabsThemeName", it.tabsThemeName),
                chromeThemeName = optStr("chromeThemeName", it.chromeThemeName),
                windowsThemeName = optStr("windowsThemeName", it.windowsThemeName),
                activeThemeName = optStr("activeThemeName", it.activeThemeName),
                paneFontSize = optInt("paneFontSize", it.paneFontSize),
            )
        }
    }

    /**
     * Mark a screen as closed. Its state is preserved for later reopening.
     * Screen 0 cannot be closed.
     *
     * @param screenIndex the screen to close (must be > 0)
     */
    fun closeScreen(screenIndex: Int) = synchronized(this) {
        if (screenIndex <= 0) return@synchronized
        mutate(screenIndex) { it.copy(open = false) }
    }

    /**
     * Open a new screen or reopen a previously closed one. If [screenIndex]
     * is -1, the next available index is assigned. Returns the screen index
     * that was opened.
     *
     * @param screenIndex the index to open, or -1 for auto-assign
     * @return the actual screen index that was opened
     */
    fun openScreen(screenIndex: Int): Int = synchronized(this) {
        val list = _screens.value.toMutableList()
        if (screenIndex >= 0) {
            val idx = list.indexOfFirst { it.screenIndex == screenIndex }
            if (idx >= 0) {
                // Reopen existing closed screen
                list[idx] = list[idx].copy(open = true)
            } else {
                // Create new screen at the requested index
                list.add(ScreenState(screenIndex = screenIndex, open = true))
            }
            _screens.value = list
            return@synchronized screenIndex
        }
        // Auto-assign: find the lowest unused index
        val used = list.map { it.screenIndex }.toSet()
        var next = 1
        while (next in used) next++
        list.add(ScreenState(screenIndex = next, open = true))
        _screens.value = list
        return@synchronized next
    }

    /**
     * Called when a tab is closed in [WindowState]. Cleans up any references
     * to [tabId] across all screens: clears activeTabId if it matches, and
     * removes the tab from focusedPaneIds.
     *
     * @param tabId the id of the tab that was closed
     * @param fallbackTabId the id of the tab the UI should fall back to, or null
     */
    fun onTabClosed(tabId: String, fallbackTabId: String?) = synchronized(this) {
        val list = _screens.value
        var changed = false
        val newList = list.map { screen ->
            var s = screen
            if (s.activeTabId == tabId) {
                s = s.copy(activeTabId = fallbackTabId)
                changed = true
            }
            if (s.focusedPaneIds.containsKey(tabId)) {
                s = s.copy(focusedPaneIds = s.focusedPaneIds - tabId)
                changed = true
            }
            s
        }
        if (changed) _screens.value = newList
    }

    /**
     * Called when a pane is closed. Removes any focusedPaneId references to
     * [paneId] across all screens.
     *
     * @param paneId the id of the pane that was closed
     */
    fun onPaneClosed(paneId: String) = synchronized(this) {
        val list = _screens.value
        var changed = false
        val newList = list.map { screen ->
            val stale = screen.focusedPaneIds.entries.filter { it.value == paneId }
            if (stale.isNotEmpty()) {
                changed = true
                screen.copy(focusedPaneIds = screen.focusedPaneIds - stale.map { it.key }.toSet())
            } else {
                screen
            }
        }
        if (changed) _screens.value = newList
    }

    /**
     * Apply [transform] to the screen with [screenIndex]. If no screen with
     * that index exists, one is created with defaults, then transformed.
     */
    private fun mutate(screenIndex: Int, transform: (ScreenState) -> ScreenState) {
        val list = _screens.value.toMutableList()
        val idx = list.indexOfFirst { it.screenIndex == screenIndex }
        if (idx >= 0) {
            val updated = transform(list[idx])
            if (updated == list[idx]) return
            list[idx] = updated
        } else {
            list.add(transform(ScreenState(screenIndex = screenIndex)))
        }
        _screens.value = list
    }
}
