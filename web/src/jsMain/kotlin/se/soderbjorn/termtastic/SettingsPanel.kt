package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.termtastic.client.PaneStatusDisplay

private val fontSizePresets = listOf(10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24)

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

    val themeGrid = document.createElement("div") as HTMLElement
    themeGrid.className = "settings-theme-grid"

    fun renderThemeGrid() {
        themeGrid.innerHTML = ""
        val currentTheme = appVm.stateFlow.value.theme
        for (t in recommendedThemes) {
            val card = document.createElement("div") as HTMLElement
            card.className = "settings-theme-card" + if (t.name == currentTheme.name) " selected" else ""
            card.innerHTML = """${renderThemeSwatch(t)}<span class="settings-theme-name">${t.name}</span>"""
            card.addEventListener("click", {
                GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) { appVm.setTheme(t) }
                applyAll()
                renderThemeGrid()
            })
            themeGrid.appendChild(card)
        }
    }
    renderThemeGrid()
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

    // ─── Pane status display ────────────────────────────────────────
    val statusSection = document.createElement("div") as HTMLElement
    statusSection.className = "settings-section"
    val statusLabel = document.createElement("div") as HTMLElement
    statusLabel.className = "settings-label"
    statusLabel.textContent = "Pane status indicator"
    statusSection.appendChild(statusLabel)

    val statusRow = document.createElement("div") as HTMLElement
    statusRow.className = "settings-button-row"
    fun renderStatusRow() {
        statusRow.innerHTML = ""
        val currentPsd = appVm.stateFlow.value.paneStatusDisplay
        for (mode in PaneStatusDisplay.values()) {
            val label = when (mode) {
                PaneStatusDisplay.Dots -> "Colored dots"
                PaneStatusDisplay.Glow -> "Colored glow"
                PaneStatusDisplay.Both -> "Both"
                PaneStatusDisplay.None -> "None"
            }
            val btn = document.createElement("button") as HTMLElement
            btn.className = "settings-choice-btn" + if (mode == currentPsd) " selected" else ""
            btn.textContent = label
            btn.addEventListener("click", {
                GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) { appVm.setPaneStatusDisplay(mode) }
                applyPaneStatusClasses()
                updateStateDots(appVm.stateFlow.value.sessionStates)
                renderStatusRow()
            })
            statusRow.appendChild(btn)
        }
    }
    renderStatusRow()
    statusSection.appendChild(statusRow)
    body.appendChild(statusSection)

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
    for ((label, state) in listOf("Working" to "working", "Waiting" to "waiting", "Clear" to null)) {
        val btn = document.createElement("button") as HTMLElement
        btn.className = "settings-choice-btn"
        btn.textContent = label
        btn.addEventListener("click", {
            val focusedCell = document.querySelector(".terminal-cell.focused") as? HTMLElement
            val paneId = focusedCell?.getAttribute("data-pane")
            if (paneId != null) {
                val entry = terminals[paneId]
                if (entry != null) {
                    if (state != null) debugSessionStates[entry.sessionId] = state
                    else debugSessionStates.remove(entry.sessionId)
                    updateStateDots(appVm.stateFlow.value.sessionStates)
                }
            }
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

    window.requestAnimationFrame { panel.classList.add("open") }

    val escHandler: (Event) -> Unit = { ev ->
        val kev = ev as? KeyboardEvent
        if (kev?.key == "Escape") closeSettingsPanel()
    }
    document.addEventListener("keydown", escHandler)
    settingsEscHandler = escHandler
}
