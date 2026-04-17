package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import se.soderbjorn.termtastic.client.ClientIdentity
import se.soderbjorn.termtastic.client.ServerUrl
import se.soderbjorn.termtastic.client.TermtasticClient
import se.soderbjorn.termtastic.client.viewmodel.AppBackingViewModel
import se.soderbjorn.termtastic.client.viewmodel.SettingsPersister
import kotlin.js.json

fun main() {
    @Suppress("UNUSED_VARIABLE")
    val css = xtermCss
    window.onload = { start() }
}

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
    windowSocket = termtasticClient.openWindowSocket()

    val webSettingsPersister = SettingsPersister { key, value ->
        val init: dynamic = js("({})")
        init.method = "POST"
        init.headers = json(
            "Content-Type" to "application/json",
            "X-Termtastic-Auth" to authTokenForSending(),
            "X-Termtastic-Client-Type" to clientTypeAtStart,
        )
        init.body = JSON.stringify(json(key to value))
        window.fetch("/api/ui-settings", init)
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
            applyPaneStatusClasses()
        }
    }

    applyAll()

    // Request notification permission (Electron grants via setPermissionRequestHandler in main.js).
    if (js("typeof Notification !== 'undefined'") as Boolean) {
        if ((js("Notification.permission") as String) != "granted") {
            js("Notification.requestPermission()")
        }
    }

    // Reactively apply visual changes when the VM's appearance/theme state changes.
    GlobalScope.launch {
        var prevTheme = appVm.stateFlow.value.theme.name
        var prevAppearance = appVm.stateFlow.value.appearance
        var prevPsd = appVm.stateFlow.value.paneStatusDisplay
        appVm.stateFlow.collect { state ->
            if (state.theme.name != prevTheme || state.appearance != prevAppearance) {
                prevTheme = state.theme.name
                prevAppearance = state.appearance
                applyAll()
            }
            if (state.paneStatusDisplay != prevPsd) {
                prevPsd = state.paneStatusDisplay
                applyPaneStatusClasses()
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
        val openMenus = document.querySelectorAll(".pane-menu.open, .tab-menu.open, .tab-menu-list.open, .pane-split-wrap.open, .pane-flyout-wrap.open")
        for (i in 0 until openMenus.length) {
            (openMenus.item(i) as HTMLElement).classList.remove("open")
        }
    })

    // Swallow stray drops.
    document.addEventListener("dragover", { event -> event.preventDefault() })
    document.addEventListener("drop", { event -> event.preventDefault() })
    updateAggregateStatus()

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
            for ((label, state) in listOf("Working" to "working", "Waiting" to "waiting", "Clear" to null)) {
                val item = document.createElement("div") as HTMLElement
                item.className = "pane-menu-item"; item.textContent = label
                item.addEventListener("click", { ev ->
                    ev.stopPropagation(); debugDropdown.classList.remove("open")
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
            val w = sidebarElLocal.getBoundingClientRect().width
            GlobalScope.launch { appVm.setSidebarWidth(w.toInt()) }
            fitVisible()
        })
    }

    // Sidebar toggle button.
    run {
        val toggleBtn = document.getElementById("sidebar-toggle") as? HTMLElement
        toggleBtn?.addEventListener("click", { ev ->
            ev.stopPropagation()
            val currentWidth = sidebarElLocal.getBoundingClientRect().width.toInt()
            val openWidth = appVm.stateFlow.value.sidebarWidth ?: 260
            if (currentWidth > 10) {
                sidebarElLocal.style.width = "0px"
                GlobalScope.launch { appVm.setSidebarWidth(0) }
            } else {
                sidebarElLocal.style.width = "${openWidth}px"
                GlobalScope.launch { appVm.setSidebarWidth(openWidth) }
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

    window.addEventListener("resize", {
        fitVisible()
        positionActiveIndicator()
    })
}
