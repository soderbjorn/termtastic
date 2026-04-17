package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.MouseEvent

class FileBrowserPaneState {
    val dirListings = HashMap<String, Array<dynamic>>()
    val expandedDirs = HashSet<String>()
    var html: String? = null
    var kind: String? = null
    var selectedRelPath: String? = null
    var fileFilter: String = ""
    var sortBy: String = "name"
}

class FileBrowserPaneView(
    val listBody: HTMLElement,
    val rendered: HTMLElement,
)

fun renderFileBrowserTree(paneId: String, view: FileBrowserPaneView, state: FileBrowserPaneState) {
    val savedScroll = view.listBody.scrollTop
    view.listBody.innerHTML = ""
    val rootEntries = state.dirListings[""]
    if (rootEntries == null) {
        val loading = document.createElement("div") as HTMLElement
        loading.className = "md-list-empty"
        loading.textContent = "Loading\u2026"
        view.listBody.appendChild(loading)
        return
    }
    if (rootEntries.isEmpty()) {
        val empty = document.createElement("div") as HTMLElement
        empty.className = "md-list-empty"
        empty.textContent = "No files"
        view.listBody.appendChild(empty)
        return
    }

    fun toggleDir(relPath: String) {
        val nowExpanded = relPath !in state.expandedDirs
        if (nowExpanded) {
            state.expandedDirs.add(relPath)
            if (state.dirListings[relPath] == null) {
                launchCmd(WindowCommand.FileBrowserListDir(paneId = paneId, dirRelPath = relPath))
            }
        } else {
            state.expandedDirs.remove(relPath)
        }
        launchCmd(WindowCommand.FileBrowserSetExpanded(paneId = paneId, dirRelPath = relPath, expanded = nowExpanded))
        renderFileBrowserTree(paneId, view, state)
    }

    fun renderDir(container: HTMLElement, entries: Array<dynamic>, depth: Int) {
        for (entry in entries) {
            val relPath = entry.relPath as String
            val name = entry.name as String
            val isDir = entry.isDir as Boolean
            val row = document.createElement("div") as HTMLElement
            row.className = "md-list-item"
            if (!isDir && relPath == state.selectedRelPath) row.classList.add("active")
            row.style.asDynamic().paddingLeft = "${4 + depth * 14}px"

            val chevron = document.createElement("span") as HTMLElement
            chevron.className = if (isDir) "md-list-chevron" else "md-list-chevron empty"
            if (isDir) {
                val expanded = relPath in state.expandedDirs
                chevron.textContent = if (expanded) "\u25BE" else "\u25B8"
                chevron.addEventListener("mousedown", { ev -> ev.stopPropagation() })
                chevron.addEventListener("click", { ev -> ev.stopPropagation(); toggleDir(relPath) })
            }
            row.appendChild(chevron)

            val icon = document.createElement("span") as HTMLElement
            icon.className = "md-list-item-icon"
            if (isDir) {
                icon.innerHTML = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><path d="M1.75 4.25a0.5 0.5 0 0 1 0.5 -0.5h3.5L7.25 5.25H13.75a0.5 0.5 0 0 1 0.5 0.5v7.5a0.5 0.5 0 0 1 -0.5 0.5H2.25a0.5 0.5 0 0 1 -0.5 -0.5z"/></svg>"""
            } else {
                icon.innerHTML = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><path d="M3 1.75h6.5L13 5.25V14.25a0.5 0.5 0 0 1 -0.5 0.5H3a0.5 0.5 0 0 1 -0.5 -0.5V2.25a0.5 0.5 0 0 1 0.5 -0.5Z"/><path d="M9.25 1.75V5.25H13"/></svg>"""
            }
            val label = document.createElement("span") as HTMLElement
            label.className = "md-list-item-name"
            label.textContent = name
            label.title = relPath
            row.appendChild(icon)
            row.appendChild(label)
            row.addEventListener("mousedown", { ev -> ev.stopPropagation() })
            row.addEventListener("click", { _ ->
                if (isDir) toggleDir(relPath)
                else launchCmd(WindowCommand.FileBrowserOpenFile(paneId = paneId, relPath = relPath))
            })
            container.appendChild(row)
            if (isDir && relPath in state.expandedDirs) {
                val children = state.dirListings[relPath]
                if (children == null) {
                    val loading = document.createElement("div") as HTMLElement
                    loading.className = "md-list-empty"
                    loading.style.asDynamic().paddingLeft = "${4 + (depth + 1) * 14}px"
                    loading.textContent = "Loading\u2026"
                    container.appendChild(loading)
                } else {
                    renderDir(container, children, depth + 1)
                }
            }
        }
    }
    renderDir(view.listBody, rootEntries, 0)
    view.listBody.scrollTop = savedScroll
}

