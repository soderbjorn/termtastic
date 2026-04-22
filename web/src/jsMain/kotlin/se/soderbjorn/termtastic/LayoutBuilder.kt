/**
 * Layout builder for the Termtastic web frontend free-form pane layout.
 *
 * Responsible for constructing the DOM for the server-provided list of panes.
 * Handles terminal pane creation (xterm.js instances with WebSocket PTY
 * connections), file browser panes, git panes, per-pane absolute positioning,
 * drag-to-move (via the titlebar), drag-to-resize (via the bottom-right
 * corner), pane maximize/restore animations, and drag-and-drop for files and
 * cross-tab pane reordering.
 *
 * This is the core rendering engine that translates the declarative pane list
 * from the server into a live, interactive DOM.
 *
 * @see buildPane
 * @see buildLeafCell
 * @see renderConfig
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.encodeToString
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import kotlin.js.json

/**
 * Attaches drag-and-drop file handling to a terminal container.
 *
 * When files are dropped onto the terminal, their paths are shell-quoted and
 * pasted into the terminal input. Uses Electron's `getPathForFile` API when
 * available, falling back to the standard File API path property.
 *
 * @param container the terminal container DOM element to attach handlers to
 * @param term the xterm.js [Terminal] instance to paste file paths into
 */
fun attachDragDrop(container: HTMLElement, term: Terminal) {
    container.addEventListener("dragenter", { event -> event.preventDefault() })
    container.addEventListener("dragover", { event ->
        event.preventDefault()
        event.asDynamic().dataTransfer.dropEffect = "copy"
    })
    container.addEventListener("drop", { event ->
        event.preventDefault(); event.stopPropagation()
        val files = event.asDynamic().dataTransfer?.files ?: return@addEventListener
        val count = (files.length as Number).toInt()
        if (count == 0) return@addEventListener
        val api = window.asDynamic().electronApi
        val parts = mutableListOf<String>()
        for (k in 0 until count) {
            val file = files[k]
            val path = (if (api?.getPathForFile != null) api.getPathForFile(file) else file.path) as? String
            if (!path.isNullOrEmpty()) parts.add(shellQuote(path))
        }
        if (parts.isEmpty()) return@addEventListener
        term.focus(); term.paste(parts.joinToString(" "))
    })
}

/**
 * Establishes a WebSocket connection to the server's PTY endpoint for a terminal pane.
 *
 * Handles bidirectional data flow: user keystrokes are sent as binary data to the
 * server, and PTY output (binary) and control messages (JSON) are received and
 * written to the xterm.js terminal. Automatically reconnects on close (unless the
 * pane has been removed), and shows a device-rejected overlay on auth failure (code 1008).
 *
 * @param entry the [TerminalEntry] containing the terminal, session ID, and connection state
 * @see ensureTerminal
 * @see TerminalEntry
 */
