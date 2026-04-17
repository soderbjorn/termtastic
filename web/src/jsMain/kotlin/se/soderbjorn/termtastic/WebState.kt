package se.soderbjorn.termtastic

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import se.soderbjorn.termtastic.client.TermtasticClient
import se.soderbjorn.termtastic.client.WindowSocket
import se.soderbjorn.termtastic.client.viewmodel.AppBackingViewModel
import se.soderbjorn.termtastic.client.viewmodel.FileBrowserBackingViewModel
import se.soderbjorn.termtastic.client.viewmodel.GitPaneBackingViewModel
import se.soderbjorn.termtastic.client.viewmodel.findLeafBySessionId

// ── Core references (initialized in start()) ────────────────────────
internal lateinit var termtasticClient: TermtasticClient
internal lateinit var windowSocket: WindowSocket
internal lateinit var appVm: AppBackingViewModel

// ── DOM references ──────────────────────────────────────────────────
internal var tabBarEl: HTMLElement? = null
internal var terminalWrapEl: HTMLElement? = null
internal var sidebarEl: HTMLElement? = null
internal var sidebarDividerEl: HTMLElement? = null
internal var usageBar: HTMLElement? = null

// ── Terminal registry (xterm.js instances, imperative) ──────────────
internal val terminals = HashMap<String, TerminalEntry>()
internal val connectionState = HashMap<String, String>()

// ── Per-pane VM registries ──────────────────────────────────────────
internal val fileBrowserVms = HashMap<String, Pair<FileBrowserBackingViewModel, Job>>()
internal val gitPaneVms = HashMap<String, Pair<GitPaneBackingViewModel, Job>>()

// ── Rendering-only state (client-local, not in VMs) ─────────────────
internal var activeTabId: String? = null
internal var currentConfig: dynamic = null
internal val maximizedPaneIds = mutableMapOf<String, String>()
internal val collapsedTabs = HashSet<String>()
internal val previousTabIds = HashSet<String>()
internal val previousPaneIds = HashSet<String>()
internal var pendingTabFlip: Map<String, Double>? = null
internal var firstRender = true
internal var devToolsEnabled = false
internal val debugSessionStates = HashMap<String, String?>()
internal val previousSessionStates = HashMap<String, String?>()

// Settings panel DOM state
internal var settingsPanel: HTMLElement? = null
internal var settingsEscHandler: ((Event) -> Unit)? = null

// Pane type modal DOM state
internal val previewEntries = mutableListOf<dynamic>()
internal var modalEscHandler: ((Event) -> Unit)? = null

// File browser view registry (live DOM handles, refilled by renderConfig)
internal val fileBrowserPaneStates = HashMap<String, FileBrowserPaneState>()
internal val fileBrowserPaneViews = HashMap<String, FileBrowserPaneView>()

// Git pane view registry
internal val gitPaneStates = HashMap<String, GitPaneState>()
internal val gitPaneViews = HashMap<String, GitPaneView>()
internal val collapsedGitSections = HashSet<String>()

// Popout / Electron detection
internal var isElectronClient = false
internal var isPopoutMode = false
internal var popoutPaneIdParam: String? = null
internal var proto = "ws"
internal var authQueryParam = ""
internal var clientTypeAtStart = "Web"

// ── Convenience: fire-and-forget command via the WindowSocket ────────
internal fun launchCmd(cmd: WindowCommand) {
    GlobalScope.launch { windowSocket.send(cmd) }
}

// ── Helpers that were on App ────────────────────────────────────────

internal fun updateAggregateStatus() {
    val states = connectionState.values
    if (states.isNotEmpty() && states.any { it == "disconnected" }) showDisconnectedModal()
    else hideDisconnectedModal()
}

internal fun isPaneFloating(paneId: String): Boolean {
    val cfg = appVm.stateFlow.value.config ?: return false
    for (tab in cfg.tabs) {
        for (fp in tab.floating) {
            if (fp.leaf.id == paneId) return true
        }
    }
    return false
}

internal fun findTabPane(el: HTMLElement): HTMLElement? {
    var cur: HTMLElement? = el
    while (cur != null) {
        if (cur.classList.contains("tab-pane")) return cur
        cur = cur.parentElement as? HTMLElement
    }
    return null
}

