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
import se.soderbjorn.termtastic.SplitDirection
import se.soderbjorn.termtastic.TerminalTheme
import se.soderbjorn.termtastic.WindowCommand
import se.soderbjorn.termtastic.WindowConfig
import se.soderbjorn.termtastic.WindowEnvelope
import se.soderbjorn.termtastic.client.PaneStatusDisplay
import se.soderbjorn.termtastic.client.WindowSocket
import se.soderbjorn.termtastic.client.WindowStateRepository
import se.soderbjorn.termtastic.client.parsePaneStatusDisplay
import se.soderbjorn.termtastic.recommendedThemes

class AppBackingViewModel(
    private val windowSocket: WindowSocket,
    private val windowState: WindowStateRepository,
    private val settingsPersister: SettingsPersister? = null,
) {
    private val _stateFlow = MutableStateFlow(State())
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    data class State(
        val config: WindowConfig? = null,
        val sessionStates: Map<String, String?> = emptyMap(),
        val pendingApproval: Boolean = false,
        val claudeUsage: ClaudeUsageData? = null,
        val theme: TerminalTheme = recommendedThemes.first { it.name == DEFAULT_THEME_NAME },
        val appearance: Appearance = Appearance.Auto,
        val paneStatusDisplay: PaneStatusDisplay = PaneStatusDisplay.Glow,
        val paneFontSize: Int? = null,
        val sidebarWidth: Int? = null,
        val desktopNotifications: Boolean = true,
    )

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
                        is WindowEnvelope.UiSettings ->
                            applyServerUiSettings(envelope.settings)
                        else -> Unit
                    }
                }
            }
        }
    }

    // ── UI settings mutations ───────────────────────────────────────

    suspend fun setTheme(theme: TerminalTheme) {
        emit(_stateFlow.value.copy(theme = theme))
        settingsPersister?.putSetting("theme", theme.name)
    }

    suspend fun setAppearance(appearance: Appearance) {
        emit(_stateFlow.value.copy(appearance = appearance))
        settingsPersister?.putSetting("appearance", appearance.name)
    }

    suspend fun setPaneStatusDisplay(display: PaneStatusDisplay) {
        emit(_stateFlow.value.copy(paneStatusDisplay = display))
        settingsPersister?.putSetting("paneStatusDisplay", display.name)
    }

    suspend fun setPaneFontSize(size: Int) {
        emit(_stateFlow.value.copy(paneFontSize = size))
        settingsPersister?.putSetting("paneFontSize", size.toString())
    }

    suspend fun setSidebarWidth(width: Int) {
        emit(_stateFlow.value.copy(sidebarWidth = width))
        settingsPersister?.putSetting("sidebarWidth", width.toString())
    }

    suspend fun setDesktopNotifications(enabled: Boolean) {
        emit(_stateFlow.value.copy(desktopNotifications = enabled))
        settingsPersister?.putSetting("desktopNotifications", enabled.toString())
    }

    // ── Layout mutations (delegated to server) ──────────────────────

    suspend fun setActiveTab(tabId: String) {
        windowSocket.send(WindowCommand.SetActiveTab(tabId = tabId))
    }

    suspend fun setFocusedPane(tabId: String, paneId: String) {
        windowSocket.send(WindowCommand.SetFocusedPane(tabId = tabId, paneId = paneId))
    }

    suspend fun addTab() {
        windowSocket.send(WindowCommand.AddTab)
    }

    suspend fun closeTab(tabId: String) {
        windowSocket.send(WindowCommand.CloseTab(tabId = tabId))
    }

    suspend fun renameTab(tabId: String, title: String) {
        windowSocket.send(WindowCommand.RenameTab(tabId = tabId, title = title))
    }

    suspend fun moveTab(tabId: String, targetTabId: String, before: Boolean) {
        windowSocket.send(WindowCommand.MoveTab(tabId = tabId, targetTabId = targetTabId, before = before))
    }

    suspend fun closePane(paneId: String) {
        windowSocket.send(WindowCommand.Close(paneId = paneId))
    }

    suspend fun closeSession(sessionId: String) {
        windowSocket.send(WindowCommand.CloseSession(sessionId = sessionId))
    }

    suspend fun renamePane(paneId: String, title: String) {
        windowSocket.send(WindowCommand.Rename(paneId = paneId, title = title))
    }

    suspend fun splitTerminal(paneId: String, direction: SplitDirection) {
        windowSocket.send(WindowCommand.SplitTerminal(paneId = paneId, direction = direction))
    }

    suspend fun splitFileBrowser(paneId: String, direction: SplitDirection) {
        windowSocket.send(WindowCommand.SplitFileBrowser(paneId = paneId, direction = direction))
    }

    suspend fun splitGit(paneId: String, direction: SplitDirection) {
        windowSocket.send(WindowCommand.SplitGit(paneId = paneId, direction = direction))
    }

    suspend fun splitLink(paneId: String, direction: SplitDirection, targetSessionId: String) {
        windowSocket.send(WindowCommand.SplitLink(paneId = paneId, direction = direction, targetSessionId = targetSessionId))
    }

    suspend fun addPaneToTab(tabId: String) {
        windowSocket.send(WindowCommand.AddPaneToTab(tabId = tabId))
    }

    suspend fun addFileBrowserToTab(tabId: String) {
        windowSocket.send(WindowCommand.AddFileBrowserToTab(tabId = tabId))
    }

    suspend fun addGitToTab(tabId: String) {
        windowSocket.send(WindowCommand.AddGitToTab(tabId = tabId))
    }

    suspend fun addLinkToTab(tabId: String, targetSessionId: String) {
        windowSocket.send(WindowCommand.AddLinkToTab(tabId = tabId, targetSessionId = targetSessionId))
    }

    suspend fun toggleFloating(paneId: String) {
        windowSocket.send(WindowCommand.ToggleFloating(paneId = paneId))
    }

    suspend fun setFloatingGeom(paneId: String, x: Double, y: Double, width: Double, height: Double) {
        windowSocket.send(WindowCommand.SetFloatingGeom(paneId = paneId, x = x, y = y, width = width, height = height))
    }

    suspend fun raiseFloating(paneId: String) {
        windowSocket.send(WindowCommand.RaiseFloating(paneId = paneId))
    }

    suspend fun setRatio(splitId: String, ratio: Double) {
        windowSocket.send(WindowCommand.SetRatio(splitId = splitId, ratio = ratio))
    }

    suspend fun movePaneToTab(paneId: String, targetTabId: String) {
        windowSocket.send(WindowCommand.MovePaneToTab(paneId = paneId, targetTabId = targetTabId))
    }

    suspend fun popOut(paneId: String) {
        windowSocket.send(WindowCommand.PopOut(paneId = paneId))
    }

    suspend fun dockPoppedOut(paneId: String) {
        windowSocket.send(WindowCommand.DockPoppedOut(paneId = paneId))
    }

    suspend fun refreshUsage() {
        windowSocket.send(WindowCommand.RefreshUsage)
    }

    suspend fun openSettings() {
        windowSocket.send(WindowCommand.OpenSettings)
    }

    // ── Internal ────────────────────────────────────────────────────

    fun applyServerUiSettings(settings: kotlinx.serialization.json.JsonObject) {
        val cur = _stateFlow.value
        val themeName = settings["theme"]?.jsonPrimitive?.contentOrNull
        val theme = themeName?.let { n -> recommendedThemes.firstOrNull { it.name == n } } ?: cur.theme

        val appearanceName = settings["appearance"]?.jsonPrimitive?.contentOrNull
        val appearance = appearanceName
            ?.let { runCatching { Appearance.valueOf(it) }.getOrNull() }
            ?: cur.appearance

        val psd = parsePaneStatusDisplay(settings["paneStatusDisplay"]?.jsonPrimitive?.contentOrNull)
        val fontSize = settings["paneFontSize"]?.jsonPrimitive?.intOrNull ?: cur.paneFontSize
        val sidebarW = settings["sidebarWidth"]?.jsonPrimitive?.intOrNull ?: cur.sidebarWidth
        val desktopNotif = settings["desktopNotifications"]?.jsonPrimitive?.booleanOrNull ?: cur.desktopNotifications

        emit(cur.copy(
            theme = theme,
            appearance = appearance,
            paneStatusDisplay = psd,
            paneFontSize = fontSize,
            sidebarWidth = sidebarW,
            desktopNotifications = desktopNotif,
        ))
    }

    private fun emit(state: State) {
        _stateFlow.value = state
    }
}
