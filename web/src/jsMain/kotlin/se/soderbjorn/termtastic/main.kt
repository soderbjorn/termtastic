/**
 * Entry point for the Termtastic Kotlin/JS web frontend.
 *
 * Bootstraps the application by initializing authentication, creating the
 * [TermtasticClient] and [WindowSocket], setting up the [AppBackingViewModel],
 * hydrating UI settings from the server, and connecting the WebSocket-driven
 * rendering pipeline.
 *
 * @see start
 * @see connectWindow
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import se.soderbjorn.termtastic.client.ClientIdentity
import se.soderbjorn.termtastic.client.ServerUrl
import se.soderbjorn.termtastic.client.TermtasticClient
import se.soderbjorn.termtastic.client.viewmodel.AppBackingViewModel
import se.soderbjorn.termtastic.client.viewmodel.SettingsPersister
import se.soderbjorn.termtastic.client.viewmodel.findLeafById
import kotlin.js.json

/**
 * Kotlin/JS main entry point. Imports the xterm.js CSS and schedules [start]
 * to run on `window.onload`.
 */
fun main() {
    @Suppress("UNUSED_VARIABLE")
    val css = xtermCss
    window.onload = { start() }
}

/** SVG icon for the sun (Light mode appearance toggle). */
private const val SUN_SVG = """<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/></svg>"""

/** SVG icon for the moon (Dark mode appearance toggle). */
private const val MOON_SVG = """<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>"""

/** SVG icon for Auto mode appearance toggle (half-filled circle). */
private const val AUTO_SVG = """<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 3a9 9 0 0 1 0 18" fill="currentColor"/></svg>"""

/**
 * Dwell (in milliseconds) before a split-bar reveals its resize affordance on
 * hover. Picked to match the existing rename-arm timer in [PaneHeader], so the
 * two "settle before you act" interactions feel consistent.
 *
 * @see attachDelayedHoverArm
 */
private const val SPLIT_BAR_HOVER_ARM_MS = 1000

/**
 * Wire a split-bar divider so its hover-state styling (colored highlight and
 * resize cursor) is applied only after the pointer has dwelled on it for
 * [SPLIT_BAR_HOVER_ARM_MS]. Incidental mouse movement across the divider no
 * longer causes an immediate color/cursor flicker — the affordance fades in
 * only when the user is clearly lingering on the bar with intent to resize.
 *
 * Adds `.hover-armed` to [element] after the dwell elapses, and removes it on
 * `mouseleave` or when a drag begins (`mousedown`) — while dragging, the
 * existing `.dragging` class takes over as the source of the highlight.
 *
 * Called from [start] for the sidebar / header / usage-bar dividers, and from
 * [mountFileBrowserPane] and [mountGitPane] for the Markdown and Git list
 * dividers. Mirrors the `armed` timer pattern used in `PaneHeader.createHeader`
 * for the rename-on-hover interaction.
 *
 * @param element The divider element to attach listeners to. Must have its
 *   hover styling in CSS gated on `.hover-armed` (and/or `.dragging`) rather
 *   than the `:hover` pseudo-class.
 * @see PaneHeader
 */
internal fun attachDelayedHoverArm(element: HTMLElement) {
    var armTimer: Int = -1
    fun disarm() {
        if (armTimer != -1) { window.clearTimeout(armTimer); armTimer = -1 }
        element.classList.remove("hover-armed")
    }
    element.addEventListener("mouseenter", { _ ->
        disarm()
        armTimer = window.setTimeout(
            { armTimer = -1; element.classList.add("hover-armed") },
            SPLIT_BAR_HOVER_ARM_MS,
        )
    })
    element.addEventListener("mouseleave", { _ -> disarm() })
    // Once a drag starts, the .dragging class owns the highlight — cancel
    // any pending arm so we don't layer both classes or leave a stale
    // hover-armed behind when the mouse leaves mid-drag.
    element.addEventListener("mousedown", { _ -> disarm() })
}

/**
 * Returns whether the host reports a dark system colour-scheme preference.
 * Consulted only when the user's [Appearance] is [Appearance.Auto] — in
 * [Appearance.Dark] / [Appearance.Light] the explicit choice always wins.
 *
 * @return `true` when `prefers-color-scheme: dark` matches.
 */
