/**
 * Far-right "⋯" overflow menu for the Termtastic tab bar.
 *
 * Before this file existed, each tab carried its own hover-activated ⋯ button
 * that exposed Rename / Close for that single tab. The issue-#2 redesign
 * consolidates those affordances into a single dropdown anchored at the right
 * end of the tab bar. The dropdown operates on the currently-active tab and
 * also surfaces any tabs the user has hidden from the strip.
 *
 * The DOM structure (`.tab-bar-menu-button` + `.tab-bar-menu-list` rendered
 * to `document.body` so it escapes the tab bar's horizontal scroll area)
 * mirrors the positioning strategy of the older per-tab menus. The
 * dropdown is fixed-positioned and placed relative to the button's screen
 * rect. Clicks on any item commit and close the menu; clicks elsewhere on
 * the document dismiss the menu via the global outside-click handler in
 * `main.kt`.
 *
 * @see renderConfig — the single caller; invoked after the visible tab
 *   buttons and the `+` add button have been appended.
 * @see WindowCommand.SetTabHidden for the hide/unhide server round-trip.
 * @see WindowCommand.CloseTab / WindowCommand.RenameTab / WindowCommand.SetActiveTab
 *   for the other actions dispatched from this menu.
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

/** Material Symbols "edit": pencil. Used for the "Rename" entry. */
private val ICON_RENAME = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>"""

/** Material Symbols "visibility_off": eye with slash. Used for "Hide". */
private val ICON_HIDE = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10.94 10.94 0 0 1 12 19c-6 0-10-7-10-7a19.77 19.77 0 0 1 4.06-4.94"/><path d="M9.9 4.24A10.94 10.94 0 0 1 12 4c6 0 10 7 10 7a19.77 19.77 0 0 1-3.17 4.19"/><path d="M1 1l22 22"/><path d="M9.5 9.5a3 3 0 0 0 4.24 4.24"/></svg>"""

/** Material Symbols "visibility": eye. Used for "Unhide". */
private val ICON_UNHIDE = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7S1 12 1 12Z"/><circle cx="12" cy="12" r="3"/></svg>"""

/** Terminal icon: a window with a ">" prompt. Used for hidden terminal tabs. */
private val ICON_TAB_TERMINAL = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="16" rx="2"/><polyline points="7 9 10 12 7 15"/><line x1="12" y1="15" x2="16" y2="15"/></svg>"""

/** Folder icon. Used for hidden file-browser tabs. */
private val ICON_TAB_FILES = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z"/></svg>"""

/** Git branch icon. Used for hidden git tabs. */
private val ICON_TAB_GIT = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="6" cy="6" r="2"/><circle cx="6" cy="18" r="2"/><circle cx="18" cy="12" r="2"/><path d="M6 8v2c0 2.2 1.8 4 4 4h4"/><line x1="6" y1="8" x2="6" y2="16"/></svg>"""

/** Generic "window" icon used when a hidden tab has zero panes. */
private val ICON_TAB_EMPTY = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="16" rx="2"/></svg>"""

/**
 * Resolve the icon SVG string for a tab's dropdown entry.
 *
 * The issue (#2) calls for hidden-tab rows to show "their ordinary type-
 * dependent icon". A single tab can hold panes of mixed kinds, so this
 * function picks the first pane's `content.kind` discriminator (emitted by
 * [windowJson] from [LeafContent]) — that matches the user's intuition of
 * "whatever the tab mostly is" in the common single-pane case while giving
 * a stable choice when panes are mixed.
 *
 * Kinds map to icons as follows:
 *  - `"terminal"` (or legacy `null`) → [ICON_TAB_TERMINAL]
 *  - `"fileBrowser"`                 → [ICON_TAB_FILES]
 *  - `"git"`                         → [ICON_TAB_GIT]
 *  - empty pane list / unknown kind  → [ICON_TAB_EMPTY]
 *
 * @param tab the dynamic tab JSON, as produced by the server's WindowConfig push
 * @return a raw `<svg>` string, safe to drop into `element.innerHTML`
 * @see LeafContent
 * @see TerminalContent
 */