internal fun countPanesForSession(sessionId: String?): Int {
    if (sessionId.isNullOrEmpty()) return 0
    val cfg = appVm.stateFlow.value.config ?: return 0
    var count = 0
    fun walk(node: PaneNode?) {
        if (node == null) return
        if (node is LeafNode) { if (node.sessionId == sessionId) count++ }
        else if (node is SplitNode) { walk(node.first); walk(node.second) }
    }
    for (tab in cfg.tabs) {
        walk(tab.root)
        for (fp in tab.floating) if (fp.leaf.sessionId == sessionId) count++
        for (po in tab.poppedOut) if (po.leaf.sessionId == sessionId) count++
    }
    return count
}

internal fun shellQuote(p: String): String = "'" + p.replace("'", "'\\''") + "'"

internal fun collectPaneIds(node: dynamic, into: HashSet<String>) {
    if (node.kind == "leaf") into.add(node.id as String)
    else { collectPaneIds(node.first, into); collectPaneIds(node.second, into) }
}

internal fun savedFocusedPaneId(tabId: String): String? {
    val cfg = appVm.stateFlow.value.config ?: return null
    for (tab in cfg.tabs) {
        if (tab.id == tabId) return tab.focusedPaneId
    }
    return null
}

internal fun markPaneFocused(cell: HTMLElement) {
    val all = kotlinx.browser.document.querySelectorAll(".terminal-cell.focused")
    for (i in 0 until all.length) {
        val el = all.item(i) as HTMLElement
        if (el !== cell) el.classList.remove("focused")
    }
    cell.classList.add("focused")
    val tabPane = cell.asDynamic().closest(".tab-pane") as? HTMLElement
    val tabId = tabPane?.id
    val paneId = cell.getAttribute("data-pane")
    if (!tabId.isNullOrEmpty() && !paneId.isNullOrEmpty()) {
        launchCmd(WindowCommand.SetFocusedPane(tabId = tabId, paneId = paneId))
    }
    if (!paneId.isNullOrEmpty()) {
        val sidebar = kotlinx.browser.document.getElementById("sidebar")
        val items = sidebar?.querySelectorAll(".sidebar-pane-item")
        if (items != null) {
            for (i in 0 until items.length) {
                val item = items.item(i) as HTMLElement
                val itemPane = item.getAttribute("data-pane")
                if (itemPane == paneId) item.classList.add("active-pane")
                else item.classList.remove("active-pane")
            }
        }
    }
}

internal fun focusFirstPaneInActiveTab(): Boolean {
    val wrap = terminalWrapEl ?: return false
    val activePane = wrap.querySelector(".tab-pane.active") as? HTMLElement ?: return false
    val activeId = appVm.stateFlow.value.config?.activeTabId
    val rememberedPaneId = activeId?.let { savedFocusedPaneId(it) }
    if (rememberedPaneId != null) {
        val entry = terminals[rememberedPaneId]
        if (entry != null) { entry.term.focus(); return true }
        val cell = activePane.querySelector("[data-pane=\"$rememberedPaneId\"]") as? HTMLElement
        if (cell != null) { markPaneFocused(cell); return true }
    }
    val paneCells = activePane.querySelectorAll("[data-pane]")
    for (i in 0 until paneCells.length) {
        val cell = paneCells.item(i) as HTMLElement
        val pid = cell.getAttribute("data-pane") ?: continue
        val entry = terminals[pid] ?: continue
        entry.term.focus()
        return true
    }
    return false
}

internal fun fitVisible() {
    for (entry in terminals.values) {
        val parent = (entry.term.asDynamic().element as? HTMLElement)?.offsetParent
        if (parent != null) {
            try { fitPreservingScroll(entry.term, entry.fit) } catch (_: Throwable) {}
        }
    }
}

internal fun buildXtermTheme(): dynamic {
    val state = appVm.stateFlow.value
    val bg = themeBackgroundForCurrent(state.theme, state.appearance)
    val fg = themeForegroundForCurrent(state.theme, state.appearance)
    val bgLight = isBackgroundLight(bg)
    val base = kotlin.js.json(
        "background" to bg, "foreground" to fg, "cursor" to fg, "cursorAccent" to bg,
        "selectionBackground" to if (bgLight) "rgba(0,0,0,0.18)" else "rgba(255,255,255,0.25)"
    )
    if (bgLight) {
        base["white"] = "#3b3b3b"; base["brightWhite"] = "#5a5a5a"
        base["yellow"] = "#866a00"; base["brightYellow"] = "#9d7e00"
        base["green"] = "#116329"; base["brightGreen"] = "#1a7f37"
        base["cyan"] = "#0b6e6e"; base["brightCyan"] = "#0f8585"
        base["blue"] = "#0550ae"; base["brightBlue"] = "#0969da"
        base["magenta"] = "#6e3996"; base["brightMagenta"] = "#8250df"
        base["red"] = "#b3261e"; base["brightRed"] = "#cf222e"
    } else {
        base["black"] = "#5a5a5a"; base["brightBlack"] = "#8a8a8a"
    }
    return base
}

