/**
 * Tab-bar overflow menu for the Termtastic web frontend.
 *
 * Anchored at the far-right edge of the tab strip via a static button in
 * `index.html` (`#tab-overflow-button`). Opens a dropdown containing:
 *
 *  1. **Rename** — inline-rename the active tab. Falls back to a
 *     `window.prompt` when the active tab is hidden (no strip button for
 *     its label to become an input).
 *  2. **Close** — close the active tab. Disabled when only one tab exists,
 *     so the user can't end up with zero tabs. Triggers the same
 *     confirmation dialog the old per-tab menu used.
 *  3. **Hide / Unhide** — toggle [TabConfig.hidden] on the active tab.
 *     Disabled when there is only one visible tab left (the strip must
 *     always show something). Hiding the active tab makes the server
 *     switch the active selection to the next visible neighbour — see
 *     [WindowState.setTabHidden] for the server-side rule.
 *  4. **A separator**, followed by one entry per hidden tab. Each entry
 *     shows the content-kind icon of the tab's first pane (via
 *     [iconSvgForLeafKind]) so the user can tell a file-browser tab from
 *     a terminal tab at a glance. Clicking an entry sends
 *     [WindowCommand.SetActiveTab] — the tab becomes active but stays
 *     hidden; the user chooses whether to unhide afterwards.
 *
 * This file replaces the per-tab hover-menu that used to open next to each
 * tab label. The overflow menu is a single, always-reachable affordance
 * that doesn't steal horizontal space from the tab strip the way the old
 * hover icon did.
 *
 * @see WindowCommand.SetTabHidden for the hide/unhide command
 * @see WindowCommand.SetActiveTab for the activate-hidden-tab path
 * @see iconSvgForLeafKind for the per-kind icon used in the hidden list
 * @see renderConfig the tab-strip renderer that skips `hidden == true` tabs
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

/** Pencil glyph for the "Rename tab" row. Stroke-only so it inherits the
 *  menu row's `currentColor`. Sized to match the 14×14 leaf icons. */
private val ICON_OVERFLOW_PENCIL =
    """<svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"><path d="M2.5 13.5l1-3 7-7a1.4 1.4 0 0 1 2 2l-7 7-3 1z"/><line x1="9.5" y1="4.5" x2="11.5" y2="6.5"/></svg>"""

/** Close (X) glyph for the "Close tab" row. Mirrors the pane-header close
 *  icon style at 14×14 instead of 16×16 so it fits inline with the leaf-
 *  kind icons used for hidden tabs. */
private val ICON_OVERFLOW_CLOSE =
    """<svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"><line x1="4" y1="4" x2="12" y2="12"/><line x1="12" y1="4" x2="4" y2="12"/></svg>"""

/** Eye-with-slash glyph for the "Hide tab" row — same eye outline as
 *  [ICON_OVERFLOW_EYE] with a diagonal strike-through. */
private val ICON_OVERFLOW_EYE_OFF =
    """<svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"><path d="M2 8c1.6-2.5 3.6-4 6-4s4.4 1.5 6 4c-1.6 2.5-3.6 4-6 4s-4.4-1.5-6-4z"/><circle cx="8" cy="8" r="1.6"/><line x1="2.5" y1="13.5" x2="13.5" y2="2.5"/></svg>"""

/** Open-eye glyph for the "Unhide tab" row. Identical to [ICON_OVERFLOW_EYE_OFF]
 *  minus the slash so the toggled state reads at a glance. */
private val ICON_OVERFLOW_EYE =
    """<svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"><path d="M2 8c1.6-2.5 3.6-4 6-4s4.4 1.5 6 4c-1.6 2.5-3.6 4-6 4s-4.4-1.5-6-4z"/><circle cx="8" cy="8" r="1.6"/></svg>"""

/**
 * Wire up the overflow-button click and outside-click/Esc dismissal.
 *
 * Called once from [main.kt][main] at boot after the rest of the header
 * toolbar has been initialised. The click handlers stay attached for the
 * lifetime of the page.
 *
 * @see populateTabOverflowMenu for the menu body rebuild
 */
