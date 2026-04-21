/**
 * Modal dialog for selecting the type of a new pane in Termtastic.
 *
 * Presents a grid of pane type cards (Terminal, Terminal Link, File Browser, Git)
 * and dispatches the appropriate [WindowCommand] to create the new pane. When
 * "Terminal link" is selected, transitions to a link picker view showing live
 * xterm.js previews of existing terminal sessions.
 *
 * The modal can be triggered from:
 * - The "New window" icon in [buildPaneHeader]
 * - The "New pane" button in [buildEmptyTabPlaceholder]
 *
 * @see showPaneTypeModal
 * @see closePaneTypeModal
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLElement
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import kotlin.js.json

private val MODAL_TERMINAL_SVG =
    """<svg viewBox="0 0 32 32" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" width="40" height="40"><rect x="3" y="5" width="26" height="22" rx="2"/><polyline points="9,13 13,16 9,19"/><line x1="15" y1="20" x2="22" y2="20"/></svg>"""
private val MODAL_FILE_BROWSER_SVG =
    """<svg viewBox="0 0 32 32" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" width="40" height="40"><path d="M4 9 a1 1 0 0 1 1-1 h7 l2 3 h13 a1 1 0 0 1 1 1 v13 a1 1 0 0 1 -1 1 H5 a1 1 0 0 1 -1 -1 Z"/><line x1="9" y1="17" x2="23" y2="17"/><line x1="9" y1="21" x2="19" y2="21"/></svg>"""
private val MODAL_GIT_SVG =
    """<svg viewBox="0 0 32 32" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" width="40" height="40"><circle cx="10" cy="8" r="2.5"/><circle cx="10" cy="24" r="2.5"/><circle cx="22" cy="16" r="2.5"/><line x1="10" y1="10.5" x2="10" y2="21.5"/><path d="M10 10.5 C10 16 16 16 19.5 16"/></svg>"""
private val MODAL_LINK_SVG =
    """<svg viewBox="0 0 32 32" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" width="40" height="40"><path d="M13 19a5 5 0 0 0 7.07 0l4-4a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M19 13a5 5 0 0 0-7.07 0l-4 4a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>"""

/**
 * Closes all live terminal preview WebSockets and disposes their xterm.js instances.
 *
 * Called when the pane type modal is closed or when navigating away from the
 * link picker view, to clean up resources created for the preview thumbnails.
 */
fun disposeAllPreviews() {
    for (entry in previewEntries) {
        try { (entry.socket as WebSocket).close() } catch (_: Throwable) {}
        try { entry.term.asDynamic().dispose() } catch (_: Throwable) {}
    }
    previewEntries.clear()
}

/**
 * Closes the pane type modal, disposing previews and cleaning up the Escape key handler.
 *
 * @see showPaneTypeModal
 */
fun closePaneTypeModal() {
    disposeAllPreviews()
    modalEscHandler?.let { document.removeEventListener("keydown", it) }
    modalEscHandler = null
    document.getElementById("pane-type-modal")?.remove()
}

/**
 * Opens the pane type selection modal for a specific tab.
 *
 * Shows a card grid with options: Terminal, Terminal Link, File Browser, and Git.
 * Selecting "Terminal link" transitions to a picker showing live previews of
 * existing terminal sessions. Only one modal can be open at a time.
 *
 * @param emptyTabId the tab that will receive the new pane (every pane creation
 *   flow is tab-scoped now; the parameter name is kept for backwards-compat
 *   with callers)
 * @param anchorPaneId optional pane id used to inherit cwd context when a new
 *   pane is created from within an existing pane's new-window button
 * @see closePaneTypeModal
 * @see buildPaneHeader
 */