fun connectPane(entry: TerminalEntry) {
    val url = "$proto://${window.location.host}/pty/${entry.sessionId}?$authQueryParam"
    connectionState[entry.sessionId] = "connecting"
    updateAggregateStatus()

    val socket = org.w3c.dom.WebSocket(url)
    socket.asDynamic().binaryType = "arraybuffer"
    entry.socket = socket

    fun isOpen(): Boolean = socket.readyState.toInt() == org.w3c.dom.WebSocket.OPEN.toInt()

    var pendingResize: Int? = null
    fun sendResize() {
        if (entry.applyingServerSize) return
        if (!isOpen()) return
        val cols = entry.term.cols; val rows = entry.term.rows
        pendingResize?.let { window.clearTimeout(it) }
        pendingResize = window.setTimeout({
            pendingResize = null
            if (!isOpen()) return@setTimeout
            socket.send(windowJson.encodeToString<PtyControl>(PtyControl.Resize(cols = cols, rows = rows)))
        }, 50)
    }

    fun sendInput(data: String) {
        if (!isOpen()) return
        val encoder = js("new TextEncoder()")
        val bytes = encoder.encode(data)
        socket.send(bytes.buffer as org.khronos.webgl.ArrayBuffer)
    }

    entry.sendInput = ::sendInput
    entry.term.onData { data -> sendInput(data) }
    entry.term.onResize { _ -> sendResize(); updateOobOverlay(entry) }

    socket.onopen = { _: org.w3c.dom.events.Event ->
        entry.connected = true
        connectionState[entry.sessionId] = "connected"
        updateAggregateStatus()
        window.setTimeout({ sendResize() }, 0)
    }
    socket.onmessage = { event ->
        val data = event.asDynamic().data
        if (data is String) {
            runCatching {
                val msg = windowJson.decodeFromString<PtyServerMessage>(data)
                when (msg) { is PtyServerMessage.Size -> applyServerSize(entry, msg.cols, msg.rows) }
            }
        } else {
            val buf = data as org.khronos.webgl.ArrayBuffer
            val bytes = org.khronos.webgl.Uint8Array(buf)
            entry.term.write(bytes)
            if (containsShowCursor(bytes)) {
                val term = entry.term
                window.requestAnimationFrame {
                    try { term.asDynamic().refresh(0, term.rows - 1) } catch (_: Throwable) {}
                }
            }
        }
    }
    socket.onclose = { event ->
        entry.connected = false
        if (terminals[entry.paneId] === entry) {
            connectionState[entry.sessionId] = "disconnected"
            updateAggregateStatus()
            val code = (event.asDynamic().code as? Number)?.toInt() ?: 0
            val reason = (event.asDynamic().reason as? String) ?: ""
            if (code == 1008) showDeviceRejectedOverlay(code, reason)
            else window.setTimeout({ connectPane(entry) }, 500)
        }
    }
    socket.onerror = { socket.close() }
}

/**
 * Returns the existing [TerminalEntry] for a pane, or creates a new xterm.js terminal
 * instance with a fit addon, connects it to the PTY WebSocket, and registers it
 * in the [terminals] registry.
 *
 * Also sets up a [ResizeObserver] to automatically refit the terminal when its
 * container dimensions change, and attaches drag-and-drop file handling.
 *
 * @param paneId the unique pane identifier
 * @param sessionId the PTY session identifier for the WebSocket connection
 * @return the existing or newly created [TerminalEntry]
 * @see connectPane
 */
fun ensureTerminal(paneId: String, sessionId: String): TerminalEntry {
    terminals[paneId]?.let { return it }

    val container = document.createElement("div") as HTMLElement
    container.className = "terminal"
    container.setAttribute("data-session", sessionId)
    val inner = document.createElement("div") as HTMLElement
    inner.className = "terminal-inner"
    container.appendChild(inner)

    val term = Terminal(kotlin.js.json(
        "cursorBlink" to true,
        "fontFamily" to resolveFontFamilyCss(appVm.stateFlow.value.paneFontFamily),
        "fontSize" to 13,
        "minimumContrastRatio" to 4.5,
        "theme" to buildXtermTheme()
    ))
    val fit = FitAddon()
    term.loadAddon(fit)
    term.open(inner)
    term.options.theme = buildXtermTheme()
    try { safeFit(term, fit) } catch (_: Throwable) {}
    try { term.focus() } catch (_: Throwable) {}

    attachDragDrop(container, term)

    container.addEventListener("focusin", { _ ->
        val cell = container.asDynamic().closest(".terminal-cell") as? HTMLElement ?: return@addEventListener
        markPaneFocused(cell)
    })

    val entry = TerminalEntry(paneId, sessionId, term, fit, container)
    terminals[paneId] = entry
    connectionState[sessionId] = "connecting"
    updateAggregateStatus()

    val observer = ResizeObserver { _, _ ->
        try {
            if (entry.container.offsetParent != null) {
                fitPreservingScroll(entry.term, entry.fit)
                updateOobOverlay(entry)
            }
        } catch (_: Throwable) {}
    }
    observer.observe(entry.container)
    entry.resizeObserver = observer
    connectPane(entry)
    return entry
}

/**
 * Builds a single leaf pane cell element based on its content kind.
 *
 * Dispatches to the appropriate builder:
 * - "fileBrowser": creates a [buildFileBrowserView] with pane header
 * - "git": creates a [buildGitView] with pane header
 * - default (terminal): creates an xterm.js terminal via [ensureTerminal]
 *
 * Each cell includes a pane header with appropriate icon and controls.
 *
 * @param leaf the dynamic leaf node from the server config
 * @return the root HTMLElement for the pane cell
 * @see buildPane
 * @see buildPaneHeader
 */
