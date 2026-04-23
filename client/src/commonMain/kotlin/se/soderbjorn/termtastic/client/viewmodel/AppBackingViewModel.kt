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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import se.soderbjorn.termtastic.Appearance
import se.soderbjorn.termtastic.ClaudeUsageData
import se.soderbjorn.termtastic.ConfigMode
import se.soderbjorn.termtastic.CustomScheme
import se.soderbjorn.termtastic.DEFAULT_DARK_THEME_NAME
import se.soderbjorn.termtastic.DEFAULT_LIGHT_THEME_NAME
import se.soderbjorn.termtastic.DEFAULT_THEME_NAME
import se.soderbjorn.termtastic.ResolvedPalette
import se.soderbjorn.termtastic.ColorScheme
import se.soderbjorn.termtastic.Theme
import se.soderbjorn.termtastic.WindowCommand
import se.soderbjorn.termtastic.WindowConfig
import se.soderbjorn.termtastic.WindowEnvelope
import se.soderbjorn.termtastic.client.WindowSocket
import se.soderbjorn.termtastic.client.WindowStateRepository
import se.soderbjorn.termtastic.defaultThemes
import se.soderbjorn.termtastic.recommendedColorSchemes
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
     * @property paneFontFamily     preset key for the terminal/code font family
     *   (e.g. `"menlo"`, `"jetbrainsMono"`), or `null` to use the system default
     *   monospace stack. Applied to xterm.js terminals and CSS-based monospace
     *   surfaces (git diff, markdown code blocks) on the web client. Other
     *   clients ignore the value.
     * @property sidebarWidth       persisted sidebar width in pixels, or `null`.
     * @property sidebarCollapsed    whether the sidebar is currently collapsed.
     * @property headerCollapsed     whether the app header (tab bar + toolbar)
     *   is currently hidden. Toggled by dragging the horizontal divider below
     *   the header to 0 px; the divider stays visible so the user can drag the
     *   header back into view.
     * @property usageBarCollapsed   whether the Claude usage bar is currently
     *   hidden. Toggled by dragging the horizontal divider above the bar to
     *   0 px; only applies when the server has pushed usage data — if no data
     *   is available, the bar and its divider are hidden regardless.
     * @property desktopNotifications whether desktop notifications are enabled.
     * @property electronCustomTitleBar whether the Electron window should hide
     *   the native OS title bar in favour of the themed chrome (`true`) or
     *   render the native OS title bar (`false`, default). Ignored by
     *   non-Electron clients.
     * @property uiSettingsHydrated `true` once the server has pushed at least
     *   one UiSettings envelope and [applyServerUiSettings] has populated the
     *   state with authoritative values. Consumers that forward state changes
     *   to out-of-process side effects (e.g. the Electron main process for the
     *   custom title-bar toggle) must gate on this flag to avoid acting on the
     *   defaults emitted before the server responds.
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
        /**
         * Name of the theme (default or custom) to apply when the resolved
         * appearance is Light. Resolved via [lookupThemeByName]. When absent
         * (migration from older settings) this seeds from [theme] on hydrate.
         */
        val lightThemeName: String = DEFAULT_LIGHT_THEME_NAME,
        /**
         * Name of the theme (default or custom) to apply when the resolved
         * appearance is Dark. Resolved via [lookupThemeByName].
         */
        val darkThemeName: String = DEFAULT_DARK_THEME_NAME,
        /**
         * Ordered list of favourited theme names. May mix default and
         * custom themes. Used by the toolbar theme picker and the settings
         * favourites strip; names that no longer resolve are silently
         * dropped on next save.
         */
        val favoriteThemes: List<String> = emptyList(),
        /**
         * Ordered list of favourited colour-scheme names. Mirrors
         * [favoriteThemes]; used by the Theme Manager's Color-schemes tab
         * and the per-pane palette dropdown to surface favourites first.
         */
        val favoriteSchemes: List<String> = emptyList(),
        /**
         * User-defined custom colour schemes, keyed by name. Schemes in
         * this map take precedence over same-named [recommendedColorSchemes]
         * during lookup — giving users a way to override built-ins by
         * cloning under the original name.
         */
        val customSchemes: Map<String, CustomScheme> = emptyMap(),
        /**
         * User-defined custom themes, keyed by name. Same precedence
         * rule as [customSchemes]. Stored under the `themeConfigs` key
         * on the server for backwards compatibility with saved user
         * content from earlier versions.
         */
        val customThemes: Map<String, Theme> = emptyMap(),
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
    suspend fun setTheme(theme: ColorScheme) {
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
     * Update the terminal/code font-family preset and persist it to the server.
     *
     * The value is a short preset key (e.g. `"menlo"`, `"jetbrainsMono"`) that
     * the web client maps to a CSS font-family stack. Other clients currently
     * ignore the setting.
     *
     * @param key the preset key to store, or the empty string to clear and
     *   fall back to the system default.
     */
    suspend fun setPaneFontFamily(key: String) {
        emit(_stateFlow.value.copy(paneFontFamily = key.ifEmpty { null }))
        persistSetting("paneFontFamily", key)
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
     * Collapse or expand the app header (tab bar + toolbar). The header is
     * hidden with `display: none` when collapsed; the drag divider below the
     * header stays visible so the user can drag the header back into view.
     *
     * @param collapsed `true` to hide the header, `false` to show it.
     */
    suspend fun setHeaderCollapsed(collapsed: Boolean) {
        emit(_stateFlow.value.copy(headerCollapsed = collapsed))
        persistSetting("headerCollapsed", collapsed.toString())
    }

    /**
     * Collapse or expand the Claude usage bar at the bottom of the window.
     * Mirrors [setHeaderCollapsed]; only takes visual effect while usage data
     * is available, but the preference is persisted regardless so reloads
     * respect the user's last choice.
     *
     * @param collapsed `true` to hide the bar, `false` to show it.
     */
    suspend fun setUsageBarCollapsed(collapsed: Boolean) {
        emit(_stateFlow.value.copy(usageBarCollapsed = collapsed))
        persistSetting("usageBarCollapsed", collapsed.toString())
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
     * Enable or disable the custom (themed) Electron window title bar and
     * persist the preference. Only meaningful inside the Electron desktop
     * shell; other clients store the value but ignore it.
     *
     * @param enabled `true` to hide the native OS title bar and render the
     *   themed chrome, `false` to show the native OS title bar.
     */
    suspend fun setElectronCustomTitleBar(enabled: Boolean) {
        emit(_stateFlow.value.copy(electronCustomTitleBar = enabled))
        persistSetting("electronCustomTitleBar", enabled.toString())
    }

    /**
     * Set a per-section theme override and persist it. Pass `null` to clear
     * the override and fall back to the global theme.
     *
     * @param section one of `"sidebar"`, `"terminal"`, `"diff"`, `"fileBrowser"`, `"tabs"`, `"chrome"`, `"windows"`, `"active"`, `"bottomBar"`
     * @param theme   the override theme, or `null` to clear
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

    /** Update the geometry (position and size) for [paneId]. */
    suspend fun setPaneGeom(paneId: String, x: Double, y: Double, width: Double, height: Double) {
        windowSocket.send(WindowCommand.SetPaneGeom(paneId = paneId, x = x, y = y, width = width, height = height))
    }

    /** Bring [paneId] to the front of its tab's z-order. */
    suspend fun raisePane(paneId: String) {
        windowSocket.send(WindowCommand.RaisePane(paneId = paneId))
    }

    /** Move [paneId] from its current tab to [targetTabId]. */
    suspend fun movePaneToTab(paneId: String, targetTabId: String) {
        windowSocket.send(WindowCommand.MovePaneToTab(paneId = paneId, targetTabId = targetTabId))
    }

    /** Ask the server to re-send Claude AI usage data. */
    suspend fun refreshUsage() {
        windowSocket.send(WindowCommand.RefreshUsage)
    }

    /** Request the server to open the settings panel. */
    suspend fun openSettings() {
        windowSocket.send(WindowCommand.OpenSettings)
    }

    // ── Internal ────────────────────────────────────────────────────

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

        // ── Custom schemes / themes: parse first so theme lookups see them ──
        val customSchemes = parseCustomSchemes(settings["customSchemes"]) ?: cur.customSchemes
        val customThemes = parseCustomThemes(settings["themeConfigs"]) ?: cur.customThemes

        fun themeByName(name: String?): ColorScheme? {
            if (name.isNullOrEmpty()) return null
            customSchemes[name]?.let { return it.toColorScheme() }
            return recommendedColorSchemes.firstOrNull { it.name == name }
        }

        val themeName = settings["theme"]?.jsonPrimitive?.contentOrNull
        val theme = themeByName(themeName) ?: cur.theme

        val appearanceName = settings["appearance"]?.jsonPrimitive?.contentOrNull
        val appearance = appearanceName
            ?.let { runCatching { Appearance.valueOf(it) }.getOrNull() }
            ?: cur.appearance

        val fontSize = settings["paneFontSize"]?.jsonPrimitive?.intOrNull ?: cur.paneFontSize
        val fontFamily = settings["paneFontFamily"]?.jsonPrimitive?.contentOrNull
            ?.ifEmpty { null } ?: cur.paneFontFamily
        val sidebarW = settings["sidebarWidth"]?.jsonPrimitive?.intOrNull ?: cur.sidebarWidth
        val sidebarCol = settings["sidebarCollapsed"]?.jsonPrimitive?.booleanOrNull ?: cur.sidebarCollapsed
        val headerCol = settings["headerCollapsed"]?.jsonPrimitive?.booleanOrNull ?: cur.headerCollapsed
        val usageBarCol = settings["usageBarCollapsed"]?.jsonPrimitive?.booleanOrNull ?: cur.usageBarCollapsed
        val desktopNotif = settings["desktopNotifications"]?.jsonPrimitive?.booleanOrNull ?: cur.desktopNotifications
        val electronCustom = settings["electronCustomTitleBar"]?.jsonPrimitive?.booleanOrNull ?: cur.electronCustomTitleBar

        // Section overrides used to be top-level `theme.<section>` keys on the
        // server, selected independently of the main theme. After the
        // 2026-04 rearchitecture they live inside each theme bundle
        // ([Theme]); section fields on [State] are therefore
        // **derived** by [refreshActiveTheme] and must not be overwritten
        // here. Ignoring the legacy keys also keeps a stray server echo
        // from reimposing a long-dead override after startup.

        // ── Light / dark theme slot names ─────────────────────────────
        val storedLight = settings["theme.light"]?.jsonPrimitive?.contentOrNull
        val storedDark = settings["theme.dark"]?.jsonPrimitive?.contentOrNull
        // Migration: if the new per-mode keys are absent, seed both slots
        // from the legacy top-level `theme` key (or keep current values).
        val lightName = storedLight?.takeIf { it.isNotEmpty() }
            ?: themeName?.takeIf { it.isNotEmpty() } ?: cur.lightThemeName
        val darkName = storedDark?.takeIf { it.isNotEmpty() }
            ?: themeName?.takeIf { it.isNotEmpty() } ?: cur.darkThemeName

        // If this is a first-load migration (no per-mode slot keys on the
        // server yet) AND the server still has legacy top-level
        // `theme.<section>` overrides, proactively clear them. Nothing on
        // this client reads them anymore, but an older mobile build still
        // does — so leaving them in place cross-pollutes state across
        // devices. Persist the new slot names + empty section keys in a
        // single fire-and-forget POST; the server's merge semantics
        // accept blanks without complaint.
        val firstLoadMigration = storedLight == null && storedDark == null
        val hasLegacySectionKey = listOf(
            "theme.sidebar", "theme.terminal", "theme.diff", "theme.fileBrowser",
            "theme.tabs", "theme.chrome", "theme.windows", "theme.active",
            "theme.bottomBar",
        ).any { (settings[it]?.jsonPrimitive?.contentOrNull).orEmpty().isNotEmpty() }
        if (firstLoadMigration || hasLegacySectionKey) {
            settingsPersister?.fireAndForgetPutSettings(buildMap {
                put("theme.light", lightName)
                put("theme.dark", darkName)
                put("theme.sidebar", "")
                put("theme.terminal", "")
                put("theme.diff", "")
                put("theme.fileBrowser", "")
                put("theme.tabs", "")
                put("theme.chrome", "")
                put("theme.windows", "")
                put("theme.active", "")
                put("theme.bottomBar", "")
            })
            lastLocalSettingsChange = TimeSource.Monotonic.markNow()
        }

        // ── Favourites ────────────────────────────────────────────────
        val favorites = parseStringList(settings["favorites.themes"]) ?: cur.favoriteThemes
        val favoriteSchemes = parseStringList(settings["favorites.schemes"]) ?: cur.favoriteSchemes

        emit(cur.copy(
            theme = theme,
            appearance = appearance,
            paneFontSize = fontSize,
            paneFontFamily = fontFamily,
            sidebarWidth = sidebarW,
            sidebarCollapsed = sidebarCol,
            headerCollapsed = headerCol,
            usageBarCollapsed = usageBarCol,
            desktopNotifications = desktopNotif,
            electronCustomTitleBar = electronCustom,
            // Section overrides are intentionally untouched — they are
            // derived from the active theme bundle by [refreshActiveTheme]
            // and get recomputed whenever slots / custom maps change.
            // Writing them from legacy server keys (what the old
            // per-section APIs did) would reimpose dead overrides on
            // every hydrate / envelope echo.
            uiSettingsHydrated = true,
            lightThemeName = lightName,
            darkThemeName = darkName,
            favoriteThemes = favorites,
            favoriteSchemes = favoriteSchemes,
            customSchemes = customSchemes,
            customThemes = customThemes,
        ))
    }

    /**
     * Parse a `customSchemes` JsonElement into a typed map. Returns `null`
     * when the element is absent or malformed so callers can keep the
     * current state; returns an empty map when the element is an empty
     * object (explicit clear).
     */
    private fun parseCustomSchemes(el: kotlinx.serialization.json.JsonElement?): Map<String, CustomScheme>? {
        val obj = (el as? JsonObject) ?: return null
        val out = linkedMapOf<String, CustomScheme>()
        for ((name, value) in obj) {
            val o = value as? JsonObject ?: continue
            val darkFg = o["darkFg"]?.jsonPrimitive?.contentOrNull ?: continue
            val lightFg = o["lightFg"]?.jsonPrimitive?.contentOrNull ?: continue
            val darkBg = o["darkBg"]?.jsonPrimitive?.contentOrNull ?: continue
            val lightBg = o["lightBg"]?.jsonPrimitive?.contentOrNull ?: continue
            val ovr = (o["overrides"] as? JsonObject)?.let { om ->
                om.mapNotNull { (k, v) ->
                    val p = v as? JsonPrimitive ?: return@mapNotNull null
                    val l = p.longOrNull ?: p.contentOrNull?.toLongOrNull()
                    l?.let { k to it }
                }.toMap()
            } ?: emptyMap()
            out[name] = CustomScheme(name, darkFg, lightFg, darkBg, lightBg, ovr)
        }
        return out
    }

    /**
     * Parse the `themeConfigs` JsonElement into typed user-defined
     * [Theme]s. Legacy entries without a `mode` field default
     * to [ConfigMode.Both]; legacy default-marker sentinels (`__default`)
     * are ignored — defaults live in [defaultThemes].
     */
    private fun parseCustomThemes(el: kotlinx.serialization.json.JsonElement?): Map<String, Theme>? {
        val obj = (el as? JsonObject) ?: return null
        val out = linkedMapOf<String, Theme>()
        for ((name, value) in obj) {
            val o = value as? JsonObject ?: continue
            if (o["__default"]?.jsonPrimitive?.booleanOrNull == true) continue
            val colorScheme = o["colorScheme"]?.jsonPrimitive?.contentOrNull ?: continue
            val modeStr = o["mode"]?.jsonPrimitive?.contentOrNull
            val mode = modeStr?.let { runCatching { ConfigMode.valueOf(it) }.getOrNull() } ?: ConfigMode.Both
            out[name] = Theme(
                name = name,
                mode = mode,
                colorScheme = colorScheme,
                sidebar = o["theme.sidebar"]?.jsonPrimitive?.contentOrNull ?: "",
                terminal = o["theme.terminal"]?.jsonPrimitive?.contentOrNull ?: "",
                diff = o["theme.diff"]?.jsonPrimitive?.contentOrNull ?: "",
                fileBrowser = o["theme.fileBrowser"]?.jsonPrimitive?.contentOrNull ?: "",
                tabs = o["theme.tabs"]?.jsonPrimitive?.contentOrNull ?: "",
                chrome = o["theme.chrome"]?.jsonPrimitive?.contentOrNull ?: "",
                windows = o["theme.windows"]?.jsonPrimitive?.contentOrNull ?: "",
                active = o["theme.active"]?.jsonPrimitive?.contentOrNull ?: "",
                bottomBar = o["theme.bottomBar"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }
        return out
    }

    /** Parse a JsonArray-of-strings element into a Kotlin list. */
    private fun parseStringList(el: kotlinx.serialization.json.JsonElement?): List<String>? {
        val arr = (el as? JsonArray) ?: return null
        return arr.mapNotNull { it.jsonPrimitive.contentOrNull }
    }

    private fun emit(state: State) {
        _stateFlow.value = state
    }

    // ── Theme / scheme registries and resolution ────────────────────

    /**
     * Look up a theme (bundle of per-section scheme assignments) by name.
     * Checks custom themes first then built-in [defaultThemes], so
     * a user may override a default by cloning under the same name.
     *
     * @param name the theme name to look up
     * @return the matching [Theme], or `null` if none exists
     */
    fun lookupTheme(name: String): Theme? {
        if (name.isEmpty()) return null
        val cur = _stateFlow.value
        cur.customThemes[name]?.let { return it }
        return defaultThemes.firstOrNull { it.name == name }
    }

    /**
     * Look up a colour scheme by name. Custom schemes take precedence
     * over built-in [recommendedColorSchemes].
     *
     * @param name the scheme name; empty returns `null`
     * @return the matching [ColorScheme] or `null` when not found
     */
    fun lookupScheme(name: String): ColorScheme? {
        if (name.isEmpty()) return null
        val cur = _stateFlow.value
        cur.customSchemes[name]?.let { return it.toColorScheme() }
        return recommendedColorSchemes.firstOrNull { it.name == name }
    }

    /**
     * Apply the currently-active theme (chosen by [Appearance] +
     * [systemIsDark] via the user's light / dark slot selection) to
     * [State.theme] and all per-section override fields. Called by the
     * web client on appearance changes, system-preference changes, and
     * any mutation that affects which theme should be live.
     *
     * @param systemIsDark whether the host reports dark mode; only
     *   consulted when appearance is [Appearance.Auto].
     */
    fun refreshActiveTheme(systemIsDark: Boolean) {
        val cur = _stateFlow.value
        val effectiveDark = when (cur.appearance) {
            Appearance.Dark -> true
            Appearance.Light -> false
            Appearance.Auto -> systemIsDark
        }
        val selectedName = if (effectiveDark) cur.darkThemeName else cur.lightThemeName
        // If no theme bundle exists with this name, treat [selectedName] as a
        // bare scheme choice: the main scheme is [selectedName] and every
        // section falls through to it. Do NOT hunt for an arbitrary preset
        // that happens to use [selectedName] as its main scheme — those
        // presets usually carry deliberate per-section overrides (e.g.
        // Neon Circuit uses Tron as main but pairs it with Vapor Pink
        // sidebar + Cyber Teal terminal), which would produce a visibly
        // mixed look when the user just wants a uniform theme.
        val preset = lookupTheme(selectedName)
            ?: Theme(name = selectedName, colorScheme = selectedName)
        val main = lookupScheme(preset.colorScheme)
            ?: lookupScheme(DEFAULT_THEME_NAME)
            ?: recommendedColorSchemes.first()
        fun sec(s: String): ColorScheme? = lookupScheme(s)
        emit(cur.copy(
            theme = main,
            sidebarTheme = sec(preset.sidebar),
            terminalTheme = sec(preset.terminal),
            diffTheme = sec(preset.diff),
            fileBrowserTheme = sec(preset.fileBrowser),
            tabsTheme = sec(preset.tabs),
            chromeTheme = sec(preset.chrome),
            windowsTheme = sec(preset.windows),
            activeTheme = sec(preset.active),
            bottomBarTheme = sec(preset.bottomBar),
        ))
    }

    // ── New mutators: light/dark slots, favourites, custom entities ─

    /**
     * Persist the chosen theme for the Light slot. Does not change the
     * active appearance, but the web client should call
     * [refreshActiveTheme] afterwards so the derived fields update if
     * the light slot happens to be live.
     */
    suspend fun setLightThemeName(name: String) {
        emit(_stateFlow.value.copy(lightThemeName = name))
        persistSetting("theme.light", name)
    }

    /**
     * Persist the chosen theme for the Dark slot. See [setLightThemeName].
     */
    suspend fun setDarkThemeName(name: String) {
        emit(_stateFlow.value.copy(darkThemeName = name))
        persistSetting("theme.dark", name)
    }

    /**
     * Toggle the favourite status of [name]. Adds to the end if absent;
     * removes if present. Persists the full ordered list as a JSON
     * array under `favorites.themes`.
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

    /**
     * Reorder favourites by replacing the full list. Used when the user
     * drags to reorder within the manager or settings panel.
     */
    suspend fun setFavoriteThemes(ordered: List<String>) {
        emit(_stateFlow.value.copy(favoriteThemes = ordered))
        persistFavorites(ordered)
    }

    private suspend fun persistFavorites(list: List<String>) {
        lastLocalSettingsChange = TimeSource.Monotonic.markNow()
        val obj = buildJsonObject {
            put("favorites.themes", JsonArray(list.map { JsonPrimitive(it) }))
        }
        settingsPersister?.putJsonSettings(obj)
    }

    /**
     * Toggle the favourite status of a colour scheme. Mirrors
     * [toggleFavoriteTheme] but writes to `favorites.schemes`.
     *
     * @param name the scheme name to flip
     * @see toggleFavoriteTheme
     */
    suspend fun toggleFavoriteScheme(name: String) {
        val cur = _stateFlow.value
        val next = if (name in cur.favoriteSchemes)
            cur.favoriteSchemes - name
        else
            cur.favoriteSchemes + name
        emit(cur.copy(favoriteSchemes = next))
        persistSchemeFavorites(next)
    }

    /**
     * Replace the full ordered list of favourite schemes. Mirrors
     * [setFavoriteThemes].
     *
     * @param ordered the new favourites list, in display order
     * @see setFavoriteThemes
     */
    suspend fun setFavoriteSchemes(ordered: List<String>) {
        emit(_stateFlow.value.copy(favoriteSchemes = ordered))
        persistSchemeFavorites(ordered)
    }

    private suspend fun persistSchemeFavorites(list: List<String>) {
        lastLocalSettingsChange = TimeSource.Monotonic.markNow()
        val obj = buildJsonObject {
            put("favorites.schemes", JsonArray(list.map { JsonPrimitive(it) }))
        }
        settingsPersister?.putJsonSettings(obj)
    }

    /**
     * Insert or replace a custom colour scheme by name, and persist the
     * full [State.customSchemes] map.
     */
    suspend fun saveCustomScheme(scheme: CustomScheme) {
        val cur = _stateFlow.value
        val next = cur.customSchemes.toMutableMap().apply { put(scheme.name, scheme) }
        emit(cur.copy(customSchemes = next))
        persistCustomSchemes(next)
    }

    /**
     * Remove a custom scheme. Does not touch themes that still reference
     * it — those will transparently fall back to built-in lookup and
     * render as the default theme if the name has no built-in either.
     *
     * Drops the name from [State.favoriteSchemes] and clears any pane
     * override that was pointing at it (via [WindowCommand.SetPaneColorScheme]
     * with `scheme = null`) so no dead references survive the delete.
     */
    suspend fun deleteCustomScheme(name: String) {
        val cur = _stateFlow.value
        val next = cur.customSchemes.toMutableMap().apply { remove(name) }
        val nextFavs = cur.favoriteSchemes - name
        // Find every docked pane currently using this scheme; fire a clear
        // for each before the local state change so the server authoritative
        // state matches once the broadcast round-trips back.
        val orphanPanes = buildList {
            val cfg = cur.config ?: return@buildList
            for (tab in cfg.tabs) {
                for (pane in tab.panes) {
                    if (pane.colorScheme == name) add(pane.leaf.id)
                }
            }
        }
        for (paneId in orphanPanes) {
            windowSocket.send(WindowCommand.SetPaneColorScheme(paneId = paneId, scheme = null))
        }
        emit(cur.copy(customSchemes = next, favoriteSchemes = nextFavs))
        persistCustomSchemes(next)
        // Favourites piggyback on the next UI-settings write so a single
        // round-trip to the server covers both changes.
        lastLocalSettingsChange = TimeSource.Monotonic.markNow()
        val meta = buildJsonObject {
            put("favorites.schemes", JsonArray(nextFavs.map { JsonPrimitive(it) }))
        }
        settingsPersister?.putJsonSettings(meta)
    }

    private suspend fun persistCustomSchemes(schemes: Map<String, CustomScheme>) {
        lastLocalSettingsChange = TimeSource.Monotonic.markNow()
        val obj = buildJsonObject {
            put("customSchemes", JsonObject(schemes.mapValues { (_, s) ->
                buildJsonObject {
                    put("darkFg", JsonPrimitive(s.darkFg))
                    put("lightFg", JsonPrimitive(s.lightFg))
                    put("darkBg", JsonPrimitive(s.darkBg))
                    put("lightBg", JsonPrimitive(s.lightBg))
                    if (s.overrides.isNotEmpty()) {
                        put("overrides", JsonObject(s.overrides.mapValues { JsonPrimitive(it.value) }))
                    }
                }
            }))
        }
        settingsPersister?.putJsonSettings(obj)
    }

    /**
     * Insert or replace a custom theme (bundle). Persisted under the
     * legacy `themeConfigs` key so pre-existing server data survives.
     */
    suspend fun saveCustomTheme(theme: Theme) {
        val cur = _stateFlow.value
        val next = cur.customThemes.toMutableMap().apply { put(theme.name, theme) }
        emit(cur.copy(customThemes = next))
        persistCustomThemes(next)
    }

    /**
     * Delete a custom theme by name. Drops it from [State.favoriteThemes]
     * and from the light/dark slots (falling back to the default theme
     * when orphaned) before persisting.
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
        persistCustomThemes(nextThemes)
        val meta = buildJsonObject {
            put("favorites.themes", JsonArray(nextFavs.map { JsonPrimitive(it) }))
            put("theme.light", JsonPrimitive(nextLight))
            put("theme.dark", JsonPrimitive(nextDark))
        }
        settingsPersister?.putJsonSettings(meta)
    }

    private suspend fun persistCustomThemes(themes: Map<String, Theme>) {
        lastLocalSettingsChange = TimeSource.Monotonic.markNow()
        val obj = buildJsonObject {
            put("themeConfigs", JsonObject(themes.mapValues { (_, t) ->
                buildJsonObject {
                    put("mode", JsonPrimitive(t.mode.name))
                    put("colorScheme", JsonPrimitive(t.colorScheme))
                    put("theme.sidebar", JsonPrimitive(t.sidebar))
                    put("theme.terminal", JsonPrimitive(t.terminal))
                    put("theme.diff", JsonPrimitive(t.diff))
                    put("theme.fileBrowser", JsonPrimitive(t.fileBrowser))
                    put("theme.tabs", JsonPrimitive(t.tabs))
                    put("theme.chrome", JsonPrimitive(t.chrome))
                    put("theme.windows", JsonPrimitive(t.windows))
                    put("theme.active", JsonPrimitive(t.active))
                    put("theme.bottomBar", JsonPrimitive(t.bottomBar))
                }
            }))
        }
        settingsPersister?.putJsonSettings(obj)
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
 * @param section one of `"sidebar"`, `"terminal"`, `"diff"`, `"fileBrowser"`, `"tabs"`, `"chrome"`, `"windows"`, `"active"`, `"bottomBar"`
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
        "bottomBar" -> bottomBarTheme
        else -> null
    }
    return (sectionTheme ?: theme).resolve(appearance, systemIsDark)
}
