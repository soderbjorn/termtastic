package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import org.w3c.dom.HTMLElement

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
        fun walkTree(node: dynamic): dynamic? {
            if (node == null) return null
            if (node.kind == "leaf") return if ((node.id as String) == paneId) node else null
            return walkTree(node.first) ?: walkTree(node.second)
        }
        for (tab in tabsArr) {
            val treeHit = walkTree(tab.root)
            if (treeHit != null) return Pair(treeHit, false)
            val floats = tab.floating as? Array<dynamic> ?: emptyArray()
            for (fp in floats) {
                if ((fp.leaf.id as String) == paneId) return Pair(fp.leaf, false)
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
                    applyPaneStatusClasses()
                }
                is WindowEnvelope.State -> {
                    val sid = sessionId; val c = cell
                    if (sid != null && c != null) {
                        val state = envelope.states[sid]
                        val dot = c.querySelector(".pane-state-dot[data-session='$sid']") as? HTMLElement
                        if (dot != null) {
                            val base = "pane-state-dot"
                            dot.className = if (state != null) "$base state-$state" else base
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