fun buildLeafCell(leaf: dynamic, maximized: Boolean = false): HTMLElement {
    val paneId = leaf.id as String
    val title = leaf.title as String
    val contentKind: String = (leaf.content?.kind as? String) ?: "terminal"

    val cell = document.createElement("div") as HTMLElement
    cell.className = "terminal-cell"
    cell.setAttribute("data-pane", paneId)
    cell.setAttribute("data-content-kind", contentKind)

    when (contentKind) {
        "fileBrowser" -> {
            val header = buildPaneHeader(paneId, title, null, maximized = maximized)
            val fbIcon = document.createElement("span") as HTMLElement
            fbIcon.className = "pane-header-icon"
            fbIcon.innerHTML = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><path d="M2 4.5 a0.5 0.5 0 0 1 0.5 -0.5 h3.5 l1.25 1.75 h6.25 a0.5 0.5 0 0 1 0.5 0.5 v7.25 a0.5 0.5 0 0 1 -0.5 0.5 H2.5 a0.5 0.5 0 0 1 -0.5 -0.5 Z"/></svg>"""
            header.insertBefore(fbIcon, header.firstChild)
            cell.appendChild(header)
            val fbView = buildFileBrowserView(paneId, leaf, header)
            cell.appendChild(fbView)
            val fbRenderedEl = fbView.querySelector(".md-rendered") as? HTMLElement
            fbRenderedEl?.style?.fontSize = "${(appVm.stateFlow.value.paneFontSize ?: 14)}px"
            // Focus on either titlebar click or anywhere else in the pane body.
            // The header listener is critical — clicking the title text needs
            // to activate before the bubble reaches the cell so the in-tab
            // drag gate sees the correct focused state on the *next* click.
            header.addEventListener("mousedown", { _ -> markPaneFocused(cell) })
            cell.addEventListener("mousedown", { _ -> markPaneFocused(cell) })
        }
        "git" -> {
            val header = buildPaneHeader(paneId, title, null, maximized = maximized)
            val gitIcon = document.createElement("span") as HTMLElement
            gitIcon.className = "pane-header-icon"
            gitIcon.innerHTML = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><circle cx="5" cy="4" r="1.5"/><circle cx="5" cy="12" r="1.5"/><circle cx="11" cy="8" r="1.5"/><line x1="5" y1="5.5" x2="5" y2="10.5"/><path d="M5 5.5 C5 8 8 8 9.5 8"/></svg>"""
            header.insertBefore(gitIcon, header.firstChild)
            cell.appendChild(header)
            cell.appendChild(buildGitView(paneId, leaf, header))
            header.addEventListener("mousedown", { _ -> markPaneFocused(cell) })
            cell.addEventListener("mousedown", { _ -> markPaneFocused(cell) })
        }
        else -> {
            val sessionId = (leaf.content?.sessionId as? String) ?: (leaf.sessionId as String)
            val isLink = leaf.isLink as? Boolean ?: false
            val header = buildPaneHeader(paneId, title, sessionId, isLink = isLink, maximized = maximized)
            val headerIcon = document.createElement("span") as HTMLElement
            headerIcon.className = "pane-header-icon"
            if (isLink) {
                headerIcon.innerHTML = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><path d="M6.5 9.5a3 3 0 0 0 4.24 0l2.5-2.5a3 3 0 0 0-4.24-4.24L7.5 3.76"/><path d="M9.5 6.5a3 3 0 0 0-4.24 0l-2.5 2.5a3 3 0 0 0 4.24 4.24l1-1"/></svg>"""
            } else {
                headerIcon.innerHTML = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><rect x="1" y="2" width="14" height="12" rx="1.5"/><polyline points="4,7 6,5 4,3"/><line x1="7" y1="7" x2="11" y2="7" stroke-linecap="round"/></svg>"""
            }
            header.insertBefore(headerIcon, header.firstChild)
            cell.appendChild(header)
            header.addEventListener("mousedown", { _ -> markPaneFocused(cell) })
            val entry = ensureTerminal(paneId, sessionId)
            entry.term.options.fontSize = (appVm.stateFlow.value.paneFontSize ?: 14)
            entry.term.options.fontFamily = resolveFontFamilyCss(appVm.stateFlow.value.paneFontFamily)
            try { safeFit(entry.term, entry.fit) } catch (_: Throwable) {}
            cell.appendChild(entry.container)
        }
    }
    return cell
}

