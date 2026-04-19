/**
 * Window WebSocket connection handler for the Termtastic web frontend.
 *
 * Manages the main WebSocket connection that carries configuration updates,
 * session state changes, Claude usage data, and pane content messages (file browser
 * listings/content, git file lists/diffs). Also handles the full DOM rendering
 * pipeline via [renderConfig].
 *
 * The [connectWindow] function sets up a coroutine that collects typed
 * [WindowEnvelope] messages from the [WindowSocket] and routes them to the
 * appropriate rendering functions.
 *
 * @see connectWindow
 * @see renderConfig
 * @see handlePaneContentMessage
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import org.w3c.dom.HTMLElement

/**
 * Routes pane content messages to the appropriate rendering function.
 *
 * Handles the following message types:
 * - "fileBrowserDir": updates directory listing cache and re-renders the file tree
 * - "fileBrowserContent": updates file content and re-renders the preview panel
 * - "fileBrowserError": shows an error message in the preview panel
 * - "gitList": updates the changed files list and re-renders it
 * - "gitDiff": renders the diff in the appropriate mode (inline/split/graphical)
 * - "gitError": shows an error message in the diff panel
 *
 * @param type the message type string, or null
 * @param parsed the dynamic parsed message object
 * @return true if the message was handled, false if the type was unrecognized
 */
fun handlePaneContentMessage(type: String?, parsed: dynamic): Boolean {
    when (type) {
        "fileBrowserDir" -> {
            val paneId = parsed.paneId as String
            val dirRelPath = parsed.dirRelPath as String
            val state = fileBrowserPaneStates.getOrPut(paneId) { FileBrowserPaneState() }
            state.dirListings[dirRelPath] = parsed.entries as Array<dynamic>
            val view = fileBrowserPaneViews[paneId]
            if (view != null) renderFileBrowserTree(paneId, view, state)
        }
        "fileBrowserContent" -> {
            val paneId = parsed.paneId as String
            val relPath = parsed.relPath as String
            val html = parsed.html as String
            val kind = parsed.kind as String
            val state = fileBrowserPaneStates.getOrPut(paneId) { FileBrowserPaneState() }
            state.selectedRelPath = relPath; state.html = html; state.kind = kind
            val view = fileBrowserPaneViews[paneId]
            if (view != null) {
                renderFileBrowserContent(view, kind, html)
                renderFileBrowserTree(paneId, view, state)
            }
        }
        "fileBrowserError" -> {
            val paneId = parsed.paneId as String
            val message = parsed.message as String
            val state = fileBrowserPaneStates[paneId]
            if (state != null) { state.selectedRelPath = null; state.html = null; state.kind = null }
            val view = fileBrowserPaneViews[paneId]
            if (view != null) {
                val err = document.createElement("div") as HTMLElement
                err.className = "md-error"; err.textContent = message
                view.rendered.innerHTML = ""; view.rendered.appendChild(err)
                renderFileBrowserTree(paneId, view, state ?: FileBrowserPaneState())
            }
        }
        "gitList" -> {
            val paneId = parsed.paneId as String
            val state = gitPaneStates.getOrPut(paneId) { GitPaneState() }
            state.entries = parsed.entries
            val view = gitPaneViews[paneId]
            if (view != null) renderGitList(paneId, view, state)
        }
        "gitDiff" -> {
            val paneId = parsed.paneId as String
            val filePath = parsed.filePath as String
            val state = gitPaneStates.getOrPut(paneId) { GitPaneState() }
            state.selectedFilePath = filePath
            val view = gitPaneViews[paneId]
            if (view != null) {
                val hasOld = parsed.oldContent as? String != null
                val hasNew = parsed.newContent as? String != null
                val oneSided = !hasOld || !hasNew
                if (!oneSided && state.diffMode == "Split" && state.graphicalDiff) {
                    renderGitDiffGraphical(view.diffPane, parsed, state)
                } else if (!oneSided && state.diffMode == "Split") {
                    renderGitDiffSplit(view.diffPane, parsed)
                } else {
                    renderGitDiffInline(view.diffPane, parsed)
                }
                if (state.searchQuery.isNotEmpty() && view.searchCounter != null) {
                    state.searchMatchIndex = 0
                    performDiffSearch(view.diffPane, state.searchQuery, state, view.searchCounter!!, view.searchNavButtons)
                }
                state.diffHtml = if (state.diffMode == "Split" && state.graphicalDiff) null
                    else view.diffPane.innerHTML
                updateGitListActiveFile(view, filePath)
            }
        }
        "gitError" -> {
            val paneId = parsed.paneId as String
            val message = parsed.message as String
            val state = gitPaneStates[paneId]
            if (state != null) { state.selectedFilePath = null; state.diffHtml = null }
            val view = gitPaneViews[paneId]
            if (view != null) {
                val err = document.createElement("div") as HTMLElement
                err.className = "git-error"; err.textContent = message
                view.diffPane.innerHTML = ""; view.diffPane.appendChild(err)
                renderGitList(paneId, view, state ?: GitPaneState())
            }
        }
        else -> return false
    }
    return true
}