internal fun applyAppearanceClass() {
    val state = appVm.stateFlow.value
    kotlinx.browser.document.body?.classList?.remove("appearance-light", "appearance-dark", "dark-spiced")
    val light = isLightActive(state.appearance)
    kotlinx.browser.document.body?.classList?.add(if (light) "appearance-light" else "appearance-dark")
    if (!light && DARK_SPICED) kotlinx.browser.document.body?.classList?.add("dark-spiced")
    val bg = themeBackgroundForCurrent(state.theme, state.appearance)
    kotlinx.browser.document.body?.style?.setProperty("--terminal-bg", bg)
}

internal fun applyThemeToTerminals() {
    val xtermTheme = buildXtermTheme()
    for (entry in terminals.values) entry.term.options.theme = xtermTheme
}

internal fun applyAll() {
    applyAppearanceClass()
    applyThemeToTerminals()
}

internal fun applyPaneStatusClasses() {
    val psd = appVm.stateFlow.value.paneStatusDisplay
    kotlinx.browser.document.body?.classList?.remove("show-pane-dots", "show-pane-glow")
    when (psd) {
        se.soderbjorn.termtastic.client.PaneStatusDisplay.Dots -> kotlinx.browser.document.body?.classList?.add("show-pane-dots")
        se.soderbjorn.termtastic.client.PaneStatusDisplay.Glow -> kotlinx.browser.document.body?.classList?.add("show-pane-glow")
        se.soderbjorn.termtastic.client.PaneStatusDisplay.Both -> {
            kotlinx.browser.document.body?.classList?.add("show-pane-dots")
            kotlinx.browser.document.body?.classList?.add("show-pane-glow")
        }
        se.soderbjorn.termtastic.client.PaneStatusDisplay.None -> {}
    }
}

internal fun renderThemeSwatch(t: TerminalTheme): String =
    """<span class="theme-swatch">
        <span class="half light" style="color:${t.lightFg};background:${t.lightBg}">A</span>
        <span class="half dark" style="color:${t.darkFg};background:${t.darkBg}">A</span>
    </span>"""

internal fun applyGlobalFontSize(size: Int) {
    for ((_, entry) in terminals) {
        entry.term.options.fontSize = size
        try { safeFit(entry.term, entry.fit) } catch (_: Throwable) {}
    }
    val mdRoots = kotlinx.browser.document.querySelectorAll(".md-rendered")
    for (i in 0 until mdRoots.length) {
        (mdRoots.item(i) as? HTMLElement)?.style?.fontSize = "${size}px"
    }
    for ((paneId, view) in gitPaneViews) {
        val state = gitPaneStates[paneId] ?: continue
        state.diffFontSize = size
        state.diffHtml = null
        view.diffPane.style.fontSize = "${size}px"
        view.diffPane.style.lineHeight = "${(size * 1.54).toInt()}px"
        val sel = state.selectedFilePath
        if (sel != null) launchCmd(WindowCommand.GitDiff(paneId = paneId, filePath = sel))
    }
}

internal fun wireMarkdownAnchorLinks(container: HTMLElement) {
    container.addEventListener("click", { ev ->
        var node = ev.target as? HTMLElement
        while (node != null && node != container && node !is org.w3c.dom.HTMLAnchorElement) {
            node = node.parentElement as? HTMLElement
        }
        if (node is org.w3c.dom.HTMLAnchorElement) {
            val href = node.getAttribute("href") ?: return@addEventListener
            if (href.startsWith("#")) {
                ev.preventDefault()
                val id = href.removePrefix("#")
                val heading = container.querySelector("[id='${id.replace("'", "\\'")}']")
                heading?.scrollIntoView(js("({behavior:'smooth',block:'start'})"))
            }
        }
    })
}

