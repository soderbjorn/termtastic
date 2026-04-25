/**
 * Termtastic-side adapter for the darkness-toolkit theme manager.
 *
 * The full two-tab Theme Manager modal now lives in the toolkit at
 * `se.soderbjorn.darkness.web.themeeditor.ThemeManager`. This file owns
 * the termtastic-specific glue:
 *
 * 1. [TermtasticThemeManagerHost] — a [ThemeManagerHost] adapter that
 *    bridges the toolkit's read/write/render contract to termtastic's
 *    [AppBackingViewModel] state and side-effects.
 * 2. [showThemeManager] — termtastic's old entry point, now a thin
 *    forwarder that constructs the host and delegates to the toolkit's
 *    `openDarknessThemeManager`.
 * 3. [refreshThemeManager] — pass-through to the toolkit so external
 *    callers (window-state observers, server reconciliation, etc.) can
 *    poke the open editor.
 * 4. The per-pane palette dropdown ([openPanePaletteMenu]) — termtastic
 *    specific (driven by the live [WindowConfig] / `Pane.colorScheme`),
 *    so it stays here and re-uses the toolkit's [buildSchemeGroups] and
 *    [fillSchemeRowContent] helpers for visual consistency with the
 *    theme-editor section dropdown.
 *
 * @see se.soderbjorn.darkness.web.themeeditor.showThemeManager
 * @see AppBackingViewModel
 */
package se.soderbjorn.termtastic

import se.soderbjorn.darkness.web.showConfirmDialog

import se.soderbjorn.darkness.core.*
import se.soderbjorn.darkness.web.themeeditor.ThemeManagerHost
import se.soderbjorn.darkness.web.themeeditor.buildSchemeGroups
import se.soderbjorn.darkness.web.themeeditor.fillSchemeRowContent
import se.soderbjorn.darkness.web.themeeditor.showThemeManager as openDarknessThemeManager
import se.soderbjorn.darkness.web.themeeditor.refreshThemeManager as refreshDarknessThemeManager
import se.soderbjorn.darkness.web.themeeditor.closeThemeManager as closeDarknessThemeManager

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * [ThemeManagerHost] implementation that bridges the toolkit's read/write
 * surface to termtastic's [AppBackingViewModel].
 *
 * The host is recreated each time the manager is opened — the toolkit
 * reads through it on every render, so re-fetching `appVm.stateFlow.value`
 * keeps the editor in sync with whatever the rest of the app has done.
 */
private object TermtasticThemeManagerHost : ThemeManagerHost {
    override val mainSchemeName: String
        get() = appVm.stateFlow.value.theme.name
    override val appearance: Appearance
        get() = appVm.stateFlow.value.appearance
    override val lightThemeName: String?
        get() = appVm.stateFlow.value.lightThemeName
    override val darkThemeName: String?
        get() = appVm.stateFlow.value.darkThemeName
    override val customThemes: Map<String, Theme>
        get() = appVm.stateFlow.value.customThemes
    override val customSchemes: Map<String, CustomScheme>
        get() = appVm.stateFlow.value.customSchemes
    override val favoriteThemes: Collection<String>
        get() = appVm.stateFlow.value.favoriteThemes
    override val favoriteSchemes: Collection<String>
        get() = appVm.stateFlow.value.favoriteSchemes

    // [AppBackingViewModel]'s setters are suspend functions because they
    // can roundtrip to the server. We bridge into the toolkit's
    // synchronous host contract by launching on [GlobalScope] — the
    // toolkit doesn't await completion; the next [refreshThemeManager]
    // pass picks up the new state once the launched coroutine settles.
    private fun launch(block: suspend () -> Unit) {
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch { block() }
    }

    override fun setLightThemeName(name: String?) {
        if (name != null) launch { appVm.setLightThemeName(name) }
    }
    override fun setDarkThemeName(name: String?) {
        if (name != null) launch { appVm.setDarkThemeName(name) }
    }
    override fun toggleFavoriteTheme(name: String) { launch { appVm.toggleFavoriteTheme(name) } }
    override fun toggleFavoriteScheme(name: String) { launch { appVm.toggleFavoriteScheme(name) } }
    override fun saveCustomTheme(theme: Theme) { launch { appVm.saveCustomTheme(theme) } }
    override fun deleteCustomTheme(name: String) { launch { appVm.deleteCustomTheme(name) } }
    override fun saveCustomScheme(scheme: CustomScheme) { launch { appVm.saveCustomScheme(scheme) } }
    override fun deleteCustomScheme(name: String) { launch { appVm.deleteCustomScheme(name) } }

    override fun renderConfigSilhouetteHtml(theme: Theme): String =
        renderConfigSilhouette(theme.toDynamic())
    override fun renderThemeSwatchHtml(scheme: ColorScheme): String =
        renderThemeSwatch(scheme)
}

/**
 * Termtastic's compatibility entry point for opening the Theme Manager.
 *
 * Existing callers (toolbar theme picker, settings panel handoff, status
 * bar shortcuts) keep calling [showThemeManager]; this forwarder wires up
 * the [TermtasticThemeManagerHost] and delegates to the toolkit.
 *
 * Mutual exclusion with the settings panel (closes settings first if it's
 * open) is preserved here, since the settings panel is termtastic-owned
 * and the toolkit doesn't know about it.
 *
 * @param initialTab    "themes" or "schemes" — which tab to surface first.
 * @param focusTheme    optional theme name to scroll into view / open.
 * @param focusScheme   optional scheme name to scroll into view / open.
 */
