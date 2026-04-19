/**
 * Pane header bar component for the Termtastic web frontend.
 *
 * Builds the header bar that appears at the top of each pane, containing:
 * - A renameable title (hover + click or double-click to rename)
 * - A connection status dot (for terminal panes)
 * - Action buttons: split/float/dock, maximize/restore, copy path, reformat, close
 * - A split flyout with compass-style directional split buttons
 *
 * The header also supports drag-and-drop for pane reordering (via [attachPaneTabDrag]).
 *
 * @see buildPaneHeader
 * @see buildLeafCell
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.encodeToString
import org.w3c.dom.HTMLElement

/** SVG icon for the maximize button. Shared with [LayoutBuilder] for the restore animation. */
val ICON_MAXIMIZE = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="1.5"/></svg>"""
/** SVG icon for the restore button, shown when a pane is maximized. */
val ICON_RESTORE = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="6" y="3" width="15" height="15" rx="1.5"/><path d="M6 7H4.5A1.5 1.5 0 0 0 3 8.5V21a1.5 1.5 0 0 0 1.5 1.5H17A1.5 1.5 0 0 0 18.5 21v-1.5"/></svg>"""

private val ICON_SPLIT = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="16" rx="1.5"/><line x1="12" y1="4" x2="12" y2="20"/></svg>"""
private val ICON_FLOAT = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="6" width="16" height="13" rx="1.5"/><line x1="4" y1="9" x2="20" y2="9"/></svg>"""
private val ICON_DOCK = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="1.5"/><line x1="3" y1="9" x2="21" y2="9"/></svg>"""
private val ICON_POP_OUT = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 4h6v6"/><line x1="10" y1="14" x2="20" y2="4"/><path d="M20 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1h5"/></svg>"""
private val ICON_COPY = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="11" height="11" rx="1.5"/><path d="M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1"/></svg>"""
private val ICON_REFORMAT = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="5" width="18" height="14" rx="1.5"/><polyline points="7 10 4 12 7 14"/><polyline points="17 10 20 12 17 14"/></svg>"""
private val ICON_CLOSE = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="6" y1="6" x2="18" y2="18"/><line x1="18" y1="6" x2="6" y2="18"/></svg>"""
/** SVG icon for the "Create worktree" button: a git branch with a plus sign. */
private val ICON_WORKTREE = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="6" cy="6" r="2"/><circle cx="6" cy="18" r="2"/><circle cx="18" cy="12" r="2"/><path d="M6 8v2c0 2.2 1.8 4 4 4h4"/><line x1="6" y1="8" x2="6" y2="16"/><line x1="21" y1="9" x2="21" y2="15"/><line x1="18" y1="12" x2="24" y2="12"/></svg>"""

/**
 * Creates a small icon button element for use in pane headers and flyout menus.
 *
 * @param titleText the tooltip text for the button
 * @param svg the SVG markup for the icon
 * @param onClick click handler, receives the click event
 * @param extraClass optional additional CSS class(es) to add
 * @return the button HTMLElement
 */
private fun makeIconBtn(titleText: String, svg: String, onClick: (dynamic) -> Unit, extraClass: String = ""): HTMLElement {
    val btn = document.createElement("button") as HTMLElement
    btn.className = "pane-action-btn" + (if (extraClass.isNotEmpty()) " $extraClass" else "")
    btn.setAttribute("type", "button")
    btn.setAttribute("title", titleText)
    btn.innerHTML = svg
    btn.addEventListener("click", { ev ->
        ev.stopPropagation()
        onClick(ev)
    })
    return btn
}

/**
 * Builds the complete pane header bar element with title, status dot, and action buttons.
 *
 * The title supports inline renaming: hover for 1 second to arm, then click to edit,
 * or double-click at any time. The header includes a split/float flyout menu,
 * maximize/restore button, and close button (with confirmation dialog for linked sessions).
 *
 * @param paneId the unique pane identifier
 * @param title the display title for the pane
 * @param sessionId the PTY session ID (null for non-terminal panes); used for the status spinner
 * @param popoutMode true if rendering in a pop-out window (shows "Dock" instead of split options)
 * @param isLink true if this pane is a linked view of another terminal session
 * @param extraControls additional control elements to insert before the action buttons
 * @return the header HTMLElement
 * @see buildLeafCell
 * @see startRename
 */
