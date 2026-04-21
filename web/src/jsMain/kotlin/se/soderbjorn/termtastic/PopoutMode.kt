/**
 * Pop-out window mode handling for the Termtastic web frontend.
 *
 * When a pane is popped out into a separate Electron window, this module
 * initializes a minimal single-pane UI that shares the same [WindowSocket]
 * as the main window. It listens for config updates to find its pane in the
 * server state, renders it, and automatically closes when the pane is docked
 * back into the main window.
 *
 * @see initPopoutMode
 * @see WindowCommand.PopOut
 * @see WindowCommand.DockPoppedOut
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import org.w3c.dom.HTMLElement

/**
 * Initializes the pop-out window mode for a single pane.
 *
 * Sets up a coroutine that collects envelopes from the shared [WindowSocket]
 * and renders the target pane (identified by [popoutPaneIdParam]) in isolation.
 * Handles config updates (to render the pane and track title changes), UI settings
 * changes, session state updates, and pane content messages.
 *
 * Automatically closes the window when the pane is docked back into the main window
 * (i.e., it disappears from the poppedOut array in the config).
 *
 * Called by [start] when a `popout` URL parameter is present.
 */
fun initPopoutMode() {
    val paneId = popoutPaneIdParam ?: return

    val wrap = document.getElementById("terminal-wrap") as? HTMLElement
    wrap?.innerHTML = ""

    val placeholder = document.createElement("div") as HTMLElement
    placeholder.className = "popout-placeholder"
    placeholder.textContent = "Loading\u2026"
    (wrap ?: document.body as HTMLElement).appendChild(placeholder)

    var cell: HTMLElement? = null
    var sessionId: String? = null
    var seenAsPopped = false

    fun findPaneInConfig(cfg: dynamic): Pair<dynamic, Boolean>? {
        val tabsArr = cfg.tabs as? Array<dynamic> ?: return null
        for (tab in tabsArr) {
            val popped = tab.poppedOut as? Array<dynamic> ?: emptyArray()
            for (po in popped) {
                if ((po.leaf.id as String) == paneId) return Pair(po.leaf, true)
            }
        }
        for (tab in tabsArr) {
            val panes = tab.panes as? Array<dynamic> ?: emptyArray()
            for (p in panes) {
                if ((p.leaf.id as String) == paneId) return Pair(p.leaf, false)
            }
        }
        return null
    }

    // Collect envelopes from the shared WindowSocket for the popout pane.
    GlobalScope.launch {
        windowSocket.envelopes.collect { envelope ->
            when (envelope) {
                is WindowEnvelope.PendingApproval -> showPendingApprovalOverlay()
                is WindowEnvelope.Config -> {
                    hidePendingApprovalOverlay()
                    val json = windowJson.encodeToString(envelope.config)
                    val parsed = js("JSON.parse(json)")
                    val hit = findPaneInConfig(parsed)
                    val leaf = hit?.first
                    val isPopped = hit?.second == true
                    if (leaf != null) {
                        if (cell == null) {
                            val built = buildLeafCell(leaf, popoutMode = true)
                            built.classList.add("popout-cell")
                            cell = built
                            sessionId = (leaf.content?.sessionId as? String) ?: (leaf.sessionId as? String)
                            placeholder.remove()
                            (wrap ?: document.body as HTMLElement).appendChild(built)
                        }
                        if (isPopped) {
                            seenAsPopped = true
                            val newTitle = leaf.title as? String
                            if (newTitle != null) {
                                val titleEl = cell?.querySelector(".terminal-title") as? HTMLElement
                                if (titleEl != null && titleEl.textContent != newTitle) titleEl.textContent = newTitle
                                document.title = newTitle
                            }
                        } else if (seenAsPopped) { window.close() }
                    } else if (seenAsPopped) { window.close() }
                }
                is WindowEnvelope.UiSettings -> {
                    applyAll()
                }
                is WindowEnvelope.State -> {
                    val sid = sessionId; val c = cell
                    if (sid != null && c != null) {
                        val state = envelope.states[sid]
                        val spinner = c.querySelector(".pane-status-spinner[data-session='$sid']") as? HTMLElement
                        if (spinner != null) {
                            applySpinnerState(spinner, "pane-status-spinner spinner-header", state)
                        }
                    }
                }
                else -> {
                    val json = windowJson.encodeToString(envelope)
                    val parsed = js("JSON.parse(json)")
                    handlePaneContentMessage(parsed.type as? String, parsed)
                }
            }
        }
    }

    window.addEventListener("resize", { fitVisible() })
}