/**
 * Performs a full re-render of the application from a server configuration update.
 *
 * This is the main rendering entry point, called whenever a [WindowEnvelope.Config]
 * envelope is received. It:
 * 1. Removes terminals for panes that no longer exist in the config
 * 2. Rebuilds the tab bar with drag-and-drop support
 * 3. Rebuilds all tab pane content trees via [buildNode]
 * 4. Constructs floating pane layers
 * 5. Restores maximized panes, scroll positions, and focus state
 * 6. Applies entrance animations for new tabs/panes
 * 7. Re-renders the sidebar and state dots
 * 8. Triggers terminal refitting
 *
 * @param config the dynamic server configuration object
 * @see connectWindow
 * @see buildNode
 */
fun renderConfig(config: dynamic) {
    currentConfig = config
    val wrap = terminalWrapEl ?: return
    val tabBar = tabBarEl ?: return

    val savedFileBrowserScrolls = HashMap<String, Double>()
    for ((pid, view) in fileBrowserPaneViews) {
        val s = view.listBody.scrollTop; if (s > 0) savedFileBrowserScrolls[pid] = s
    }
    fileBrowserPaneViews.clear()
    val savedGitScrolls = HashMap<String, Double>()
    for ((pid, view) in gitPaneViews) {
        val s = view.listBody.scrollTop; if (s > 0) savedGitScrolls[pid] = s
    }
    gitPaneViews.clear()

    val tabsArr = config.tabs as Array<dynamic>
    if (tabsArr.isEmpty()) {
        tabBar.innerHTML = ""
        val emptyIndicator = document.createElement("div") as HTMLElement
        emptyIndicator.className = "tab-active-indicator"
        tabBar.appendChild(emptyIndicator)
        wrap.innerHTML = ""; sidebarEl?.innerHTML = ""
        previousTabIds.clear(); previousPaneIds.clear(); return
    }

    val previousTabIdsSnapshot = previousTabIds.toSet()
    val previousPaneIdsSnapshot = previousPaneIds.toSet()

    val livePanes = HashSet<String>()
    for (tab in tabsArr) {
        if (tab.root != null) collectPaneIds(tab.root, livePanes)
        val floats = tab.floating as? Array<dynamic> ?: emptyArray()
        for (fp in floats) livePanes.add(fp.leaf.id as String)
    }
    val toRemove = terminals.keys.filter { it !in livePanes }
    for (pid in toRemove) {
        val entry = terminals.remove(pid) ?: continue
        try { entry.socket?.close() } catch (_: Throwable) {}
        try { (entry.resizeObserver as? ResizeObserver)?.disconnect() } catch (_: Throwable) {}
        connectionState.remove(entry.sessionId)
    }
    updateAggregateStatus()

    val tabIds = tabsArr.map { it.id as String }
    val serverActive = config.activeTabId as? String
    val active = activeTabId.takeIf { it != null && tabIds.contains(it) }
        ?: serverActive?.takeIf { tabIds.contains(it) }
        ?: tabIds.first()
    activeTabId = active

    val savedIndicator = tabBar.querySelector(".tab-active-indicator") as? HTMLElement
    savedIndicator?.let { it.parentElement?.removeChild(it) }
    val staleMenus = document.querySelectorAll(".tab-menu-list")
    for (i in 0 until staleMenus.length) {
        val el = staleMenus.item(i) as HTMLElement; el.parentElement?.removeChild(el)
    }
    tabBar.innerHTML = ""
    val canCloseTab = tabsArr.size > 1
    val renderedTabIds = HashSet<String>()

    for (tab in tabsArr) {
        val tabId = tab.id as String
        val title = tab.title as String
        val tabWrap = document.createElement("div") as HTMLElement
        tabWrap.className = if (tabId == active) "tab-button active" else "tab-button"
        tabWrap.setAttribute("data-tab", tabId)
        tabWrap.setAttribute("draggable", "true")
        renderedTabIds.add(tabId)
        if (!firstRender && tabId !in previousTabIdsSnapshot) {
            tabWrap.classList.add("entering")
            tabWrap.addEventListener("animationend", { _ -> tabWrap.classList.remove("entering") })
        }

        val label = document.createElement("span") as HTMLElement
        label.className = "tab-label"; label.textContent = title
        tabWrap.appendChild(label)

        val tabSpinner = document.createElement("span") as HTMLElement
        tabSpinner.className = "pane-status-spinner spinner-tab"
        tabSpinner.setAttribute("data-tab-state", tabId)
        tabWrap.appendChild(tabSpinner)

        tabWrap.addEventListener("click", { ev -> ev.stopPropagation(); if (activeTabId != tabId) setActiveTab(tabId) })

        tabWrap.addEventListener("dragstart", { ev ->
            val dt = ev.asDynamic().dataTransfer ?: return@addEventListener
            dt.effectAllowed = "move"; dt.setData("text/plain", tabId)
            tabWrap.classList.add("dragging")
        })
        tabWrap.addEventListener("dragend", {
            tabWrap.classList.remove("dragging")
            val all = tabBar.querySelectorAll(".tab-button")
            for (i in 0 until all.length) (all.item(i) as HTMLElement).classList.remove("drop-before", "drop-after")
        })
        fun isPaneDrag(dynamicEv: dynamic): Boolean {
            val types = dynamicEv.dataTransfer?.types ?: return false
            val len = (types.length as Number).toInt()
            for (i in 0 until len) { if (types[i] == "application/x-termtastic-pane") return true }; return false
        }
        tabWrap.addEventListener("dragover", { ev ->
            ev.preventDefault(); ev.asDynamic().dataTransfer.dropEffect = "move"
            if (isPaneDrag(ev.asDynamic())) {
                tabWrap.classList.add("drop-pane"); tabWrap.classList.remove("drop-before", "drop-after"); return@addEventListener
            }
            val rect = tabWrap.asDynamic().getBoundingClientRect()
            val midpoint = (rect.left as Double) + (rect.width as Double) / 2.0
            val before = (ev.asDynamic().clientX as Double) < midpoint
            if (before) { tabWrap.classList.add("drop-before"); tabWrap.classList.remove("drop-after") }
            else { tabWrap.classList.add("drop-after"); tabWrap.classList.remove("drop-before") }
        })
        tabWrap.addEventListener("dragleave", { tabWrap.classList.remove("drop-before", "drop-after", "drop-pane") })
        tabWrap.addEventListener("drop", { ev ->
            ev.preventDefault(); ev.stopPropagation()
            tabWrap.classList.remove("drop-before", "drop-after", "drop-pane")
            val dt = ev.asDynamic().dataTransfer ?: return@addEventListener
            val droppedPaneId = dt.getData("application/x-termtastic-pane") as? String
            if (!droppedPaneId.isNullOrEmpty()) {
                launchCmd(WindowCommand.MovePaneToTab(paneId = droppedPaneId, targetTabId = tabId))
                return@addEventListener
            }
            val sourceId = dt.getData("text/plain") as? String
            if (sourceId.isNullOrEmpty() || sourceId == tabId) return@addEventListener
            if (sourceId.startsWith("pane:")) return@addEventListener
            val rect = tabWrap.asDynamic().getBoundingClientRect()
            val midpoint = (rect.left as Double) + (rect.width as Double) / 2.0
            val before = (ev.asDynamic().clientX as Double) < midpoint
            val snapshot = HashMap<String, Double>()
            val all = tabBar.querySelectorAll(".tab-button")
            for (i in 0 until all.length) {
                val el = all.item(i) as HTMLElement
                val tid = el.getAttribute("data-tab") ?: continue
                snapshot[tid] = (el.asDynamic().getBoundingClientRect().left as Number).toDouble()
            }
            pendingTabFlip = snapshot
            launchCmd(WindowCommand.MoveTab(tabId = sourceId, targetTabId = tabId, before = before))
        })

        run {
            val menuWrap = document.createElement("div") as HTMLElement
            menuWrap.className = "tab-menu"
            val menuBtn = document.createElement("button") as HTMLElement
            menuBtn.className = "tab-menu-button"; menuBtn.setAttribute("type", "button")
            menuBtn.setAttribute("title", "Tab menu"); menuBtn.textContent = "\u22EF"
            val menuList = document.createElement("div") as HTMLElement
            menuList.className = "tab-menu-list"

            val renameItem = document.createElement("div") as HTMLElement
            renameItem.className = "tab-menu-item"; renameItem.textContent = "Rename tab"
            renameItem.addEventListener("click", { ev ->
                ev.stopPropagation(); menuWrap.classList.remove("open"); menuList.classList.remove("open")
                startTabRename(label, tabId)
            })
            menuList.appendChild(renameItem)

            if (canCloseTab) {
                val closeItem = document.createElement("div") as HTMLElement
                closeItem.className = "tab-menu-item"; closeItem.textContent = "Close tab"
                closeItem.addEventListener("click", { ev ->
                    ev.stopPropagation(); menuWrap.classList.remove("open"); menuList.classList.remove("open")
                    showConfirmDialog("Close tab", "Are you sure you want to close tab <strong>${title}</strong> and all its panes?", "Close") {
                        tabWrap.classList.add("leaving")
                        window.setTimeout({ launchCmd(WindowCommand.CloseTab(tabId = tabId)) }, 220)
                    }
                })
                menuList.appendChild(closeItem)
            }

            menuBtn.addEventListener("click", { ev ->
                ev.stopPropagation()
                val openWraps = document.querySelectorAll(".tab-menu.open, .pane-menu.open")
                for (i in 0 until openWraps.length) { val other = openWraps.item(i) as HTMLElement; if (other !== menuWrap) other.classList.remove("open") }
                val openLists = document.querySelectorAll(".tab-menu-list.open")
                for (i in 0 until openLists.length) { val other = openLists.item(i) as HTMLElement; if (other !== menuList) other.classList.remove("open") }
                val opening = !menuWrap.classList.contains("open")
                menuWrap.classList.toggle("open"); menuList.classList.toggle("open")
                if (opening) {
                    val listWidth = menuList.asDynamic().offsetWidth as Number
                    val rect = menuBtn.asDynamic().getBoundingClientRect()
                    val right = rect.right as Double; val bottom = rect.bottom as Double
                    val leftPos = (right - listWidth.toDouble()).coerceAtLeast(4.0)
                    menuList.style.left = "${leftPos}px"; menuList.style.top = "${bottom + 4}px"
                }
            })
            menuBtn.setAttribute("draggable", "false")
            menuBtn.addEventListener("dragstart", { ev -> ev.preventDefault(); ev.stopPropagation() })
            menuWrap.appendChild(menuBtn); tabWrap.appendChild(menuWrap)
            document.body?.appendChild(menuList)
        }
        tabBar.appendChild(tabWrap)
    }

    val indicatorEl = savedIndicator ?: (document.createElement("div") as HTMLElement).also {
        it.className = "tab-active-indicator"; it.setAttribute("aria-hidden", "true")
    }
    tabBar.appendChild(indicatorEl)

    val addBtn = document.createElement("button") as HTMLElement
    addBtn.className = "tab-add"; addBtn.setAttribute("type", "button")
    addBtn.setAttribute("title", "New tab"); addBtn.textContent = "+"
    addBtn.addEventListener("click", { ev -> ev.stopPropagation(); launchCmd(WindowCommand.AddTab) })
    tabBar.appendChild(addBtn)
    scrollActiveTabIntoView()

    wrap.innerHTML = ""
    for (tab in tabsArr) {
        val tabId = tab.id as String
        val isActive = tabId == active
        val tabPane = document.createElement("section") as HTMLElement
        tabPane.id = tabId
        tabPane.className = if (isActive) "tab-pane active" else "tab-pane"
        val rootNode = tab.root
        val floats = (tab.floating as? Array<dynamic>) ?: emptyArray()
        if (rootNode != null) tabPane.appendChild(buildNode(rootNode))
        else if (floats.isEmpty()) tabPane.appendChild(buildEmptyTabPlaceholder(tabId))
        if (floats.isNotEmpty()) {
            val layer = document.createElement("div") as HTMLElement
            layer.className = "floating-layer"
            val sorted = floats.toList().sortedBy { (it.z as Number).toDouble() }
            for (fp in sorted) layer.appendChild(buildFloatingPane(fp, tabPane))
            tabPane.appendChild(layer)
        }
        wrap.appendChild(tabPane)
    }

    val focusedPaneId = activeTabId?.let { savedFocusedPaneId(it) }
    if (focusedPaneId != null) {
        val cell = wrap.querySelector("[data-pane=\"$focusedPaneId\"]") as? HTMLElement
        cell?.classList?.add("focused")
    }

    applyAll()
    renderSidebar(config)
    updateStateIndicators(appVm.stateFlow.value.sessionStates)

    for ((tabId, paneId) in maximizedPaneIds.toMap()) {
        val cell = wrap.querySelector("[data-pane=\"$paneId\"]") as? HTMLElement
        if (cell != null) { maximizedPaneIds.remove(tabId); maximizePane(paneId, animate = false) }
        else maximizedPaneIds.remove(tabId)
    }

    if (savedGitScrolls.isNotEmpty() || savedFileBrowserScrolls.isNotEmpty()) {
        window.requestAnimationFrame {
            for ((pid, scroll) in savedGitScrolls) { gitPaneViews[pid]?.listBody?.scrollTop = scroll }
            for ((pid, scroll) in savedFileBrowserScrolls) { fileBrowserPaneViews[pid]?.listBody?.scrollTop = scroll }
        }
    }

    val freshPaneIds = HashSet<String>()
    val cells = wrap.querySelectorAll("[data-pane]")
    for (i in 0 until cells.length) {
        val cell = cells.item(i) as HTMLElement
        val pid = cell.getAttribute("data-pane") ?: continue
        freshPaneIds.add(pid)
        if (firstRender || pid in previousPaneIdsSnapshot) continue
        val splitChild = cell.parentElement as? HTMLElement
        if (splitChild?.classList?.contains("split-child") == true) {
            splitChild.classList.add("entering")
            splitChild.addEventListener("animationend", { _ -> splitChild.classList.remove("entering") })
        } else {
            val floating = cell.parentElement as? HTMLElement
            if (floating?.classList?.contains("floating-pane") == true) {
                floating.classList.add("entering")
                floating.addEventListener("animationend", { _ -> floating.classList.remove("entering") })
            }
        }
    }

    previousTabIds.clear(); previousTabIds.addAll(renderedTabIds)
    previousPaneIds.clear(); previousPaneIds.addAll(freshPaneIds)

    window.requestAnimationFrame {
        if (firstRender) snapActiveIndicator() else positionActiveIndicator()
        pendingTabFlip?.let { snapshot -> runTabFlip(snapshot); pendingTabFlip = null }
    }

    window.setTimeout({
        fitVisible(); focusFirstPaneInActiveTab()
        if (firstRender) {
            document.querySelector(".terminal-wrap.booting")?.let { (it as HTMLElement).classList.remove("booting") }
            document.querySelector(".app-header.booting")?.let { (it as HTMLElement).classList.remove("booting") }
        }
        firstRender = false
    }, 0)
    window.setTimeout({ fitVisible() }, 80)
}