fun buildPaneHeader(
    paneId: String,
    title: String,
    sessionId: String? = null,
    popoutMode: Boolean = false,
    isLink: Boolean = false,
    extraControls: List<HTMLElement> = emptyList(),
): HTMLElement {
    val header = document.createElement("div") as HTMLElement
    header.className = "terminal-header"

    val titleEl = document.createElement("div") as HTMLElement
    titleEl.className = "terminal-title"
    titleEl.textContent = title
    titleEl.setAttribute("title", "Hover, then click to rename")

    var armTimer: Int = -1
    fun disarm() {
        if (armTimer != -1) { window.clearTimeout(armTimer); armTimer = -1 }
        titleEl.classList.remove("armed")
    }
    titleEl.addEventListener("mouseenter", { _ ->
        disarm()
        armTimer = window.setTimeout({ armTimer = -1; titleEl.classList.add("armed") }, 1000)
    })
    titleEl.addEventListener("mouseleave", { _ -> disarm() })
    titleEl.addEventListener("click", { ev ->
        if (!titleEl.classList.contains("armed")) return@addEventListener
        ev.stopPropagation()
        disarm()
        startRename(titleEl, paneId)
    })
    titleEl.addEventListener("dblclick", { ev ->
        ev.stopPropagation(); ev.preventDefault(); disarm()
        startRename(titleEl, paneId)
    })
    if (sessionId != null) {
        val spinner = document.createElement("span") as HTMLElement
        spinner.className = "pane-status-spinner spinner-header"
        spinner.setAttribute("data-session", sessionId)
        header.appendChild(spinner)
    }
    header.appendChild(titleEl)

    val spacer = document.createElement("div") as HTMLElement
    spacer.className = "terminal-header-spacer"
    header.appendChild(spacer)

    for (ctrl in extraControls) header.appendChild(ctrl)

    val actions = document.createElement("div") as HTMLElement
    actions.className = "pane-actions"
    actions.addEventListener("mousedown", { ev -> ev.stopPropagation(); (ev.currentTarget as? HTMLElement)?.asDynamic()?.closest(".terminal-cell")?.let { cell -> markPaneFocused(cell as HTMLElement) } })

    fun performClose() {
        val linkedCount = if (!isLink) countPanesForSession(sessionId) else 0
        val hasLinks = linkedCount > 1
        val msg = if (hasLinks) {
            "This terminal session has <strong>${linkedCount - 1} linked pane${if (linkedCount > 2) "s" else ""}</strong> " +
                "that will also be closed."
        } else {
            "Are you sure you want to close <strong>${title}</strong>?"
        }
        showConfirmDialog(
            "Close pane", msg,
            if (hasLinks) "Close all" else "Close",
        ) {
            val cell = (header.parentElement as? HTMLElement)
            val splitChild = cell?.parentElement as? HTMLElement
            val animTarget: HTMLElement? = when {
                splitChild?.classList?.contains("split-child") == true -> splitChild
                cell?.parentElement?.classList?.contains("floating-pane") == true ->
                    cell.parentElement as? HTMLElement
                else -> cell
            }
            animTarget?.classList?.add("leaving")
            window.setTimeout({
                if (hasLinks && sessionId != null) {
                    launchCmd(WindowCommand.CloseSession(sessionId = sessionId))
                } else {
                    launchCmd(WindowCommand.Close(paneId = paneId))
                }
            }, 200)
        }
    }

    if (sessionId != null) {
        actions.appendChild(makeIconBtn("Reformat", ICON_REFORMAT, { _ ->
            val entry = terminals[paneId] ?: return@makeIconBtn
            forceReassert(entry)
        }, "reformat"))
    }

    if (popoutMode) {
        actions.appendChild(makeIconBtn("Dock", ICON_DOCK, { _ ->
            launchCmd(WindowCommand.DockPoppedOut(paneId = paneId))
            val api = window.asDynamic().electronApi
            if (api?.closePopout != null) api.closePopout(paneId) else window.close()
        }))
    } else {
        val floating = isPaneFloating(paneId)
        val splitWrap = document.createElement("div") as HTMLElement
        splitWrap.className = "pane-split-wrap"

        val flyout = document.createElement("div") as HTMLElement
        flyout.className = "pane-split-flyout"

        val splitBtn = makeIconBtn("Pane layout", ICON_SPLIT, { ev ->
            val btn = ev.currentTarget as HTMLElement
            val openFlyouts = document.querySelectorAll(".pane-split-flyout.open")
            for (i in 0 until openFlyouts.length) {
                val other = openFlyouts.item(i) as HTMLElement
                if (other !== flyout) other.classList.remove("open")
            }
            val opening = !flyout.classList.contains("open")
            flyout.classList.toggle("open")
            if (opening) {
                val rect = btn.asDynamic().getBoundingClientRect()
                val right = rect.right as Double
                val bottom = rect.bottom as Double
                val flyoutWidth = flyout.asDynamic().offsetWidth as Number
                val leftPos = (right - flyoutWidth.toDouble()).coerceAtLeast(4.0)
                flyout.style.left = "${leftPos}px"
                flyout.style.top = "${bottom + 4}px"
            }
        })
        splitWrap.appendChild(splitBtn)

        if (!floating) {
            val compass = document.createElement("div") as HTMLElement
            compass.className = "pane-split-compass"
            fun addDir(cssCls: String, titleTxt: String, arrowSvg: String, dir: SplitDirection) {
                val b = makeIconBtn(titleTxt, arrowSvg, { _ ->
                    flyout.classList.remove("open")
                    showPaneTypeModal(paneId, dir, null)
                }, cssCls)
                compass.appendChild(b)
            }
            val arrowUp = """<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 14 12 8 18 14"/></svg>"""
            val arrowDown = """<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 10 12 16 18 10"/></svg>"""
            val arrowLeft = """<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="14 6 8 12 14 18"/></svg>"""
            val arrowRight = """<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="10 6 16 12 10 18"/></svg>"""
            addDir("dir-up", "Split up", arrowUp, SplitDirection.Up)
            addDir("dir-left", "Split left", arrowLeft, SplitDirection.Left)
            addDir("dir-right", "Split right", arrowRight, SplitDirection.Right)
            addDir("dir-down", "Split down", arrowDown, SplitDirection.Down)
            flyout.appendChild(compass)

            val sep = document.createElement("div") as HTMLElement
            sep.className = "pane-split-sep"
            flyout.appendChild(sep)
        }

        fun addMenuItem(label: String, svg: String, onClick: () -> Unit) {
            val item = document.createElement("button") as HTMLElement
            item.className = "pane-split-item"
            item.setAttribute("type", "button")
            item.innerHTML = """<span class="pane-split-item-icon">$svg</span><span class="pane-split-item-label">$label</span>"""
            item.addEventListener("click", { ev ->
                ev.stopPropagation()
                flyout.classList.remove("open")
                onClick()
            })
            flyout.appendChild(item)
        }

        addMenuItem(
            if (floating) "Dock pane" else "Float pane",
            if (floating) ICON_DOCK else ICON_FLOAT,
        ) { launchCmd(WindowCommand.ToggleFloating(paneId = paneId)) }

        val electronApi = window.asDynamic().electronApi
        if (electronApi?.popOutPane != null) {
            addMenuItem("Pop out to window", ICON_POP_OUT) {
                launchCmd(WindowCommand.PopOut(paneId = paneId))
                electronApi.popOutPane(paneId, title)
            }
        }

        document.body?.appendChild(flyout)
        actions.appendChild(splitWrap)
    }

    if (fileBrowserPaneStates.containsKey(paneId)) {
        actions.appendChild(makeIconBtn("Copy path", ICON_COPY, { _ ->
            val rel = fileBrowserPaneStates[paneId]?.selectedRelPath ?: return@makeIconBtn
            window.asDynamic().navigator.clipboard.writeText(rel)
        }))
    }

    actions.appendChild(makeIconBtn("Create worktree", ICON_WORKTREE, { _ ->
        launchCmd(WindowCommand.GetWorktreeDefaults(paneId = paneId))
    }))

    if (!popoutMode && !isPaneFloating(paneId)) {
        val isMaximized = maximizedPaneIds.values.contains(paneId)
        val maxBtn = makeIconBtn(
            if (isMaximized) "Restore pane" else "Maximize pane",
            if (isMaximized) ICON_RESTORE else ICON_MAXIMIZE,
            { _ ->
                val tabPane = findTabPane(header)
                val tabId = tabPane?.id ?: return@makeIconBtn
                if (maximizedPaneIds[tabId] == paneId) restorePane(tabId)
                else maximizePane(paneId)
            },
            "pane-maximize-btn",
        )
        actions.appendChild(maxBtn)
    }

    actions.appendChild(makeIconBtn("Close pane", ICON_CLOSE, { _ -> performClose() }, "close"))
    header.appendChild(actions)
    return header
}