fun initTabOverflowMenu() {
    val wrap = document.getElementById("tab-overflow-dropdown") as? HTMLElement ?: return
    val button = document.getElementById("tab-overflow-button") as? HTMLElement ?: return
    val menu = document.getElementById("tab-overflow-menu") as? HTMLElement ?: return

    button.addEventListener("click", { ev ->
        ev.stopPropagation()
        if (wrap.classList.contains("open")) {
            wrap.classList.remove("open")
        } else {
            populateTabOverflowMenu(menu)
            wrap.classList.add("open")
        }
    })

    // Close on any outside click / Esc. Same pattern as the layout menu.
    document.addEventListener("click", { ev ->
        val t = ev.target as? HTMLElement ?: return@addEventListener
        if (wrap.contains(t)) return@addEventListener
        wrap.classList.remove("open")
    })
    document.addEventListener("keydown", { ev ->
        val key = ev.asDynamic().key as? String ?: return@addEventListener
        if (key == "Escape") wrap.classList.remove("open")
    })
}

/**
 * Rebuild the menu body against the current server config every time the
 * dropdown opens. Kept fresh (rather than wired once at init) so the hidden
 * tab list reflects the latest push and the Hide/Unhide item label tracks
 * the active tab's current state.
 *
 * @param menu the `#tab-overflow-menu` container element
 */
private fun populateTabOverflowMenu(menu: HTMLElement) {
    menu.innerHTML = ""
    val cfg = currentConfig
    if (cfg == null) {
        menu.appendChild(emptyOverflowItem("No tabs"))
        return
    }

    val tabsArr = (cfg.tabs as? Array<dynamic>) ?: emptyArray()
    if (tabsArr.isEmpty()) {
        menu.appendChild(emptyOverflowItem("No tabs"))
        return
    }

    val active = activeTabId
    val activeTab = tabsArr.firstOrNull { (it.id as? String) == active }
    val totalTabs = tabsArr.size
    val visibleTabs = tabsArr.count { (it.hidden as? Boolean) != true }
    val activeHidden = (activeTab?.hidden as? Boolean) == true

    // --- Active-tab actions ---------------------------------------------

    val renameItem = buildActionItem(
        label = "Rename tab",
        iconSvg = ICON_OVERFLOW_PENCIL,
        enabled = activeTab != null,
    ) {
        val tabId = activeTab?.id as? String ?: return@buildActionItem
        val tabBar = tabBarEl
        val labelEl = tabBar?.querySelector(
            ".tab-button[data-tab=\"$tabId\"] .tab-label"
        ) as? HTMLElement
        if (labelEl != null) {
            startTabRename(labelEl, tabId)
        } else {
            // Active tab is hidden so has no strip label to edit inline.
            // Fall back to a browser prompt; same effect, uglier UX.
            val current = (activeTab.title as? String) ?: ""
            val next = window.prompt("Rename tab", current) ?: return@buildActionItem
            val trimmed = next.trim()
            if (trimmed.isEmpty() || trimmed == current) return@buildActionItem
            launchCmd(WindowCommand.RenameTab(tabId = tabId, title = trimmed))
        }
    }
    menu.appendChild(renameItem)

    val closeItem = buildActionItem(
        label = "Close tab",
        iconSvg = ICON_OVERFLOW_CLOSE,
        // Server refuses if only one tab exists (the UI has no path out of
        // zero tabs). Grey it out here for parity.
        enabled = activeTab != null && totalTabs > 1,
    ) {
        val tabId = activeTab?.id as? String ?: return@buildActionItem
        val tabTitle = (activeTab.title as? String) ?: ""
        showConfirmDialog(
            "Close tab",
            "Are you sure you want to close tab <strong>${tabTitle}</strong> and all its panes?",
            "Close",
        ) {
            launchCmd(WindowCommand.CloseTab(tabId = tabId))
        }
    }
    menu.appendChild(closeItem)

    val hideLabel = if (activeHidden) "Unhide tab" else "Hide tab"
    val hideIcon = if (activeHidden) ICON_OVERFLOW_EYE else ICON_OVERFLOW_EYE_OFF
    // Hiding the last visible tab would leave the strip empty. The server
    // refuses it; the client also disables the menu entry so the user gets
    // immediate feedback instead of a silent no-op.
    val hideEnabled = activeTab != null && (activeHidden || visibleTabs > 1)
    val hideItem = buildActionItem(
        label = hideLabel,
        iconSvg = hideIcon,
        enabled = hideEnabled,
    ) {
        val tabId = activeTab?.id as? String ?: return@buildActionItem
        launchCmd(WindowCommand.SetTabHidden(tabId = tabId, hidden = !activeHidden))
    }
    menu.appendChild(hideItem)

    // --- Hidden tab list ------------------------------------------------

    val hiddenTabs = tabsArr.filter { (it.hidden as? Boolean) == true }
    if (hiddenTabs.isEmpty()) return

    val separator = document.createElement("div") as HTMLElement
    separator.className = "tab-overflow-separator"
    separator.setAttribute("role", "separator")
    menu.appendChild(separator)

    val header = document.createElement("div") as HTMLElement
    header.className = "tab-overflow-section-header"
    header.textContent = "Hidden tabs"
    menu.appendChild(header)

    for (tab in hiddenTabs) {
        val tabId = tab.id as? String ?: continue
        val title = (tab.title as? String) ?: ""
        val panes = (tab.panes as? Array<dynamic>) ?: emptyArray()
        // Pick the first pane's content kind for the row icon. Empty tabs
        // fall back to the terminal icon (same default the sidebar uses).
        val firstLeaf = panes.firstOrNull()?.leaf
        val kind = (firstLeaf?.content?.kind as? String) ?: "terminal"
        val isLinked = (firstLeaf?.isLink as? Boolean) ?: false

        val row = document.createElement("button") as HTMLElement
        row.className = "tab-overflow-item tab-overflow-hidden-tab"
        row.setAttribute("type", "button")
        row.setAttribute("data-tab", tabId)
        row.setAttribute("title", "Activate '$title' (stays hidden)")

        val iconSpan = document.createElement("span") as HTMLElement
        iconSpan.className = "tab-overflow-item-icon"
        iconSpan.innerHTML = iconSvgForLeafKind(kind, isLinked)
        row.appendChild(iconSpan)

        val labelSpan = document.createElement("span") as HTMLElement
        labelSpan.className = "tab-overflow-item-label"
        labelSpan.textContent = title
        row.appendChild(labelSpan)

        row.addEventListener("click", { ev ->
            ev.stopPropagation()
            closeOverflowMenu()
            // Activate the hidden tab. It stays hidden — the user unhides
            // separately via the Hide/Unhide item once it's active. Per
            // issue #2: "A tab that is hidden is not shown on the tab strip.
            // It's shown only in the dropdown instead."
            setActiveTab(tabId)
        })
        menu.appendChild(row)
    }
}