/**
 * Makes the pane's type icon (upper-left of the titlebar) the sole drag
 * source for cross-tab pane moves and in-tab pane swaps. Dragging it sets
 * the pane id as DataTransfer payload; tab buttons accept the drop (cross-
 * tab move, see `WindowConnection.renderConfig`) and other pane cells in
 * the same tab accept the drop to swap positions with the dragged pane
 * (see [WindowCommand.SwapPanes]).
 *
 * Keeping the handle scoped to just the icon — not the whole titlebar — means
 * the rest of the header (title text, spacer, action buttons) is free to host
 * the in-tab drag-to-move gesture without the two conflicting, and the drag
 * works on both active and inactive panes without the "click to activate
 * first" gate the titlebar-drag has.
 *
 * @param cell the pane cell element containing the header
 * @param paneId the unique pane identifier to set as drag data
 */
fun attachPaneTabDrag(cell: HTMLElement, paneId: String) {
    val icon = cell.querySelector(".pane-header-icon") as? HTMLElement ?: return
    icon.setAttribute("draggable", "true")
    icon.addEventListener("dragstart", { ev ->
        val dt = ev.asDynamic().dataTransfer ?: return@addEventListener
        dt.effectAllowed = "move"
        dt.setData("application/x-termtastic-pane", paneId)
        dt.setData("text/plain", "pane:$paneId")
        cell.classList.add("pane-dragging")
    })
    icon.addEventListener("dragend", { _ ->
        cell.classList.remove("pane-dragging")
        val highlighted = document.querySelectorAll(".tab-button.drop-pane, .terminal-cell.drop-target")
        for (i in 0 until highlighted.length) {
            (highlighted.item(i) as HTMLElement).classList.remove("drop-pane", "drop-target")
        }
    })

    // The cell itself is a drop target for OTHER panes' icon drags. When an
    // icon-drag from a different pane passes over or lands on this cell we
    // ask the server to swap positions with the drop source. Same-cell drops
    // and non-pane drags are ignored so the normal in-tab free-form drag,
    // file drops into terminals, and tab-bar drops keep working unchanged.
    fun hasPaneDragPayload(ev: dynamic): Boolean {
        val types = ev.dataTransfer?.types ?: return false
        val len = (types.length as? Number)?.toInt() ?: 0
        for (i in 0 until len) { if (types[i] == "application/x-termtastic-pane") return true }
        return false
    }
    cell.addEventListener("dragover", { ev ->
        if (!hasPaneDragPayload(ev.asDynamic())) return@addEventListener
        if (cell.classList.contains("pane-dragging")) return@addEventListener
        ev.preventDefault()
        ev.asDynamic().dataTransfer.dropEffect = "move"
        cell.classList.add("drop-target")
    })
    cell.addEventListener("dragleave", { ev ->
        // `dragleave` fires for every child boundary crossing; only clear
        // the highlight when the pointer has actually left the cell.
        val related = ev.asDynamic().relatedTarget as? HTMLElement
        if (related != null && cell.asDynamic().contains(related) as Boolean) return@addEventListener
        cell.classList.remove("drop-target")
    })
    cell.addEventListener("drop", { ev ->
        if (!hasPaneDragPayload(ev.asDynamic())) return@addEventListener
        val dt = ev.asDynamic().dataTransfer ?: return@addEventListener
        val sourcePaneId = dt.getData("application/x-termtastic-pane") as? String
        cell.classList.remove("drop-target")
        if (sourcePaneId.isNullOrEmpty() || sourcePaneId == paneId) return@addEventListener
        ev.preventDefault(); ev.stopPropagation()
        launchCmd(WindowCommand.SwapPanes(paneAId = sourcePaneId, paneBId = paneId))
    })
}

/**
 * Builds a placeholder element shown when a tab has no windows.
 *
 * Displays a heading and a "New window" button that opens the [showPaneTypeModal].
 *
 * @param tabId the tab identifier to create a new window in
 * @return the placeholder HTMLElement
 */