fun renderFileBrowserContent(view: FileBrowserPaneView, kind: String?, html: String?) {
    view.rendered.innerHTML = ""
    if (kind == "Markdown") view.rendered.classList.remove("is-source")
    else view.rendered.classList.add("is-source")
    when (kind) {
        "Markdown" -> { view.rendered.innerHTML = html ?: "" }
        "Text" -> {
            val pre = document.createElement("pre") as HTMLElement
            pre.className = "file-source"
            val code = document.createElement("code") as HTMLElement
            code.innerHTML = html ?: ""
            pre.appendChild(code)
            view.rendered.appendChild(pre)
        }
        "Binary" -> {
            val placeholder = document.createElement("div") as HTMLElement
            placeholder.className = "md-rendered-empty"
            placeholder.innerHTML = """
                <div class="md-rendered-empty-icon">&#128452;</div>
                <div class="md-rendered-empty-title">Binary file</div>
                <div class="md-rendered-empty-subtitle">Preview unavailable</div>
            """.trimIndent()
            view.rendered.appendChild(placeholder)
        }
        else -> {
            val emptyState = document.createElement("div") as HTMLElement
            emptyState.className = "md-rendered-empty"
            emptyState.innerHTML = """
                <div class="md-rendered-empty-icon">&#128196;</div>
                <div class="md-rendered-empty-title">No document selected</div>
                <div class="md-rendered-empty-subtitle">Choose a file from the tree</div>
            """.trimIndent()
            view.rendered.appendChild(emptyState)
        }
    }
}