/**
 * Build a standard action row (Rename / Close / Hide). Centralised so every
 * row gets the same class list, icon slot, disabled behaviour, and click
 * plumbing.
 *
 * @param label text shown on the row
 * @param iconSvg inline SVG markup for the leading icon. The icon inherits
 *   `currentColor` so disabled rows (which dim the row's text) also dim
 *   the icon. The issue spec requires every menu item to carry an icon.
 * @param enabled when `false`, the row renders disabled and its click is a
 *   no-op — matches server-side refusals so the UI never lies to the user
 * @param onClick handler fired when the row is clicked and enabled; the
 *   menu is closed before the handler runs
 * @return the fully-wired HTML element, ready to append to the menu
 */
private fun buildActionItem(
    label: String,
    iconSvg: String,
    enabled: Boolean,
    onClick: () -> Unit,
): HTMLElement {
    val item = document.createElement("button") as HTMLElement
    item.className = "tab-overflow-item tab-overflow-action"
    item.setAttribute("type", "button")

    val iconSpan = document.createElement("span") as HTMLElement
    iconSpan.className = "tab-overflow-item-icon"
    iconSpan.innerHTML = iconSvg
    item.appendChild(iconSpan)

    val labelSpan = document.createElement("span") as HTMLElement
    labelSpan.className = "tab-overflow-item-label"
    labelSpan.textContent = label
    item.appendChild(labelSpan)

    if (!enabled) {
        item.classList.add("disabled")
        item.setAttribute("aria-disabled", "true")
    }
    item.addEventListener("click", { ev ->
        ev.stopPropagation()
        if (!enabled) return@addEventListener
        closeOverflowMenu()
        onClick()
    })
    return item
}

/**
 * Render a placeholder row for the rare case where the menu opens but there
 * are no tabs (should not happen in practice — the server never lets the
 * window drop to zero tabs — but the menu renders gracefully if it does).
 */
private fun emptyOverflowItem(text: String): HTMLElement {
    val el = document.createElement("div") as HTMLElement
    el.className = "tab-overflow-empty"
    el.textContent = text
    return el
}

/**
 * Close the overflow dropdown if it is open. Called after any click on a
 * menu row so the user sees immediate dismissal even before the server
 * pushes a new config.
 */
private fun closeOverflowMenu() {
    val wrap = document.getElementById("tab-overflow-dropdown") as? HTMLElement ?: return
    wrap.classList.remove("open")
}
