/**
 * Main application ViewModel that combines window layout, per-session state,
 * and UI settings into a single observable [AppBackingViewModel.State].
 *
 * Platform UI layers (Compose on Android, SwiftUI on iOS, xterm.js on web)
 * collect [AppBackingViewModel.stateFlow] to drive their rendering. Layout
 * mutation methods (tab/pane CRUD, split, float, pop-out) delegate to the
 * server via [WindowSocket]; settings mutations are persisted through
 * [SettingsPersister].
 *
 * @see se.soderbjorn.termtastic.client.WindowSocket
 * @see SettingsPersister
 */
package se.soderbjorn.termtastic.client.viewmodel

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import se.soderbjorn.termtastic.Appearance
import se.soderbjorn.termtastic.ClaudeUsageData
import se.soderbjorn.termtastic.DEFAULT_THEME_NAME
import se.soderbjorn.termtastic.ResolvedPalette
import se.soderbjorn.termtastic.ScreenBounds
import se.soderbjorn.termtastic.ScreenState
import se.soderbjorn.termtastic.SplitDirection
import se.soderbjorn.termtastic.TerminalTheme
import se.soderbjorn.termtastic.WindowCommand
import se.soderbjorn.termtastic.WindowConfig
import se.soderbjorn.termtastic.WindowEnvelope
import se.soderbjorn.termtastic.client.WindowSocket
import se.soderbjorn.termtastic.client.WindowStateRepository
import se.soderbjorn.termtastic.recommendedThemes
import se.soderbjorn.termtastic.resolve
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Backing ViewModel for the top-level application screen. Merges server-pushed
 * window config, session states, and UI settings into a single [State] flow.
 *
 * @param windowSocket     the live `/window` WebSocket for sending commands.
 * @param windowState      the shared [WindowStateRepository] cache.
 * @param settingsPersister optional callback to persist UI setting changes to
 *   the server; `null` in unit tests.
 */