fun buildEmptyTabPlaceholder(tabId: String): HTMLElement {
    val wrap = document.createElement("div") as HTMLElement
    wrap.className = "empty-tab"
    val message = document.createElement("div") as HTMLElement
    message.className = "empty-tab-message"
    message.textContent = "This tab has no windows."
    val btn = document.createElement("button") as HTMLElement
    btn.className = "empty-tab-button"
    btn.setAttribute("type", "button")
    btn.textContent = "New window"
    btn.addEventListener("click", { ev: org.w3c.dom.events.Event ->
        ev.stopPropagation()
        showPaneTypeModal(emptyTabId = tabId)
    })
    wrap.appendChild(message); wrap.appendChild(btn)
    return wrap
}

/**
 * Builds an absolutely-positioned pane element with move/resize affordances.
 *
 * Every pane in the new free-form layout renders through this one function.
 * The pane is positioned via CSS `left/top/width/height` as percentages of the
 * tab section, and its `z-index` is set from the server's stacking key. The
 * titlebar drags to move; a bottom-right corner grip drags to resize. Both
 * interactions feed raw cursor fractions into [PaneGeometry.normalize] so the
 * pane visibly snaps to the 10% grid during the drag, and the final geometry
 * is persisted to the server via [WindowCommand.SetPaneGeom]. A single click
 * on an unfocused pane activates it (focus only, no raise); a double-click
 * anywhere on the pane fires [WindowCommand.RaisePane] to pop it above its
 * overlapping neighbours. A plain click on an already-active pane intentionally
 * does NOT raise — the user has to double-click to bring a background pane
 * (or re-affirm the foreground pane) to the top of the stack.
 *
 * @param paneDesc the dynamic pane descriptor with leaf, x, y, width, height, z
 * @param tabSection the parent tab section used as the coordinate reference
 * @return the pane HTMLElement
 * @see WindowCommand.SetPaneGeom
 * @see WindowCommand.RaisePane
 * @see PaneGeometry
 */
/**
 * Write the pane's fractional geometry into the CSS custom properties the
 * `.floating-pane` rule reads. The visible box is offset inward by the shared
 * `--pane-inset` so two adjacent snap-aligned panes still show a gap between
 * them — the underlying percentage values stay on the grid.
 */
private fun setPaneGeomVars(pane: HTMLElement, x: Double, y: Double, w: Double, h: Double) {
    val style = pane.style.asDynamic()
    style.setProperty("--px", "${x * 100}%")
    style.setProperty("--py", "${y * 100}%")
    style.setProperty("--pw", "${w * 100}%")
    style.setProperty("--ph", "${h * 100}%")
}

