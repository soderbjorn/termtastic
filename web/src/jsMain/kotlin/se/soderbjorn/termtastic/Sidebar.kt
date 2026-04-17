package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

data class LeafInfo(val paneId: String, val title: String, val sessionId: String, val contentKind: String, val isLink: Boolean)

fun collectLeaves(node: dynamic, into: MutableList<LeafInfo>) {
    if (node.kind == "leaf") {
        val kind = (node.content?.kind as? String) ?: "terminal"
        val link = node.isLink as? Boolean ?: false
        into.add(LeafInfo(node.id as String, node.title as String, node.sessionId as String, kind, link))
    } else {
        collectLeaves(node.first, into)
        collectLeaves(node.second, into)
    }
}

fun renderSidebar(config: dynamic) {
    val sidebar = sidebarEl ?: return
    sidebar.innerHTML = ""
    val tree = document.createElement("div") as HTMLElement
    tree.className = "sidebar-tree"

    val tabsArr = config.tabs as? Array<dynamic> ?: return

    for (tab in tabsArr) {
        val tabId = tab.id as String
        val tabTitle = tab.title as String
        val isActiveTab = tabId == activeTabId

        val header = document.createElement("div") as HTMLElement
        header.className = "sidebar-tab-header"
        if (isActiveTab) header.classList.add("active-tab")
        if (tabId in collapsedTabs) header.classList.add("collapsed-tree")

        val chevron = document.createElement("span") as HTMLElement
        chevron.className = "sidebar-tab-chevron"
        chevron.textContent = "\u25BE"
        header.appendChild(chevron)

        val titleSpan = document.createElement("span") as HTMLElement
        titleSpan.textContent = tabTitle
        titleSpan.style.asDynamic().overflow = "hidden"
        titleSpan.style.asDynamic().textOverflow = "ellipsis"
        titleSpan.style.asDynamic().whiteSpace = "nowrap"
        titleSpan.style.asDynamic().minWidth = "0"
        titleSpan.style.asDynamic().direction = "rtl"
        titleSpan.style.asDynamic().textAlign = "left"
        header.appendChild(titleSpan)

        val sidebarTabDot = document.createElement("span") as HTMLElement
        sidebarTabDot.className = "pane-state-dot sidebar-state-dot"
        sidebarTabDot.setAttribute("data-tab-state", tabId)
        header.appendChild(sidebarTabDot)

        header.addEventListener("click", { _ ->
            if (tabId in collapsedTabs) {
                collapsedTabs.remove(tabId)
                header.classList.remove("collapsed-tree")
                val children = header.nextElementSibling as? HTMLElement
                children?.classList?.remove("collapsed-tree")
                children?.let { it.style.maxHeight = "${it.scrollHeight}px" }
            } else {
                collapsedTabs.add(tabId)
                header.classList.add("collapsed-tree")
                val children = header.nextElementSibling as? HTMLElement
                children?.classList?.add("collapsed-tree")
            }
        })
        tree.appendChild(header)

        val leaves = mutableListOf<LeafInfo>()
        val floatingIds = HashSet<String>()
        if (tab.root != null) collectLeaves(tab.root, leaves)
        val floats = (tab.floating as? Array<dynamic>) ?: emptyArray()
        for (fp in floats) {
            val leaf = fp.leaf
            val kind = (leaf.content?.kind as? String) ?: "terminal"
            val link = leaf.isLink as? Boolean ?: false
            leaves.add(LeafInfo(leaf.id as String, leaf.title as String, leaf.sessionId as String, kind, link))
            floatingIds.add(leaf.id as String)
        }

        val children = document.createElement("div") as HTMLElement
        children.className = "sidebar-tab-children"
        if (tabId in collapsedTabs) children.classList.add("collapsed-tree")

        for ((paneId, paneTitle, sId, contentKind, isLinked) in leaves) {
            val item = document.createElement("div") as HTMLElement
            item.className = "sidebar-pane-item"
            item.setAttribute("data-pane", paneId)
            if (paneId in floatingIds) item.classList.add("floating")
            if (isActiveTab) {
                val focusedId = tab.focusedPaneId as? String
                if (focusedId == paneId) item.classList.add("active-pane")
            }

            val icon = document.createElement("span") as HTMLElement
            icon.className = "sidebar-pane-icon"
            icon.innerHTML = if (contentKind == "fileBrowser") {
                """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><path d="M2 4.5 a0.5 0.5 0 0 1 0.5 -0.5 h3.5 l1.25 1.75 h6.25 a0.5 0.5 0 0 1 0.5 0.5 v7.25 a0.5 0.5 0 0 1 -0.5 0.5 H2.5 a0.5 0.5 0 0 1 -0.5 -0.5 Z"/></svg>"""
            } else if (contentKind == "git") {
                """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><circle cx="5" cy="4" r="1.5"/><circle cx="5" cy="12" r="1.5"/><circle cx="11" cy="8" r="1.5"/><line x1="5" y1="5.5" x2="5" y2="10.5"/><path d="M5 5.5 C5 8 8 8 9.5 8"/></svg>"""
            } else if (isLinked) {
                """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><path d="M6.5 9.5a3 3 0 0 0 4.24 0l2.5-2.5a3 3 0 0 0-4.24-4.24L7.5 3.76"/><path d="M9.5 6.5a3 3 0 0 0-4.24 0l-2.5 2.5a3 3 0 0 0 4.24 4.24l1-1"/></svg>"""
            } else if (paneId in floatingIds) {
                """<svg viewBox="0 0 16 16" fill="currentColor" width="14" height="14"><rect x="2" y="1" width="12" height="10" rx="1.5" fill="none" stroke="currentColor" stroke-width="1.3"/><line x1="4" y1="8" x2="10" y2="8" stroke="currentColor" stroke-width="1.2"/><rect x="4" y="13" width="8" height="1.2" rx="0.6" fill="currentColor" opacity="0.5"/></svg>"""
            } else {
                """<svg viewBox="0 0 16 16" fill="currentColor" width="14" height="14"><rect x="1" y="2" width="14" height="12" rx="1.5" fill="none" stroke="currentColor" stroke-width="1.3"/><polyline points="4,7 6,5 4,3" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/><line x1="7" y1="7" x2="11" y2="7" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/></svg>"""
            }
            item.appendChild(icon)

            val sidebarDot = document.createElement("span") as HTMLElement
            sidebarDot.className = "pane-state-dot sidebar-state-dot"
            sidebarDot.setAttribute("data-session", sId)
            item.appendChild(sidebarDot)

            val label = document.createElement("span") as HTMLElement
            label.textContent = paneTitle
            label.style.asDynamic().overflow = "hidden"
            label.style.asDynamic().textOverflow = "ellipsis"
            label.style.asDynamic().whiteSpace = "nowrap"
            label.style.asDynamic().minWidth = "0"
            label.style.asDynamic().direction = "rtl"
            label.style.asDynamic().textAlign = "left"
            item.appendChild(label)

            item.addEventListener("click", { ev ->
                ev.stopPropagation()
                val curMax = maximizedPaneIds[tabId]
                if (curMax != null && curMax != paneId) restorePane(tabId, animate = false)
                val needsTabSwitch = activeTabId != tabId
                if (needsTabSwitch) setActiveTab(tabId)
                fun doFocus() {
                    val wrap = terminalWrapEl ?: return
                    val cellEl = wrap.querySelector("[data-pane=\"$paneId\"]") as? HTMLElement
                    val entry = terminals[paneId]
                    if (entry != null) entry.term.focus()
                    else if (cellEl != null) markPaneFocused(cellEl)
                }
                if (needsTabSwitch) window.setTimeout({ doFocus() }, 50) else doFocus()
            })
            children.appendChild(item)
        }

        if (tabId !in collapsedTabs) children.style.maxHeight = "9999px"
        tree.appendChild(children)
    }
    sidebar.appendChild(tree)
}
