/**
 * Web application state management for the Termtastic web frontend.
 *
 * Contains all global mutable state for the web client, organized into categories:
 * - Core references: the [TermtasticClient], [WindowSocket], and [AppBackingViewModel]
 * - DOM references: cached references to key layout elements
 * - Terminal registry: xterm.js instances indexed by pane ID
 * - Per-pane VM registries: backing view models for file browser and git panes
 * - Rendering state: active tab, maximized panes, animation state, etc.
 * - Settings panel and modal DOM state
 * - Electron detection flags
 *
 * Also provides utility functions used across the rendering layer: command dispatch,
 * aggregate connection status, pane focus management, terminal fitting, theme
 * application, session state notification checking, and state dot updates.
 *
 * @see main
 * @see connectWindow
 * @see renderConfig
 */
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
internal var usageBarDividerEl: HTMLElement? = null
internal var appHeaderEl: HTMLElement? = null
internal var headerDividerEl: HTMLElement? = null

// ── Terminal registry (xterm.js instances, imperative) ──────────────
internal val terminals = HashMap<String, TerminalEntry>()
internal val connectionState = HashMap<String, String>()
internal var windowSocketConnected = false

// ── Per-pane VM registries ──────────────────────────────────────────
internal val fileBrowserVms = HashMap<String, Pair<FileBrowserBackingViewModel, Job>>()
internal val gitPaneVms = HashMap<String, Pair<GitPaneBackingViewModel, Job>>()

// ── Rendering-only state (client-local, not in VMs) ─────────────────
internal var activeTabId: String? = null
internal var currentConfig: dynamic = null
/**
 * Per-pane maximized state captured just before `renderConfig` wipes the DOM,
 * so that [buildPane] can rebuild the pane in its PREVIOUS class state first
 * and then schedule a `requestAnimationFrame` flip to the new state — that
 * triggers the `.floating-pane` left/top/width/height CSS transition and
 * gives us the old animated maximize/restore back even though each config
 * update recreates every pane element.
 */
internal val previousMaximizedStates = HashMap<String, Boolean>()
internal val collapsedTabs = HashSet<String>()
internal val previousTabIds = HashSet<String>()
internal val previousPaneIds = HashSet<String>()
internal var pendingTabFlip: Map<String, Double>? = null
internal var firstRender = true
internal var devToolsEnabled = false
internal val previousSessionStates = HashMap<String, String?>()

// Settings panel DOM state
internal var settingsPanel: HTMLElement? = null
internal var settingsEscHandler: ((Event) -> Unit)? = null

// Theme manager (right-sidebar) DOM state. Only one right sidebar —
// settings OR theme manager — is visible at a time; opening either closes
// the other via closeSettingsPanel / closeThemeManager.
internal var themeManagerPanel: HTMLElement? = null
internal var themeManagerEscHandler: ((Event) -> Unit)? = null

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

// Electron detection
internal var isElectronClient = false
internal var proto = "ws"
internal var authQueryParam = ""
internal var clientTypeAtStart = "Web"

/**
 * Sends a [WindowCommand] to the server via the [WindowSocket] in a fire-and-forget coroutine.
 *
 * This is the primary mechanism for all client-to-server communication in the
 * web frontend. Called from UI event handlers throughout the codebase.
 *
 * @param cmd the command to send
 */
internal fun launchCmd(cmd: WindowCommand) {
    GlobalScope.launch { windowSocket.send(cmd) }
}

/**
 * Checks all PTY connection states and shows/hides the disconnected modal accordingly.
 *
 * Shows the disconnected overlay if any connection is in "disconnected" state.
 *
 * @see showDisconnectedModal
 * @see hideDisconnectedModal
 */
internal fun updateAggregateStatus() {
    val ptyDisconnected = connectionState.values.any { it == "disconnected" }
    if (!windowSocketConnected || ptyDisconnected) showDisconnectedModal()
    else hideDisconnectedModal()
}

/**
 * Walks up the DOM tree from the given element to find the nearest ancestor
 * with the "tab-pane" CSS class.
 *
 * @param el the starting element
 * @return the tab pane element, or null if not found
 */
