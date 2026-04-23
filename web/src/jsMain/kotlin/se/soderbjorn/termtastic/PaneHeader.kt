/**
 * Pane header bar component for the Termtastic web frontend.
 *
 * Builds the header bar that appears at the top of each pane, containing:
 * - A renameable title (hover + click or double-click to rename)
 * - A connection status dot (for terminal panes)
 * - Action buttons: maximize/restore, copy path, reformat, close
 *
 * The "New window" action lives in the top toolbar (see [main]); the header
 * itself no longer carries a new-window button. The header also supports
 * drag-and-drop for pane reordering (via [attachPaneTabDrag]).
 *
 * @see buildPaneHeader
 * @see buildLeafCell
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.encodeToString
import org.w3c.dom.HTMLElement

/** Material Symbols "open_in_full": two diagonal arrows pointing outward. */
val ICON_MAXIMIZE = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 4h6v6"/><path d="M10 20H4v-6"/><line x1="20" y1="4" x2="14" y2="10"/><line x1="4" y1="20" x2="10" y2="14"/></svg>"""
/** Material Symbols "close_fullscreen": two diagonal arrows pointing inward. */
val ICON_RESTORE = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 14h6v6"/><path d="M20 10h-6V4"/><line x1="10" y1="14" x2="4" y2="20"/><line x1="14" y1="10" x2="20" y2="4"/></svg>"""

/** Material Symbols "content_copy": foreground page with a back page peeking out. */
private val ICON_COPY = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="8" y="8" width="13" height="13" rx="1.5"/><path d="M16 8V4.5A1.5 1.5 0 0 0 14.5 3H4.5A1.5 1.5 0 0 0 3 4.5v10A1.5 1.5 0 0 0 4.5 16H8"/></svg>"""
private val ICON_REFORMAT = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="5" width="18" height="14" rx="1.5"/><polyline points="7 10 4 12 7 14"/><polyline points="17 10 20 12 17 14"/></svg>"""
/** Material Symbols "close": an X glyph. Shared between pane headers and the tab-bar overflow menu. */
internal val ICON_CLOSE = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="6" y1="6" x2="18" y2="18"/><line x1="18" y1="6" x2="6" y2="18"/></svg>"""
/** SVG icon for the "Create worktree" button: a git branch with a plus sign. */
private val ICON_WORKTREE = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="6" cy="6" r="2"/><circle cx="6" cy="18" r="2"/><circle cx="18" cy="12" r="2"/><path d="M6 8v2c0 2.2 1.8 4 4 4h4"/><line x1="6" y1="8" x2="6" y2="16"/><line x1="21" y1="9" x2="21" y2="15"/><line x1="18" y1="12" x2="24" y2="12"/></svg>"""
/** Material Symbols "palette": a painter's palette with four colour wells. */
private val ICON_PALETTE = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22a10 10 0 1 1 10-10c0 2.5-2 4-4.5 4H16a2 2 0 0 0-1.5 3.3A2 2 0 0 1 13 22Z"/><circle cx="7.5" cy="10.5" r="1"/><circle cx="12" cy="7" r="1"/><circle cx="16.5" cy="10.5" r="1"/></svg>"""

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
    isLink: Boolean = false,
    maximized: Boolean = false,
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
            val animTarget: HTMLElement? = if (cell?.parentElement?.classList?.contains("floating-pane") == true) {
                cell.parentElement as? HTMLElement
            } else cell
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

    // Pane-scoped action buttons (operating on this pane's type/context)
    // come first, followed by a [pane-actions-sep] spacer, then the
    // window-level buttons (pop out, maximize, close).
    actions.appendChild(makeIconBtn("Create worktree", ICON_WORKTREE, { _ ->
        launchCmd(WindowCommand.GetWorktreeDefaults(paneId = paneId))
    }))

    // Colour-scheme picker — opens the pane palette dropdown styled like
    // the theme editor's section picker. First item is "Default" to clear
    // the override; then Favorites / Custom / Default groups.
    actions.appendChild(makeIconBtn("Color scheme", ICON_PALETTE, { ev ->
        openPanePaletteMenu(ev.currentTarget as HTMLElement, paneId)
    }))

    if (sessionId != null) {
        actions.appendChild(makeIconBtn("Reformat", ICON_REFORMAT, { _ ->
            val entry = terminals[paneId] ?: return@makeIconBtn
            forceReassert(entry)
        }, "reformat"))
    }

    if (fileBrowserPaneStates.containsKey(paneId)) {
        actions.appendChild(makeIconBtn("Copy path", ICON_COPY, { _ ->
            val rel = fileBrowserPaneStates[paneId]?.selectedRelPath ?: return@makeIconBtn
            window.asDynamic().navigator.clipboard.writeText(rel)
        }))
    }

    val sep = document.createElement("div") as HTMLElement
    sep.className = "pane-actions-sep"
    actions.appendChild(sep)

    // The authoritative maximized state lives on the server; the flag flows
    // in through [maximized] so the icon and tooltip render correctly on the
    // very first paint (before the pane is attached to the DOM, so we can't
    // read the `.maximized` class off an ancestor here). Clicking dispatches
    // [ToggleMaximized]; the server flips the flag and re-pushes the config,
    // which triggers a full pane rebuild with the new icon/tooltip.
    val maxBtn = makeIconBtn(
        if (maximized) "Restore pane" else "Maximize pane",
        if (maximized) ICON_RESTORE else ICON_MAXIMIZE,
        { _ -> launchCmd(WindowCommand.ToggleMaximized(paneId = paneId)) },
        "pane-maximize-btn",
    )
    actions.appendChild(maxBtn)

    actions.appendChild(makeIconBtn("Close pane", ICON_CLOSE, { _ -> performClose() }, "close"))
    header.appendChild(actions)
    return header
}
