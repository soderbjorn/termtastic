/**
 * Entry point for the Termtastic Kotlin/JS web frontend.
 *
 * Bootstraps the application by initializing authentication, creating the
 * [TermtasticClient] and [WindowSocket], setting up the [AppBackingViewModel],
 * hydrating UI settings from the server, and connecting the WebSocket-driven
 * rendering pipeline.
 *
 * The application supports two modes:
 * - **Normal mode**: full multi-tab, multi-pane terminal layout with sidebar,
 *   tab bar, settings panel, and all pane types
 * - **Popout mode**: single-pane window for Electron pop-out, delegated to [initPopoutMode]
 *
 * @see start
 * @see connectWindow
 * @see initPopoutMode
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.GlobalScope
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
 * 2. Detects client type (Electron vs. Web) and popout mode
 * 3. Creates the [TermtasticClient], [WindowSocket], and [AppBackingViewModel]
 * 4. Hydrates UI settings from the server via REST
 * 5. Sets up reactive state observers for theme/appearance/pane-status changes
 * 6. Registers global event listeners (click, drag, resize)
 * 7. For popout mode: delegates to [initPopoutMode]
 * 8. For normal mode: sets up DOM references, sidebar resize, tab bar scrolling,
 *    and calls [connectWindow] to start the rendering pipeline
 */
private fun start() {
    ensureAuthToken()

    clientTypeAtStart =
        if (window.navigator.userAgent.contains("Electron", ignoreCase = true)) "Computer" else "Web"
    authQueryParam = "auth=" + encodeUriComponent(authTokenForSending()) +
        "&clientType=" + encodeUriComponent(clientTypeAtStart)
    proto = if (window.location.protocol == "https:") "wss" else "ws"
    isElectronClient = window.navigator.userAgent.contains("Electron", ignoreCase = true)
    val urlSearch = js("new URLSearchParams(window.location.search)")
    popoutPaneIdParam = (urlSearch.get("popout") as? String)?.takeIf { it.isNotEmpty() }
    isPopoutMode = popoutPaneIdParam != null
    screenIndex = (urlSearch.get("screen") as? String)?.toIntOrNull() ?: 0

    if (isPopoutMode) {
        document.body?.classList?.add("popout-mode")
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
    windowSocket = termtasticClient.openWindowSocket(screenIndex = screenIndex)

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
            applyAll()
            applySidebarState()
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
    GlobalScope.launch {
        var prevTheme = appVm.stateFlow.value.theme.name
        var prevAppearance = appVm.stateFlow.value.appearance
        appVm.stateFlow.collect { state ->
            if (state.theme.name != prevTheme || state.appearance != prevAppearance) {
                prevTheme = state.theme.name
                prevAppearance = state.appearance
                applyAll()
                updateAppearanceToggle()
            }
        }
    }

    // React to system color scheme changes when in Auto.
    val mql = window.matchMedia("(prefers-color-scheme: dark)")
    mql.asDynamic().addEventListener("change", {
        if (appVm.stateFlow.value.appearance == Appearance.Auto) applyAll()
    })

    // Close dropdowns on outside click.
    document.addEventListener("click", {
        (document.getElementById("debug-dropdown") as? HTMLElement)?.classList?.remove("open")
        val openMenus = document.querySelectorAll(".pane-menu.open, .tab-menu.open, .tab-menu-list.open, .pane-split-flyout.open, .pane-flyout-wrap.open")
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
        applyAll()
        updateAppearanceToggle()
        settingsAppearanceRefresh?.invoke()
    })
    updateAppearanceToggle()

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

    // ── Popout mode ─────────────────────────────────────────────────
    if (isPopoutMode) {
        initPopoutMode()
        return
    }

    // ── Normal mode DOM setup ───────────────────────────────────────
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

    // Connect and go.
    connectWindow()

    // Electron popout-close handler.
    val electronApiForPopout = window.asDynamic().electronApi
    if (electronApiForPopout?.onPopoutClosed != null) {
        electronApiForPopout.onPopoutClosed { paneId: String ->
            launchCmd(WindowCommand.DockPoppedOut(paneId = paneId))
        }
    }

    // Electron window bounds persistence: relay move/resize from main process
    // to the server so window positions survive restarts.
    val electronApiForBounds = window.asDynamic().electronApi
    if (electronApiForBounds?.onWindowBoundsChanged != null) {
        electronApiForBounds.onWindowBoundsChanged { bounds: dynamic ->
            val idx = (bounds.screenIndex as? Int) ?: screenIndex
            val sb = ScreenBounds(
                x = (bounds.x as? Int) ?: 0,
                y = (bounds.y as? Int) ?: 0,
                width = (bounds.width as? Int) ?: 1280,
                height = (bounds.height as? Int) ?: 800,
                displayId = bounds.displayId as? String,
            )
            launchCmd(WindowCommand.SetScreenBounds(screenIndex = idx, bounds = sb))
        }
    }

    window.addEventListener("resize", {
        fitVisible()
        positionActiveIndicator()
    })
}
