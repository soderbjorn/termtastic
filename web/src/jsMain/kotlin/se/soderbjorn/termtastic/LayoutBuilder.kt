/**
 * Layout builder for the Termtastic web frontend pane tree.
 *
 * Responsible for recursively constructing the DOM tree from the server-provided
 * pane configuration. Handles terminal pane creation (xterm.js instances with
 * WebSocket PTY connections), file browser panes, git panes, split containers
 * with draggable dividers, floating panes, pane maximize/restore animations,
 * and drag-and-drop for files and pane reordering.
 *
 * This is the core rendering engine that translates the declarative pane tree
 * from the server into a live, interactive DOM.
 *
 * @see buildNode
 * @see buildLeafCell
 * @see renderConfig
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.encodeToString
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
        "fontFamily" to "Menlo, Monaco, 'Courier New', monospace",
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
 * Attaches mouse-drag behavior to a split divider element, allowing the user
 * to resize the two children of a split container by dragging.
 *
 * On mouse-up, persists the new ratio to the server via [WindowCommand.SetRatio].
 *
 * @param divider the divider DOM element to attach drag listeners to
 * @param container the parent split container element
 * @param firstWrap the first child wrapper element
 * @param secondWrap the second child wrapper element
 * @param splitId the unique split identifier for persisting the ratio
 * @param isHorizontal true for horizontal splits (left/right), false for vertical (top/bottom)
 */