fun buildPane(paneDesc: dynamic, tabSection: HTMLElement): HTMLElement {
    val leaf = paneDesc.leaf
    val paneId = leaf.id as String
    val initialX = (paneDesc.x as Number).toDouble()
    val initialY = (paneDesc.y as Number).toDouble()
    val initialW = (paneDesc.width as Number).toDouble()
    val initialH = (paneDesc.height as Number).toDouble()
    val z = (paneDesc.z as Number).toDouble()
    val maximized = paneDesc.maximized as? Boolean ?: false

    val pane = document.createElement("div") as HTMLElement
    pane.setAttribute("data-pane", paneId)
    // Animate across rebuild: if the previous DOM had this pane in the
    // opposite maximized state, mount the new element in that OLD state
    // first, then rAF into the new state. That lets the CSS transition on
    // `.floating-pane` (left/top/width/height) fire, producing the same
    // smooth grow / shrink we had before the pane-system rewrite.
    val prior = previousMaximizedStates[paneId]
    val shouldAnimate = prior != null && prior != maximized
    val initialMaximized = if (shouldAnimate) !maximized else maximized
    pane.className = if (initialMaximized) "floating-pane maximized" else "floating-pane"
    // When maximized the `.maximized` class overrides the geom vars to fill
    // the tab area via CSS; we still set the vars from the stored geometry
    // so a later restore snaps back to the same place without the server
    // needing to round-trip those values.
    setPaneGeomVars(pane, initialX, initialY, initialW, initialH)
    pane.style.zIndex = z.toInt().toString()
    if (shouldAnimate) {
        window.requestAnimationFrame {
            if (maximized) pane.classList.add("maximized")
            else pane.classList.remove("maximized")
        }
    }

    // When the maximize/minimize (or edge-drag) geometry transition on
    // `.floating-pane` finishes, re-assert the PTY size. The mid-transition
    // ResizeObserver fits often land a row/col short due to floor-rounding
    // in safeFit against a viewport that hasn't settled, which otherwise
    // leaves the user needing to click Reformat manually.
    pane.addEventListener("transitionend", { ev ->
        val te = ev.asDynamic()
        if (te.target !== pane) return@addEventListener
        val prop = te.propertyName as? String ?: return@addEventListener
        if (prop != "width" && prop != "height") return@addEventListener
        terminals[paneId]?.let { forceReassert(it) }
    })

    val cell = buildLeafCell(leaf, maximized = maximized)
    pane.appendChild(cell)
    // Apply the pane's colour-scheme override (if any) by scoping CSS vars
    // on the floating-pane wrapper, the cell root, and the content container.
    // Must run after `pane.appendChild(cell)` so the override can walk up to
    // the `.floating-pane` ancestor via `cell.parentElement` — painting the
    // wrapper's own border/background with the override colour. Setting vars
    // only on `.terminal-cell` leaves the outer frame reading from :root.
    val paneScheme = (paneDesc.colorScheme as? String)?.takeIf { it.isNotEmpty() }
    applyPaneSchemeOverride(cell, paneScheme)
    attachPaneTabDrag(cell, paneId)

    // Capture-phase snapshot of focus state. Runs BEFORE any bubble-phase
    // listener on this mousedown event, so we see whether the pane was
    // already active *before* the click that might have activated it.
    // The drag and resize handlers read this to enforce the rule that
    // first click must activate; only a subsequent click-drag can move
    // or resize. A bubble-phase read would see the focused class that
    // the cell's own mousedown listener has just added, making every
    // click look like a "second click".
    var wasFocusedAtMousedown = false
    pane.addEventListener("mousedown", { _ ->
        wasFocusedAtMousedown = cell.classList.contains("focused")
    }, true)

    // Raise only on an explicit double-click. A plain click on a background
    // pane just activates it (focus), and a plain click on the already-active
    // pane does nothing extra — matching the quieter desktop-window idiom
    // where raising to the top is always an intentional gesture.
    //
    // The rename handler on `.terminal-title` calls `stopPropagation` on its
    // own dblclick listener, so double-clicking the title text opens rename
    // instead of raising the pane.
    pane.addEventListener("dblclick", { _ ->
        launchCmd(WindowCommand.RaisePane(paneId = paneId))
    })

    val header = cell.querySelector(".terminal-header") as? HTMLElement
    if (header != null) {
        header.classList.add("floating-header")
        header.addEventListener("mousedown", drag@{ ev ->
            val mouse = ev as MouseEvent
            if (mouse.button.toInt() != 0) return@drag
            // Use the DOM Element base type so SVG targets (inside the
            // pane-type icon) still satisfy these .closest() guards — casting
            // to HTMLElement silently drops SVGElement hits, which let the
            // titlebar free-form drag kick in whenever the user grabbed the
            // icon and broke the cross-tab drag that the icon is meant to
            // initiate.
            val target = ev.target as? Element
            if (target != null && target.closest(".pane-actions") != null) return@drag
            // The pane-type icon in the upper-left is reserved for HTML5
            // drag — don't start an in-tab move when the user grabs it.
            if (target != null && target.closest(".pane-header-icon") != null) return@drag
            // First click on an unfocused pane only activates it; the user
            // must release and click again on the titlebar to start dragging.
            if (!wasFocusedAtMousedown) return@drag
            // A maximized pane has no meaningful smaller geometry to drag to;
            // users restore first via the toolbar button.
            if (pane.classList.contains("maximized")) return@drag
            mouse.preventDefault()

            val sectionRect = tabSection.asDynamic().getBoundingClientRect()
            val sectionWidth = (sectionRect.width as Double)
            val sectionHeight = (sectionRect.height as Double)
            if (sectionWidth <= 0.0 || sectionHeight <= 0.0) return@drag

            val paneRect = pane.asDynamic().getBoundingClientRect()
            val grabDx = mouse.clientX.toDouble() - (paneRect.left as Double)
            val grabDy = mouse.clientY.toDouble() - (paneRect.top as Double)
            // Freeze width/height during move so resize and move don't fight.
            val curW = (paneRect.width as Double) / sectionWidth
            val curH = (paneRect.height as Double) / sectionHeight

            pane.classList.add("dragging")
            val previousBodyCursor = document.body?.style?.cursor ?: ""
            document.body?.style?.cursor = "grabbing"
            var latestX = initialX
            var latestY = initialY

            val moveListener: (org.w3c.dom.events.Event) -> Unit = { evMove ->
                val m = evMove as MouseEvent
                val rawX = (m.clientX.toDouble() - grabDx - (sectionRect.left as Double)) / sectionWidth
                val rawY = (m.clientY.toDouble() - grabDy - (sectionRect.top as Double)) / sectionHeight
                val box = PaneGeometry.normalize(rawX, rawY, curW, curH)
                latestX = box.x; latestY = box.y
                setPaneGeomVars(pane, box.x, box.y, box.width, box.height)
            }
            lateinit var upListener: (org.w3c.dom.events.Event) -> Unit
            upListener = { _ ->
                document.removeEventListener("mousemove", moveListener)
                document.removeEventListener("mouseup", upListener)
                pane.classList.remove("dragging")
                document.body?.style?.cursor = previousBodyCursor
                launchCmd(WindowCommand.SetPaneGeom(
                    paneId = paneId, x = latestX, y = latestY, width = curW, height = curH,
                ))
            }
            document.addEventListener("mousemove", moveListener)
            document.addEventListener("mouseup", upListener)
        })
    }

    // Bottom-right resize grip
    val grip = document.createElement("div") as HTMLElement
    grip.className = "floating-resize-handle"
    grip.addEventListener("mousedown", resize@{ ev ->
        val mouse = ev as MouseEvent
        if (mouse.button.toInt() != 0) return@resize
        // Guard: resize only on the focused, non-maximized pane. The CSS
        // already hides the grip unless the pane is focused, but a stale
        // mousedown could still arrive (e.g. via scripted dispatch), so
        // enforce the same invariant here.
        if (!wasFocusedAtMousedown) return@resize
        if (pane.classList.contains("maximized")) return@resize
        mouse.preventDefault(); mouse.stopPropagation()

        val sectionRect = tabSection.asDynamic().getBoundingClientRect()
        val sectionWidth = (sectionRect.width as Double)
        val sectionHeight = (sectionRect.height as Double)
        if (sectionWidth <= 0.0 || sectionHeight <= 0.0) return@resize

        val paneRect = pane.asDynamic().getBoundingClientRect()
        val fxOrigin = ((paneRect.left as Double) - (sectionRect.left as Double)) / sectionWidth
        val fyOrigin = ((paneRect.top as Double) - (sectionRect.top as Double)) / sectionHeight

        pane.classList.add("dragging")
        val previousBodyCursor = document.body?.style?.cursor ?: ""
        document.body?.style?.cursor = "nwse-resize"
        var latestW = initialW
        var latestH = initialH

        val moveListener: (org.w3c.dom.events.Event) -> Unit = { evMove ->
            val m = evMove as MouseEvent
            val rawW = (m.clientX.toDouble() - (sectionRect.left as Double)) / sectionWidth - fxOrigin
            val rawH = (m.clientY.toDouble() - (sectionRect.top as Double)) / sectionHeight - fyOrigin
            // Cap size so the pane's bottom-right corner never leaves the tab area.
            val capW = kotlin.math.min(rawW, 1.0 - fxOrigin)
            val capH = kotlin.math.min(rawH, 1.0 - fyOrigin)
            val box = PaneGeometry.normalize(fxOrigin, fyOrigin, capW, capH)
            latestW = box.width; latestH = box.height
            setPaneGeomVars(pane, box.x, box.y, box.width, box.height)
        }
        lateinit var upListener: (org.w3c.dom.events.Event) -> Unit
        upListener = { _ ->
            document.removeEventListener("mousemove", moveListener)
            document.removeEventListener("mouseup", upListener)
            pane.classList.remove("dragging")
            document.body?.style?.cursor = previousBodyCursor
            launchCmd(WindowCommand.SetPaneGeom(
                paneId = paneId, x = fxOrigin, y = fyOrigin, width = latestW, height = latestH,
            ))
        }
        document.addEventListener("mousemove", moveListener)
        document.addEventListener("mouseup", upListener)
    })
    pane.appendChild(grip)
    return pane
}