fun showThemeManager(
    initialTab: String = "themes",
    focusTheme: String? = null,
    focusScheme: String? = null,
) {
    if (settingsPanel != null) {
        closeSettingsPanel(onClosed = {
            showThemeManager(initialTab, focusTheme, focusScheme)
        })
        return
    }
    val appBody = document.querySelector(".app-body") as? HTMLElement ?: return
    openDarknessThemeManager(
        hostArg = TermtasticThemeManagerHost,
        mountInto = appBody,
        initialTab = initialTab,
        focusTheme = focusTheme,
        focusScheme = focusScheme,
    )
}

/**
 * Termtastic-side wrapper around the toolkit's [closeDarknessThemeManager].
 * Kept for source-compatibility with existing callers in the codebase.
 */
fun closeThemeManager(onClosed: (() -> Unit)? = null) {
    closeDarknessThemeManager(onClosed)
}

/**
 * Termtastic-side wrapper around the toolkit's [refreshDarknessThemeManager].
 *
 * Called by window-state observers and server reconciliation to repaint
 * the open editor when external state changes.
 */
fun refreshThemeManager() {
    refreshDarknessThemeManager()
}

// ── Per-pane palette dropdown ──────────────────────────────────────────
//
// The palette icon in each pane header opens this popover. It mirrors
// [openSectionSchemeMenu]'s visual style but is scoped to a single pane's
// [Pane.colorScheme] override. The first row is an italicized "Default"
// clear-action; then Favorites / Custom / Default groups via
// [buildSchemeGroups] so it stays consistent with the theme-editor picker.

/**
 * Look up the current per-pane colour-scheme override for [paneId] by
 * walking the live window config. Returns `null` when no override is
 * stored (i.e. the pane inherits from the global theme).
 *
 * @param paneId the pane to inspect
 * @return the scheme name stored on [Pane.colorScheme], or `null`
 */
private fun findPaneScheme(paneId: String): String? {
    val cfg = appVm.stateFlow.value.config ?: return null
    for (tab in cfg.tabs) {
        tab.panes.firstOrNull { it.leaf.id == paneId }?.let { return it.colorScheme }
    }
    return null
}

/**
 * Open the per-pane palette dropdown anchored below [anchor]. First row is
 * an italicized "Default" that clears the override; followed by Favorites,
 * Custom, and Default groups via [buildSchemeGroups]. Dismissed by outside
 * click; only one menu may be open at a time.
 *
 * @param anchor the palette button the menu is anchored below
 * @param paneId the pane whose [Pane.colorScheme] this menu controls
 */
internal fun openPanePaletteMenu(anchor: HTMLElement, paneId: String) {
    document.querySelectorAll(".section-scheme-menu").let { nl ->
        for (i in 0 until nl.length) (nl.item(i) as? HTMLElement)?.remove()
    }

    val menu = document.createElement("div") as HTMLElement
    menu.className = "section-scheme-menu pane-palette-menu"

    val selected = findPaneScheme(paneId)

    // ── Leading italicized "Default" (clear-action) ──
    run {
        val item = document.createElement("button") as HTMLElement
        item.className = "section-scheme-option pane-palette-default" +
            if (selected == null) " selected" else ""
        item.setAttribute("type", "button")
        val inner = document.createElement("span") as HTMLElement
        inner.className = "section-scheme-option-content"
        val label = document.createElement("span") as HTMLElement
        label.className = "section-scheme-label pane-palette-default-label"
        label.textContent = "Default"
        inner.appendChild(label)
        item.appendChild(inner)
        item.addEventListener("click", { ev: Event ->
            ev.stopPropagation()
            menu.remove()
            launchCmd(WindowCommand.SetPaneColorScheme(paneId = paneId, scheme = null))
        })
        menu.appendChild(item)
    }

    // ── Favorites / Custom / Default groups (shared with theme editor) ──
    for ((header, names) in buildSchemeGroups()) {
        val h = document.createElement("div") as HTMLElement
        h.className = "section-scheme-menu-header"
        h.textContent = header
        menu.appendChild(h)
        for (n in names) {
            val item = document.createElement("button") as HTMLElement
            item.className = "section-scheme-option" + if (n == selected) " selected" else ""
            item.setAttribute("type", "button")
            val inner = document.createElement("span") as HTMLElement
            inner.className = "section-scheme-option-content"
            // Pass the scheme as its own "main" so [fillSchemeRowContent]
            // never falls back to the inherit path — this menu only shows
            // real scheme rows; the explicit "Default" row above is the
            // only clear-action.
            fillSchemeRowContent(inner, n, mainSchemeName = n)
            item.appendChild(inner)
            item.addEventListener("click", { ev: Event ->
                ev.stopPropagation()
                menu.remove()
                launchCmd(WindowCommand.SetPaneColorScheme(paneId = paneId, scheme = n))
            })
            menu.appendChild(item)
        }
    }

    val rect = anchor.getBoundingClientRect()
    menu.style.position = "fixed"
    menu.style.left = "${rect.left}px"
    menu.style.top = "${rect.bottom + 4}px"
    menu.style.minWidth = "220px"
    document.body?.appendChild(menu)

    // Flip above when clipped by the viewport bottom; else cap height so
    // the menu remains scrollable inside the visible area. Mirrors
    // [openSectionSchemeMenu]'s placement logic.
    val viewportH = window.innerHeight
    val menuH = menu.getBoundingClientRect().height
    if (rect.bottom + 4 + menuH > viewportH - 8) {
        val above = rect.top - menuH - 4
        if (above >= 8) {
            menu.style.top = "${above}px"
        } else {
            menu.style.maxHeight = "${(viewportH - rect.bottom - 12).coerceAtLeast(120.0)}px"
        }
    }

    val dismiss = { _: Event -> menu.remove() }
    document.addEventListener("click", dismiss, js("({once:true})"))
}