internal fun findTabPane(el: HTMLElement): HTMLElement? {
    var cur: HTMLElement? = el
    while (cur != null) {
        if (cur.classList.contains("tab-pane")) return cur
        cur = cur.parentElement as? HTMLElement
    }
    return null
}

/**
 * Counts how many panes (including linked views) share the given session ID.
 *
 * Used by the close confirmation dialog to warn when closing a session with linked panes.
 *
 * @param sessionId the PTY session identifier to count panes for
 * @return the number of panes sharing this session, or 0 if sessionId is null/empty
 */
internal fun countPanesForSession(sessionId: String?): Int {
    if (sessionId.isNullOrEmpty()) return 0
    val cfg = appVm.stateFlow.value.config ?: return 0
    var count = 0
    for (tab in cfg.tabs) {
        for (p in tab.panes) if (p.leaf.sessionId == sessionId) count++
    }
    return count
}

/**
 * Shell-quotes a file path for safe pasting into a terminal.
 *
 * Wraps the path in single quotes and escapes any embedded single quotes.
 *
 * @param p the file path to quote
 * @return the shell-safe quoted string
 */
internal fun shellQuote(p: String): String = "'" + p.replace("'", "'\\''") + "'"


/**
 * Looks up the server-persisted focused pane ID for a given tab.
 *
 * @param tabId the tab identifier
 * @return the focused pane ID, or null if not set or tab not found
 */
internal fun savedFocusedPaneId(tabId: String): String? {
    // Read from currentConfig (the dynamic config set synchronously by
    // renderConfig) rather than appVm.stateFlow.value.config, which may
    // lag behind because the AppBackingViewModel's coroutine hasn't had a
    // chance to process the StateFlow update yet.
    val cfg = currentConfig ?: return null
    val tabs = cfg.tabs as? Array<dynamic> ?: return null
    for (tab in tabs) {
        if ((tab.id as? String) == tabId) return tab.focusedPaneId as? String
    }
    return null
}

/**
 * Marks a pane cell as focused, removing the "focused" class from all other cells.
 *
 * Also updates the sidebar active-pane highlight and sends a [WindowCommand.SetFocusedPane]
 * to persist the focus on the server. Does *not* raise the pane — raising on click is
 * handled by the pane-level mousedown listener in [buildPane], which only raises when
 * the pane was already focused before the click (i.e. a second click on the active pane).
 *
 * @param cell the pane cell DOM element to mark as focused
 */
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

/**
 * Focuses the first terminal pane in the active tab, preferring the server-remembered
 * focused pane if available.
 *
 * @return true if a pane was successfully focused
 */
