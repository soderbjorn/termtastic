/**
 * Settings panel UI component for the Termtastic web frontend.
 *
 * Builds and manages a slide-in sidebar panel with controls for:
 * - Text size (font size presets from 10px to 24px)
 * - Font family (monospace preset picker; bundled fonts are always shown,
 *   system-detected fonts are only shown when installed locally)
 * - Desktop notifications toggle
 * - Custom title bar toggle (Electron only)
 * - Hidden developer tools (activated by 5 rapid clicks on "Settings" title)
 * - A link to server-side settings
 *
 * Theme selection lives in the dedicated Theme Manager sidebar (see
 * [ThemeManager.kt]), opened via the toolbar palette button.
 *
 * All settings are persisted to the server via the [AppBackingViewModel] and
 * the [SettingsPersister] REST endpoint.
 *
 * @see openSettingsPanel
 * @see closeSettingsPanel
 */
package se.soderbjorn.termtastic

import se.soderbjorn.darkness.core.*

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
 * @param onClosed optional callback invoked once the slide-out transition has
 *                 finished and the panel's DOM node has been detached. Used by
 *                 [showThemeManager] to sequence a settings→theme-manager
 *                 handoff so the two sidebars don't overlap mid-animation.
 *                 Invoked immediately (synchronously) when the panel was not
 *                 open to begin with.
 * @see openSettingsPanel
 */
fun closeSettingsPanel(onClosed: (() -> Unit)? = null) {
    val panel = settingsPanel ?: run { onClosed?.invoke(); return }
    var done = false
    panel.classList.remove("open")
    panel.addEventListener("transitionend", {
        if (!done && !panel.classList.contains("open")) {
            done = true
            panel.remove()
            fitVisible()
            onClosed?.invoke()
        }
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
    // Mutual exclusion: only one right-side sidebar visible at a time.
    // If the theme manager is open, wait for its close animation to complete
    // before opening settings so the two sidebars don't overlap mid-slide.
    if (themeManagerPanel != null) {
        closeThemeManager(onClosed = { openSettingsPanel() })
        return
    }

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

    // ─── Font ──────────────────────────────────────────────────────
    val fontSection = document.createElement("div") as HTMLElement
    fontSection.className = "settings-section"
    val fontLabel = document.createElement("div") as HTMLElement
    fontLabel.className = "settings-label"
    fontLabel.textContent = "Font"
    fontSection.appendChild(fontLabel)

    val fontRow = document.createElement("div") as HTMLElement
    fontRow.className = "settings-button-row settings-font-row"
    fun renderFontRow() {
        fontRow.innerHTML = ""
        val currentKey = appVm.stateFlow.value.paneFontFamily ?: "system"
        val installed = detectInstalledFonts()
        for (preset in fontPresets) {
            if (preset.key !in installed) continue
            val btn = document.createElement("button") as HTMLElement
            val isSelected = preset.key == currentKey
            btn.className = "settings-choice-btn" + if (isSelected) " selected" else ""
            btn.textContent = preset.displayName
            btn.style.fontFamily = preset.cssStack
            btn.addEventListener("click", {
                if (preset.key != currentKey) {
                    GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        appVm.setPaneFontFamily(preset.key)
                    }
                    applyGlobalFontFamily(preset.key)
                }
                renderFontRow()
            })
            fontRow.appendChild(btn)
        }
    }
    renderFontRow()
    fontSection.appendChild(fontRow)
    body.appendChild(fontSection)

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

    // ─── Custom title bar (Electron only) ──────────────────────────
    if (isElectronClient) {
        val titleBarSection = document.createElement("div") as HTMLElement
        titleBarSection.className = "settings-section"
        val titleBarLabel = document.createElement("div") as HTMLElement
        titleBarLabel.className = "settings-label"
        titleBarLabel.textContent = "Custom title bar"
        titleBarSection.appendChild(titleBarLabel)

        val titleBarRow = document.createElement("div") as HTMLElement
        titleBarRow.className = "settings-button-row"
        fun renderTitleBarRow() {
            titleBarRow.innerHTML = ""
            val enabled = appVm.stateFlow.value.electronCustomTitleBar
            for ((label, value) in listOf("On" to true, "Off" to false)) {
                val btn = document.createElement("button") as HTMLElement
                btn.className = "settings-choice-btn" + if (value == enabled) " selected" else ""
                btn.textContent = label
                btn.addEventListener("click", {
                    GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        appVm.setElectronCustomTitleBar(value)
                    }
                    renderTitleBarRow()
                })
                titleBarRow.appendChild(btn)
            }
        }
        renderTitleBarRow()
        titleBarSection.appendChild(titleBarRow)
        body.appendChild(titleBarSection)
    }

    // ─── Server settings ──────────────────────────────────────────
    val srvBtn = document.createElement("button") as HTMLElement
    srvBtn.className = "settings-server-btn"
    srvBtn.textContent = "Server settings\u2026"
    srvBtn.addEventListener("click", { launchCmd(WindowCommand.OpenSettings) })
    body.appendChild(srvBtn)

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
    panel.addEventListener("transitionend", { fitVisible() }, js("({once:true})"))

    val escHandler: (Event) -> Unit = { ev ->
        val kev = ev as? KeyboardEvent
        if (kev?.key == "Escape") closeSettingsPanel()
    }
    document.addEventListener("keydown", escHandler)
    settingsEscHandler = escHandler
}