class AppBackingViewModel(
    private val windowSocket: WindowSocket,
    private val windowState: WindowStateRepository,
    private val settingsPersister: SettingsPersister? = null,
) {
    /**
     * Monotonic timestamp of the most recent local settings mutation.
     * Used to suppress server-pushed [WindowEnvelope.UiSettings] echoes
     * that arrive shortly after the client itself POSTed — these echoes
     * carry partially-merged state that would overwrite the correct
     * local state.
     */
    private var lastLocalSettingsChange: TimeSource.Monotonic.ValueTimeMark =
        TimeSource.Monotonic.markNow()

    private val _stateFlow = MutableStateFlow(State())
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * Immutable snapshot of the entire application UI state. Emitted via
     * [stateFlow] whenever any constituent value changes.
     *
     * @property config             the current window layout, or `null` before
     *   the first server push.
     * @property sessionStates      per-session state labels keyed by session ID.
     * @property pendingApproval    `true` if the device is awaiting approval.
     * @property claudeUsage        latest AI token usage data, if available.
     * @property theme              the active terminal colour theme (global default).
     * @property appearance         the user's light/dark mode preference.
     * @property paneFontSize       per-pane font size override, or `null` for default.
     * @property sidebarWidth       persisted sidebar width in pixels, or `null`.
     * @property sidebarCollapsed    whether the sidebar is currently collapsed.
     * @property desktopNotifications whether desktop notifications are enabled.
     * @property sidebarTheme       optional theme override for sidebar sections,
     *   or `null` to use [theme].
     * @property terminalTheme      optional theme override for terminal panes,
     *   or `null` to use [theme].
     * @property diffTheme          optional theme override for git diff panes,
     *   or `null` to use [theme].
     * @property fileBrowserTheme   optional theme override for file browser panes,
     *   or `null` to use [theme].
     * @property tabsTheme          optional theme override for the tab bar,
     *   or `null` to use [theme].
     * @property chromeTheme        optional theme override for window chrome
     *   (pane titlebars and borders), or `null` to use [theme].
     * @property activeTheme        optional theme override for active indicators
     *   (tab ring, focused-pane border, sidebar active-pane highlight),
     *   or `null` to use each section's own accent.
     */
    data class State(
        val config: WindowConfig? = null,
        val sessionStates: Map<String, String?> = emptyMap(),
        val pendingApproval: Boolean = false,
        val claudeUsage: ClaudeUsageData? = null,
        val theme: TerminalTheme = recommendedThemes.first { it.name == DEFAULT_THEME_NAME },
        val appearance: Appearance = Appearance.Auto,
        val paneFontSize: Int? = null,
        val sidebarWidth: Int? = null,
        val sidebarCollapsed: Boolean = false,
        val desktopNotifications: Boolean = true,
        val sidebarTheme: TerminalTheme? = null,
        val terminalTheme: TerminalTheme? = null,
        val diffTheme: TerminalTheme? = null,
        val fileBrowserTheme: TerminalTheme? = null,
        val tabsTheme: TerminalTheme? = null,
        val chromeTheme: TerminalTheme? = null,
        val windowsTheme: TerminalTheme? = null,
        val activeTheme: TerminalTheme? = null,
        /** Per-screen view state pushed from the server, or `null` before the
         *  first push. Used for multi-window: active tab, focused pane, sidebar,
         *  and theme overrides are per-screen. */
        val screenState: ScreenState? = null,
    )

    /**
     * Start collecting window state and envelope streams. This is a long-running
     * suspend function -- call it from a lifecycle-scoped coroutine. It returns
     * only when the enclosing scope is cancelled.
     */
    suspend fun run() {
        coroutineScope {
            launch {
                combine(
                    windowState.config,
                    windowState.states,
                    windowState.pendingApproval,
                ) { cfg, states, pending ->
                    Triple(cfg, states, pending)
                }.collect { (cfg, states, pending) ->
                    emit(_stateFlow.value.copy(
                        config = cfg,
                        sessionStates = states,
                        pendingApproval = pending,
                    ))
                }
            }
            launch {
                windowSocket.envelopes.collect { envelope ->
                    when (envelope) {
                        is WindowEnvelope.ClaudeUsage ->
                            emit(_stateFlow.value.copy(claudeUsage = envelope.usage))
                        is WindowEnvelope.UiSettings -> {
                            val elapsed = lastLocalSettingsChange.elapsedNow()
                            if (elapsed > 2.seconds) {
                                applyServerUiSettings(envelope.settings)
                            }
                        }
                        is WindowEnvelope.ScreenStateMsg -> {
                            val elapsed = lastLocalSettingsChange.elapsedNow()
                            if (elapsed > 2.seconds) {
                                applyScreenState(envelope.state)
                            } else {
                                // Still persist the screenState object so
                                // non-theme fields (bounds, etc.) update.
                                emit(_stateFlow.value.copy(screenState = envelope.state))
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    /** Stamp the local-change time and persist a single setting. */
    private suspend fun persistSetting(key: String, value: String) {
        lastLocalSettingsChange = TimeSource.Monotonic.markNow()
        settingsPersister?.putSetting(key, value)
    }

    /** Stamp the local-change time and persist a batch of settings. */
    private suspend fun persistSettings(settings: Map<String, String>) {
        lastLocalSettingsChange = TimeSource.Monotonic.markNow()
        settingsPersister?.putSettings(settings)
    }

    // ── UI settings mutations ───────────────────────────────────────

    /**
     * Update the terminal colour theme and persist the choice to the server.
     *
     * @param theme the new theme to apply.
     */
    suspend fun setTheme(theme: TerminalTheme) {
        emit(_stateFlow.value.copy(theme = theme))
        persistSetting("theme", theme.name)
    }

    /**
     * Update the light/dark appearance mode and persist it to the server.
     *
     * @param appearance the new appearance preference.
     */
    suspend fun setAppearance(appearance: Appearance) {
        emit(_stateFlow.value.copy(appearance = appearance))
        persistSetting("appearance", appearance.name)
    }

    /**
     * Update the per-pane font size and persist it to the server.
     *
     * @param size the new font size in points.
     */
    suspend fun setPaneFontSize(size: Int) {
        emit(_stateFlow.value.copy(paneFontSize = size))
        persistSetting("paneFontSize", size.toString())
    }

    /**
     * Update the sidebar width and persist it to the server.
     *
     * @param width the new width in pixels.
     */
    suspend fun setSidebarWidth(width: Int) {
        emit(_stateFlow.value.copy(sidebarWidth = width))
        persistSetting("sidebarWidth", width.toString())
    }

    /**
     * Collapse or expand the sidebar and persist the preference.
     *
     * @param collapsed `true` to collapse, `false` to expand.
     */
    suspend fun setSidebarCollapsed(collapsed: Boolean) {
        emit(_stateFlow.value.copy(sidebarCollapsed = collapsed))
        persistSetting("sidebarCollapsed", collapsed.toString())
    }

    /**
     * Enable or disable desktop notifications and persist the preference.
     *
     * @param enabled `true` to enable notifications.
     */
    suspend fun setDesktopNotifications(enabled: Boolean) {
        emit(_stateFlow.value.copy(desktopNotifications = enabled))
        persistSetting("desktopNotifications", enabled.toString())
    }

    /**
     * Set a per-section theme override and persist it. Pass `null` to clear
     * the override and fall back to the global theme.
     *
     * @param section one of `"sidebar"`, `"terminal"`, `"diff"`, `"fileBrowser"`, `"active"`
     * @param theme   the override theme, or `null` to clear
     */
    suspend fun setSectionTheme(section: String, theme: TerminalTheme?) {
        val cur = _stateFlow.value
        val updated = when (section) {
            "sidebar" -> cur.copy(sidebarTheme = theme)
            "terminal" -> cur.copy(terminalTheme = theme)
            "diff" -> cur.copy(diffTheme = theme)
            "fileBrowser" -> cur.copy(fileBrowserTheme = theme)
            "tabs" -> cur.copy(tabsTheme = theme)
            "chrome" -> cur.copy(chromeTheme = theme)
            "windows" -> cur.copy(windowsTheme = theme)
            "active" -> cur.copy(activeTheme = theme)
            else -> cur
        }
        emit(updated)
        persistSetting("theme.$section", theme?.name ?: "")
    }

    /**
     * Apply a full theme configuration atomically — main theme plus all
     * section overrides — in a single **synchronous** state emission.
     * Persistence is fire-and-forget so the caller can run `applyAll()`
     * immediately after this returns without any coroutine involvement.
     *
     * @param theme the main theme
     * @param sections map of section key to optional override theme
     */
    fun applyThemeConfiguration(
        theme: TerminalTheme,
        sections: Map<String, TerminalTheme?>,
    ) {
        val updated = _stateFlow.value.copy(
            theme = theme,
            sidebarTheme = sections["sidebar"],
            terminalTheme = sections["terminal"],
            diffTheme = sections["diff"],
            fileBrowserTheme = sections["fileBrowser"],
            tabsTheme = sections["tabs"],
            chromeTheme = sections["chrome"],
            windowsTheme = sections["windows"],
            activeTheme = sections["active"],
        )
        emit(updated)
        val batch = buildMap {
            put("theme", theme.name)
            for ((section, t) in sections) {
                put("theme.$section", t?.name ?: "")
            }
        }
        lastLocalSettingsChange = TimeSource.Monotonic.markNow()
        settingsPersister?.fireAndForgetPutSettings(batch)
    }

    // ── Layout mutations (delegated to server) ──────────────────────

    /** Tell the server to switch to tab [tabId]. */
    suspend fun setActiveTab(tabId: String) {
        windowSocket.send(WindowCommand.SetActiveTab(tabId = tabId))
    }

    /** Focus [paneId] within [tabId]. */
    suspend fun setFocusedPane(tabId: String, paneId: String) {
        windowSocket.send(WindowCommand.SetFocusedPane(tabId = tabId, paneId = paneId))
    }

    /** Create a new tab with a default terminal pane. */
    suspend fun addTab() {
        windowSocket.send(WindowCommand.AddTab)
    }

    /** Close tab [tabId] and all panes within it. */
    suspend fun closeTab(tabId: String) {
        windowSocket.send(WindowCommand.CloseTab(tabId = tabId))
    }

    /** Rename tab [tabId] to [title]. */
    suspend fun renameTab(tabId: String, title: String) {
        windowSocket.send(WindowCommand.RenameTab(tabId = tabId, title = title))
    }

    /** Reorder [tabId] relative to [targetTabId]. */
    suspend fun moveTab(tabId: String, targetTabId: String, before: Boolean) {
        windowSocket.send(WindowCommand.MoveTab(tabId = tabId, targetTabId = targetTabId, before = before))
    }

    /** Close pane [paneId] (terminal, file-browser, or git). */
    suspend fun closePane(paneId: String) {
        windowSocket.send(WindowCommand.Close(paneId = paneId))
    }

    /** Close all panes that share [sessionId] and terminate the PTY. */
    suspend fun closeSession(sessionId: String) {
        windowSocket.send(WindowCommand.CloseSession(sessionId = sessionId))
    }

    /** Set a custom title for [paneId]. */
    suspend fun renamePane(paneId: String, title: String) {
        windowSocket.send(WindowCommand.Rename(paneId = paneId, title = title))
    }

    /** Split [paneId] and create a new terminal in [direction]. */
    suspend fun splitTerminal(paneId: String, direction: SplitDirection) {
        windowSocket.send(WindowCommand.SplitTerminal(paneId = paneId, direction = direction))
    }

    /** Split [paneId] and create a file browser in [direction]. */
    suspend fun splitFileBrowser(paneId: String, direction: SplitDirection) {
        windowSocket.send(WindowCommand.SplitFileBrowser(paneId = paneId, direction = direction))
    }

    /** Split [paneId] and create a git pane in [direction]. */
    suspend fun splitGit(paneId: String, direction: SplitDirection) {
        windowSocket.send(WindowCommand.SplitGit(paneId = paneId, direction = direction))
    }

    /** Split [paneId] and link a new pane to [targetSessionId]. */
    suspend fun splitLink(paneId: String, direction: SplitDirection, targetSessionId: String) {
        windowSocket.send(WindowCommand.SplitLink(paneId = paneId, direction = direction, targetSessionId = targetSessionId))
    }

    /** Add a new terminal pane to [tabId]. */
    suspend fun addPaneToTab(tabId: String) {
        windowSocket.send(WindowCommand.AddPaneToTab(tabId = tabId))
    }

    /** Add a file-browser pane to [tabId]. */
    suspend fun addFileBrowserToTab(tabId: String) {
        windowSocket.send(WindowCommand.AddFileBrowserToTab(tabId = tabId))
    }

    /** Add a git pane to [tabId]. */
    suspend fun addGitToTab(tabId: String) {
        windowSocket.send(WindowCommand.AddGitToTab(tabId = tabId))
    }

    /** Add a linked pane to [tabId] sharing [targetSessionId]. */
    suspend fun addLinkToTab(tabId: String, targetSessionId: String) {
        windowSocket.send(WindowCommand.AddLinkToTab(tabId = tabId, targetSessionId = targetSessionId))
    }

    /** Toggle [paneId] between docked and floating state. */
    suspend fun toggleFloating(paneId: String) {
        windowSocket.send(WindowCommand.ToggleFloating(paneId = paneId))
    }

    /** Update the floating geometry (position and size) for [paneId]. */
    suspend fun setFloatingGeom(paneId: String, x: Double, y: Double, width: Double, height: Double) {
        windowSocket.send(WindowCommand.SetFloatingGeom(paneId = paneId, x = x, y = y, width = width, height = height))
    }

    /** Bring floating [paneId] to the front of the z-order. */
    suspend fun raiseFloating(paneId: String) {
        windowSocket.send(WindowCommand.RaiseFloating(paneId = paneId))
    }

    /** Adjust the split ratio for [splitId] (0.0 -- 1.0). */
    suspend fun setRatio(splitId: String, ratio: Double) {
        windowSocket.send(WindowCommand.SetRatio(splitId = splitId, ratio = ratio))
    }

    /** Move [paneId] from its current tab to [targetTabId]. */
    suspend fun movePaneToTab(paneId: String, targetTabId: String) {
        windowSocket.send(WindowCommand.MovePaneToTab(paneId = paneId, targetTabId = targetTabId))
    }

    /** Pop [paneId] out into its own OS-level window. */
    suspend fun popOut(paneId: String) {
        windowSocket.send(WindowCommand.PopOut(paneId = paneId))
    }

    /** Dock a previously popped-out [paneId] back into the main window. */
    suspend fun dockPoppedOut(paneId: String) {
        windowSocket.send(WindowCommand.DockPoppedOut(paneId = paneId))
    }

    /** Ask the server to re-send Claude AI usage data. */
    suspend fun refreshUsage() {
        windowSocket.send(WindowCommand.RefreshUsage)
    }

    /** Request the server to open the settings panel. */
    suspend fun openSettings() {
        windowSocket.send(WindowCommand.OpenSettings)
    }

    // ── Per-screen commands (multi-window support) ───────────────────

    /**
     * Switch the active tab for this screen only.
     *
     * @param screenIndex the screen index
     * @param tabId the tab to activate
     */
    suspend fun setScreenActiveTab(screenIndex: Int, tabId: String) {
        windowSocket.send(WindowCommand.SetScreenActiveTab(screenIndex = screenIndex, tabId = tabId))
    }

    /**
     * Record the focused pane for a tab within this screen.
     *
     * @param screenIndex the screen index
     * @param tabId the tab containing the pane
     * @param paneId the pane that received focus
     */
    suspend fun setScreenFocusedPane(screenIndex: Int, tabId: String, paneId: String) {
        windowSocket.send(WindowCommand.SetScreenFocusedPane(screenIndex = screenIndex, tabId = tabId, paneId = paneId))
    }

    /**
     * Update the sidebar state for this screen.
     *
     * @param screenIndex the screen index
     * @param collapsed whether the sidebar is collapsed
     * @param width sidebar width in pixels, or null
     */
    suspend fun setScreenSidebar(screenIndex: Int, collapsed: Boolean, width: Int? = null) {
        windowSocket.send(WindowCommand.SetScreenSidebar(screenIndex = screenIndex, collapsed = collapsed, width = width))
    }

    /**
     * Persist the window bounds for this screen.
     *
     * @param screenIndex the screen index
     * @param bounds the window geometry
     */
    suspend fun setScreenBounds(screenIndex: Int, bounds: ScreenBounds) {
        windowSocket.send(WindowCommand.SetScreenBounds(screenIndex = screenIndex, bounds = bounds))
    }

    /**
     * Open a new screen or reopen a closed one.
     *
     * @param screenIndex the screen index to open, or -1 for auto-assign
     */
    suspend fun openScreen(screenIndex: Int = -1) {
        windowSocket.send(WindowCommand.OpenScreen(screenIndex = screenIndex))
    }

    /**
     * Mark a screen as closed (preserving its state).
     *
     * @param screenIndex the screen to close
     */
    suspend fun closeScreen(screenIndex: Int) {
        windowSocket.send(WindowCommand.CloseScreen(screenIndex = screenIndex))
    }

    // ── Internal ────────────────────────────────────────────────────

    /**
     * Apply a server-pushed [ScreenState] to the local state. Per-screen
     * theme/appearance overrides take precedence over global settings when
     * non-null.
     *
     * @param screenState the screen state received from the server
     */
    private fun applyScreenState(screenState: ScreenState) {
        val cur = _stateFlow.value
        var updated = cur.copy(screenState = screenState)

        // Apply per-screen theme overrides when present.
        fun resolveTheme(name: String?): TerminalTheme? =
            name?.takeIf { it.isNotBlank() }
                ?.let { n -> recommendedThemes.firstOrNull { it.name == n } }

        screenState.themeName?.let { name ->
            resolveTheme(name)?.let { updated = updated.copy(theme = it) }
        }
        screenState.appearance?.let { name ->
            runCatching { Appearance.valueOf(name) }.getOrNull()
                ?.let { updated = updated.copy(appearance = it) }
        }
        screenState.paneFontSize?.let { updated = updated.copy(paneFontSize = it) }
        updated = updated.copy(
            sidebarCollapsed = screenState.sidebarCollapsed,
            sidebarWidth = screenState.sidebarWidth ?: updated.sidebarWidth,
            sidebarTheme = resolveTheme(screenState.sidebarThemeName) ?: updated.sidebarTheme,
            terminalTheme = resolveTheme(screenState.terminalThemeName) ?: updated.terminalTheme,
            diffTheme = resolveTheme(screenState.diffThemeName) ?: updated.diffTheme,
            fileBrowserTheme = resolveTheme(screenState.fileBrowserThemeName) ?: updated.fileBrowserTheme,
            tabsTheme = resolveTheme(screenState.tabsThemeName) ?: updated.tabsTheme,
            chromeTheme = resolveTheme(screenState.chromeThemeName) ?: updated.chromeTheme,
            windowsTheme = resolveTheme(screenState.windowsThemeName) ?: updated.windowsTheme,
            activeTheme = resolveTheme(screenState.activeThemeName) ?: updated.activeTheme,
        )

        emit(updated)
    }

    /**
     * Apply a server-pushed UI settings JSON object to the local state.
     * Called when a [WindowEnvelope.UiSettings] envelope arrives.
     *
     * Unknown keys are silently ignored; unknown enum values fall back to the
     * current state so a bad server blob never crashes the client.
     *
     * @param settings the raw JSON object from the server.
     */
    fun applyServerUiSettings(settings: kotlinx.serialization.json.JsonObject) {
        val cur = _stateFlow.value
        val themeName = settings["theme"]?.jsonPrimitive?.contentOrNull
        val theme = themeName?.let { n -> recommendedThemes.firstOrNull { it.name == n } } ?: cur.theme

        val appearanceName = settings["appearance"]?.jsonPrimitive?.contentOrNull
        val appearance = appearanceName
            ?.let { runCatching { Appearance.valueOf(it) }.getOrNull() }
            ?: cur.appearance

        val fontSize = settings["paneFontSize"]?.jsonPrimitive?.intOrNull ?: cur.paneFontSize
        val sidebarW = settings["sidebarWidth"]?.jsonPrimitive?.intOrNull ?: cur.sidebarWidth
        val sidebarCol = settings["sidebarCollapsed"]?.jsonPrimitive?.booleanOrNull ?: cur.sidebarCollapsed
        val desktopNotif = settings["desktopNotifications"]?.jsonPrimitive?.booleanOrNull ?: cur.desktopNotifications

        fun sectionTheme(key: String, current: TerminalTheme?): TerminalTheme? {
            val name = settings[key]?.jsonPrimitive?.contentOrNull ?: return current
            if (name.isEmpty()) return null
            return recommendedThemes.firstOrNull { it.name == name } ?: current
        }

        emit(cur.copy(
            theme = theme,
            appearance = appearance,
            paneFontSize = fontSize,
            sidebarWidth = sidebarW,
            sidebarCollapsed = sidebarCol,
            desktopNotifications = desktopNotif,
            sidebarTheme = sectionTheme("theme.sidebar", cur.sidebarTheme),
            terminalTheme = sectionTheme("theme.terminal", cur.terminalTheme),
            diffTheme = sectionTheme("theme.diff", cur.diffTheme),
            fileBrowserTheme = sectionTheme("theme.fileBrowser", cur.fileBrowserTheme),
            tabsTheme = sectionTheme("theme.tabs", cur.tabsTheme),
            chromeTheme = sectionTheme("theme.chrome", cur.chromeTheme),
            windowsTheme = sectionTheme("theme.windows", cur.windowsTheme),
            activeTheme = sectionTheme("theme.active", cur.activeTheme),
        ))
    }

    private fun emit(state: State) {
        _stateFlow.value = state
    }
}

/**
 * Resolves the full semantic palette for this state snapshot.
 *
 * @param systemIsDark whether the host OS is currently in dark mode
 * @return the fully resolved [ResolvedPalette]
 * @see se.soderbjorn.termtastic.resolve
 */
fun AppBackingViewModel.State.resolvedPalette(systemIsDark: Boolean): ResolvedPalette =
    theme.resolve(appearance, systemIsDark)

/**
 * Resolves the semantic palette for a specific app section, using the
 * section-specific theme override if set, or falling back to the global theme.
 *
 * @param section one of `"sidebar"`, `"terminal"`, `"diff"`, `"fileBrowser"`, `"tabs"`, `"chrome"`, `"windows"`, `"active"`
 * @param systemIsDark whether the host OS is currently in dark mode
 * @return the resolved [ResolvedPalette] for that section
 * @see resolvedPalette
 */
fun AppBackingViewModel.State.sectionPalette(section: String, systemIsDark: Boolean): ResolvedPalette {
    val sectionTheme = when (section) {
        "sidebar" -> sidebarTheme
        "terminal" -> terminalTheme
        "diff" -> diffTheme
        "fileBrowser" -> fileBrowserTheme
        "tabs" -> tabsTheme
        "chrome" -> chromeTheme
        "windows" -> windowsTheme
        "active" -> activeTheme
        else -> null
    }
    return (sectionTheme ?: theme).resolve(appearance, systemIsDark)
}