fun attachDividerDrag(
    divider: HTMLElement, container: HTMLElement,
    firstWrap: HTMLElement, secondWrap: HTMLElement,
    splitId: String, isHorizontal: Boolean,
) {
    divider.addEventListener("mousedown", { ev ->
        val mouse = ev as MouseEvent
        if (mouse.button.toInt() != 0) return@addEventListener
        mouse.preventDefault()
        val rect = container.getBoundingClientRect()
        val total = if (isHorizontal) rect.width else rect.height
        if (total <= 0.0) return@addEventListener
        divider.classList.add("dragging")
        container.classList.add("resizing")
        val previousBodyCursor = document.body?.style?.cursor ?: ""
        document.body?.style?.cursor = if (isHorizontal) "col-resize" else "row-resize"
        var latestRatio = -1.0
        val moveListener: (org.w3c.dom.events.Event) -> Unit = { evMove ->
            val m = evMove as MouseEvent
            val offset = if (isHorizontal) m.clientX - rect.left else m.clientY - rect.top
            var r = offset / total
            if (r < 0.05) r = 0.05; if (r > 0.95) r = 0.95
            latestRatio = r
            val secondR = 1.0 - r
            firstWrap.style.flex = "$r $r 0%"
            secondWrap.style.flex = "$secondR $secondR 0%"
        }
        lateinit var upListener: (org.w3c.dom.events.Event) -> Unit
        upListener = { _ ->
            document.removeEventListener("mousemove", moveListener)
            document.removeEventListener("mouseup", upListener)
            divider.classList.remove("dragging")
            container.classList.remove("resizing")
            document.body?.style?.cursor = previousBodyCursor
            if (latestRatio > 0.0) launchCmd(WindowCommand.SetRatio(splitId = splitId, ratio = latestRatio))
        }
        document.addEventListener("mousemove", moveListener)
        document.addEventListener("mouseup", upListener)
    })
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
 * @param popoutMode true if this cell is rendered in a pop-out window
 * @return the root HTMLElement for the pane cell
 * @see buildNode
 * @see buildPaneHeader
 */
fun buildLeafCell(leaf: dynamic, popoutMode: Boolean = false): HTMLElement {
    val paneId = leaf.id as String
    val title = leaf.title as String
    val contentKind: String = (leaf.content?.kind as? String) ?: "terminal"

    val cell = document.createElement("div") as HTMLElement
    cell.className = "terminal-cell"
    cell.setAttribute("data-pane", paneId)

    when (contentKind) {
        "fileBrowser" -> {
            val header = buildPaneHeader(paneId, title, null, popoutMode = popoutMode)
            val fbIcon = document.createElement("span") as HTMLElement
            fbIcon.className = "pane-header-icon"
            fbIcon.innerHTML = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><path d="M2 4.5 a0.5 0.5 0 0 1 0.5 -0.5 h3.5 l1.25 1.75 h6.25 a0.5 0.5 0 0 1 0.5 0.5 v7.25 a0.5 0.5 0 0 1 -0.5 0.5 H2.5 a0.5 0.5 0 0 1 -0.5 -0.5 Z"/></svg>"""
            val fbTitleEl = header.querySelector(".terminal-title") as? HTMLElement
            if (fbTitleEl != null) header.insertBefore(fbIcon, fbTitleEl)
            cell.appendChild(header)
            val fbView = buildFileBrowserView(paneId, leaf, header)
            cell.appendChild(fbView)
            val fbRenderedEl = fbView.querySelector(".md-rendered") as? HTMLElement
            fbRenderedEl?.style?.fontSize = "${(appVm.stateFlow.value.paneFontSize ?: 14)}px"
            cell.addEventListener("mousedown", { _ -> markPaneFocused(cell) })
        }
        "git" -> {
            val header = buildPaneHeader(paneId, title, null, popoutMode = popoutMode)
            val gitIcon = document.createElement("span") as HTMLElement
            gitIcon.className = "pane-header-icon"
            gitIcon.innerHTML = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><circle cx="5" cy="4" r="1.5"/><circle cx="5" cy="12" r="1.5"/><circle cx="11" cy="8" r="1.5"/><line x1="5" y1="5.5" x2="5" y2="10.5"/><path d="M5 5.5 C5 8 8 8 9.5 8"/></svg>"""
            val gitTitleEl = header.querySelector(".terminal-title") as? HTMLElement
            if (gitTitleEl != null) header.insertBefore(gitIcon, gitTitleEl)
            cell.appendChild(header)
            cell.appendChild(buildGitView(paneId, leaf, header))
            cell.addEventListener("mousedown", { _ -> markPaneFocused(cell) })
        }
        else -> {
            val sessionId = (leaf.content?.sessionId as? String) ?: (leaf.sessionId as String)
            val isLink = leaf.isLink as? Boolean ?: false
            val header = buildPaneHeader(paneId, title, sessionId, popoutMode = popoutMode, isLink = isLink)
            val headerIcon = document.createElement("span") as HTMLElement
            headerIcon.className = "pane-header-icon"
            if (isLink) {
                headerIcon.innerHTML = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><path d="M6.5 9.5a3 3 0 0 0 4.24 0l2.5-2.5a3 3 0 0 0-4.24-4.24L7.5 3.76"/><path d="M9.5 6.5a3 3 0 0 0-4.24 0l-2.5 2.5a3 3 0 0 0 4.24 4.24l1-1"/></svg>"""
            } else {
                headerIcon.innerHTML = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><rect x="1" y="2" width="14" height="12" rx="1.5"/><polyline points="4,7 6,5 4,3"/><line x1="7" y1="7" x2="11" y2="7" stroke-linecap="round"/></svg>"""
            }
            val headerTitleEl = header.querySelector(".terminal-title") as? HTMLElement
            if (headerTitleEl != null) header.insertBefore(headerIcon, headerTitleEl)
            cell.appendChild(header)
            header.addEventListener("mousedown", { _ -> markPaneFocused(cell) })
            val entry = ensureTerminal(paneId, sessionId)
            entry.term.options.fontSize = (appVm.stateFlow.value.paneFontSize ?: 14)
            try { safeFit(entry.term, entry.fit) } catch (_: Throwable) {}
            cell.appendChild(entry.container)
        }
    }
    return cell
}

/**
 * Makes a pane cell's header draggable for pane-to-tab drag-and-drop reordering.
 *
 * When the header is dragged, the pane ID is set as transfer data so that tab
 * buttons can accept the drop and move the pane to a different tab.
 *
 * @param cell the pane cell element containing the header
 * @param paneId the unique pane identifier to set as drag data
 */
fun attachPaneTabDrag(cell: HTMLElement, paneId: String) {
    val header = cell.querySelector(".terminal-header") as? HTMLElement ?: return
    header.setAttribute("draggable", "true")
    header.addEventListener("dragstart", { ev ->
        val target = ev.target
        if (target != null && (target as HTMLElement).closest(".pane-actions") != null) {
            ev.preventDefault(); return@addEventListener
        }
        val dt = ev.asDynamic().dataTransfer ?: return@addEventListener
        dt.effectAllowed = "move"
        dt.setData("application/x-termtastic-pane", paneId)
        dt.setData("text/plain", "pane:$paneId")
        cell.classList.add("pane-dragging")
    })
    header.addEventListener("dragend", { _ ->
        cell.classList.remove("pane-dragging")
        val highlighted = document.querySelectorAll(".tab-button.drop-pane")
        for (i in 0 until highlighted.length) (highlighted.item(i) as HTMLElement).classList.remove("drop-pane")
    })
}

/**
 * Recursively builds the DOM tree for a pane tree node.
 *
 * For leaf nodes, delegates to [buildLeafCell] and attaches pane drag behavior.
 * For split nodes, creates a flex container with two children separated by a
 * draggable divider, with the split ratio applied via CSS flex properties.
 *
 * @param node a dynamic object with kind "leaf" or "split", from the server config
 * @return the root HTMLElement for this node's subtree
 * @see buildLeafCell
 * @see attachDividerDrag
 */
fun buildNode(node: dynamic): HTMLElement {
    if (node.kind == "leaf") {
        val cell = buildLeafCell(node)
        attachPaneTabDrag(cell, node.id as String)
        return cell
    } else {
        val splitId = node.id as String
        val orientation = node.orientation as String
        val isHorizontal = orientation == "Horizontal"
        val ratio = (node.ratio as Number).toDouble()
        val container = document.createElement("div") as HTMLElement
        container.className = "split ${if (isHorizontal) "split-horizontal" else "split-vertical"}"
        container.setAttribute("data-split", splitId)

        val firstWrap = document.createElement("div") as HTMLElement
        firstWrap.className = "split-child"
        firstWrap.style.flex = "$ratio $ratio 0%"
        firstWrap.appendChild(buildNode(node.first))

        val secondWrap = document.createElement("div") as HTMLElement
        secondWrap.className = "split-child"
        val secondRatio = 1.0 - ratio
        secondWrap.style.flex = "$secondRatio $secondRatio 0%"
        secondWrap.appendChild(buildNode(node.second))

        val divider = document.createElement("div") as HTMLElement
        divider.className = "split-divider ${if (isHorizontal) "split-divider-horizontal" else "split-divider-vertical"}"
        divider.setAttribute("data-split-divider", splitId)
        attachDividerDrag(divider, container, firstWrap, secondWrap, splitId, isHorizontal)

        container.appendChild(firstWrap); container.appendChild(divider); container.appendChild(secondWrap)
        return container
    }
}

/**
 * Builds a placeholder element shown when a tab has no panes.
 *
 * Displays a message and a "New pane" button that opens the [showPaneTypeModal].
 *
 * @param tabId the tab identifier to create a new pane in
 * @return the placeholder HTMLElement
 */
fun buildEmptyTabPlaceholder(tabId: String): HTMLElement {
    val wrap = document.createElement("div") as HTMLElement
    wrap.className = "empty-tab"
    val message = document.createElement("div") as HTMLElement
    message.className = "empty-tab-message"
    message.textContent = "This tab has no panes."
    val btn = document.createElement("button") as HTMLElement
    btn.className = "empty-tab-button"
    btn.setAttribute("type", "button")
    btn.textContent = "New pane"
    btn.addEventListener("click", { ev: org.w3c.dom.events.Event ->
        ev.stopPropagation()
        showPaneTypeModal(emptyTabId = tabId)
    })
    wrap.appendChild(message); wrap.appendChild(btn)
    return wrap
}

/**
 * Builds a floating (detached) pane element with absolute positioning within its tab.
 *
 * The pane can be moved by dragging its header and resized via a bottom-right grip.
 * Position and size are expressed as fractions of the tab section dimensions and
 * persisted to the server via [WindowCommand.SetFloatingGeom].
 *
 * @param floater the dynamic floating pane descriptor with leaf, x, y, width, height
 * @param tabSection the parent tab section element used as the coordinate reference
 * @return the floating pane HTMLElement
 * @see WindowCommand.ToggleFloating
 * @see WindowCommand.RaiseFloating
 */
fun buildFloatingPane(floater: dynamic, tabSection: HTMLElement): HTMLElement {
    val leaf = floater.leaf
    val paneId = leaf.id as String
    val x = (floater.x as Number).toDouble()
    val y = (floater.y as Number).toDouble()
    val w = (floater.width as Number).toDouble()
    val h = (floater.height as Number).toDouble()

    val pane = document.createElement("div") as HTMLElement
    pane.className = "floating-pane"
    pane.setAttribute("data-pane", paneId)
    pane.style.left = "${x * 100}%"; pane.style.top = "${y * 100}%"
    pane.style.width = "${w * 100}%"; pane.style.height = "${h * 100}%"

    val cell = buildLeafCell(leaf)
    pane.appendChild(cell)

    pane.addEventListener("mousedown", { _ -> launchCmd(WindowCommand.RaiseFloating(paneId = paneId)) })

    val header = cell.querySelector(".terminal-header") as? HTMLElement
    if (header != null) {
        header.classList.add("floating-header")
        header.addEventListener("mousedown", drag@{ ev ->
            val mouse = ev as MouseEvent
            if (mouse.button.toInt() != 0) return@drag
            val target = ev.target
            if (target != null && (target as HTMLElement).closest(".pane-actions") != null) return@drag
            mouse.preventDefault()

            val sectionRect = tabSection.asDynamic().getBoundingClientRect()
            val sectionWidth = (sectionRect.width as Double)
            val sectionHeight = (sectionRect.height as Double)
            if (sectionWidth <= 0.0 || sectionHeight <= 0.0) return@drag

            val paneRect = pane.asDynamic().getBoundingClientRect()
            val grabDx = mouse.clientX.toDouble() - (paneRect.left as Double)
            val grabDy = mouse.clientY.toDouble() - (paneRect.top as Double)
            val paneWidth = (paneRect.width as Double); val paneHeight = (paneRect.height as Double)

            pane.classList.add("dragging")
            val previousBodyCursor = document.body?.style?.cursor ?: ""
            document.body?.style?.cursor = "grabbing"
            var latestX = x; var latestY = y

            val moveListener: (org.w3c.dom.events.Event) -> Unit = { evMove ->
                val m = evMove as MouseEvent
                var fx = (m.clientX.toDouble() - grabDx - (sectionRect.left as Double)) / sectionWidth
                var fy = (m.clientY.toDouble() - grabDy - (sectionRect.top as Double)) / sectionHeight
                val maxX = 1.0 - (paneWidth / sectionWidth)
                val maxY = 1.0 - (paneHeight / sectionHeight)
                if (fx < 0.0) fx = 0.0; if (fy < 0.0) fy = 0.0
                if (fx > maxX) fx = maxX; if (fy > maxY) fy = maxY
                latestX = fx; latestY = fy
                pane.style.left = "${fx * 100}%"; pane.style.top = "${fy * 100}%"
            }
            lateinit var upListener: (org.w3c.dom.events.Event) -> Unit
            upListener = { _ ->
                document.removeEventListener("mousemove", moveListener)
                document.removeEventListener("mouseup", upListener)
                pane.classList.remove("dragging")
                document.body?.style?.cursor = previousBodyCursor
                launchCmd(WindowCommand.SetFloatingGeom(paneId = paneId, x = latestX, y = latestY,
                    width = paneWidth / sectionWidth, height = paneHeight / sectionHeight))
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
        var latestW = w; var latestH = h

        val moveListener: (org.w3c.dom.events.Event) -> Unit = { evMove ->
            val m = evMove as MouseEvent
            var fw = (m.clientX.toDouble() - (sectionRect.left as Double)) / sectionWidth - fxOrigin
            var fh = (m.clientY.toDouble() - (sectionRect.top as Double)) / sectionHeight - fyOrigin
            if (fw < 0.05) fw = 0.05; if (fh < 0.05) fh = 0.05
            if (fw > 1.0 - fxOrigin) fw = 1.0 - fxOrigin
            if (fh > 1.0 - fyOrigin) fh = 1.0 - fyOrigin
            latestW = fw; latestH = fh
            pane.style.width = "${fw * 100}%"; pane.style.height = "${fh * 100}%"
        }
        lateinit var upListener: (org.w3c.dom.events.Event) -> Unit
        upListener = { _ ->
            document.removeEventListener("mousemove", moveListener)
            document.removeEventListener("mouseup", upListener)
            pane.classList.remove("dragging")
            document.body?.style?.cursor = previousBodyCursor
            launchCmd(WindowCommand.SetFloatingGeom(paneId = paneId, x = fxOrigin, y = fyOrigin, width = latestW, height = latestH))
        }
        document.addEventListener("mousemove", moveListener)
        document.addEventListener("mouseup", upListener)
    })
    pane.appendChild(grip)
    return pane
}

/**
 * Restores a maximized pane back to its original position within the split layout.
 *
 * Optionally animates the transition from full-tab to original size using CSS
 * transitions. Removes the maximized backdrop and updates the maximize button icon.
 *
 * @param tabId the tab containing the maximized pane
 * @param animate whether to animate the restore transition (default true)
 * @see maximizePane
 */
fun restorePane(tabId: String, animate: Boolean = true) {
    val paneId = maximizedPaneIds.remove(tabId) ?: return
    val cell = document.querySelector(".terminal-cell[data-pane=\"$paneId\"]") as? HTMLElement ?: return
    val tabPane = findTabPane(cell) ?: return
    val backdrop = tabPane.querySelector(".maximized-backdrop") as? HTMLElement
    val btn = cell.querySelector(".pane-maximize-btn") as? HTMLElement
    btn?.innerHTML = ICON_MAXIMIZE; btn?.setAttribute("title", "Maximize pane")

    if (animate) {
        val splitChild = cell.parentElement as? HTMLElement
        val tabRect = tabPane.getBoundingClientRect()
        val targetRect = splitChild?.getBoundingClientRect()
        val targetTop = (targetRect?.top ?: 0.0) - tabRect.top
        val targetLeft = (targetRect?.left ?: 0.0) - tabRect.left
        val targetWidth = targetRect?.width ?: tabRect.width
        val targetHeight = targetRect?.height ?: tabRect.height

        cell.classList.remove("maximized")
        cell.style.position = "absolute"; cell.style.zIndex = "20"
        cell.style.top = "0px"; cell.style.left = "0px"
        cell.style.width = "${tabRect.width}px"; cell.style.height = "${tabRect.height}px"
        cell.classList.add("restoring")
        cell.offsetHeight // reflow
        backdrop?.classList?.remove("visible")
        cell.style.top = "${targetTop}px"; cell.style.left = "${targetLeft}px"
        cell.style.width = "${targetWidth}px"; cell.style.height = "${targetHeight}px"

        var restored = false
        cell.addEventListener("transitionend", { ev ->
            if (restored) return@addEventListener
            if ((ev.target as? HTMLElement) !== cell) return@addEventListener
            restored = true
            cell.classList.remove("restoring")
            cell.style.removeProperty("position"); cell.style.removeProperty("z-index")
            cell.style.removeProperty("top"); cell.style.removeProperty("left")
            cell.style.removeProperty("width"); cell.style.removeProperty("height")
            backdrop?.remove(); fitVisible()
        })
    } else {
        cell.classList.remove("maximized")
        cell.style.removeProperty("position"); cell.style.removeProperty("z-index")
        cell.style.removeProperty("top"); cell.style.removeProperty("left")
        cell.style.removeProperty("width"); cell.style.removeProperty("height")
        backdrop?.remove(); fitVisible()
    }
}

/**
 * Maximizes a pane to fill its entire tab area, overlaying all other panes.
 *
 * Optionally animates the transition from original size to full-tab. Adds a
 * backdrop overlay that can be clicked to restore. Only one pane per tab can
 * be maximized at a time; maximizing a different pane restores the previous one.
 *
 * @param paneId the pane to maximize
 * @param animate whether to animate the maximize transition (default true)
 * @see restorePane
 */
fun maximizePane(paneId: String, animate: Boolean = true) {
    val cell = document.querySelector(".terminal-cell[data-pane=\"$paneId\"]") as? HTMLElement ?: return
    val tabPane = findTabPane(cell) ?: return
    val tabId = tabPane.id
    val prev = maximizedPaneIds[tabId]
    if (prev != null && prev != paneId) restorePane(tabId, animate = false)
    maximizedPaneIds[tabId] = paneId

    var backdrop = tabPane.querySelector(".maximized-backdrop") as? HTMLElement
    if (backdrop == null) {
        backdrop = document.createElement("div") as HTMLElement
        backdrop.className = "maximized-backdrop"
        backdrop.addEventListener("click", { _ -> restorePane(tabId) })
        tabPane.appendChild(backdrop)
    }

    if (animate) {
        val tabRect = tabPane.getBoundingClientRect()
        val cellRect = cell.getBoundingClientRect()
        cell.style.position = "absolute"; cell.style.zIndex = "20"
        cell.style.top = "${cellRect.top - tabRect.top}px"; cell.style.left = "${cellRect.left - tabRect.left}px"
        cell.style.width = "${cellRect.width}px"; cell.style.height = "${cellRect.height}px"
        cell.classList.add("maximizing")
        cell.offsetHeight // reflow
        backdrop.classList.add("visible")
        cell.style.top = "0px"; cell.style.left = "0px"
        cell.style.width = "100%"; cell.style.height = "100%"
        cell.classList.add("focused")

        var maximized = false
        cell.addEventListener("transitionend", { ev ->
            if (maximized) return@addEventListener
            if ((ev.target as? HTMLElement) !== cell) return@addEventListener
            maximized = true
            cell.classList.remove("maximizing"); cell.classList.add("maximized")
            cell.style.removeProperty("top"); cell.style.removeProperty("left")
            cell.style.removeProperty("width"); cell.style.removeProperty("height")
            cell.style.removeProperty("position"); cell.style.removeProperty("z-index")
            markPaneFocused(cell)
            terminals[paneId]?.term?.focus(); fitVisible()
        })
    } else {
        cell.classList.add("maximized"); backdrop.classList.add("visible")
        markPaneFocused(cell); terminals[paneId]?.term?.focus(); fitVisible()
    }

    val btn = cell.querySelector(".pane-maximize-btn") as? HTMLElement
    btn?.innerHTML = ICON_RESTORE; btn?.setAttribute("title", "Restore pane")
}