internal fun isSystemDark(): Boolean {
    val mql = window.matchMedia("(prefers-color-scheme: dark)")
    return mql.matches
}

/**
 * Recompute the active theme (main + per-section overrides) from the
 * ViewModel's current light/dark slot selection and appearance, then
 * apply the derived palette to the DOM and xterm.js instances.
 *
 * Called from every code path that changes which slot is live or what
 * that slot points at: appearance toggle, media-query change, theme
 * selection in the Theme Manager, scheme edits, etc.
 *
 * @see AppBackingViewModel.refreshActiveTheme
 */
internal fun refreshAndApplyActiveTheme() {
    appVm.refreshActiveTheme(isSystemDark())
    val s = appVm.stateFlow.value
    console.log(
        "[theme] refresh done — main=${s.theme.name}" +
            " light=${s.lightThemeName} dark=${s.darkThemeName}" +
            " appearance=${s.appearance}" +
            " sections: sidebar=${s.sidebarTheme?.name} terminal=${s.terminalTheme?.name}" +
            " diff=${s.diffTheme?.name} fileBrowser=${s.fileBrowserTheme?.name}" +
            " tabs=${s.tabsTheme?.name} chrome=${s.chromeTheme?.name}" +
            " windows=${s.windowsTheme?.name} active=${s.activeTheme?.name}"
    )
    applyAll()
    refreshThemeManager()
}

/**
 * Updates the appearance toggle button in the header to reflect the current
 * [Appearance] mode. Sets the button's SVG icon and tooltip.
 *
 * Called on init and reactively whenever the appearance state changes, so the
 * toggle stays in sync even when changed from the settings panel.
 *
 * @see start
 */
internal fun updateAppearanceToggle() {
    val btn = document.getElementById("appearance-toggle") as? HTMLElement ?: return
    val appearance = appVm.stateFlow.value.appearance
    btn.innerHTML = when (appearance) {
        Appearance.Light -> SUN_SVG
        Appearance.Dark -> MOON_SVG
        Appearance.Auto -> AUTO_SVG
    }
    btn.title = "Appearance: ${appearance.name}"
}

/**
 * Initializes the Termtastic web application.
 *
 * Performs the following steps:
 * 1. Ensures an auth token exists and is stored in a cookie
 * 2. Detects client type (Electron vs. Web)
 * 3. Creates the [TermtasticClient], [WindowSocket], and [AppBackingViewModel]
 * 4. Hydrates UI settings from the server via REST
 * 5. Sets up reactive state observers for theme/appearance/pane-status changes
 * 6. Registers global event listeners (click, drag, resize)
 * 7. Sets up DOM references, sidebar resize, tab bar scrolling, and calls
 *    [connectWindow] to start the rendering pipeline
 */
