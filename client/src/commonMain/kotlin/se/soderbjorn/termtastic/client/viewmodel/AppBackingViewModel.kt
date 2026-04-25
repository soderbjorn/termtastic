/**
 * Main application ViewModel that combines window layout, per-session state,
 * and UI settings into a single observable [AppBackingViewModel.State].
 *
 * Platform UI layers (Compose on Android, SwiftUI on iOS, xterm.js on web)
 * collect [AppBackingViewModel.stateFlow] to drive their rendering. Layout
 * mutation methods (tab/pane CRUD, split, float, pop-out) delegate to a
 * [LayoutViewModel]; settings mutations are routed through a
 * [SettingsViewModel]; server-pushed dynamic state and envelope streams
 * are folded in by a [SessionStateViewModel]. This file is a thin
 * coordinator that preserves the original public API of
 * `AppBackingViewModel` so the iOS Kotlin/Native binary surface and the
 * web/Android consumers do not have to change.
 *
 * @see se.soderbjorn.termtastic.client.WindowSocket
 * @see SettingsPersister
 * @see LayoutViewModel
 * @see SettingsViewModel
 * @see SessionStateViewModel
 */
package se.soderbjorn.termtastic.client.viewmodel

import se.soderbjorn.darkness.core.*

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import se.soderbjorn.darkness.core.Appearance
import se.soderbjorn.termtastic.ClaudeUsageData
import se.soderbjorn.darkness.core.CustomScheme
import se.soderbjorn.darkness.core.DEFAULT_DARK_THEME_NAME
import se.soderbjorn.darkness.core.DEFAULT_LIGHT_THEME_NAME
import se.soderbjorn.darkness.core.DEFAULT_THEME_NAME
import se.soderbjorn.darkness.core.ResolvedPalette
import se.soderbjorn.darkness.core.ColorScheme
import se.soderbjorn.darkness.core.Theme
import se.soderbjorn.termtastic.WindowCommand
import se.soderbjorn.termtastic.WindowConfig
import se.soderbjorn.termtastic.client.WindowSocket
import se.soderbjorn.termtastic.client.WindowStateRepository
import se.soderbjorn.darkness.core.recommendedColorSchemes
import se.soderbjorn.darkness.core.resolve
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
    private val layout = LayoutViewModel(windowSocket)
    private val settings = SettingsViewModel(settingsPersister)
    private val sessionState = SessionStateViewModel(windowSocket, windowState)

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
     * @property paneFontFamily     preset key for the terminal/code font family
     *   (e.g. `"menlo"`, `"jetbrainsMono"`), or `null` to use the system default
     *   monospace stack. Applied to xterm.js terminals and CSS-based monospace
     *   surfaces (git diff, markdown code blocks) on the web client. Other
     *   clients ignore the value.
     * @property sidebarWidth       persisted sidebar width in pixels, or `null`.
     * @property sidebarCollapsed    whether the sidebar is currently collapsed.
     * @property headerCollapsed     whether the app header (tab bar + toolbar)
     *   is currently hidden.
     * @property usageBarCollapsed   whether the Claude usage bar is currently
     *   hidden.
     * @property desktopNotifications whether desktop notifications are enabled.
     * @property electronCustomTitleBar whether the Electron window should hide
     *   the native OS title bar in favour of the themed chrome.
     * @property uiSettingsHydrated `true` once the server has pushed at least
     *   one UiSettings envelope.
     * @property sidebarTheme       optional theme override for sidebar sections.
     * @property terminalTheme      optional theme override for terminal panes.
     * @property diffTheme          optional theme override for git diff panes.
     * @property fileBrowserTheme   optional theme override for file browser panes.
     * @property tabsTheme          optional theme override for the tab bar.
     * @property chromeTheme        optional theme override for window chrome.
     * @property activeTheme        optional theme override for active indicators.
     */
    data class State(
        val config: WindowConfig? = null,
        val sessionStates: Map<String, String?> = emptyMap(),
        val pendingApproval: Boolean = false,
        val claudeUsage: ClaudeUsageData? = null,
        val theme: ColorScheme = recommendedColorSchemes.first { it.name == DEFAULT_THEME_NAME },
        val appearance: Appearance = Appearance.Auto,
        val paneFontSize: Int? = null,
        val paneFontFamily: String? = null,
        val sidebarWidth: Int? = null,
        val sidebarCollapsed: Boolean = false,
        val headerCollapsed: Boolean = false,
        val usageBarCollapsed: Boolean = false,
        val desktopNotifications: Boolean = true,
        val electronCustomTitleBar: Boolean = false,
        val sidebarTheme: ColorScheme? = null,
        val terminalTheme: ColorScheme? = null,
        val diffTheme: ColorScheme? = null,
        val fileBrowserTheme: ColorScheme? = null,
        val tabsTheme: ColorScheme? = null,
        val chromeTheme: ColorScheme? = null,
        val windowsTheme: ColorScheme? = null,
        val activeTheme: ColorScheme? = null,
        val bottomBarTheme: ColorScheme? = null,
        val uiSettingsHydrated: Boolean = false,
        val lightThemeName: String = DEFAULT_LIGHT_THEME_NAME,
        val darkThemeName: String = DEFAULT_DARK_THEME_NAME,
        val favoriteThemes: List<String> = emptyList(),
        val favoriteSchemes: List<String> = emptyList(),
        val customSchemes: Map<String, CustomScheme> = emptyMap(),
        val customThemes: Map<String, Theme> = emptyMap(),
    )

    /**
     * Start collecting window state and envelope streams. This is a
     * long-running suspend function — call it from a lifecycle-scoped
     * coroutine. It returns only when the enclosing scope is cancelled.
     */
    suspend fun run() {
        sessionState.run(
            onDynamic = { dyn ->
                emit(_stateFlow.value.copy(
                    config = dyn.config,
                    sessionStates = dyn.sessionStates,
                    pendingApproval = dyn.pendingApproval,
                ))
            },
            onClaudeUsage = { usage ->
                emit(_stateFlow.value.copy(claudeUsage = usage))
            },
            onUiSettings = { uiSettings ->
                val elapsed = settings.lastLocalSettingsChange.elapsedNow()
                if (elapsed > 2.seconds) {
                    applyServerUiSettings(uiSettings)
                }
            },
        )
    }

    // ── UI settings mutations ───────────────────────────────────────

    /** Update the terminal colour theme and persist the choice to the server. */
    suspend fun setTheme(theme: ColorScheme) {
        emit(_stateFlow.value.copy(theme = theme))
        settings.persistSetting("theme", theme.name)
    }

    /** Update the light/dark appearance mode and persist it. */
    suspend fun setAppearance(appearance: Appearance) {
        emit(_stateFlow.value.copy(appearance = appearance))
        settings.persistSetting("appearance", appearance.name)
    }

    /** Update the per-pane font size and persist it. */
    suspend fun setPaneFontSize(size: Int) {
        emit(_stateFlow.value.copy(paneFontSize = size))
        settings.persistSetting("paneFontSize", size.toString())
    }

    /** Update the terminal/code font-family preset and persist it. */
    suspend fun setPaneFontFamily(key: String) {
        emit(_stateFlow.value.copy(paneFontFamily = key.ifEmpty { null }))
        settings.persistSetting("paneFontFamily", key)
    }

    /** Update the sidebar width and persist it. */
    suspend fun setSidebarWidth(width: Int) {
        emit(_stateFlow.value.copy(sidebarWidth = width))
        settings.persistSetting("sidebarWidth", width.toString())
    }

    /** Collapse or expand the sidebar and persist the preference. */
    suspend fun setSidebarCollapsed(collapsed: Boolean) {
        emit(_stateFlow.value.copy(sidebarCollapsed = collapsed))
        settings.persistSetting("sidebarCollapsed", collapsed.toString())
    }

    /** Collapse or expand the app header and persist the preference. */
    suspend fun setHeaderCollapsed(collapsed: Boolean) {
        emit(_stateFlow.value.copy(headerCollapsed = collapsed))
        settings.persistSetting("headerCollapsed", collapsed.toString())
    }

    /** Collapse or expand the Claude usage bar and persist the preference. */
    suspend fun setUsageBarCollapsed(collapsed: Boolean) {
        emit(_stateFlow.value.copy(usageBarCollapsed = collapsed))
        settings.persistSetting("usageBarCollapsed", collapsed.toString())
    }

    /** Enable or disable desktop notifications and persist the preference. */
    suspend fun setDesktopNotifications(enabled: Boolean) {
        emit(_stateFlow.value.copy(desktopNotifications = enabled))
        settings.persistSetting("desktopNotifications", enabled.toString())
    }

    /** Enable or disable the custom Electron title bar and persist it. */
    suspend fun setElectronCustomTitleBar(enabled: Boolean) {
        emit(_stateFlow.value.copy(electronCustomTitleBar = enabled))
        settings.persistSetting("electronCustomTitleBar", enabled.toString())
    }

    /**
     * Set a per-section theme override and persist it. Pass `null` to clear
     * the override and fall back to the global theme.
     */
    suspend fun setSectionTheme(section: String, theme: ColorScheme?) {
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
            "bottomBar" -> cur.copy(bottomBarTheme = theme)
            else -> cur
        }
        emit(updated)
        settings.persistSetting("theme.$section", theme?.name ?: "")
    }

    /**
     * Apply a full theme configuration atomically — main theme plus all
     * section overrides — in a single **synchronous** state emission.
     * Persistence is fire-and-forget so the caller can run `applyAll()`
     * immediately after this returns without any coroutine involvement.
     */
    fun applyThemeConfiguration(
        theme: ColorScheme,
        sections: Map<String, ColorScheme?>,
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
            bottomBarTheme = sections["bottomBar"],
        )
        emit(updated)
        val batch = buildMap {
            put("theme", theme.name)
            for ((section, t) in sections) {
                put("theme.$section", t?.name ?: "")
            }
        }
        settings.fireAndForgetPersistSettings(batch)
    }

    // ── Layout mutations (delegated to LayoutViewModel) ─────────────

    /** Tell the server to switch to tab [tabId]. */
    suspend fun setActiveTab(tabId: String) = layout.setActiveTab(tabId)

    /** Focus [paneId] within [tabId]. */
    suspend fun setFocusedPane(tabId: String, paneId: String) = layout.setFocusedPane(tabId, paneId)

    /** Create a new tab with a default terminal pane. */
    suspend fun addTab() = layout.addTab()

    /** Close tab [tabId] and all panes within it. */
    suspend fun closeTab(tabId: String) = layout.closeTab(tabId)

    /** Rename tab [tabId] to [title]. */
    suspend fun renameTab(tabId: String, title: String) = layout.renameTab(tabId, title)

    /** Reorder [tabId] relative to [targetTabId]. */
    suspend fun moveTab(tabId: String, targetTabId: String, before: Boolean) =
        layout.moveTab(tabId, targetTabId, before)

    /** Close pane [paneId] (terminal, file-browser, or git). */
    suspend fun closePane(paneId: String) = layout.closePane(paneId)

    /** Close all panes that share [sessionId] and terminate the PTY. */
    suspend fun closeSession(sessionId: String) = layout.closeSession(sessionId)

    /** Set a custom title for [paneId]. */
    suspend fun renamePane(paneId: String, title: String) = layout.renamePane(paneId, title)

    /** Add a new terminal pane to [tabId]. */
    suspend fun addPaneToTab(tabId: String) = layout.addPaneToTab(tabId)

    /** Add a file-browser pane to [tabId]. */
    suspend fun addFileBrowserToTab(tabId: String) = layout.addFileBrowserToTab(tabId)

    /** Add a git pane to [tabId]. */
    suspend fun addGitToTab(tabId: String) = layout.addGitToTab(tabId)

    /** Add a linked pane to [tabId] sharing [targetSessionId]. */
    suspend fun addLinkToTab(tabId: String, targetSessionId: String) =
        layout.addLinkToTab(tabId, targetSessionId)

    /** Update the geometry (position and size) for [paneId]. */
    suspend fun setPaneGeom(paneId: String, x: Double, y: Double, width: Double, height: Double) =
        layout.setPaneGeom(paneId, x, y, width, height)

    /** Bring [paneId] to the front of its tab's z-order. */
    suspend fun raisePane(paneId: String) = layout.raisePane(paneId)

    /** Move [paneId] from its current tab to [targetTabId]. */
    suspend fun movePaneToTab(paneId: String, targetTabId: String) =
        layout.movePaneToTab(paneId, targetTabId)

    /** Ask the server to re-send Claude AI usage data. */
    suspend fun refreshUsage() = layout.refreshUsage()

    /** Request the server to open the settings panel. */
    suspend fun openSettings() = layout.openSettings()

    // ── Internal ────────────────────────────────────────────────────

    /**
     * Apply a server-pushed UI settings JSON object to the local state.
     * Called when a [se.soderbjorn.termtastic.WindowEnvelope.UiSettings]
     * envelope arrives.
     */
    fun applyServerUiSettings(settingsJson: kotlinx.serialization.json.JsonObject) {
        val cur = _stateFlow.value
        emit(settings.applyServerUiSettings(cur, settingsJson))
    }

    private fun emit(state: State) {
        _stateFlow.value = state
    }

    // ── Theme / scheme registries and resolution ────────────────────

    /**
     * Look up a theme (bundle of per-section scheme assignments) by name.
     * Checks custom themes first then built-in defaults, so a user may
     * override a default by cloning under the same name.
     */
    fun lookupTheme(name: String): Theme? = settings.lookupTheme(_stateFlow.value, name)

    /**
     * Look up a colour scheme by name. Custom schemes take precedence
     * over built-in [recommendedColorSchemes].
     */
    fun lookupScheme(name: String): ColorScheme? = settings.lookupScheme(_stateFlow.value, name)

    /**
     * Apply the currently-active theme (chosen by [Appearance] +
     * [systemIsDark] via the user's light / dark slot selection) to
     * [State.theme] and all per-section override fields.
     */
    fun refreshActiveTheme(systemIsDark: Boolean) {
        emit(settings.refreshActiveTheme(_stateFlow.value, systemIsDark))
    }

    // ── Light/dark slots, favourites, custom entities ───────────────

    /** Persist the chosen theme for the Light slot. */
    suspend fun setLightThemeName(name: String) {
        emit(_stateFlow.value.copy(lightThemeName = name))
        settings.persistSetting("theme.light", name)
    }

    /** Persist the chosen theme for the Dark slot. */
    suspend fun setDarkThemeName(name: String) {
        emit(_stateFlow.value.copy(darkThemeName = name))
        settings.persistSetting("theme.dark", name)
    }

    /**
     * Toggle the favourite status of [name] in the theme favourites list.
     * Adds to the end if absent; removes if present.
     */
    suspend fun toggleFavoriteTheme(name: String) {
        val cur = _stateFlow.value
        val next = if (name in cur.favoriteThemes)
            cur.favoriteThemes - name
        else
            cur.favoriteThemes + name
        emit(cur.copy(favoriteThemes = next))
        persistFavorites(next)
    }

    /** Reorder favourite themes by replacing the full list. */
    suspend fun setFavoriteThemes(ordered: List<String>) {
        emit(_stateFlow.value.copy(favoriteThemes = ordered))
        persistFavorites(ordered)
    }

    private suspend fun persistFavorites(list: List<String>) {
        val obj = buildJsonObject {
            put("favorites.themes", JsonArray(list.map { JsonPrimitive(it) }))
        }
        settings.persistJsonSettings(obj)
    }

    /** Toggle the favourite status of a colour scheme. */
    suspend fun toggleFavoriteScheme(name: String) {
        val cur = _stateFlow.value
        val next = if (name in cur.favoriteSchemes)
            cur.favoriteSchemes - name
        else
            cur.favoriteSchemes + name
        emit(cur.copy(favoriteSchemes = next))
        persistSchemeFavorites(next)
    }

    /** Replace the full ordered list of favourite schemes. */
    suspend fun setFavoriteSchemes(ordered: List<String>) {
        emit(_stateFlow.value.copy(favoriteSchemes = ordered))
        persistSchemeFavorites(ordered)
    }

    private suspend fun persistSchemeFavorites(list: List<String>) {
        val obj = buildJsonObject {
            put("favorites.schemes", JsonArray(list.map { JsonPrimitive(it) }))
        }
        settings.persistJsonSettings(obj)
    }

    /** Insert or replace a custom colour scheme by name. */
    suspend fun saveCustomScheme(scheme: CustomScheme) {
        val cur = _stateFlow.value
        val next = cur.customSchemes.toMutableMap().apply { put(scheme.name, scheme) }
        emit(cur.copy(customSchemes = next))
        settings.persistJsonSettings(settings.buildCustomSchemesJson(next))
    }

    /**
     * Remove a custom scheme. Drops the name from [State.favoriteSchemes]
     * and clears any pane override that was pointing at it.
     */
    suspend fun deleteCustomScheme(name: String) {
        val cur = _stateFlow.value
        val next = cur.customSchemes.toMutableMap().apply { remove(name) }
        val nextFavs = cur.favoriteSchemes - name
        val orphanPanes = buildList {
            val cfg = cur.config ?: return@buildList
            for (tab in cfg.tabs) {
                for (pane in tab.panes) {
                    if (pane.colorScheme == name) add(pane.leaf.id)
                }
            }
        }
        for (paneId in orphanPanes) {
            layout.clearPaneColorScheme(paneId)
        }
        emit(cur.copy(customSchemes = next, favoriteSchemes = nextFavs))
        settings.persistJsonSettings(settings.buildCustomSchemesJson(next))
        val meta = buildJsonObject {
            put("favorites.schemes", JsonArray(nextFavs.map { JsonPrimitive(it) }))
        }
        settings.persistJsonSettings(meta)
    }

    /** Insert or replace a custom theme (bundle). */
    suspend fun saveCustomTheme(theme: Theme) {
        val cur = _stateFlow.value
        val next = cur.customThemes.toMutableMap().apply { put(theme.name, theme) }
        emit(cur.copy(customThemes = next))
        settings.persistJsonSettings(settings.buildCustomThemesJson(next))
    }

    /**
     * Delete a custom theme by name. Drops it from [State.favoriteThemes]
     * and from the light/dark slots (falling back to defaults when
     * orphaned) before persisting.
     */
    suspend fun deleteCustomTheme(name: String) {
        val cur = _stateFlow.value
        val nextThemes = cur.customThemes.toMutableMap().apply { remove(name) }
        val nextFavs = cur.favoriteThemes - name
        val nextLight = if (cur.lightThemeName == name) DEFAULT_LIGHT_THEME_NAME else cur.lightThemeName
        val nextDark = if (cur.darkThemeName == name) DEFAULT_DARK_THEME_NAME else cur.darkThemeName
        emit(cur.copy(
            customThemes = nextThemes,
            favoriteThemes = nextFavs,
            lightThemeName = nextLight,
            darkThemeName = nextDark,
        ))
        settings.persistJsonSettings(settings.buildCustomThemesJson(nextThemes))
        val meta = buildJsonObject {
            put("favorites.themes", JsonArray(nextFavs.map { JsonPrimitive(it) }))
            put("theme.light", JsonPrimitive(nextLight))
            put("theme.dark", JsonPrimitive(nextDark))
        }
        settings.persistJsonSettings(meta)
    }
}

/**
 * Resolves the full semantic palette for this state snapshot.
 *
 * @param systemIsDark whether the host OS is currently in dark mode
 * @return the fully resolved [ResolvedPalette]
 */
fun AppBackingViewModel.State.resolvedPalette(systemIsDark: Boolean): ResolvedPalette =
    theme.resolve(appearance, systemIsDark)

/**
 * Resolves the semantic palette for a specific app section, using the
 * section-specific theme override if set, or falling back to the global theme.
 *
 * @param section one of `"sidebar"`, `"terminal"`, `"diff"`, `"fileBrowser"`, `"tabs"`, `"chrome"`, `"windows"`, `"active"`, `"bottomBar"`
 * @param systemIsDark whether the host OS is currently in dark mode
 * @return the resolved [ResolvedPalette] for that section
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
        "bottomBar" -> bottomBarTheme
        else -> null
    }
    return (sectionTheme ?: theme).resolve(appearance, systemIsDark)
}