/**
 * Collect typed envelopes from the WindowSocket and route them to the
 * rendering layer. The backing VMs also receive these envelopes
 * independently via their own run() loops.
 */
fun connectWindow() {
    GlobalScope.launch {
        windowSocket.envelopes.collect { envelope ->
            when (envelope) {
                is WindowEnvelope.Config -> {
                    hidePendingApprovalOverlay()
                    val json = windowJson.encodeToString(envelope.config)
                    val dynamic = js("JSON.parse(json)")
                    renderConfig(dynamic)
                }
                is WindowEnvelope.State -> {
                    updateStateIndicators(envelope.states)
                }
                is WindowEnvelope.ClaudeUsage -> {
                    val json = windowJson.encodeToString(envelope)
                    val dynamic = js("JSON.parse(json)")
                    updateClaudeUsageBadge(dynamic.usage)
                }
                is WindowEnvelope.PendingApproval -> {
                    showPendingApprovalOverlay()
                }
                is WindowEnvelope.UiSettings -> {
                    // Handled reactively: the AppBackingViewModel updates its
                    // state from this envelope, and the stateFlow collector in
                    // main.kt calls applyAll() + applyPaneStatusClasses().
                }
                else -> {
                    val json = windowJson.encodeToString(envelope)
                    val parsed = js("JSON.parse(json)")
                    handlePaneContentMessage(parsed.type as? String, parsed)
                }
            }
        }
    }
}