internal fun focusFirstPaneInActiveTab(): Boolean {
    val wrap = terminalWrapEl ?: return false
    val activePane = wrap.querySelector(".tab-pane.active") as? HTMLElement ?: return false
    val activeId = (currentConfig?.activeTabId as? String) ?: appVm.stateFlow.value.config?.activeTabId
    val rememberedPaneId = activeId?.let { savedFocusedPaneId(it) }
    if (rememberedPaneId != null) {
        val entry = terminals[rememberedPaneId]
        if (entry != null) { entry.term.focus(); return true }
        // Scope to `.terminal-cell` — the outer `.floating-pane` also
        // carries `data-pane`, and an unscoped selector would match it
        // first, feeding `markPaneFocused` the wrong element (which would
        // strip `focused` from every real cell and add it to the wrapper
        // where the `.terminal-cell.focused` CSS rule doesn't apply).
        val cell = activePane.querySelector(".terminal-cell[data-pane=\"$rememberedPaneId\"]") as? HTMLElement
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

/**
 * Refits all visible terminal instances to their current container sizes.
 *
 * Iterates all registered terminals and calls [fitPreservingScroll] on those
 * whose DOM element has a non-null offsetParent (i.e., is visible).
 */
internal fun fitVisible() {
    for (entry in terminals.values) {
        val parent = (entry.term.asDynamic().element as? HTMLElement)?.offsetParent
        if (parent != null) {
            try { fitPreservingScroll(entry.term, entry.fit) } catch (_: Throwable) {}
        }
    }
}

/**
 * Builds a dynamic xterm.js theme options object from the current app theme and appearance.
 *
 * Sets background, foreground, cursor, and selection colors. For light backgrounds,
 * overrides several ANSI colors to ensure readability. For dark backgrounds, adjusts
 * black/brightBlack.
 *
 * @return a dynamic object suitable for `term.options.theme`
 */
internal fun buildXtermTheme(): dynamic {
    val palette = sectionPalette("terminal")
    val bg = argbToHex(palette.terminal.bg)
    val fg = argbToHex(palette.terminal.fg)
    val bgLight = isColorLight(palette.terminal.bg)
    val base = kotlin.js.json(
        "background" to bg,
        "foreground" to fg,
        "cursor" to argbToHex(palette.terminal.cursor),
        "cursorAccent" to argbToHex(palette.accent.onPrimary),
        "selectionBackground" to argbToCss(palette.terminal.selection),
    )
    // ANSI colour overrides for terminal readability — not part of the
    // semantic palette, but required so ANSI-coloured output stays
    // legible against light or dark backgrounds.
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

/**
 * Updates the CSS appearance classes on `<body>` based on the current theme/appearance state.
 *
 * Sets "appearance-light" or "appearance-dark" and the `--terminal-bg` CSS custom property.
 */
internal fun applyAppearanceClass() {
    val state = appVm.stateFlow.value
    kotlinx.browser.document.body?.classList?.remove("appearance-light", "appearance-dark", "dark-spiced")
    val light = isLightActive(state.appearance)
    kotlinx.browser.document.body?.classList?.add(if (light) "appearance-light" else "appearance-dark")

    // Emit all semantic --t-* CSS custom properties from the resolved palette.
    val palette = currentResolvedPalette()
    val root = kotlinx.browser.document.documentElement as? HTMLElement
    for ((prop, value) in palette.toCssVarMap()) {
        root?.style?.setProperty(prop, value)
    }

    // Tint the native OS window chrome (macOS title bar) to match the theme.
    // The Electron main process receives the hex colour via preload IPC and
    // calls BrowserWindow.setBackgroundColor — the translucent macOS title
    // bar then picks up the theme colour through its vibrancy material.
    // No-op in a plain browser (electronApi is undefined).
    val electronApi = kotlinx.browser.window.asDynamic().electronApi
    if (electronApi?.setWindowBackgroundColor != null) {
        electronApi.setWindowBackgroundColor(argbToHex(palette.chrome.titlebar))
    }

    // Active-indicator accent — always on :root so it is available in
    // every section container regardless of per-section scoping.
    if (root != null) {
        val activeVars = activeAccentCssVars()
        if (activeVars.isNotEmpty()) {
            for ((prop, value) in activeVars) root.style.setProperty(prop, value)
        } else {
            clearActiveAccentVars(root)
        }
    }

    // Apply per-section overrides. CSS alias vars (--surface, --background,
    // etc.) are resolved at :root, so children inherit the resolved value —
    // setting --t-* on a child does NOT update the alias. We must set BOTH
    // the --t-* vars AND the legacy aliases on each section container.
    fun queryElements(selector: String): List<HTMLElement> {
        val nodes = kotlinx.browser.document.querySelectorAll(selector)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? HTMLElement }
    }
    val sectionContainers: Map<String, List<HTMLElement>> = buildMap {
        val sidebarEls = buildList {
            (kotlinx.browser.document.getElementById("sidebar") as? HTMLElement)?.let { add(it) }
            (kotlinx.browser.document.querySelector(".settings-sidebar") as? HTMLElement)?.let { add(it) }
            (kotlinx.browser.document.querySelector(".theme-manager-sidebar") as? HTMLElement)?.let { add(it) }
        }
        if (sidebarEls.isNotEmpty()) put("sidebar", sidebarEls)
        // Tabs section covers the entire top bar (app-header + tab-bar).
        // The Claude usage footer used to live here too, but now has its
        // own "bottomBar" section so it can be themed independently.
        val tabsEls = listOfNotNull(
            kotlinx.browser.document.querySelector(".app-header") as? HTMLElement,
            kotlinx.browser.document.getElementById("tab-bar") as? HTMLElement,
        )
        if (tabsEls.isNotEmpty()) put("tabs", tabsEls)
        // Bottom bar (Claude usage footer) — its own theming slot so
        // themes like Verdant can keep a dark strip in light mode.
        val bottomBarEls = listOfNotNull(
            kotlinx.browser.document.getElementById("claude-usage-bar") as? HTMLElement,
        )
        if (bottomBarEls.isNotEmpty()) put("bottomBar", bottomBarEls)
        // Windows section covers all pane frames (header, border, controls).
        queryElements(".terminal-cell").takeIf { it.isNotEmpty() }?.let { put("windows", it) }
        // Content-kind sections target the content container inside the pane,
        // not the .terminal-cell itself, so they don't conflict with the
        // windows override that styles the pane chrome (header, border).
        queryElements(".terminal-cell[data-content-kind='terminal'] > .terminal").takeIf { it.isNotEmpty() }?.let { put("terminal", it) }
        queryElements(".terminal-cell[data-content-kind='fileBrowser'] > .md-view").takeIf { it.isNotEmpty() }?.let { put("fileBrowser", it) }
        queryElements(".terminal-cell[data-content-kind='git'] > .git-view").takeIf { it.isNotEmpty() }?.let { put("diff", it) }
    }
    // First pass: clear all previous inline overrides from every section container.
    val allProps = palette.toCssVarMap().keys + palette.toCssAliasMap().keys
    for ((_, elements) in sectionContainers) {
        for (el in elements) {
            for (prop in allProps) el.style.removeProperty(prop)
        }
    }
    // Second pass: apply overrides. "windows" targets .terminal-cell while
    // content-kind sections target the content container inside (e.g.
    // .git-view, .md-view, .terminal). Because CSS custom properties are
    // inherited, a windows override on .terminal-cell cascades into content
    // containers. Content-kind sections must re-apply their own palette
    // (even the global one) to block inherited windows vars.
    val windowsPalette = sectionPalette("windows")
    val windowsOverrideActive = windowsPalette != palette
    val paneContentSections = setOf("terminal", "fileBrowser", "diff")
    for ((section, elements) in sectionContainers) {
        val sp = sectionPalette(section)
        val differs = sp != palette
        // Content sections must apply their palette when the windows override
        // is active, even if their own palette matches global, to prevent
        // the inherited windows CSS custom properties from bleeding through.
        val needsApply = differs || (windowsOverrideActive && section in paneContentSections)
        console.log("[theme] section=$section elements=${elements.size} differs=$differs needsApply=$needsApply")
        if (needsApply) {
            val cssVars = sp.toCssVarMap() + sp.toCssAliasMap()
            for (el in elements) {
                for ((prop, value) in cssVars) {
                    el.style.setProperty(prop, value)
                }
            }
        }
    }

    // Per-pane scheme overrides — applied last so they beat the section-windows
    // override for each specific pane's .terminal-cell root. The cleanup pass
    // above strips inline vars from every .terminal-cell, so without this
    // re-apply step an appearance/theme switch would silently drop active
    // per-pane overrides.
    val cfg = state.config
    if (cfg != null) {
        for (tab in cfg.tabs) {
            for (pane in tab.panes) {
                val override = pane.colorScheme?.takeIf { it.isNotEmpty() } ?: continue
                val selector = ".terminal-cell[data-pane='${pane.leaf.id}']"
                val cell = kotlinx.browser.document.querySelector(selector) as? HTMLElement
                    ?: continue
                applyPaneSchemeOverride(cell, override)
            }
        }
    }
}

/**
 * Applies the current xterm theme to all registered terminal instances,
 * respecting per-pane `colorScheme` overrides. Panes without an override
 * receive the globally-resolved terminal theme; panes with one receive a
 * theme built from their chosen scheme's palette.
 *
 * Without the override-awareness step below, this function would stomp on
 * the xterm themes just set by [applyAppearanceClass] for every pane that
 * had picked its own colour scheme.
 */
internal fun applyThemeToTerminals() {
    val globalTheme = buildXtermTheme()
    val state = appVm.stateFlow.value
    val isDark = !isLightActive(state.appearance)
    val overrides = HashMap<String, String>()
    state.config?.tabs?.forEach { tab ->
        tab.panes.forEach { pane ->
            pane.colorScheme?.takeIf { it.isNotEmpty() }?.let { overrides[pane.leaf.id] = it }
        }
    }
    for ((paneId, entry) in terminals) {
        val overrideName = overrides[paneId]
        val theme = if (overrideName != null) {
            val resolved = resolvePaneScheme(overrideName)
            if (resolved != null) buildXtermThemeFromPalette(resolved.resolve(isDark))
            else globalTheme
        } else globalTheme
        entry.term.options.theme = theme
    }
}

/** Applies all visual changes: appearance CSS classes and xterm themes. */
internal fun applyAll() {
    applyAppearanceClass()
    applyThemeToTerminals()
}

/**
 * Updates CSS classes on `<body>` to control pane status indicator visibility
 * (spinners and waiting-pulse) based on the user's preference.
 */
/**
 * Applies the persisted sidebar width and collapsed state to the DOM.
 * Called once during initial settings hydration so that the sidebar
 * renders in the correct state on page load without fighting ongoing
 * user interactions.
 */
internal fun applySidebarState() {
    val state = appVm.stateFlow.value
    val sb = sidebarEl ?: return
    if (state.sidebarCollapsed) {
        sb.style.width = "0px"
    } else {
        val w = (state.sidebarWidth ?: 0).takeIf { it > 10 } ?: 260
        sb.style.width = "${w}px"
    }
}

/**
 * Applies the persisted collapsed state of the app header to the DOM by
 * toggling the `.collapsed` class. Called during initial settings hydration
 * and after drag interactions change the state.
 *
 * @see AppBackingViewModel.State.headerCollapsed
 */
internal fun applyHeaderCollapsedState() {
    val hdr = appHeaderEl ?: return
    val collapsed = appVm.stateFlow.value.headerCollapsed
    if (collapsed) hdr.classList.add("collapsed") else hdr.classList.remove("collapsed")
}

/**
 * Applies the persisted collapsed state of the Claude usage bar to the DOM.
 * The bar is only shown when both usage data is present AND the user has
 * not collapsed it; the divider stays visible whenever data is present so
 * the user can drag the bar back into view.
 *
 * Safe to call before the first usage envelope arrives — in that case the
 * bar and divider both stay hidden (inline `display: none`, set in the
 * initial HTML and by [updateClaudeUsageBadge] when `usage` is null).
 *
 * @see AppBackingViewModel.State.usageBarCollapsed
 * @see updateClaudeUsageBadge
 */
internal fun applyUsageBarCollapsedState() {
    val bar = usageBar ?: return
    val collapsed = appVm.stateFlow.value.usageBarCollapsed
    if (collapsed) bar.classList.add("collapsed") else bar.classList.remove("collapsed")
}


/**
 * Renders an HTML snippet for a theme swatch preview, showing light and dark halves.
 *
 * @param t the terminal theme to render
 * @return an HTML string with two colored half-swatches
 */
/**
 * Renders an HTML snippet for a theme swatch preview, showing a mini
 * terminal rectangle with accent prompt and a row of syntax colour dots.
 *
 * The preview resolves the palette for the current appearance mode so it
 * reflects what the user would actually see.
 *
 * @param t the terminal theme to render
 * @return an HTML string with the rich theme preview
 */
internal fun renderThemeSwatch(t: ColorScheme): String {
    val isDark = !isLightActive(appVm.stateFlow.value.appearance)
    val p = t.resolve(isDark)
    val bg = argbToHex(p.terminal.bg)
    val fg = argbToHex(p.terminal.fg)
    val accent = argbToHex(p.accent.primary)
    val syntaxDots = listOf(
        p.syntax.keyword, p.syntax.string, p.syntax.number, p.syntax.comment,
        p.syntax.function, p.syntax.type, p.syntax.operator, p.syntax.constant,
    ).joinToString("") { color ->
        """<span class="syntax-dot" style="background:${argbToHex(color)}"></span>"""
    }
    return """<span class="theme-swatch">
        <span class="swatch-terminal" style="background:$bg;color:$fg">
            <span class="swatch-prompt" style="color:$accent">❯</span> ls
        </span>
        <span class="swatch-syntax-row">$syntaxDots</span>
    </span>"""
}

/**
 * Renders a miniature app-layout silhouette for a theme configuration,
 * showing coloured zones that mirror the real UI: a tab strip along the
 * top, a sidebar column on the left, a large terminal area in the centre,
 * and an active-indicator accent line at the bottom.
 *
 * Each zone is filled with the background colour of the section's theme
 * (or the main theme when no override is set). This gives an at-a-glance
 * spatial preview of the whole configuration.
 *
 * @param config a dynamic JS object with `"theme"` and `"theme.<section>"`
 *   keys, as produced by [Theme.toDynamic]
 * @return an HTML string with the mini layout preview
 * @see renderThemeSwatch
 */
internal fun renderConfigSilhouette(config: dynamic): String {
    val isDark = !isLightActive(appVm.stateFlow.value.appearance)

    fun themeFor(key: String): ColorScheme? {
        val name = config[key] as? String
        if (name.isNullOrEmpty()) return null
        return recommendedColorSchemes.firstOrNull { it.name == name }
    }

    val mainTheme = themeFor("theme") ?: return ""

    fun paletteFor(section: String): ResolvedPalette {
        val t = themeFor("theme.$section") ?: mainTheme
        return t.resolve(isDark)
    }

    val tabsP    = paletteFor("tabs")
    val sidebarP = paletteFor("sidebar")
    val termP    = paletteFor("terminal")
    val windowsP = paletteFor("windows")
    val activeP  = (themeFor("theme.active") ?: mainTheme).resolve(isDark)

    val tabsBg      = argbToHex(tabsP.surface.base)
    val tabsAccent  = argbToHex(tabsP.accent.primary)
    val tabsFg      = argbToHex(tabsP.text.tertiary)
    val sidebarBg   = argbToHex(sidebarP.surface.base)
    val sidebarFg   = argbToHex(sidebarP.text.tertiary)
    val sidebarHi   = argbToHex(sidebarP.accent.primary)
    val termBg      = argbToHex(termP.terminal.bg)
    val termFg      = argbToHex(termP.terminal.fg)
    val windowsBg   = argbToHex(windowsP.surface.raised)
    val activeBg    = argbToHex(activeP.accent.primary)

    return """<span class="config-silhouette">
        <span class="cs-tabs" style="background:$tabsBg">
            <span class="cs-tab-dot" style="background:$tabsAccent"></span>
            <span class="cs-tab-dot cs-tab-dot-dim" style="background:$tabsFg"></span>
        </span>
        <span class="cs-body">
            <span class="cs-sidebar" style="background:$sidebarBg">
                <span class="cs-sidebar-line" style="background:$sidebarHi"></span>
                <span class="cs-sidebar-line cs-short" style="background:$sidebarFg"></span>
                <span class="cs-sidebar-line" style="background:$sidebarFg"></span>
            </span>
            <span class="cs-main" style="background:$windowsBg">
                <span class="cs-terminal" style="background:$termBg">
                    <span class="cs-prompt" style="background:$activeBg"></span>
                    <span class="cs-text" style="background:$termFg"></span>
                </span>
            </span>
        </span>
        <span class="cs-accent" style="background:$activeBg"></span>
    </span>"""
}

/**
 * Applies a new font size to all terminals, file browser rendered areas, and git diff panes.
 *
 * Updates the xterm.js fontSize option, refits terminals, sets CSS font-size on
 * markdown and diff containers, and re-requests git diffs at the new size.
 *
 * @param size the new font size in pixels
 */
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

/**
 * Wires up click handlers for anchor links within rendered Markdown content.
 *
 * Intercepts clicks on `#`-prefixed href links and smoothly scrolls to the
 * corresponding heading element within the same container, preventing navigation.
 *
 * @param container the Markdown content container element
 */
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

/**
 * Checks for session state transitions that warrant desktop notifications.
 *
 * Sends a notification when a session transitions to "waiting" (needs input) or
 * finishes "working" (returns to idle). Respects the user's desktop notification
 * preference and browser permission state.
 *
 * @param sessionStates the current session ID to state map
 */
private fun checkStateNotifications(sessionStates: Map<String, String?>) {
    if (!appVm.stateFlow.value.desktopNotifications) return
    if (js("typeof Notification === 'undefined'") as Boolean) return
    if ((js("Notification.permission") as String) != "granted") return

    val effective = HashMap(sessionStates)

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

/** Small warning-triangle SVG shown in the spinner slot when a pane is waiting for input. */
private const val WAITING_WARNING_SVG = """<svg viewBox="0 0 16 16" fill="currentColor" width="12" height="12"><path d="M8 1.5 L14.5 13.5 H1.5 Z" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/><rect x="7.25" y="6" width="1.5" height="4" rx="0.5" fill="currentColor"/><circle cx="8" cy="12" r="0.85" fill="currentColor"/></svg>"""

/** 14px variant of the warning triangle for pane headers. */
private const val WAITING_WARNING_SVG_HEADER = """<svg viewBox="0 0 16 16" fill="currentColor" width="14" height="14"><path d="M8 1.5 L14.5 13.5 H1.5 Z" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/><rect x="7.25" y="6" width="1.5" height="4" rx="0.5" fill="currentColor"/><circle cx="8" cy="12" r="0.85" fill="currentColor"/></svg>"""

/**
 * Applies the correct class and content to a spinner element based on the
 * given pane state: spinning border for "working", warning triangle for
 * "waiting", or hidden for idle.
 *
 * @param el the spinner element
 * @param baseClass the element's base CSS classes (without state suffixes)
 * @param state the pane state string, or `null` for idle
 */
internal fun applySpinnerState(el: HTMLElement, baseClass: String, state: String?) {
    when (state) {
        "working" -> { el.innerHTML = ""; el.className = "$baseClass state-working" }
        "waiting" -> {
            val svg = if ("spinner-header" in baseClass) WAITING_WARNING_SVG_HEADER else WAITING_WARNING_SVG
            el.innerHTML = svg; el.className = "$baseClass state-waiting"
        }
        else -> { el.innerHTML = ""; el.className = baseClass }
    }
}

/**
 * Updates all session state indicators (spinners for "working", warning
 * icons for "waiting") across the sidebar, tab bar, pane headers, and the
 * lower-right app logo dot.
 *
 * Also triggers desktop notification checks and adjusts the tab bar active
 * indicator position.
 *
 * @param sessionStates the current session ID to state map from the server
 * @see checkStateNotifications
 * @see updateAppLogoState
 */
internal fun updateStateIndicators(sessionStates: Map<String, String?>) {
    val document = kotlinx.browser.document
    checkStateNotifications(sessionStates)
    // Keep the lower-right app logo dot in sync with the sidebar/tab-bar
    // spinners and warning icons — all three surfaces derive from the same
    // per-session state map.
    updateAppLogoState(sessionStates)
    val effective = HashMap(sessionStates)
    for ((sessionId, state) in effective) {
        val spinners = document.querySelectorAll(".pane-status-spinner[data-session='$sessionId']")
        for (i in 0 until spinners.length) {
            val el = spinners.item(i) as? HTMLElement ?: continue
            val baseClass = if (el.classList.contains("spinner-sidebar")) "pane-status-spinner spinner-sidebar"
                else if (el.classList.contains("spinner-tab")) "pane-status-spinner spinner-tab"
                else "pane-status-spinner spinner-header"
            applySpinnerState(el, baseClass, state)
        }
    }
    val cfg = appVm.stateFlow.value.config ?: return
    for (tab in cfg.tabs) {
        val tabId = tab.id
        val sids = mutableListOf<String>()
        for (p in tab.panes) sids.add(p.leaf.sessionId)
        var tabState: String? = null
        for (sid in sids) {
            when (effective[sid]) {
                "waiting" -> { tabState = "waiting"; break }
                "working" -> if (tabState != "working") tabState = "working"
            }
        }
        val tabSpinners = document.querySelectorAll("[data-tab-state='$tabId']")
        for (j in 0 until tabSpinners.length) {
            val el = tabSpinners.item(j) as? HTMLElement ?: continue
            val baseClass = if (el.classList.contains("spinner-sidebar")) "pane-status-spinner spinner-sidebar" else "pane-status-spinner spinner-tab"
            applySpinnerState(el, baseClass, tabState)
        }
    }
    val indicator = document.querySelector(".tab-active-indicator") as? HTMLElement
    val activeBtn = document.querySelector(".tab-button.active") as? HTMLElement
    if (indicator != null && activeBtn != null) {
        indicator.style.width = "${(activeBtn.asDynamic().offsetWidth as Number).toDouble()}px"
        indicator.style.transform = "translate3d(${(activeBtn.asDynamic().offsetLeft as Number).toDouble()}px, 0, 0)"
    }
}