private fun start() {
    ensureAuthToken()

    clientTypeAtStart =
        if (window.navigator.userAgent.contains("Electron", ignoreCase = true)) "Computer" else "Web"
    authQueryParam = "auth=" + encodeUriComponent(authTokenForSending()) +
        "&clientType=" + encodeUriComponent(clientTypeAtStart)
    proto = if (window.location.protocol == "https:") "wss" else "ws"
    isElectronClient = window.navigator.userAgent.contains("Electron", ignoreCase = true)
    // Tag the body when we're in an Electron renderer on macOS. The main
    // BrowserWindow uses titleBarStyle: "hiddenInset" so the theme tint can
    // bleed through the titlebar; that leaves the native traffic lights
    // floating over the upper-left of our content. A body class lets CSS
    // reserve horizontal room for them (see styles.css — look for
    // `is-electron-mac`). Non-mac Electron puts window controls on the
    // right and needs no such padding, hence the platform gate.
    if (isElectronClient && window.navigator.userAgent.contains("Mac OS X", ignoreCase = true)) {
        document.body?.classList?.add("is-electron-mac")
    }

    // Create the TermtasticClient + WindowSocket + AppBackingViewModel
    val loc = window.location
    val port = loc.port.toIntOrNull() ?: if (loc.protocol == "https:") 443 else 80
    val serverUrl = ServerUrl(host = loc.hostname, port = port, useTls = loc.protocol == "https:")
    termtasticClient = TermtasticClient(
        serverUrl = serverUrl,
        authToken = authTokenForSending(),
        identity = ClientIdentity(type = clientTypeAtStart),
    )
    windowSocket = termtasticClient.openWindowSocket()

    val webSettingsPersister = object : SettingsPersister {
        private fun postSettings(body: dynamic) {
            val init: dynamic = js("({})")
            init.method = "POST"
            init.headers = json(
                "Content-Type" to "application/json",
                "X-Termtastic-Auth" to authTokenForSending(),
                "X-Termtastic-Client-Type" to clientTypeAtStart,
            )
            init.body = JSON.stringify(body)
            // keepalive lets the POST outlive the renderer. Required for
            // settings whose side effect tears the renderer down before the
            // request can complete — notably electronCustomTitleBar, where
            // the state change triggers a BrowserWindow rebuild in the main
            // process. Without this, the cancelled POST never reaches the
            // server and the new renderer re-hydrates from stale DB state.
            init.keepalive = true
            window.fetch("/api/ui-settings", init)
        }

        override suspend fun putSetting(key: String, value: String) {
            postSettings(json(key to value))
        }

        override suspend fun putSettings(settings: Map<String, String>) {
            val obj: dynamic = js("({})")
            for ((k, v) in settings) { obj[k] = v }
            postSettings(obj)
        }

        override fun fireAndForgetPutSettings(settings: Map<String, String>) {
            val obj: dynamic = js("({})")
            for ((k, v) in settings) { obj[k] = v }
            postSettings(obj)
        }

        override suspend fun putJsonSettings(settings: kotlinx.serialization.json.JsonObject) {
            // Round-trip through the runtime JSON parser so nested
            // objects/arrays reach the server as real JSON — not the
            // stringified-blob fallback from the default implementation.
            val body = js("JSON.parse")(settings.toString())
            postSettings(body)
        }
    }
    appVm = AppBackingViewModel(windowSocket, termtasticClient.windowState, webSettingsPersister)

    // Launch the backing VM's run loop
    GlobalScope.launch { appVm.run() }

    // Hydrate UI settings from server via REST (fast initial load — the
    // WebSocket UiSettings envelope is a backup for cross-client pushes).
    val uiSettingsInit: dynamic = js("({})")
    uiSettingsInit.headers = json(
        "X-Termtastic-Auth" to authTokenForSending(),
        "X-Termtastic-Client-Type" to clientTypeAtStart,
    )
    window.fetch("/api/ui-settings", uiSettingsInit).then { resp ->
        val ok = resp.asDynamic().ok as Boolean
        if (!ok) return@then null
        resp.json()
    }.then { data ->
        if (data != null) {
            val jsonStr = JSON.stringify(data)
            val jsonObj = kotlinx.serialization.json.Json.parseToJsonElement(jsonStr)
                as kotlinx.serialization.json.JsonObject
            appVm.applyServerUiSettings(jsonObj)
            refreshAndApplyActiveTheme()
            applySidebarState()
            applyHeaderCollapsedState()
            applyUsageBarCollapsedState()
        }
    }

    applyAll()

    // Request notification permission (Electron grants via setPermissionRequestHandler in main.js).
    if (js("typeof Notification !== 'undefined'") as Boolean) {
        if ((js("Notification.permission") as String) != "granted") {
            js("Notification.requestPermission()")
        }
    }

    // Show/hide the disconnected modal based on the main server WebSocket state.
    // Skip the initial `false` emission — we only show the modal after the
    // connection has been established at least once and then drops.
    GlobalScope.launch {
        var wasConnected = false
        windowSocket.connected.collect { connected ->
            if (connected) {
                wasConnected = true
                windowSocketConnected = true
                updateAggregateStatus()
            } else if (wasConnected) {
                windowSocketConnected = false
                updateAggregateStatus()
            }
        }
    }

    // Reactively apply visual changes when the VM's appearance/theme state changes.
    // We track the derived `theme` plus the light/dark slot names and the
    // custom maps so any upstream change that could affect which palette is
    // live triggers a refresh. `refreshActiveTheme` is cheap (just recomputes
    // theme + section fields from the current state + systemIsDark), and
    // applyAll() downstream repaints the DOM.
    GlobalScope.launch {
        var prevTheme = appVm.stateFlow.value.theme.name
        var prevAppearance = appVm.stateFlow.value.appearance
        var prevLight = appVm.stateFlow.value.lightThemeName
        var prevDark = appVm.stateFlow.value.darkThemeName
        var prevCustomSchemes = appVm.stateFlow.value.customSchemes
        var prevCustomThemes = appVm.stateFlow.value.customThemes
        appVm.stateFlow.collect { state ->
            val slotChanged = state.lightThemeName != prevLight || state.darkThemeName != prevDark
            val customChanged = state.customSchemes !== prevCustomSchemes || state.customThemes !== prevCustomThemes
            val appearanceChanged = state.appearance != prevAppearance
            val themeChanged = state.theme.name != prevTheme
            if (slotChanged || appearanceChanged || customChanged) {
                prevLight = state.lightThemeName
                prevDark = state.darkThemeName
                prevCustomSchemes = state.customSchemes
                prevCustomThemes = state.customThemes
                prevAppearance = state.appearance
                refreshAndApplyActiveTheme()
                prevTheme = appVm.stateFlow.value.theme.name
                updateAppearanceToggle()
            } else if (themeChanged) {
                prevTheme = state.theme.name
                applyAll()
                updateAppearanceToggle()
            }
        }
    }

    // Reactively push the persisted font family into xterm.js instances and
    // the `--t-font-mono` CSS var whenever it changes — covers initial
    // hydration (null → server value) and cross-tab sync through the
    // UiSettings envelope. Per-pane terminals also pick up the setting at
    // creation time via LayoutBuilder, but that doesn't set the CSS var that
    // drives git diff panes and markdown code blocks.
    GlobalScope.launch {
        var prevFontFamily: String? = appVm.stateFlow.value.paneFontFamily
        applyGlobalFontFamily(prevFontFamily)
        appVm.stateFlow.collect { state ->
            if (state.paneFontFamily != prevFontFamily) {
                prevFontFamily = state.paneFontFamily
                applyGlobalFontFamily(prevFontFamily)
            }
        }
    }

    // Reconcile Electron's cached `titleBarStyle` with the server value. The
    // setting is the only one whose effect happens in the Electron main
    // process (outside the renderer), so any change — whether from this
    // device or another client — needs to be forwarded via IPC. The main
    // process diffs against its own cache and only rebuilds the window when
    // the value actually flipped.
    //
    // We MUST wait for `uiSettingsHydrated` before firing any IPC. The state
    // flow's initial value is the default `false`, which is emitted before
    // the server pushes the authoritative value. If the disk cache on the
    // main process disagrees with that default, forwarding the default would
    // trigger a window rebuild, the fresh renderer would again emit the
    // default, the next server push would flip it back, and the app would
    // be stuck in an infinite rebuild loop.
    if (isElectronClient) {
        GlobalScope.launch {
            var prev: Boolean? = null
            appVm.stateFlow
                .filter { it.uiSettingsHydrated }
                .collect { state ->
                    val value = state.electronCustomTitleBar
                    if (value != prev) {
                        prev = value
                        val electronApi = window.asDynamic().electronApi
                        if (electronApi?.setElectronCustomTitleBar != null) {
                            electronApi.setElectronCustomTitleBar(value)
                        }
                    }
                }
        }
    }

    // React to system color scheme changes when in Auto.
    val mql = window.matchMedia("(prefers-color-scheme: dark)")
    mql.asDynamic().addEventListener("change", {
        if (appVm.stateFlow.value.appearance == Appearance.Auto) refreshAndApplyActiveTheme()
    })

    // Close dropdowns on outside click.
    document.addEventListener("click", {
        (document.getElementById("debug-dropdown") as? HTMLElement)?.classList?.remove("open")
        val openMenus = document.querySelectorAll(".pane-menu.open, .tab-bar-menu.open, .tab-bar-menu-list.open, .pane-split-flyout.open, .pane-flyout-wrap.open")
        for (i in 0 until openMenus.length) {
            (openMenus.item(i) as HTMLElement).classList.remove("open")
        }
    })

    // Swallow stray drops.
    document.addEventListener("dragover", { event -> event.preventDefault() })
    document.addEventListener("drop", { event -> event.preventDefault() })
    updateAggregateStatus()

    // Appearance toggle button.
    val appearanceToggle = document.getElementById("appearance-toggle") as? HTMLElement
    appearanceToggle?.addEventListener("click", { event ->
        event.stopPropagation()
        val next = when (appVm.stateFlow.value.appearance) {
            Appearance.Auto -> Appearance.Dark
            Appearance.Dark -> Appearance.Light
            Appearance.Light -> Appearance.Auto
        }
        GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) { appVm.setAppearance(next) }
        refreshAndApplyActiveTheme()
        updateAppearanceToggle()
    })
    updateAppearanceToggle()

    // Toolbar theme button. Tooltip reflects the active theme; click
    // opens the Theme Manager sidebar.
    initToolbarThemeButton()

    // New window button. Opens the pane-type modal scoped to the active
    // tab. When that tab has a persisted focused pane, it is passed as the
    // anchor so the new pane inherits its cwd — matching the behaviour of
    // the former per-pane "New window" header button.
    val newWindowButton = document.getElementById("new-window-button") as? HTMLElement
    newWindowButton?.addEventListener("click", { event ->
        event.stopPropagation()
        val tabId = activeTabId ?: return@addEventListener
        val anchor = savedFocusedPaneId(tabId)
        showPaneTypeModal(emptyTabId = tabId, anchorPaneId = anchor)
    })

    // About button.
    val aboutButton = document.getElementById("about-button") as? HTMLElement
    aboutButton?.addEventListener("click", { event ->
        event.stopPropagation()
        showAboutDialog()
    })

    // Settings button.
    val settingsButton = document.getElementById("settings-button") as? HTMLElement
    settingsButton?.addEventListener("click", { event ->
        event.stopPropagation()
        openSettingsPanel()
    })

    // Debug menu.
    val isDev = SHOW_DEBUG_MENU && window.location.hostname.let { it == "localhost" || it == "127.0.0.1" }
    val debugDropdown = document.getElementById("debug-dropdown") as? HTMLElement
    val debugButton = document.getElementById("debug-button") as? HTMLElement
    val debugMenu = document.getElementById("debug-menu") as? HTMLElement
    if (isDev && debugDropdown != null && debugButton != null && debugMenu != null) {
        debugDropdown.style.display = ""
        fun buildDebugMenu() {
            debugMenu.innerHTML = ""
            val submenu = document.createElement("div") as HTMLElement
            submenu.className = "pane-submenu pane-menu-item open-left"
            submenu.textContent = "Set pane state"
            val submenuList = document.createElement("div") as HTMLElement
            submenuList.className = "pane-submenu-list"
            for ((label, mode) in listOf("Working" to "working", "Waiting" to "waiting", "Clear" to "auto")) {
                val item = document.createElement("div") as HTMLElement
                item.className = "pane-menu-item"; item.textContent = label
                item.addEventListener("click", { ev ->
                    ev.stopPropagation(); debugDropdown.classList.remove("open")
                    val focusedCell = document.querySelector(".terminal-cell.focused") as? HTMLElement
                    val paneId = focusedCell?.getAttribute("data-pane")
                    if (paneId != null) {
                        val sessionId = terminals[paneId]?.sessionId
                            ?: appVm.stateFlow.value.config?.let { findLeafById(it, paneId) }?.sessionId
                        if (!sessionId.isNullOrEmpty()) {
                            launchCmd(WindowCommand.SetStateOverride(sessionId, mode))
                        }
                    }
                })
                submenuList.appendChild(item)
            }
            submenu.appendChild(submenuList); debugMenu.appendChild(submenu)
        }
        buildDebugMenu()
        debugButton.addEventListener("click", { ev -> ev.stopPropagation(); debugDropdown.classList.toggle("open") })
    }

    usageBar = document.getElementById("claude-usage-bar") as? HTMLElement
    usageBarDividerEl = document.getElementById("usage-bar-divider") as? HTMLElement
    appHeaderEl = document.querySelector(".app-header") as? HTMLElement
    headerDividerEl = document.getElementById("header-divider") as? HTMLElement

    // ── DOM setup ───────────────────────────────────────────────────
    tabBarEl = document.getElementById("tab-bar") as HTMLElement
    terminalWrapEl = document.getElementById("terminal-wrap") as HTMLElement
    sidebarEl = document.getElementById("sidebar") as HTMLElement
    sidebarDividerEl = document.getElementById("sidebar-divider") as HTMLElement

    val sidebarElLocal = sidebarEl!!
    val sidebarDividerLocal = sidebarDividerEl!!
    val terminalWrapLocal = terminalWrapEl!!
    val tabBarLocal = tabBarEl!!

    // Sidebar resize via divider drag.
    run {
        attachDelayedHoverArm(sidebarDividerLocal)
        var dragging = false
        var startX = 0.0
        var startWidth = 0.0
        sidebarDividerLocal.addEventListener("mousedown", { ev ->
            ev.preventDefault(); dragging = true
            startX = (ev as MouseEvent).clientX.toDouble()
            startWidth = sidebarElLocal.getBoundingClientRect().width
            sidebarDividerLocal.classList.add("dragging")
            sidebarElLocal.style.transition = "none"
            document.body?.style?.cursor = "col-resize"
            document.body?.style?.setProperty("user-select", "none")
        })
        document.addEventListener("mousemove", { ev ->
            if (!dragging) return@addEventListener
            val dx = (ev as MouseEvent).clientX.toDouble() - startX
            sidebarElLocal.style.width = "${(startWidth + dx).coerceIn(0.0, 400.0)}px"
        })
        document.addEventListener("mouseup", { _ ->
            if (!dragging) return@addEventListener
            dragging = false
            sidebarDividerLocal.classList.remove("dragging")
            sidebarElLocal.style.transition = ""; document.body?.style?.cursor = ""
            document.body?.style?.removeProperty("user-select")
            val w = sidebarElLocal.getBoundingClientRect().width.toInt()
            if (w <= 10) {
                GlobalScope.launch { appVm.setSidebarCollapsed(true) }
            } else {
                GlobalScope.launch {
                    appVm.setSidebarWidth(w)
                    appVm.setSidebarCollapsed(false)
                }
            }
            fitVisible()
        })
    }

    // Sidebar toggle button.
    run {
        val toggleBtn = document.getElementById("sidebar-toggle") as? HTMLElement
        toggleBtn?.addEventListener("click", { ev ->
            ev.stopPropagation()
            val currentWidth = sidebarElLocal.getBoundingClientRect().width.toInt()
            if (currentWidth > 10) {
                // Collapse — remember current width, then hide.
                GlobalScope.launch { appVm.setSidebarWidth(currentWidth) }
                sidebarElLocal.style.width = "0px"
                GlobalScope.launch { appVm.setSidebarCollapsed(true) }
            } else {
                // Expand — restore the last open width.
                val openWidth = (appVm.stateFlow.value.sidebarWidth ?: 0).takeIf { it > 10 } ?: 260
                sidebarElLocal.style.width = "${openWidth}px"
                GlobalScope.launch { appVm.setSidebarCollapsed(false) }
            }
            sidebarElLocal.addEventListener("transitionend", { fitVisible() }, js("({once:true})"))
        })
    }

    // Header resize via horizontal divider drag — drag up to collapse the
    // header (tab bar + toolbar) to 0 px, drag down to reveal it again.
    // Mirrors the sidebar-divider drag but vertical. The divider element
    // stays in the DOM even when the header is `display: none`, giving the
    // user something to grab to un-collapse.
    run {
        val hdr = appHeaderEl ?: return@run
        val divider = headerDividerEl ?: return@run
        attachDelayedHoverArm(divider)
        var dragging = false
        var startY = 0.0
        var startHeight = 0.0
        divider.addEventListener("mousedown", { ev ->
            ev.preventDefault(); dragging = true
            startY = (ev as MouseEvent).clientY.toDouble()
            val wasCollapsed = hdr.classList.contains("collapsed")
            if (wasCollapsed) {
                // Reveal the header at 0 px so mousemove can grow it from there.
                hdr.classList.remove("collapsed")
                startHeight = 0.0
            } else {
                startHeight = hdr.getBoundingClientRect().height
            }
            hdr.style.height = "${startHeight}px"
            hdr.style.setProperty("min-height", "0px")
            hdr.style.setProperty("overflow", "hidden")
            hdr.style.transition = "none"
            divider.classList.add("dragging")
            document.body?.style?.cursor = "row-resize"
            document.body?.style?.setProperty("user-select", "none")
        })
        document.addEventListener("mousemove", { ev ->
            if (!dragging) return@addEventListener
            val dy = (ev as MouseEvent).clientY.toDouble() - startY
            val newHeight = (startHeight + dy).coerceIn(0.0, 400.0)
            hdr.style.height = "${newHeight}px"
        })
        document.addEventListener("mouseup", { _ ->
            if (!dragging) return@addEventListener
            dragging = false
            divider.classList.remove("dragging")
            document.body?.style?.cursor = ""
            document.body?.style?.removeProperty("user-select")
            val finalHeight = hdr.getBoundingClientRect().height.toInt()
            // Clear inline overrides so either the .collapsed class
            // (display:none) or the CSS-defined natural height takes over.
            hdr.style.removeProperty("height")
            hdr.style.removeProperty("min-height")
            hdr.style.removeProperty("overflow")
            hdr.style.removeProperty("transition")
            if (finalHeight <= 10) {
                hdr.classList.add("collapsed")
                GlobalScope.launch { appVm.setHeaderCollapsed(true) }
            } else {
                hdr.classList.remove("collapsed")
                GlobalScope.launch { appVm.setHeaderCollapsed(false) }
            }
            fitVisible()
        })
    }

    // Usage-bar resize via horizontal divider drag — mirror image of the
    // header drag: the divider lives *above* the bar, so dragging up grows
    // the bar and dragging down shrinks it.
    run {
        val bar = usageBar ?: return@run
        val divider = usageBarDividerEl ?: return@run
        attachDelayedHoverArm(divider)
        var dragging = false
        var startY = 0.0
        var startHeight = 0.0
        divider.addEventListener("mousedown", { ev ->
            ev.preventDefault(); dragging = true
            startY = (ev as MouseEvent).clientY.toDouble()
            val wasCollapsed = bar.classList.contains("collapsed")
            if (wasCollapsed) {
                bar.classList.remove("collapsed")
                startHeight = 0.0
            } else {
                startHeight = bar.getBoundingClientRect().height
            }
            bar.style.height = "${startHeight}px"
            bar.style.setProperty("overflow", "hidden")
            bar.style.transition = "none"
            divider.classList.add("dragging")
            document.body?.style?.cursor = "row-resize"
            document.body?.style?.setProperty("user-select", "none")
        })
        document.addEventListener("mousemove", { ev ->
            if (!dragging) return@addEventListener
            val dy = (ev as MouseEvent).clientY.toDouble() - startY
            // Divider is above the bar — dragging the cursor *up* (negative
            // dy) should grow the bar.
            val newHeight = (startHeight - dy).coerceIn(0.0, 200.0)
            bar.style.height = "${newHeight}px"
        })
        document.addEventListener("mouseup", { _ ->
            if (!dragging) return@addEventListener
            dragging = false
            divider.classList.remove("dragging")
            document.body?.style?.cursor = ""
            document.body?.style?.removeProperty("user-select")
            val finalHeight = bar.getBoundingClientRect().height.toInt()
            bar.style.removeProperty("height")
            bar.style.removeProperty("overflow")
            bar.style.removeProperty("transition")
            if (finalHeight <= 10) {
                bar.classList.add("collapsed")
                GlobalScope.launch { appVm.setUsageBarCollapsed(true) }
            } else {
                bar.classList.remove("collapsed")
                GlobalScope.launch { appVm.setUsageBarCollapsed(false) }
            }
            fitVisible()
        })
    }

    // Boot fade.
    terminalWrapLocal.classList.add("booting")
    (document.querySelector(".app-header") as? HTMLElement)?.classList?.add("booting")

    // Horizontal scroll on tab bar.
    tabBarLocal.addEventListener("wheel", { ev ->
        val we = ev.asDynamic()
        val dy = (we.deltaY as? Number)?.toDouble() ?: 0.0
        val dx = (we.deltaX as? Number)?.toDouble() ?: 0.0
        if (dy != 0.0 && dx == 0.0) { tabBarLocal.scrollLeft += dy; ev.preventDefault() }
    })

    // Layout dropdown in the header toolbar.
    initLayoutMenu()

    // Connect and go.
    connectWindow()

    window.addEventListener("resize", {
        fitVisible()
        positionActiveIndicator()
    })
}