fun buildFileBrowserView(paneId: String, leaf: dynamic, headerEl: HTMLElement? = null): HTMLElement {
    val content = leaf.content
    val initialWidth = (content?.leftColumnWidthPx as? Number)?.toInt() ?: 240
    val initialSelected = content?.selectedRelPath as? String
    val persistedExpanded = content?.expandedDirs as? Array<String>
    val persistedFilter = content?.fileFilter as? String
    val persistedSort = content?.sortBy as? String

    val state = fileBrowserPaneStates.getOrPut(paneId) { FileBrowserPaneState() }
    if (initialSelected != null) state.selectedRelPath = initialSelected
    if (persistedExpanded != null) for (p in persistedExpanded) state.expandedDirs.add(p)
    if (persistedFilter != null) state.fileFilter = persistedFilter
    if (persistedSort != null) state.sortBy = persistedSort

    val outer = document.createElement("div") as HTMLElement
    outer.className = "md-view"

    val left = document.createElement("div") as HTMLElement
    left.className = "md-list"
    left.style.width = "${initialWidth.coerceIn(0, 640)}px"
    left.style.asDynamic().flexShrink = "0"

    val header = document.createElement("div") as HTMLElement
    header.className = "md-list-header"
    val titleEl = document.createElement("span") as HTMLElement
    titleEl.className = "md-list-title"
    titleEl.textContent = "Files"
    header.appendChild(titleEl)
    left.appendChild(header)

    // Header flyout controls
    val iconFilter = """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polygon points="2,3 14,3 10,8.5 10,13 6,11 6,8.5"/></svg>"""
    val iconSort = """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><line x1="3" y1="4" x2="11" y2="4"/><line x1="3" y1="8" x2="9" y2="8"/><line x1="3" y1="12" x2="7" y2="12"/><polyline points="11,10 13,12 15,10"/><line x1="13" y1="5" x2="13" y2="12"/></svg>"""
    val iconExpand = """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="3,6 8,11 13,6"/></svg>"""
    val iconCollapse = """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="3,10 8,5 13,10"/></svg>"""

    // Filter flyout
    val filterWrap = document.createElement("div") as HTMLElement
    filterWrap.className = "pane-flyout-wrap"
    filterWrap.addEventListener("mousedown", { ev -> ev.stopPropagation() })
    val filterTrigger = document.createElement("button") as HTMLElement
    filterTrigger.className = "pane-action-btn"
    filterTrigger.setAttribute("type", "button")
    filterTrigger.setAttribute("title", "Filter")
    filterTrigger.innerHTML = iconFilter
    filterTrigger.addEventListener("click", { ev ->
        ev.stopPropagation()
        val open = document.querySelectorAll(".pane-flyout-wrap.open")
        for (i in 0 until open.length) {
            val other = open.item(i) as HTMLElement
            if (other !== filterWrap) other.classList.remove("open")
        }
        filterWrap.classList.toggle("open")
    })
    filterWrap.appendChild(filterTrigger)
    val filterFlyout = document.createElement("div") as HTMLElement
    filterFlyout.className = "pane-flyout"
    filterFlyout.addEventListener("click", { ev -> ev.stopPropagation() })

    val filterInput = document.createElement("input") as HTMLInputElement
    filterInput.className = "git-search-input"
    filterInput.type = "text"
    filterInput.placeholder = "Filter (e.g. *.md)"
    filterInput.value = state.fileFilter
    filterInput.title = "Filter files by name (glob or substring)"
    filterInput.style.width = "100%"
    filterInput.style.marginBottom = "4px"
    filterFlyout.appendChild(filterInput)

    var filterDebounce: Int? = null
    fun dispatchFilter(value: String) {
        state.fileFilter = value
        filterDebounce?.let { window.clearTimeout(it) }
        filterDebounce = window.setTimeout({
            launchCmd(WindowCommand.SetFileBrowserFilter(paneId = paneId, filter = value))
        }, 250)
    }
    filterInput.addEventListener("input", { _ -> dispatchFilter(filterInput.value) })

    val presets = listOf(
        "" to "All files", "*.md" to "Markdown", "*.{kt,kts}" to "Kotlin",
        "*.{js,ts,jsx,tsx}" to "JS / TS", "*.{py}" to "Python", "*.{java,scala,groovy}" to "JVM",
        "*.{c,h,cpp,hpp,cc}" to "C / C++", "*.{rs}" to "Rust", "*.{go}" to "Go",
        "*.{json,yaml,yml,toml,xml}" to "Config", "*.{png,jpg,jpeg,gif,svg,webp}" to "Images",
    )
    for ((value, label) in presets) {
        val item = document.createElement("div") as HTMLElement
        item.className = "pane-split-item"
        item.textContent = label
        item.addEventListener("click", { ev ->
            ev.stopPropagation()
            filterInput.value = value
            dispatchFilter(value)
            filterWrap.classList.remove("open")
        })
        filterFlyout.appendChild(item)
    }
    filterWrap.appendChild(filterFlyout)

    // Sort flyout
    val sortWrap = document.createElement("div") as HTMLElement
    sortWrap.className = "pane-flyout-wrap"
    sortWrap.addEventListener("mousedown", { ev -> ev.stopPropagation() })
    val sortTrigger = document.createElement("button") as HTMLElement
    sortTrigger.className = "pane-action-btn"
    sortTrigger.setAttribute("type", "button")
    sortTrigger.setAttribute("title", "Sort order")
    sortTrigger.innerHTML = iconSort
    sortTrigger.addEventListener("click", { ev ->
        ev.stopPropagation()
        val open = document.querySelectorAll(".pane-flyout-wrap.open")
        for (i in 0 until open.length) {
            val other = open.item(i) as HTMLElement
            if (other !== sortWrap) other.classList.remove("open")
        }
        sortWrap.classList.toggle("open")
    })
    sortWrap.appendChild(sortTrigger)
    val sortFlyout = document.createElement("div") as HTMLElement
    sortFlyout.className = "pane-flyout"
    sortFlyout.addEventListener("click", { ev -> ev.stopPropagation() })

    val sortOptions = listOf("name" to "By name", "mtime" to "By last change")
    val sortItems = HashMap<String, HTMLElement>()
    for ((value, label) in sortOptions) {
        val item = document.createElement("div") as HTMLElement
        item.className = "pane-split-item"
        if (value == state.sortBy) item.classList.add("active")
        item.innerHTML = """<span class="pane-split-item-label">$label</span><span class="check">${if (value == state.sortBy) "\u2713" else ""}</span>"""
        item.addEventListener("click", { ev ->
            ev.stopPropagation()
            state.sortBy = value
            for ((k, el) in sortItems) {
                val check = el.querySelector(".check") as? HTMLElement
                if (k == value) { el.classList.add("active"); check?.textContent = "\u2713" }
                else { el.classList.remove("active"); check?.textContent = "" }
            }
            val sortEnum = if (value == "mtime") FileBrowserSort.MTIME else FileBrowserSort.NAME
            launchCmd(WindowCommand.SetFileBrowserSort(paneId = paneId, sort = sortEnum))
            sortWrap.classList.remove("open")
        })
        sortFlyout.appendChild(item)
        sortItems[value] = item
    }
    sortWrap.appendChild(sortFlyout)

    // Expand/Collapse all
    val expandBtn = document.createElement("button") as HTMLElement
    expandBtn.className = "pane-action-btn"
    expandBtn.setAttribute("type", "button"); expandBtn.setAttribute("title", "Expand all")
    expandBtn.innerHTML = iconExpand
    expandBtn.addEventListener("mousedown", { ev -> ev.stopPropagation() })
    expandBtn.addEventListener("click", { ev -> ev.stopPropagation(); launchCmd(WindowCommand.FileBrowserExpandAll(paneId = paneId)) })

    val collapseBtn = document.createElement("button") as HTMLElement
    collapseBtn.className = "pane-action-btn"
    collapseBtn.setAttribute("type", "button"); collapseBtn.setAttribute("title", "Collapse all")
    collapseBtn.innerHTML = iconCollapse
    collapseBtn.addEventListener("mousedown", { ev -> ev.stopPropagation() })
    collapseBtn.addEventListener("click", { ev ->
        ev.stopPropagation()
        state.expandedDirs.clear()
        launchCmd(WindowCommand.FileBrowserCollapseAll(paneId = paneId))
        val v = fileBrowserPaneViews[paneId]
        if (v != null) renderFileBrowserTree(paneId, v, state)
    })

    document.addEventListener("click", { _ ->
        filterWrap.classList.remove("open")
        sortWrap.classList.remove("open")
    })

    // Refresh button
    val refreshBtn = document.createElement("button") as HTMLElement
    refreshBtn.className = "pane-action-btn"
    refreshBtn.setAttribute("type", "button"); refreshBtn.setAttribute("title", "Refresh")
    refreshBtn.innerHTML = """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M2 8a6 6 0 0 1 10.5-3.9"/><polyline points="13,2 13,5 10,5"/><path d="M14 8a6 6 0 0 1 -10.5 3.9"/><polyline points="3,14 3,11 6,11"/></svg>"""
    refreshBtn.addEventListener("mousedown", { ev -> ev.stopPropagation() })
    refreshBtn.addEventListener("click", { ev ->
        ev.stopPropagation()
        launchCmd(WindowCommand.FileBrowserListDir(paneId = paneId, dirRelPath = ""))
        for (dir in state.expandedDirs) launchCmd(WindowCommand.FileBrowserListDir(paneId = paneId, dirRelPath = dir))
    })

    if (headerEl != null) {
        val actions = headerEl.querySelector(".pane-actions") as? HTMLElement
        if (actions != null) {
            actions.parentNode?.insertBefore(filterWrap, actions)
            actions.parentNode?.insertBefore(sortWrap, actions)
            actions.parentNode?.insertBefore(expandBtn, actions)
            actions.parentNode?.insertBefore(collapseBtn, actions)
            actions.parentNode?.insertBefore(refreshBtn, actions)
            val spacer = document.createElement("div") as HTMLElement
            spacer.className = "pane-header-spacer"
            actions.parentNode?.insertBefore(spacer, actions)
        }
    }

    val listBody = document.createElement("div") as HTMLElement
    listBody.className = "md-list-body"
    left.appendChild(listBody)

    val divider = document.createElement("div") as HTMLElement
    divider.className = "md-divider"
    val right = document.createElement("div") as HTMLElement
    right.className = "md-rendered"

    outer.appendChild(left); outer.appendChild(divider); outer.appendChild(right)
    wireMarkdownAnchorLinks(right)

    val view = FileBrowserPaneView(listBody, right)
    fileBrowserPaneViews[paneId] = view
    renderFileBrowserTree(paneId, view, state)
    renderFileBrowserContent(view, state.kind, state.html)

    launchCmd(WindowCommand.FileBrowserListDir(paneId = paneId, dirRelPath = ""))
    for (dir in state.expandedDirs) {
        if (state.dirListings[dir] == null) launchCmd(WindowCommand.FileBrowserListDir(paneId = paneId, dirRelPath = dir))
    }
    if (state.selectedRelPath != null && state.html == null) {
        launchCmd(WindowCommand.FileBrowserOpenFile(paneId = paneId, relPath = state.selectedRelPath!!))
    }

    divider.addEventListener("mousedown", { ev ->
        val mev = ev as MouseEvent
        mev.preventDefault()
        divider.classList.add("dragging")
        val startX = mev.clientX; val startWidth = left.offsetWidth
        val previousBodyCursor = document.body?.style?.cursor ?: ""
        document.body?.style?.cursor = "col-resize"
        var latestPx = startWidth
        val moveListener: (org.w3c.dom.events.Event) -> Unit = { evMove ->
            val m = evMove as MouseEvent
            val w = (startWidth + (m.clientX - startX)).coerceIn(0, 640)
            latestPx = w; left.style.width = "${w}px"
        }
        lateinit var upListener: (org.w3c.dom.events.Event) -> Unit
        upListener = { _ ->
            document.removeEventListener("mousemove", moveListener)
            document.removeEventListener("mouseup", upListener)
            divider.classList.remove("dragging")
            document.body?.style?.cursor = previousBodyCursor
            launchCmd(WindowCommand.SetFileBrowserLeftWidth(paneId = paneId, px = latestPx))
        }
        document.addEventListener("mousemove", moveListener)
        document.addEventListener("mouseup", upListener)
    })

    return outer
}