private fun tabIcon(tab: dynamic): String {
    val panes = tab.panes as? Array<dynamic> ?: return ICON_TAB_EMPTY
    if (panes.isEmpty()) return ICON_TAB_EMPTY
    val firstKind = panes[0].leaf?.content?.kind as? String
    return when (firstKind) {
        null, "terminal" -> ICON_TAB_TERMINAL
        "fileBrowser" -> ICON_TAB_FILES
        "git" -> ICON_TAB_GIT
        else -> ICON_TAB_EMPTY
    }
}

/**
 * Build one `.tab-bar-menu-item` row with a leading SVG icon column.
 *
 * Also used for the hidden-tabs section, so the icon slot always reserves
 * the same width regardless of whether the item is a "system" command
 * (Rename / Close / Hide) or a hidden tab. Keeping the slot present even
 * on items with no icon keeps the label column aligned.
 *
 * @param iconSvg inline `<svg>` markup, or `""` for a blank slot
 * @param labelText the visible row label
 * @param onClick click handler; receives the click [org.w3c.dom.events.Event]
 * @return the new `<div>` element (not yet attached)
 */
private fun menuRow(
    iconSvg: String,
    labelText: String,
    onClick: (dynamic) -> Unit,
): HTMLElement {
    val item = document.createElement("div") as HTMLElement
    item.className = "tab-bar-menu-item"
    val iconEl = document.createElement("span") as HTMLElement
    iconEl.className = "tab-bar-menu-icon"
    iconEl.innerHTML = iconSvg
    val labelEl = document.createElement("span") as HTMLElement
    labelEl.className = "tab-bar-menu-label"
    labelEl.textContent = labelText
    item.appendChild(iconEl)
    item.appendChild(labelEl)
    item.addEventListener("click", { ev ->
        ev.stopPropagation()
        onClick(ev)
    })
    return item
}

/**
 * Append the far-right tab-bar overflow menu (button + fixed-position
 * dropdown list) to [tabBar]. The dropdown is appended to `document.body`
 * so the bar's horizontal overflow doesn't clip it — identical to the
 * positioning strategy the old per-tab menus used.
 *
 * Menu composition:
 *  - "Rename"  — opens the inline rename UI on the active tab's label.
 *  - "Close"   — closes the active tab (only when there is more than one
 *    tab, matching `canCloseTab` elsewhere in the renderer).
 *  - "Hide" / "Unhide" — toggles [TabConfig.isHidden] on the active tab.
 *    A hidden active tab stays active (its content still renders in the
 *    wrap) so the user doesn't lose their place.
 *  - A separator and one row per hidden tab — clicking a hidden tab
 *    activates it; the user can then Unhide it if they want the strip
 *    entry back.
 *
 * Invoked once per render from [renderConfig], after the visible tab
 * buttons and `.tab-add` have been appended.
 *
 * @param tabBar the `.tab-bar` container element
 * @param tabsArr the full (visible + hidden) array of tab JSON from the
 *   server config
 * @param activeTabIdArg the id of the active tab, or null if no tab is
 *   currently active
 * @param canCloseTab whether the "Close" row should be shown (mirrors the
 *   per-tab-menu guard: at least two tabs must exist)
 * @see renderConfig
 * @see startTabRename
 * @see WindowCommand.SetTabHidden
 */
