/**
 * Settings panel UI component for the Termtastic web frontend.
 *
 * Builds and manages a slide-in sidebar panel with controls for:
 * - Theme selection (grid of theme swatches)
 * - Appearance mode (Light / Dark / Auto)
 * - Text size (font size presets from 10px to 24px)
 * - Pane status indicator display mode (dots, glow, both, none)
 * - Desktop notifications toggle
 * - Hidden developer tools (activated by 5 rapid clicks on "Settings" title)
 * - A link to server-side settings
 *
 * All settings are persisted to the server via the [AppBackingViewModel] and
 * the [SettingsPersister] REST endpoint.
 *
 * @see openSettingsPanel
 * @see closeSettingsPanel
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.termtastic.client.viewmodel.findLeafById

/** Available font size presets for the text size setting. */
private val fontSizePresets = listOf(10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24)

/**
 * Closes the settings sidebar panel with a slide-out transition.
 *
 * Removes the Escape key handler and cleans up the [settingsPanel] reference.
 *
 * @see openSettingsPanel
 */
fun closeSettingsPanel() {
    val panel = settingsPanel ?: return
    panel.classList.remove("open")
    panel.addEventListener("transitionend", {
        if (!panel.classList.contains("open")) panel.remove()
    })
    settingsEscHandler?.let { document.removeEventListener("keydown", it) }
    settingsEscHandler = null
    settingsPanel = null
}

/**
 * Opens the settings sidebar panel, building all control sections and appending
 * it to the app body. If the panel is already open, closes it instead (toggle behavior).
 *
 * Called when the settings button in the app header is clicked.
 *
 * @see closeSettingsPanel
 */