fun showPaneTypeModal(
    emptyTabId: String,
    anchorPaneId: String? = null,
) {
    if (document.getElementById("pane-type-modal") != null) return

    val overlay = document.createElement("div") as HTMLElement
    overlay.id = "pane-type-modal"
    overlay.className = "pane-modal-overlay"

    val card = document.createElement("div") as HTMLElement
    card.className = "pane-modal"

    val closeBtn = document.createElement("button") as HTMLElement
    closeBtn.className = "pane-modal-close"
    closeBtn.innerHTML = "&times;"
    (closeBtn.asDynamic()).type = "button"
    closeBtn.addEventListener("click", { ev: Event -> ev.stopPropagation(); closePaneTypeModal() })
    card.appendChild(closeBtn)

    val titleEl = document.createElement("h2") as HTMLElement
    titleEl.className = "pane-modal-title"
    titleEl.textContent = "New pane"
    card.appendChild(titleEl)

    val body = document.createElement("div") as HTMLElement
    body.className = "pane-modal-body"
    card.appendChild(body)

    fun makeTypeCard(label: String, svg: String, onClick: () -> Unit): HTMLElement {
        val btn = document.createElement("button") as HTMLElement
        btn.className = "pane-type-card"
        (btn.asDynamic()).type = "button"
        val iconWrap = document.createElement("span") as HTMLElement
        iconWrap.className = "pane-type-icon"; iconWrap.innerHTML = svg
        val labelSpan = document.createElement("span") as HTMLElement
        labelSpan.className = "pane-type-label"; labelSpan.textContent = label
        btn.appendChild(iconWrap); btn.appendChild(labelSpan)
        btn.addEventListener("click", { ev: Event -> ev.stopPropagation(); onClick() })
        return btn
    }

    card.addEventListener("click", { ev: Event -> ev.stopPropagation() })

    data class TermLeaf(val paneId: String, val leafTitle: String, val sessionId: String)
    data class TabGroup(val tabTitle: String, val leaves: List<TermLeaf>)

    fun collectTerminalLeaves(): List<TabGroup> {
        val cfg = currentConfig ?: return emptyList()
        val tabsArr = (cfg.tabs as? Array<dynamic>) ?: return emptyList()
        val groups = mutableListOf<TabGroup>()
        for (tab in tabsArr) {
            val tabTitle = tab.title as String
            val leaves = mutableListOf<TermLeaf>()
            val panes = tab.panes as? Array<dynamic> ?: emptyArray()
            for (p in panes) {
                val leaf = p.leaf
                val kind = (leaf.content?.kind as? String) ?: "terminal"
                val sid = leaf.sessionId as String
                if (kind == "terminal" && sid.isNotEmpty()) {
                    leaves.add(TermLeaf(leaf.id as String, leaf.title as String, sid))
                }
            }
            if (leaves.isNotEmpty()) groups.add(TabGroup(tabTitle, leaves))
        }
        return groups
    }

    fun showLinkPicker() {
        disposeAllPreviews()
        body.innerHTML = ""
        val backBtn = document.createElement("button") as HTMLElement
        backBtn.className = "pane-modal-back"
        (backBtn.asDynamic()).type = "button"
        backBtn.textContent = "\u2190 Back"
        backBtn.addEventListener("click", { _: Event ->
            closePaneTypeModal()
            showPaneTypeModal(emptyTabId = emptyTabId, anchorPaneId = anchorPaneId)
        })
        body.appendChild(backBtn)
        titleEl.textContent = "Link to terminal"

        val groups = collectTerminalLeaves()
        if (groups.isEmpty()) {
            val emptyMsg = document.createElement("p") as HTMLElement
            emptyMsg.className = "pane-modal-empty"
            emptyMsg.textContent = "No terminal sessions to link to"
            body.appendChild(emptyMsg); return
        }

        val scrollContainer = document.createElement("div") as HTMLElement
        scrollContainer.className = "link-picker-scroll"

        for (group in groups) {
            val section = document.createElement("div") as HTMLElement
            section.className = "link-picker-section"
            val sectionHeader = document.createElement("div") as HTMLElement
            sectionHeader.className = "link-picker-tab-header"
            sectionHeader.innerHTML = """<span class="link-picker-tab-arrow">&#9660;</span> ${group.tabTitle}"""
            val contentDiv = document.createElement("div") as HTMLElement
            contentDiv.className = "link-picker-tab-content"
            sectionHeader.addEventListener("click", { _: Event ->
                val collapsed = contentDiv.style.display == "none"
                contentDiv.style.display = if (collapsed) "" else "none"
                sectionHeader.querySelector(".link-picker-tab-arrow")?.let { arrow ->
                    (arrow as HTMLElement).innerHTML = if (collapsed) "&#9660;" else "&#9654;"
                }
            })

            val grid = document.createElement("div") as HTMLElement
            grid.className = "link-picker-grid"

            for (leaf in group.leaves) {
                val leafCard = document.createElement("div") as HTMLElement
                leafCard.className = "link-picker-card"
                val cardTitle = document.createElement("div") as HTMLElement
                cardTitle.className = "link-picker-card-title"
                cardTitle.textContent = leaf.leafTitle
                leafCard.appendChild(cardTitle)

                val previewContainer = document.createElement("div") as HTMLElement
                previewContainer.className = "link-preview-terminal"
                val previewInner = document.createElement("div") as HTMLElement
                previewInner.className = "link-preview-inner"
                previewContainer.appendChild(previewInner)

                val previewTerm = Terminal(json(
                    "cursorBlink" to false, "disableStdin" to true,
                    "fontFamily" to "Menlo, Monaco, 'Courier New', monospace",
                    "fontSize" to 7, "scrollback" to 100, "theme" to buildXtermTheme()
                ))
                val previewFit = FitAddon()
                previewTerm.loadAddon(previewFit)
                previewTerm.open(previewInner)
                previewTerm.options.theme = buildXtermTheme()
                leafCard.appendChild(previewContainer)

                val previewUrl = "$proto://${window.location.host}/pty/${leaf.sessionId}?$authQueryParam"
                val previewSocket = WebSocket(previewUrl)
                previewSocket.asDynamic().binaryType = "arraybuffer"
                previewSocket.onmessage = { event ->
                    val data = event.asDynamic().data
                    if (data !is String) previewTerm.write(Uint8Array(data as ArrayBuffer))
                }
                previewSocket.onopen = { _: Event ->
                    try { safeFit(previewTerm, previewFit) } catch (_: Throwable) {}
                }
                previewEntries.add(json("term" to previewTerm, "fit" to previewFit, "socket" to previewSocket))
                window.setTimeout({ try { safeFit(previewTerm, previewFit) } catch (_: Throwable) {} }, 100)

                leafCard.addEventListener("click", { _: Event ->
                    closePaneTypeModal()
                    launchCmd(WindowCommand.AddLinkToTab(tabId = emptyTabId, targetSessionId = leaf.sessionId))
                })
                grid.appendChild(leafCard)
            }
            contentDiv.appendChild(grid)
            section.appendChild(sectionHeader)
            section.appendChild(contentDiv)
            scrollContainer.appendChild(section)
        }
        body.appendChild(scrollContainer)
    }

    fun showTypeCards() {
        body.innerHTML = ""
        val grid = document.createElement("div") as HTMLElement
        grid.className = "pane-type-grid"

        // Forward the anchor pane id so the server can inherit its cwd and
        // the new pane opens in the user's current directory. When the modal
        // is triggered from an empty-tab placeholder there is no anchor, in
        // which case the new pane falls back to $HOME.
        grid.appendChild(makeTypeCard("Terminal", MODAL_TERMINAL_SVG) {
            closePaneTypeModal()
            launchCmd(WindowCommand.AddPaneToTab(tabId = emptyTabId, anchorPaneId = anchorPaneId))
        })
        grid.appendChild(makeTypeCard("Terminal link", MODAL_LINK_SVG) { showLinkPicker() })
        grid.appendChild(makeTypeCard("File Browser", MODAL_FILE_BROWSER_SVG) {
            closePaneTypeModal()
            launchCmd(WindowCommand.AddFileBrowserToTab(tabId = emptyTabId, anchorPaneId = anchorPaneId))
        })
        grid.appendChild(makeTypeCard("Git", MODAL_GIT_SVG) {
            closePaneTypeModal()
            launchCmd(WindowCommand.AddGitToTab(tabId = emptyTabId, anchorPaneId = anchorPaneId))
        })
        body.appendChild(grid)
    }

    showTypeCards()
    overlay.appendChild(card)
    overlay.addEventListener("click", { ev: Event -> if (ev.target === overlay) closePaneTypeModal() })

    val escHandler: (Event) -> Unit = { ev -> if ((ev as KeyboardEvent).key == "Escape") closePaneTypeModal() }
    modalEscHandler = escHandler
    document.addEventListener("keydown", escHandler)
    document.body?.appendChild(overlay)
}