private fun checkStateNotifications(sessionStates: Map<String, String?>) {
    if (!appVm.stateFlow.value.desktopNotifications) return
    if (js("typeof Notification === 'undefined'") as Boolean) return
    if ((js("Notification.permission") as String) != "granted") return

    val effective = HashMap(sessionStates)
    for ((k, v) in debugSessionStates) effective[k] = v

    for ((sessionId, newState) in effective) {
        val oldState = previousSessionStates[sessionId]
        val kind: String? = when {
            newState == "waiting" && oldState != "waiting" -> "waiting"
            newState == null && oldState == "working" -> "done"
            else -> null
        }
        if (kind != null) {
            val config = appVm.stateFlow.value.config
            val title = if (config != null) findLeafBySessionId(config, sessionId)?.title else null
            val paneLabel = title ?: sessionId
            val body = if (kind == "waiting") "$paneLabel needs input" else "$paneLabel finished"
            val opts: dynamic = js("({})")
            opts.body = body
            opts.silent = false
            val notification = js("new Notification('Termtastic', opts)")
            notification.onclick = {
                kotlinx.browser.window.focus()
                notification.close()
            }
        }
    }
    previousSessionStates.clear()
    previousSessionStates.putAll(effective)
}

internal fun updateStateDots(sessionStates: Map<String, String?>) {
    val document = kotlinx.browser.document
    checkStateNotifications(sessionStates)
    val effective = HashMap(sessionStates)
    for ((k, v) in debugSessionStates) effective[k] = v
    for ((sessionId, state) in effective) {
        val dots = document.querySelectorAll(".pane-state-dot[data-session='$sessionId']")
        for (i in 0 until dots.length) {
            val el = dots.item(i) as? HTMLElement ?: continue
            val isSidebar = el.classList.contains("sidebar-state-dot")
            val base = if (isSidebar) "pane-state-dot sidebar-state-dot" else "pane-state-dot"
            el.className = if (state != null) "$base state-$state" else base
            if (!isSidebar) {
                val cellEl = el.closest(".terminal-cell") as? HTMLElement
                if (cellEl != null) {
                    cellEl.classList.remove("pane-working", "pane-waiting")
                    if (state != null) cellEl.classList.add("pane-$state")
                }
            }
        }
    }
    val cfg = appVm.stateFlow.value.config ?: return
    fun collectSids(node: PaneNode?, into: MutableList<String>) {
        if (node is LeafNode) into.add(node.sessionId)
        else if (node is SplitNode) { collectSids(node.first, into); collectSids(node.second, into) }
    }
    for (tab in cfg.tabs) {
        val tabId = tab.id
        val sids = mutableListOf<String>()
        if (tab.root != null) collectSids(tab.root, sids)
        for (fp in tab.floating) sids.add(fp.leaf.sessionId)
        var tabState: String? = null
        for (sid in sids) {
            when (effective[sid]) {
                "waiting" -> { tabState = "waiting"; break }
                "working" -> if (tabState != "working") tabState = "working"
            }
        }
        val tabDots = document.querySelectorAll("[data-tab-state='$tabId']")
        for (j in 0 until tabDots.length) {
            val el = tabDots.item(j) as? HTMLElement ?: continue
            val isSidebar = el.classList.contains("sidebar-state-dot")
            val base = if (isSidebar) "pane-state-dot sidebar-state-dot" else "pane-state-dot tab-state-dot"
            el.className = if (tabState != null) "$base state-$tabState" else base
        }
        val tabBtn = document.querySelector(".tab-button[data-tab='$tabId']") as? HTMLElement
        if (tabBtn != null) {
            tabBtn.classList.remove("tab-working", "tab-waiting")
            if (tabState != null) tabBtn.classList.add("tab-$tabState")
        }
    }
    val statusDot = document.getElementById("connection-status") as? HTMLElement
    var globalPaneState: String? = null
    for ((_, state) in effective) {
        when (state) {
            "waiting" -> { globalPaneState = "waiting"; break }
            "working" -> if (globalPaneState != "working") globalPaneState = "working"
        }
    }
    if (statusDot != null) {
        statusDot.classList.remove("pane-state-working", "pane-state-waiting")
        when (globalPaneState) {
            "working" -> statusDot.classList.add("pane-state-working")
            "waiting" -> statusDot.classList.add("pane-state-waiting")
        }
    }
    val indicator = document.querySelector(".tab-active-indicator") as? HTMLElement
    val activeBtn = document.querySelector(".tab-button.active") as? HTMLElement
    if (indicator != null && activeBtn != null) {
        indicator.style.width = "${(activeBtn.asDynamic().offsetWidth as Number).toDouble()}px"
        indicator.style.transform = "translate3d(${(activeBtn.asDynamic().offsetLeft as Number).toDouble()}px, 0, 0)"
    }
}