fun openSettingsPanel() {
    if (settingsPanel != null) { closeSettingsPanel(); return }

    val appBody = document.querySelector(".app-body") as? HTMLElement ?: return
    val panel = document.createElement("aside") as HTMLElement
    panel.className = "settings-sidebar"

    val closeBtn = document.createElement("button") as HTMLElement
    closeBtn.className = "pane-modal-close"
    closeBtn.innerHTML = "&times;"
    closeBtn.addEventListener("click", { closeSettingsPanel() })
    panel.appendChild(closeBtn)

    var devToolsSection: HTMLElement? = null

    val title = document.createElement("h2") as HTMLElement
    title.className = "pane-modal-title"
    title.textContent = "Settings"

    var devClickTimes = mutableListOf<Double>()
    title.style.cursor = "default"
    title.addEventListener("click", {
        val now = kotlin.js.Date.now()
        devClickTimes.add(now)
        devClickTimes = devClickTimes.filter { now - it < 2000 }.toMutableList()
        if (devClickTimes.size >= 5 && !devToolsEnabled) {
            devToolsEnabled = true
            devToolsSection?.style?.display = ""
        }
    })
    panel.appendChild(title)

    val body = document.createElement("div") as HTMLElement
    body.className = "settings-sidebar-body"

    // ─── Theme ──────────────────────────────────────────────────────
    val themeSection = document.createElement("div") as HTMLElement
    themeSection.className = "settings-section"
    val themeTitle = document.createElement("div") as HTMLElement
    themeTitle.className = "settings-label"
    themeTitle.textContent = "Theme"
    themeSection.appendChild(themeTitle)

    val slotEntries = listOf(
        "" to "Main theme",
        "tabs" to "Tab strip",
        "windows" to "Windows",
        "active" to "Active indicators",
        "sidebar" to "Sidebar",
        "terminal" to "Terminal",
        "diff" to "Diff",
        "fileBrowser" to "File browser",
    )

    var activeSlot = ""

    fun currentSectionTheme(section: String): TerminalTheme? {
        val state = appVm.stateFlow.value
        return when (section) {
            "sidebar" -> state.sidebarTheme
            "terminal" -> state.terminalTheme
            "diff" -> state.diffTheme
            "fileBrowser" -> state.fileBrowserTheme
            "tabs" -> state.tabsTheme
            "chrome" -> state.chromeTheme
            "windows" -> state.windowsTheme
            "active" -> state.activeTheme
            else -> null
        }
    }

    val slotSelect = document.createElement("select") as org.w3c.dom.HTMLSelectElement
    slotSelect.className = "settings-section-select settings-slot-select"
    for ((key, label) in slotEntries) {
        val opt = document.createElement("option") as org.w3c.dom.HTMLOptionElement
        opt.value = key
        opt.textContent = label
        slotSelect.appendChild(opt)
    }
    themeSection.appendChild(slotSelect)

    val themeGrid = document.createElement("div") as HTMLElement
    themeGrid.className = "settings-theme-grid"

    fun renderThemeGrid() {
        themeGrid.innerHTML = ""
        val isMainSlot = activeSlot.isEmpty()
        val currentTheme = if (isMainSlot) appVm.stateFlow.value.theme
            else currentSectionTheme(activeSlot)

        if (!isMainSlot) {
            val defaultCard = document.createElement("div") as HTMLElement
            defaultCard.className = "settings-theme-card settings-theme-default" +
                if (currentTheme == null) " selected" else ""
            defaultCard.innerHTML = """<div class="theme-swatch theme-swatch-default"></div><span class="settings-theme-name">Default</span>"""
            defaultCard.addEventListener("click", {
                GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    appVm.setSectionTheme(activeSlot, null)
                }
                applyAll()
                renderThemeGrid()
            })
            themeGrid.appendChild(defaultCard)
        }

        for (t in recommendedThemes) {
            val card = document.createElement("div") as HTMLElement
            card.className = "settings-theme-card" +
                if (currentTheme != null && t.name == currentTheme.name) " selected" else ""
            card.innerHTML = """${renderThemeSwatch(t)}<span class="settings-theme-name">${t.name}</span>"""
            card.addEventListener("click", {
                if (isMainSlot) {
                    GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) { appVm.setTheme(t) }
                } else {
                    GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        appVm.setSectionTheme(activeSlot, t)
                    }
                }
                applyAll()
                renderThemeGrid()
            })
            themeGrid.appendChild(card)
        }
    }
    renderThemeGrid()

    slotSelect.addEventListener("change", {
        activeSlot = slotSelect.value
        renderThemeGrid()
    })

    themeSection.appendChild(themeGrid)
    body.appendChild(themeSection)

    // ─── Appearance ─────────────────────────────────────────────────
    val appSection = document.createElement("div") as HTMLElement
    appSection.className = "settings-section"
    val appLabel = document.createElement("div") as HTMLElement
    appLabel.className = "settings-label"
    appLabel.textContent = "Appearance"
    appSection.appendChild(appLabel)

    val appRow = document.createElement("div") as HTMLElement
    appRow.className = "settings-button-row"
    fun renderAppearanceRow() {
        appRow.innerHTML = ""
        val currentAppearance = appVm.stateFlow.value.appearance
        for (a in Appearance.values()) {
            val btn = document.createElement("button") as HTMLElement
            btn.className = "settings-choice-btn" + if (a == currentAppearance) " selected" else ""
            btn.textContent = a.name
            btn.addEventListener("click", {
                GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) { appVm.setAppearance(a) }
                applyAll()
                renderAppearanceRow()
            })
            appRow.appendChild(btn)
        }
    }
    renderAppearanceRow()
    appSection.appendChild(appRow)
    body.appendChild(appSection)

    // ─── Text size ──────────────────────────────────────────────────
    val sizeSection = document.createElement("div") as HTMLElement
    sizeSection.className = "settings-section"
    val sizeLabel = document.createElement("div") as HTMLElement
    sizeLabel.className = "settings-label"
    sizeLabel.textContent = "Text size"
    sizeSection.appendChild(sizeLabel)

    val sizeRow = document.createElement("div") as HTMLElement
    sizeRow.className = "settings-button-row settings-size-row"
    fun renderSizeRow() {
        sizeRow.innerHTML = ""
        val currentFontSize = appVm.stateFlow.value.paneFontSize ?: 14
        for (s in fontSizePresets) {
            val btn = document.createElement("button") as HTMLElement
            btn.className = "settings-choice-btn" + if (s == currentFontSize) " selected" else ""
            btn.textContent = "${s}px"
            btn.addEventListener("click", {
                if (s != currentFontSize) {
                    GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) { appVm.setPaneFontSize(s) }
                    applyGlobalFontSize(s)
                }
                renderSizeRow()
            })
            sizeRow.appendChild(btn)
        }
    }
    renderSizeRow()
    sizeSection.appendChild(sizeRow)
    body.appendChild(sizeSection)

    // ─── Waiting pulse ──────────────────────────────────────────────
    val pulseSection = document.createElement("div") as HTMLElement
    pulseSection.className = "settings-section"
    val pulseLabel = document.createElement("div") as HTMLElement
    pulseLabel.className = "settings-label"
    pulseLabel.textContent = "Waiting-for-input pulse"
    pulseSection.appendChild(pulseLabel)

    val pulseRow = document.createElement("div") as HTMLElement
    pulseRow.className = "settings-button-row"
    fun renderPulseRow() {
        pulseRow.innerHTML = ""
        val enabled = appVm.stateFlow.value.showWaitingPulse
        for ((label, value) in listOf("On" to true, "Off" to false)) {
            val btn = document.createElement("button") as HTMLElement
            btn.className = "settings-choice-btn" + if (value == enabled) " selected" else ""
            btn.textContent = label
            btn.addEventListener("click", {
                GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) { appVm.setShowWaitingPulse(value) }
                applyPaneStatusClasses()
                updateStateIndicators(appVm.stateFlow.value.sessionStates)
                renderPulseRow()
            })
            pulseRow.appendChild(btn)
        }
    }
    renderPulseRow()
    pulseSection.appendChild(pulseRow)
    body.appendChild(pulseSection)

    // ─── Desktop notifications ─────────────────────────────────────
    val notifSection = document.createElement("div") as HTMLElement
    notifSection.className = "settings-section"
    val notifLabel = document.createElement("div") as HTMLElement
    notifLabel.className = "settings-label"
    notifLabel.textContent = "Desktop notifications"
    notifSection.appendChild(notifLabel)

    val notifRow = document.createElement("div") as HTMLElement
    notifRow.className = "settings-button-row"
    fun renderNotifRow() {
        notifRow.innerHTML = ""
        val enabled = appVm.stateFlow.value.desktopNotifications
        for ((label, value) in listOf("On" to true, "Off" to false)) {
            val btn = document.createElement("button") as HTMLElement
            btn.className = "settings-choice-btn" + if (value == enabled) " selected" else ""
            btn.textContent = label
            btn.addEventListener("click", {
                GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) { appVm.setDesktopNotifications(value) }
                renderNotifRow()
            })
            notifRow.appendChild(btn)
        }
    }
    renderNotifRow()
    notifSection.appendChild(notifRow)
    body.appendChild(notifSection)

    // ─── Developer Tools (hidden until activated) ───────────────────
    val devSection = document.createElement("div") as HTMLElement
    devSection.className = "settings-section"
    if (!devToolsEnabled) devSection.style.display = "none"
    devToolsSection = devSection

    val devLabel = document.createElement("div") as HTMLElement
    devLabel.className = "settings-label"
    devLabel.textContent = "Developer Tools"
    devSection.appendChild(devLabel)

    val devRow = document.createElement("div") as HTMLElement
    devRow.className = "settings-button-row"
    for ((label, mode) in listOf("Working" to "working", "Waiting" to "waiting", "Clear" to "auto")) {
        val btn = document.createElement("button") as HTMLElement
        btn.className = "settings-choice-btn"
        btn.textContent = label
        btn.addEventListener("click", {
            val focusedCell = document.querySelector(".terminal-cell.focused") as? HTMLElement
            val paneId = focusedCell?.getAttribute("data-pane")
            if (paneId == null) {
                return@addEventListener
            }
            val sessionId = terminals[paneId]?.sessionId
                ?: appVm.stateFlow.value.config?.let { findLeafById(it, paneId) }?.sessionId
            if (sessionId.isNullOrEmpty()) {
                return@addEventListener
            }
            launchCmd(WindowCommand.SetStateOverride(sessionId, mode))
        })
        devRow.appendChild(btn)
    }
    devSection.appendChild(devRow)
    body.appendChild(devSection)

    panel.appendChild(body)

    val srvBtn = document.createElement("button") as HTMLElement
    srvBtn.className = "settings-server-btn"
    srvBtn.textContent = "Server settings\u2026"
    srvBtn.addEventListener("click", { launchCmd(WindowCommand.OpenSettings) })
    panel.appendChild(srvBtn)

    appBody.appendChild(panel)
    settingsPanel = panel

    // Apply the sidebar section theme so the settings panel matches the sidebar.
    val sidebarPalette = sectionPalette("sidebar")
    val rootPalette = currentResolvedPalette()
    if (sidebarPalette != rootPalette) {
        val cssVars = sidebarPalette.toCssVarMap() + sidebarPalette.toCssAliasMap()
        for ((prop, value) in cssVars) {
            panel.style.setProperty(prop, value)
        }
    }

    window.requestAnimationFrame { panel.classList.add("open") }

    val escHandler: (Event) -> Unit = { ev ->
        val kev = ev as? KeyboardEvent
        if (kev?.key == "Escape") closeSettingsPanel()
    }
    document.addEventListener("keydown", escHandler)
    settingsEscHandler = escHandler
}