internal fun appendTabBarOverflowMenu(
    tabBar: HTMLElement,
    tabsArr: Array<dynamic>,
    activeTabIdArg: String?,
    canCloseTab: Boolean,
) {
    val menuWrap = document.createElement("div") as HTMLElement
    menuWrap.className = "tab-bar-menu"

    val menuBtn = document.createElement("button") as HTMLElement
    menuBtn.className = "tab-bar-menu-button"
    menuBtn.setAttribute("type", "button")
    menuBtn.setAttribute("title", "Tab options")
    menuBtn.textContent = "⋯"

    val menuList = document.createElement("div") as HTMLElement
    menuList.className = "tab-bar-menu-list"

    val activeTab: dynamic = tabsArr.firstOrNull { (it.id as? String) == activeTabIdArg }
    val activeIsHidden = (activeTab?.isHidden as? Boolean) == true
    val hiddenTabs = tabsArr.filter { (it.isHidden as? Boolean) == true }

    // Rename — operates on the active tab's label element in the strip, so
    // if the tab is hidden the item is still disabled (no label to edit).
    if (activeTab != null && !activeIsHidden) {
        val activeTabId = activeTab.id as String
        menuList.appendChild(menuRow(ICON_RENAME, "Rename") { _ ->
            menuWrap.classList.remove("open"); menuList.classList.remove("open")
            val labelEl = tabBar.querySelector(".tab-button[data-tab=\"$activeTabId\"] .tab-label") as? HTMLElement
            if (labelEl != null) startTabRename(labelEl, activeTabId)
        })
    }

    // Close — only when there is something to fall back on.
    if (activeTab != null && canCloseTab) {
        val activeTabId = activeTab.id as String
        val activeTitle = (activeTab.title as? String) ?: ""
        menuList.appendChild(menuRow(ICON_CLOSE, "Close") { _ ->
            menuWrap.classList.remove("open"); menuList.classList.remove("open")
            showConfirmDialog(
                "Close tab",
                "Are you sure you want to close tab <strong>${activeTitle}</strong> and all its panes?",
                "Close",
            ) {
                // Mirror the old per-tab-menu polish: add the `leaving`
                // class to trigger the CSS exit animation, then dispatch
                // the CloseTab command after the animation length so the
                // user sees the tab slide away rather than vanish.
                val btn = tabBar.querySelector(".tab-button[data-tab=\"$activeTabId\"]") as? HTMLElement
                if (btn != null) {
                    btn.classList.add("leaving")
                    window.setTimeout({ launchCmd(WindowCommand.CloseTab(tabId = activeTabId)) }, 220)
                } else {
                    launchCmd(WindowCommand.CloseTab(tabId = activeTabId))
                }
            }
        })
    }

    // Hide / Unhide — always present so the affordance is discoverable.
    if (activeTab != null) {
        val activeTabId = activeTab.id as String
        val (icon, label) = if (activeIsHidden) ICON_UNHIDE to "Unhide" else ICON_HIDE to "Hide"
        menuList.appendChild(menuRow(icon, label) { _ ->
            menuWrap.classList.remove("open"); menuList.classList.remove("open")
            launchCmd(WindowCommand.SetTabHidden(tabId = activeTabId, hidden = !activeIsHidden))
        })
    }

    // Hidden-tabs section — one row per hidden tab, preceded by a separator
    // when there are any "system" items above to separate from.
    if (hiddenTabs.isNotEmpty()) {
        val hasSystemItems = menuList.childElementCount > 0
        if (hasSystemItems) {
            val sep = document.createElement("div") as HTMLElement
            sep.className = "tab-bar-menu-separator"
            menuList.appendChild(sep)
        }
        for (tab in hiddenTabs) {
            val tabId = tab.id as String
            val title = (tab.title as? String) ?: "(untitled)"
            menuList.appendChild(menuRow(tabIcon(tab), title) { _ ->
                menuWrap.classList.remove("open"); menuList.classList.remove("open")
                // Switching to a hidden tab leaves it hidden. The menu will
                // then show "Unhide" on its next open so the user can
                // promote it back to the strip.
                setActiveTab(tabId)
            })
        }
    }

    // Toggle open/close, and dismiss other open tab-bar/pane menus first so
    // we never stack two dropdowns on screen at once.
    menuBtn.addEventListener("click", { ev ->
        ev.stopPropagation()
        val openWraps = document.querySelectorAll(".tab-bar-menu.open, .pane-menu.open")
        for (i in 0 until openWraps.length) {
            val other = openWraps.item(i) as HTMLElement
            if (other !== menuWrap) other.classList.remove("open")
        }
        val openLists = document.querySelectorAll(".tab-bar-menu-list.open")
        for (i in 0 until openLists.length) {
            val other = openLists.item(i) as HTMLElement
            if (other !== menuList) other.classList.remove("open")
        }
        val opening = !menuWrap.classList.contains("open")
        menuWrap.classList.toggle("open")
        menuList.classList.toggle("open")
        if (opening) {
            val listWidth = menuList.asDynamic().offsetWidth as Number
            val rect = menuBtn.asDynamic().getBoundingClientRect()
            val right = rect.right as Double
            val bottom = rect.bottom as Double
            val leftPos = (right - listWidth.toDouble()).coerceAtLeast(4.0)
            menuList.style.left = "${leftPos}px"
            menuList.style.top = "${bottom + 4}px"
        }
    })

    menuWrap.appendChild(menuBtn)
    tabBar.appendChild(menuWrap)
    document.body?.appendChild(menuList)
}
