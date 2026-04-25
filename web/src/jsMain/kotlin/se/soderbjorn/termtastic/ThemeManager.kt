/**
 * Comprehensive Theme Manager modal for Termtastic.
 *
 * This file contains the full two-tab dialog that lets users browse, favorite,
 * clone, delete, and edit themes (per-section scheme assignments) and colour
 * schemes (palettes). It supersedes the old per-section slot picker and saved
 * "theme configurations" list that used to live inline in the settings panel.
 *
 * The manager has two tabs:
 *  - **Themes**: grid of theme silhouettes grouped by `Optimized for…` mode
 *    (dark / both / light, reordered so the active mode's group surfaces
 *    first). Each theme opens a right-hand editor where custom themes may be
 *    renamed, have their compatibility mode changed, and have per-section
 *    colour-scheme assignments edited. Default themes are read-only and
 *    expose a prominent `Clone to edit` action.
 *  - **Color schemes**: grid of palette swatches with `+ New colour scheme` tile.
 *    Clicking a scheme opens an editor with dark/light fg+bg pickers and a
 *    flat, per-token list of semantic overrides (one row per
 *    [ResolvedPalette] token × appearance). Default schemes are read-only.
 *
 * The visual primitives — [renderThemeSwatch] and [renderConfigSilhouette] —
 * are reused throughout so every preview across the app shares a single look.
 *
 * @see renderConfigSilhouette
 * @see renderThemeSwatch
 * @see AppBackingViewModel
 */
package se.soderbjorn.termtastic

import se.soderbjorn.darkness.core.*

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import kotlin.js.json

/** Two top-level tabs in the manager. */
private enum class ManagerTab { Themes, Schemes }

/** Currently-active filter chip state for the theme grid. */
private enum class ThemeFilter { All, Favorites, Default, Custom }

/** Currently-active filter chip state for the scheme grid. */
private enum class SchemeFilter { All, Favorites, Default, Custom }

/** Section keys (order matches the visual silhouette). */
private val MANAGER_SECTIONS = listOf(
    "" to "Main",
    "tabs" to "Tab strip",
    "windows" to "Windows",
    "active" to "Active indicators",
    "sidebar" to "Sidebar",
    "terminal" to "Terminal",
    "diff" to "Diff viewer",
    "fileBrowser" to "File browser",
    "chrome" to "Window chrome",
    "bottomBar" to "Bottom bar",
)

/** Drill-down view state for a single tab in the sidebar. */
private enum class ManagerView { List, Editor }

/**
 * Set to `true` by any editor input whose value diverges from what was in
 * the preset when the editor was opened, and reset to `false` when the
 * editor is opened, saved, or reverted. Consulted by every exit path
 * (back arrow, tab switch, close button, opening another sidebar, Escape)
 * to prompt for a discard confirmation before the in-flight edits vanish.
 */
private var isEditorDirty: Boolean = false

/**
 * Show a discard-changes confirmation when the editor is dirty, otherwise
 * run [onProceed] immediately. On confirm the dirty flag is cleared and
 * [onProceed] runs; on cancel the dialog closes and nothing else happens,
 * leaving the editor intact.
 */
private fun confirmDiscardIfDirty(onProceed: () -> Unit) {
    if (!isEditorDirty) { onProceed(); return }
    showConfirmDialog(
        title = "Discard changes?",
        message = "You have unsaved changes. Discard them?",
        confirmLabel = "Discard",
    ) {
        isEditorDirty = false
        onProceed()
    }
}

/**
 * Closes the Theme Manager right-sidebar with a slide-out transition.
 *
 * Mirrors [closeSettingsPanel]: removes the `.open` class to trigger the CSS
 * width transition, detaches the Escape handler, and cleans up the global
 * [themeManagerPanel] reference. The DOM node is removed once the transition
 * finishes so terminals can refit to the reclaimed width.
 *
 * @param onClosed optional callback invoked once the slide-out transition has
 *                 finished and the panel's DOM node has been detached. Used by
 *                 [openSettingsPanel] to sequence a theme-manager→settings
 *                 handoff so the two sidebars don't overlap mid-animation.
 *                 Invoked immediately (synchronously) when the panel was not
 *                 open to begin with.
 * @see showThemeManager
 */
fun closeThemeManager(onClosed: (() -> Unit)? = null) {
    val panel = themeManagerPanel ?: run { onClosed?.invoke(); return }
    // Intercept close when the editor has unsaved changes. On confirm the
    // dirty flag is cleared and we recurse to complete the close; on
    // cancel we abort — [onClosed] is intentionally not invoked so any
    // chained handoff (e.g. settings panel opening next) doesn't proceed
    // behind the user's back.
    if (isEditorDirty) {
        confirmDiscardIfDirty { closeThemeManager(onClosed) }
        return
    }
    var done = false
    panel.classList.remove("open")
    panel.addEventListener("transitionend", {
        if (!done && !panel.classList.contains("open")) {
            done = true
            panel.remove()
            fitVisible()
            onClosed?.invoke()
        }
    })
    themeManagerEscHandler?.let { document.removeEventListener("keydown", it) }
    themeManagerEscHandler = null
    themeManagerRerender = null
    themeManagerFocusTheme = null
    themeManagerFocusScheme = null
    themeManagerPanel = null
}

/**
 * Opens the Theme Manager as a right-side sidebar. Idempotent: if already
 * open it is brought forward and any requested tab/focus target is applied
 * without rebuilding the DOM.
 *
 * The sidebar is narrower than the old modal and presents a single column of
 * theme/scheme cards. Selecting a card drills down to an editor view with a
 * `← Back` button. Only one right-side sidebar is visible at a time — if the
 * Settings panel is open it is closed before the Theme Manager is attached.
 *
 * @param initialTab  which tab to show on open (`"themes"` or `"schemes"`).
 * @param focusTheme  optional theme name to preselect and drill into.
 * @param focusScheme optional scheme name to preselect and drill into.
 * @see closeThemeManager
 * @see openSettingsPanel
 */
fun showThemeManager(
    initialTab: String = "themes",
    focusTheme: String? = null,
    focusScheme: String? = null,
) {
    // Mutual exclusion: only one right sidebar at a time. If the settings
    // panel is open, wait for its close animation to complete before
    // opening the theme manager so the two sidebars don't overlap.
    if (settingsPanel != null) {
        closeSettingsPanel(onClosed = {
            showThemeManager(initialTab, focusTheme, focusScheme)
        })
        return
    }
    if (themeManagerPanel != null) return

    val appBody = document.querySelector(".app-body") as? HTMLElement ?: return

    val panel = document.createElement("aside") as HTMLElement
    panel.id = "theme-manager-sidebar"
    panel.className = "theme-manager-sidebar"

    // ── Header: close button + title + tab strip ──
    val header = document.createElement("div") as HTMLElement
    header.className = "theme-manager-header"

    val closeBtn = document.createElement("button") as HTMLElement
    closeBtn.className = "pane-modal-close"
    closeBtn.innerHTML = "&times;"
    closeBtn.title = "Close"
    closeBtn.addEventListener("click", { closeThemeManager() })
    header.appendChild(closeBtn)

    val title = document.createElement("h2") as HTMLElement
    title.className = "pane-modal-title theme-manager-title"
    title.textContent = "Themes"
    header.appendChild(title)

    val tabStrip = document.createElement("div") as HTMLElement
    tabStrip.className = "theme-manager-tabs"
    val tabThemes = makeTabBtn("Themes", selected = initialTab != "schemes")
    val tabSchemes = makeTabBtn("Color schemes", selected = initialTab == "schemes")
    tabStrip.appendChild(tabThemes)
    tabStrip.appendChild(tabSchemes)
    header.appendChild(tabStrip)

    panel.appendChild(header)

    // ── Body (single-column: either list or editor view) ──
    val body = document.createElement("div") as HTMLElement
    body.className = "theme-manager-body"
    panel.appendChild(body)

    // ── State ─────────────────────────────────────────────────────
    var activeTab = if (initialTab == "schemes") ManagerTab.Schemes else ManagerTab.Themes
    var themeFilter = ThemeFilter.All
    var schemeFilter = SchemeFilter.All
    var selectedTheme: String? = focusTheme ?: appVm.stateFlow.value.lightThemeName
    var selectedScheme: String? = focusScheme
    // Start in the editor if a specific target was passed in; otherwise list.
    var view: ManagerView = when {
        activeTab == ManagerTab.Themes && focusTheme != null -> ManagerView.Editor
        activeTab == ManagerTab.Schemes && focusScheme != null -> ManagerView.Editor
        else -> ManagerView.List
    }

    var renderAll: () -> Unit = {}

    fun setView(v: ManagerView) {
        view = v
        renderAll()
    }

    fun renderListView(container: HTMLElement) {
        if (activeTab == ManagerTab.Themes) {
            // Highlight follows the active-mode slot so clicking swaps the
            // highlight immediately — the click IS the assignment.
            val state = appVm.stateFlow.value
            val activeLight = isLightActive(state.appearance)
            val activeSlotName = if (activeLight) state.lightThemeName else state.darkThemeName
            renderThemesLeft(container, themeFilter, activeSlotName,
                onFilter = { f -> themeFilter = f; renderAll() },
                onAssign = { name ->
                    GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        if (activeLight) appVm.setLightThemeName(name)
                        else appVm.setDarkThemeName(name)
                    }
                    pokeManager()
                },
                onEdit = { name ->
                    selectedTheme = name
                    setView(ManagerView.Editor)
                })
        } else {
            renderSchemesLeft(container, schemeFilter, selectedScheme,
                onFilter = { f -> schemeFilter = f; renderAll() },
                onSelect = { name ->
                    selectedScheme = name
                    setView(ManagerView.Editor)
                })
        }
    }

    fun renderEditorView(container: HTMLElement) {
        val backBar = document.createElement("div") as HTMLElement
        backBar.className = "theme-manager-back-bar"
        val backBtn = document.createElement("button") as HTMLElement
        backBtn.className = "theme-manager-back-btn"
        backBtn.innerHTML = "&larr;"
        val destination = when (activeTab) {
            ManagerTab.Themes -> "Themes"
            ManagerTab.Schemes -> "Color schemes"
        }
        backBtn.title = "Back to $destination"
        backBtn.addEventListener("click", {
            confirmDiscardIfDirty { setView(ManagerView.List) }
        })
        backBar.appendChild(backBtn)
        val backLabel = document.createElement("span") as HTMLElement
        backLabel.className = "theme-manager-back-label"
        backLabel.textContent = "Back to list"
        backBar.appendChild(backLabel)
        container.appendChild(backBar)

        val editorHost = document.createElement("div") as HTMLElement
        editorHost.className = "theme-manager-editor-host"
        container.appendChild(editorHost)

        if (activeTab == ManagerTab.Themes) {
            renderThemeEditor(editorHost, selectedTheme) { renderAll() }
        } else {
            renderSchemeEditor(editorHost, selectedScheme) { renderAll() }
        }
    }

    renderAll = {
        // If the editor's target has disappeared since the last render (the
        // user just deleted it, or it was removed elsewhere), fall back to
        // the list view instead of showing a dead-end "not found" message.
        if (view == ManagerView.Editor) {
            val state = appVm.stateFlow.value
            val missing = when (activeTab) {
                ManagerTab.Themes -> {
                    val n = selectedTheme
                    n == null || (state.customThemes[n] == null &&
                        defaultThemes.none { it.name == n })
                }
                ManagerTab.Schemes -> {
                    val n = selectedScheme
                    n == null || (state.customSchemes[n] == null &&
                        recommendedColorSchemes.none { it.name == n })
                }
            }
            if (missing) {
                view = ManagerView.List
                if (activeTab == ManagerTab.Themes) selectedTheme = null
                else selectedScheme = null
            }
        }

        title.textContent = if (activeTab == ManagerTab.Themes) "Themes" else "Color schemes"
        // Tabs only make sense when browsing the list — while editing a
        // theme/scheme they'd either duplicate the new back-bar label or
        // mislead the user into thinking a tab click would switch the
        // current edit's context.
        tabStrip.style.display = if (view == ManagerView.Editor) "none" else "flex"
        body.innerHTML = ""
        body.classList.toggle("view-editor", view == ManagerView.Editor)
        body.classList.toggle("view-list", view == ManagerView.List)
        if (view == ManagerView.List) renderListView(body) else renderEditorView(body)
    }

    tabThemes.addEventListener("click", {
        confirmDiscardIfDirty {
            activeTab = ManagerTab.Themes
            tabThemes.classList.add("selected"); tabSchemes.classList.remove("selected")
            view = ManagerView.List
            renderAll()
        }
    })
    tabSchemes.addEventListener("click", {
        confirmDiscardIfDirty {
            activeTab = ManagerTab.Schemes
            tabSchemes.classList.add("selected"); tabThemes.classList.remove("selected")
            if (selectedScheme == null) {
                selectedScheme = appVm.stateFlow.value.theme.name
            }
            view = ManagerView.List
            renderAll()
        }
    })

    renderAll()

    // ── Escape-to-close ──
    val escHandler: (Event) -> Unit = { ev ->
        if ((ev as? KeyboardEvent)?.key == "Escape") {
            if (view == ManagerView.Editor) {
                confirmDiscardIfDirty { setView(ManagerView.List) }
            } else {
                closeThemeManager()
            }
        }
    }
    document.addEventListener("keydown", escHandler)
    themeManagerEscHandler = escHandler

    // ── Re-render on upstream state changes ──
    themeManagerRerender = { _: Any -> renderAll() }

    // ── Focus hooks: let clone flows drop the user straight into the
    // editor for the newly-created theme/scheme, so they can tweak it
    // without having to re-locate it in the grid.
    themeManagerFocusTheme = { name ->
        activeTab = ManagerTab.Themes
        tabThemes.classList.add("selected"); tabSchemes.classList.remove("selected")
        selectedTheme = name
        view = ManagerView.Editor
        renderAll()
    }
    themeManagerFocusScheme = { name ->
        activeTab = ManagerTab.Schemes
        tabSchemes.classList.add("selected"); tabThemes.classList.remove("selected")
        selectedScheme = name
        view = ManagerView.Editor
        renderAll()
    }

    // ── Attach + slide-in ──
    appBody.appendChild(panel)
    themeManagerPanel = panel

    // Apply the sidebar section theme so the panel matches the left sidebar
    // (same treatment the settings panel applies to itself).
    val sidebarPalette = sectionPalette("sidebar")
    val rootPalette = currentResolvedPalette()
    if (sidebarPalette != rootPalette) {
        val cssVars = sidebarPalette.toCssVarMap() + sidebarPalette.toCssAliasMap()
        for ((prop, value) in cssVars) panel.style.setProperty(prop, value)
    }

    kotlinx.browser.window.requestAnimationFrame { panel.classList.add("open") }
    panel.addEventListener("transitionend", { fitVisible() }, js("({once:true})"))
}

/** Callback invoked after mutations to refresh the manager UI, if open. */
private var themeManagerRerender: ((Any) -> Unit)? = null

/**
 * Set while the manager panel is open; invoked by the theme clone flow to
 * drill into the editor for a freshly-created theme by name. No-op when the
 * panel is closed.
 */
private var themeManagerFocusTheme: ((String) -> Unit)? = null

/**
 * Set while the manager panel is open; invoked by the scheme clone flow to
 * drill into the editor for a freshly-created scheme by name. No-op when the
 * panel is closed.
 */
private var themeManagerFocusScheme: ((String) -> Unit)? = null

/** Notify the open manager (if any) to re-render. */
private fun pokeManager() {
    themeManagerRerender?.invoke(Unit)
}

/**
 * Refresh the Theme Manager panel if it is currently open. Called from
 * upstream state observers (e.g. [main.kt]'s appearance/theme collector)
 * so that appearance-dependent UI — in particular the theme list's
 * "Optimized for…" group ordering, which surfaces the active-appearance
 * group first — re-sorts when the user toggles between light and dark.
 * No-op when the panel is closed.
 */
fun refreshThemeManager() {
    themeManagerRerender?.invoke(Unit)
}

/** Build a tab button element. */
private fun makeTabBtn(label: String, selected: Boolean): HTMLElement {
    val btn = document.createElement("button") as HTMLElement
    btn.className = "theme-manager-tab" + if (selected) " selected" else ""
    btn.textContent = label
    return btn
}

// ── Theme tab (Tab A) ─────────────────────────────────────────────

/**
 * Render the left pane of Tab A: filter pills + grid of theme cards
 * grouped by compatibility mode. Groups are reordered so the one matching
 * the currently-active appearance appears first.
 *
 * @param container   the left-pane element to populate
 * @param filter      active filter chip
 * @param selected    name of the theme to highlight (the active-mode slot)
 * @param onFilter    callback when the user clicks a filter pill
 * @param onAssign    callback when the user clicks a theme card (assigns
 *                    the theme to the currently-live mode)
 * @param onEdit      callback when the user picks Edit from the card's
 *                    kebab menu, or creates a new theme via the tile
 */
private fun renderThemesLeft(
    container: HTMLElement,
    filter: ThemeFilter,
    selected: String?,
    onFilter: (ThemeFilter) -> Unit,
    onAssign: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    val state = appVm.stateFlow.value

    // ── Filter chips ──
    val chips = document.createElement("div") as HTMLElement
    chips.className = "theme-manager-filters"
    for (f in ThemeFilter.values()) {
        val label = when (f) {
            ThemeFilter.All -> "All"
            ThemeFilter.Favorites -> "★ Favorites"
            ThemeFilter.Default -> "Default"
            ThemeFilter.Custom -> "Custom"
        }
        val chip = document.createElement("button") as HTMLElement
        chip.className = "theme-manager-chip" + if (f == filter) " selected" else ""
        chip.textContent = label
        chip.addEventListener("click", { onFilter(f) })
        chips.appendChild(chip)
    }
    container.appendChild(chips)

    // ── All themes (default + custom) as (name, preset, isDefault) triples ──
    data class Row(val preset: Theme, val isDefault: Boolean)

    val allRows = buildList {
        // Custom themes first (user content prioritized in lists)
        for ((_, t) in state.customThemes) add(Row(t, isDefault = false))
        for (t in defaultThemes) add(Row(t, isDefault = true))
    }

    val filtered = allRows.filter { row ->
        when (filter) {
            ThemeFilter.All -> true
            ThemeFilter.Favorites -> row.preset.name in state.favoriteThemes
            ThemeFilter.Default -> row.isDefault
            ThemeFilter.Custom -> !row.isDefault
        }
    }

    // Group by mode; reorder groups so active mode surfaces first.
    val isDark = !isLightActive(state.appearance)
    val groupOrder = if (isDark) {
        listOf(ConfigMode.Dark to "Optimized for dark",
            ConfigMode.Both to "Optimized for Dark and Light",
            ConfigMode.Light to "Optimized for light")
    } else {
        listOf(ConfigMode.Light to "Optimized for light",
            ConfigMode.Both to "Optimized for Dark and Light",
            ConfigMode.Dark to "Optimized for dark")
    }

    val grouped = filtered.groupBy { it.preset.mode }

    for ((mode, header) in groupOrder) {
        val rows = grouped[mode] ?: continue
        if (rows.isEmpty()) continue

        val groupTitle = document.createElement("div") as HTMLElement
        groupTitle.className = "theme-manager-group-title"
        groupTitle.textContent = header
        container.appendChild(groupTitle)

        val grid = document.createElement("div") as HTMLElement
        grid.className = "theme-manager-grid"
        for (row in rows) {
            grid.appendChild(buildThemeCard(row.preset, row.isDefault, row.preset.name == selected, onAssign, onEdit))
        }
        container.appendChild(grid)
    }

    if (filtered.isEmpty()) {
        val empty = document.createElement("div") as HTMLElement
        empty.className = "theme-manager-empty"
        empty.textContent = when (filter) {
            ThemeFilter.Favorites -> "No favorites yet — star a theme to add it here."
            ThemeFilter.Custom -> "No custom themes yet — click + New theme to create one."
            else -> "No themes match this filter."
        }
        container.appendChild(empty)
    }

    // "+ New theme" tile — sits at the very end of the list as a regular
    // card-sized row. Hidden on the Default-only filter since it always
    // creates a custom theme and would be confusing under that filter.
    if (filter != ThemeFilter.Default) {
        val trailingGrid = document.createElement("div") as HTMLElement
        trailingGrid.className = "theme-manager-grid"
        val newTile = document.createElement("div") as HTMLElement
        newTile.className = "theme-manager-card-item new-theme-tile"
        newTile.innerHTML =
            """<span class="new-theme-plus">+</span><span class="new-theme-label">New theme</span>"""
        newTile.addEventListener("click", {
            promptNewTheme { name -> onEdit(name) }
        })
        trailingGrid.appendChild(newTile)
        container.appendChild(trailingGrid)
    }
}

/**
 * Build a single theme card with silhouette, name, favorite star, and kebab
 * menu. Clicking the card assigns the theme to the currently-live mode;
 * the kebab menu exposes Edit / Clone / Delete actions.
 */
private fun buildThemeCard(
    preset: Theme,
    isDefault: Boolean,
    isSelected: Boolean,
    onAssign: (String) -> Unit,
    onEdit: (String) -> Unit,
): HTMLElement {
    val card = document.createElement("div") as HTMLElement
    card.className = "theme-manager-card-item" +
        if (isSelected) " selected" else "" +
        if (isDefault) " default" else " custom"
    card.setAttribute("data-theme-name", preset.name)

    val state = appVm.stateFlow.value
    val isFav = preset.name in state.favoriteThemes

    // Per-card CSS vars so the star/kebab pick up this theme's primary
    // scheme fg/bg instead of the hardcoded greys. Falls back to the
    // recommended-themes default if the preset's main scheme can't be
    // resolved (e.g. a user deleted the custom scheme but kept the theme).
    val previewScheme = resolveSchemeByName(preset.colorScheme)
        ?: recommendedColorSchemes.first()
    applyCardPaletteVars(card, previewScheme)

    val star = document.createElement("button") as HTMLElement
    star.className = "theme-manager-star" + if (isFav) " active" else ""
    star.innerHTML = if (isFav) "★" else "☆"
    star.title = if (isFav) "Unfavorite" else "Favorite"
    star.addEventListener("click", { ev: Event ->
        ev.stopPropagation()
        GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
            appVm.toggleFavoriteTheme(preset.name)
        }
        pokeManager()
    })
    card.appendChild(star)

    val kebab = document.createElement("button") as HTMLElement
    kebab.className = "theme-manager-kebab"
    kebab.innerHTML = "⋮"
    kebab.title = "More"
    kebab.addEventListener("click", { ev: Event ->
        ev.stopPropagation()
        showThemeCardMenu(kebab, preset, isDefault, onEdit)
    })
    card.appendChild(kebab)

    val silhouette = document.createElement("span") as HTMLElement
    silhouette.className = "theme-manager-silhouette"
    silhouette.innerHTML = renderConfigSilhouette(preset.toDynamic())
    card.appendChild(silhouette)

    val nameRow = document.createElement("div") as HTMLElement
    nameRow.className = "theme-manager-card-name"
    val nameText = document.createElement("span") as HTMLElement
    nameText.textContent = preset.name
    nameRow.appendChild(nameText)

    card.appendChild(nameRow)

    card.addEventListener("click", { onAssign(preset.name) })
    return card
}

/**
 * Open a small menu anchored to the kebab button with edit / clone / delete
 * actions. Slot assignment happens via a plain click on the card itself.
 */
private fun showThemeCardMenu(
    anchor: HTMLElement,
    preset: Theme,
    isDefault: Boolean,
    onEdit: (String) -> Unit,
) {
    document.querySelectorAll(".theme-manager-menu").let { nl ->
        for (i in 0 until nl.length) (nl.item(i) as? HTMLElement)?.remove()
    }
    val menu = document.createElement("div") as HTMLElement
    menu.className = "theme-manager-menu"

    fun addItem(label: String, onClick: () -> Unit) {
        val item = document.createElement("button") as HTMLElement
        item.className = "theme-manager-menu-item"
        item.textContent = label
        item.addEventListener("click", { ev: Event ->
            ev.stopPropagation()
            onClick()
            menu.remove()
        })
        menu.appendChild(item)
    }

    addItem("Edit…") {
        onEdit(preset.name)
    }
    addItem("Clone…") {
        showCloneThemePrompt(preset)
    }
    if (!isDefault) {
        addItem("Delete") {
            showConfirmDialog(
                title = "Delete theme",
                message = "Delete theme <b>${preset.name}</b>?",
                confirmLabel = "Delete",
            ) {
                GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    appVm.deleteCustomTheme(preset.name)
                }
                pokeManager()
            }
        }
    }

    val rect = anchor.getBoundingClientRect()
    menu.style.position = "fixed"
    menu.style.top = "${rect.bottom + 4}px"
    menu.style.left = "${rect.right - 200}px"
    document.body?.appendChild(menu)

    val dismiss = { _: Event -> menu.remove() }
    document.addEventListener("click", dismiss, js("({once:true})"))
}

/**
 * Prompt the user for a name and create a clone of [source] under the new
 * name. Defaults for the new name append " Copy". After save, the manager
 * (if open) is switched to the editor view for the new theme so the user
 * can start tweaking it immediately.
 */
private fun showCloneThemePrompt(source: Theme) {
    val defaultName = "${source.name} Copy"
    val existing = buildSet {
        for (t in defaultThemes) add(t.name)
        for ((name, _) in appVm.stateFlow.value.customThemes) add(name)
    }
    showNamePrompt(
        title = "Clone theme",
        label = "New theme name",
        initial = defaultName,
        validate = { candidate ->
            when {
                candidate.isBlank() -> "Name is required"
                candidate.equals("Default", ignoreCase = true) -> "\"Default\" is reserved"
                candidate in existing -> "A theme with that name already exists"
                else -> null
            }
        },
    ) { newName ->
        val clone = source.copy(name = newName)
        GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
            appVm.saveCustomTheme(clone)
        }
        val focus = themeManagerFocusTheme
        if (focus != null) focus(newName) else pokeManager()
    }
}

/**
 * Prompt for a fresh custom theme built from scratch (not cloned).
 *
 * The new preset defaults its main scheme to the currently-active main scheme
 * so the theme renders sensibly out of the box; mode defaults to Both and all
 * per-section overrides are empty. The user can adjust all of this in the
 * right-pane editor after creation.
 *
 * @param onCreated called with the new theme's name on success.
 */
private fun promptNewTheme(onCreated: (String) -> Unit) {
    val state = appVm.stateFlow.value
    val existing = buildSet {
        for (t in defaultThemes) add(t.name)
        for ((name, _) in state.customThemes) add(name)
    }
    val defaultName = run {
        var n = 1
        var candidate = "New theme"
        while (candidate in existing) {
            n += 1
            candidate = "New theme $n"
        }
        candidate
    }
    val seedScheme = state.theme.name.ifBlank { recommendedColorSchemes.first().name }
    showNamePrompt(
        title = "New theme",
        label = "Theme name",
        initial = defaultName,
        validate = { candidate ->
            when {
                candidate.isBlank() -> "Name is required"
                candidate.equals("Default", ignoreCase = true) -> "\"Default\" is reserved"
                candidate in existing -> "A theme with that name already exists"
                else -> null
            }
        },
    ) { newName ->
        val fresh = Theme(
            name = newName,
            mode = ConfigMode.Both,
            colorScheme = seedScheme,
        )
        GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
            appVm.saveCustomTheme(fresh)
        }
        onCreated(newName)
        pokeManager()
    }
}

// ── Theme editor (right pane of Tab A) ────────────────────────────

/**
 * Render the right-hand editor for the currently-selected theme. Default
 * themes show a read-only preview with a prominent "Clone to edit" button;
 * custom themes show editable name, mode, and per-section scheme rows.
 */
private fun renderThemeEditor(
    container: HTMLElement,
    selectedName: String?,
    refresh: () -> Unit,
) {
    container.innerHTML = ""
    val state = appVm.stateFlow.value
    val name = selectedName ?: return
    val preset = state.customThemes[name] ?: defaultThemes.firstOrNull { it.name == name }
    if (preset == null) {
        val empty = document.createElement("div") as HTMLElement
        empty.className = "theme-manager-empty"
        empty.textContent = "Theme not found."
        container.appendChild(empty)
        return
    }
    val isDefault = preset.name !in state.customThemes
    // Fresh open — no pending edits yet. Any input change below flips it on.
    isEditorDirty = false
    // Wired up after the Save/Revert buttons are created below so both
    // reflect [isEditorDirty] — stays a no-op until then and for default
    // (read-only) themes, which never build those buttons.
    var syncActionsEnabled: () -> Unit = {}
    val markDirty: () -> Unit = {
        isEditorDirty = true
        syncActionsEnabled()
    }

    val header = document.createElement("div") as HTMLElement
    header.className = "theme-editor-header"

    val preview = document.createElement("div") as HTMLElement
    preview.className = "theme-editor-preview"
    preview.innerHTML = renderConfigSilhouette(preset.toDynamic())
    header.appendChild(preview)

    val info = document.createElement("div") as HTMLElement
    info.className = "theme-editor-info"

    val nameLabel = document.createElement("div") as HTMLElement
    nameLabel.className = "theme-editor-label"
    nameLabel.textContent = "Name"
    info.appendChild(nameLabel)

    val nameInput = document.createElement("input") as HTMLInputElement
    nameInput.className = "theme-editor-input"
    nameInput.type = "text"
    nameInput.value = preset.name
    nameInput.disabled = isDefault
    nameInput.addEventListener("input", { _: Event -> markDirty() })
    info.appendChild(nameInput)

    val modeLabel = document.createElement("div") as HTMLElement
    modeLabel.className = "theme-editor-label"
    modeLabel.textContent = "Optimized for"
    info.appendChild(modeLabel)

    val modeSelect = document.createElement("select") as HTMLSelectElement
    modeSelect.className = "theme-editor-select"
    modeSelect.disabled = isDefault
    for (m in ConfigMode.values()) {
        val opt = document.createElement("option") as org.w3c.dom.HTMLOptionElement
        opt.value = m.name
        opt.textContent = when (m) {
            ConfigMode.Dark -> "Dark mode"
            ConfigMode.Light -> "Light mode"
            ConfigMode.Both -> "Both dark and light mode"
        }
        if (m == preset.mode) opt.selected = true
        modeSelect.appendChild(opt)
    }
    modeSelect.addEventListener("change", { _: Event -> markDirty() })
    info.appendChild(modeSelect)

    header.appendChild(info)
    container.appendChild(header)

    // ── Section-assignment rows ──
    val sectionsWrap = document.createElement("div") as HTMLElement
    sectionsWrap.className = "theme-editor-sections"

    val sectionValues = mutableMapOf<String, String>()
    sectionValues[""] = preset.colorScheme
    sectionValues["sidebar"] = preset.sidebar
    sectionValues["terminal"] = preset.terminal
    sectionValues["diff"] = preset.diff
    sectionValues["fileBrowser"] = preset.fileBrowser
    sectionValues["tabs"] = preset.tabs
    sectionValues["chrome"] = preset.chrome
    sectionValues["windows"] = preset.windows
    sectionValues["active"] = preset.active
    sectionValues["bottomBar"] = preset.bottomBar

    // Collected so the main row's onChange can re-render sibling triggers
    // whose selection is "Default" (they inherit the main's swatch).
    val rowRefreshers = mutableListOf<() -> Unit>()
    for ((key, label) in MANAGER_SECTIONS) {
        val (row, refresh) = buildSectionRow(
            sectionKey = key,
            sectionLabel = label,
            currentSchemeName = sectionValues[key] ?: "",
            isReadonly = isDefault,
            getMainSchemeName = { sectionValues[""] ?: "" },
        ) { newName ->
            sectionValues[key] = newName
            if (key == "") rowRefreshers.forEach { it() }
            markDirty()
        }
        rowRefreshers += refresh
        sectionsWrap.appendChild(row)
    }
    container.appendChild(sectionsWrap)

    // ── Actions ──
    val actions = document.createElement("div") as HTMLElement
    actions.className = "theme-editor-actions"

    if (isDefault) {
        val cloneHint = document.createElement("div") as HTMLElement
        cloneHint.className = "theme-editor-hint"
        cloneHint.textContent = "Default themes are read-only. Clone to edit."
        actions.appendChild(cloneHint)

        val cloneBtn = document.createElement("button") as HTMLElement
        cloneBtn.className = "theme-editor-btn primary"
        cloneBtn.textContent = "Clone to edit"
        cloneBtn.addEventListener("click", { showCloneThemePrompt(preset) })
        actions.appendChild(cloneBtn)
    } else {
        val saveBtn = document.createElement("button") as HTMLElement
        saveBtn.className = "theme-editor-btn primary"
        saveBtn.textContent = "Save"
        saveBtn.addEventListener("click", { _: Event ->
            val newName = nameInput.value.trim()
            if (newName.isEmpty()) return@addEventListener
            val newMode = runCatching { ConfigMode.valueOf(modeSelect.value) }.getOrDefault(ConfigMode.Both)
            val next = preset.copy(
                name = newName,
                mode = newMode,
                colorScheme = sectionValues[""] ?: preset.colorScheme,
                sidebar = sectionValues["sidebar"] ?: "",
                terminal = sectionValues["terminal"] ?: "",
                diff = sectionValues["diff"] ?: "",
                fileBrowser = sectionValues["fileBrowser"] ?: "",
                tabs = sectionValues["tabs"] ?: "",
                chrome = sectionValues["chrome"] ?: "",
                windows = sectionValues["windows"] ?: "",
                active = sectionValues["active"] ?: "",
                bottomBar = sectionValues["bottomBar"] ?: "",
            )
            GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
                // Rename path: delete old, save new.
                if (newName != preset.name) {
                    appVm.deleteCustomTheme(preset.name)
                }
                appVm.saveCustomTheme(next)
            }
            isEditorDirty = false
            pokeManager()
        })
        actions.appendChild(saveBtn)

        val revertBtn = document.createElement("button") as HTMLElement
        revertBtn.className = "theme-editor-btn"
        revertBtn.textContent = "Revert"
        revertBtn.addEventListener("click", {
            isEditorDirty = false
            refresh()
        })
        actions.appendChild(revertBtn)

        syncActionsEnabled = {
            val clean = !isEditorDirty
            console.log("[theme-editor] syncActionsEnabled theme clean=$clean isEditorDirty=$isEditorDirty")
            saveBtn.asDynamic().disabled = clean
            revertBtn.asDynamic().disabled = clean
            saveBtn.classList.toggle("is-disabled", clean)
            revertBtn.classList.toggle("is-disabled", clean)
        }
        syncActionsEnabled()

        val deleteBtn = document.createElement("button") as HTMLElement
        deleteBtn.className = "theme-editor-btn danger"
        deleteBtn.textContent = "Delete"
        deleteBtn.addEventListener("click", {
            showConfirmDialog(
                title = "Delete theme",
                message = "Delete theme <b>${preset.name}</b>?",
                confirmLabel = "Delete",
            ) {
                GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    appVm.deleteCustomTheme(preset.name)
                }
                isEditorDirty = false
                pokeManager()
            }
        })
        actions.appendChild(deleteBtn)
    }

    container.appendChild(actions)
}

/**
 * Resolve a colour-scheme name to its [ColorScheme], checking custom
 * schemes first and falling back to [recommendedColorSchemes]. Returns `null`
 * when the name is empty or unknown.
 */
private fun resolveSchemeByName(name: String): ColorScheme? {
    if (name.isEmpty()) return null
    val state = appVm.stateFlow.value
    return state.customSchemes[name]?.toColorScheme()
        ?: recommendedColorSchemes.firstOrNull { it.name == name }
}

/**
 * Populate [container] with the visual row content for [schemeName]: a
 * mini swatch and the scheme name. An empty [schemeName] renders the
 * "Default" (inherit from main) variant with [mainSchemeName]'s swatch so
 * the user can see what it resolves to. Custom-vs-recommended distinction
 * is conveyed via group headers in the picker menu, not a per-row marker.
 */
private fun fillSchemeRowContent(
    container: HTMLElement,
    schemeName: String,
    mainSchemeName: String,
) {
    container.innerHTML = ""
    val isDefault = schemeName.isEmpty()
    val effectiveName = if (isDefault) mainSchemeName else schemeName
    val resolved = resolveSchemeByName(effectiveName)

    val swatchWrap = document.createElement("span") as HTMLElement
    swatchWrap.className = "section-scheme-swatch"
    if (resolved != null) swatchWrap.innerHTML = renderThemeSwatch(resolved)
    container.appendChild(swatchWrap)

    val labelWrap = document.createElement("span") as HTMLElement
    labelWrap.className = "section-scheme-label" + if (isDefault) " muted" else ""
    labelWrap.textContent = if (isDefault) "Default" else schemeName
    container.appendChild(labelWrap)
}

/**
 * Group scheme names for the picker dropdowns. Returns an ordered list of
 * `(headerLabel, names)` pairs — Favorites first, then Custom, then Default
 * (built-ins). Each group is alpha-sorted internally; empty groups are
 * omitted. Reads `appVm.stateFlow.value` directly so callers don't need to
 * pass state through.
 *
 * Shared between [openSectionSchemeMenu] (theme editor) and
 * [openPanePaletteMenu] (per-pane palette dropdown) so the two surfaces
 * stay visually consistent.
 *
 * @return ordered groups; callers render a header row followed by one
 *   option per name
 */
internal fun buildSchemeGroups(): List<Pair<String, List<String>>> {
    val state = appVm.stateFlow.value
    val favs = state.favoriteSchemes.sorted()
    val customs = state.customSchemes.keys.sorted()
    val defaults = recommendedColorSchemes.map { it.name }.sorted()
    return buildList {
        if (favs.isNotEmpty()) add("Favorites" to favs)
        if (customs.isNotEmpty()) add("Custom" to customs)
        add("Default" to defaults)
    }
}

/**
 * Open the scheme-picker popover anchored below [anchor]. Lists every
 * recommended, custom, and favourited scheme as a rich row (swatch + name),
 * grouped under "Favorites", "Custom", and "Default" headers (in that
 * order). Non-main sections get a leading "Inherit" entry that inherits
 * from the main scheme.
 *
 * Selection invokes [onPick]; outside click and Escape dismiss without
 * change. Only one picker menu may be open at a time.
 *
 * @see buildSchemeGroups for the shared grouping/ordering
 * @see openPanePaletteMenu for the sibling pane-level dropdown
 */
private fun openSectionSchemeMenu(
    anchor: HTMLElement,
    sectionKey: String,
    selected: String,
    getMainSchemeName: () -> String,
    onPick: (String) -> Unit,
) {
    document.querySelectorAll(".section-scheme-menu").let { nl ->
        for (i in 0 until nl.length) (nl.item(i) as? HTMLElement)?.remove()
    }

    val menu = document.createElement("div") as HTMLElement
    menu.className = "section-scheme-menu"

    fun addOption(schemeName: String) {
        val item = document.createElement("button") as HTMLElement
        item.className = "section-scheme-option" + if (schemeName == selected) " selected" else ""
        item.setAttribute("type", "button")
        val inner = document.createElement("span") as HTMLElement
        inner.className = "section-scheme-option-content"
        fillSchemeRowContent(inner, schemeName, getMainSchemeName())
        item.appendChild(inner)
        item.addEventListener("click", { ev: Event ->
            ev.stopPropagation()
            menu.remove()
            onPick(schemeName)
        })
        menu.appendChild(item)
    }

    fun addHeader(text: String) {
        val h = document.createElement("div") as HTMLElement
        h.className = "section-scheme-menu-header"
        h.textContent = text
        menu.appendChild(h)
    }

    // Non-main sections can inherit from the main scheme.
    if (sectionKey.isNotEmpty()) {
        addHeader("Inherit")
        addOption("")
    }

    for ((header, names) in buildSchemeGroups()) {
        addHeader(header)
        for (n in names) addOption(n)
    }

    val rect = anchor.getBoundingClientRect()
    menu.style.position = "fixed"
    menu.style.left = "${rect.left}px"
    menu.style.top = "${rect.bottom + 4}px"
    menu.style.minWidth = "${rect.width}px"
    document.body?.appendChild(menu)

    // Flip above when clipped by the viewport bottom; else cap height so
    // the menu remains scrollable inside the visible area.
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

    // Mirrors [showSchemeCardMenu]: a single once-listener dismisses on
    // any subsequent document click. The opening click used
    // stopPropagation so this listener is registered only after it.
    val dismiss = { _: Event -> menu.remove() }
    document.addEventListener("click", dismiss, js("({once:true})"))
}

/**
 * Build a single "label + scheme picker" row for the theme editor. The
 * picker is a custom popover (not a native `<select>`) that renders each
 * option as a real scheme swatch + name, mirroring the Tab B scheme list.
 *
 * Returns the row element paired with a `refresh` lambda the caller can
 * invoke when the main scheme changes — this lets sibling rows whose
 * selection is "Default" re-render their inherited swatch without the
 * full editor re-rendering.
 *
 * @param getMainSchemeName lazy accessor for the preset's main scheme,
 *   consulted both at [refresh] time and at menu-open time so the
 *   "Default" option always shows the current main's swatch.
 */
private fun buildSectionRow(
    sectionKey: String,
    sectionLabel: String,
    currentSchemeName: String,
    isReadonly: Boolean,
    getMainSchemeName: () -> String,
    onChange: (String) -> Unit,
): Pair<HTMLElement, () -> Unit> {
    val row = document.createElement("div") as HTMLElement
    row.className = "theme-editor-section-row"

    val label = document.createElement("div") as HTMLElement
    label.className = "theme-editor-section-label"
    label.textContent = sectionLabel
    row.appendChild(label)

    val trigger = document.createElement("button") as HTMLElement
    trigger.className = "section-scheme-picker"
    trigger.setAttribute("type", "button")
    trigger.asDynamic().disabled = isReadonly

    val content = document.createElement("span") as HTMLElement
    content.className = "section-scheme-picker-content"
    trigger.appendChild(content)

    val chevron = document.createElement("span") as HTMLElement
    chevron.className = "section-scheme-picker-chevron"
    chevron.textContent = "▾"
    trigger.appendChild(chevron)

    var selected = currentSchemeName
    val refreshTrigger = {
        fillSchemeRowContent(content, selected, getMainSchemeName())
    }
    refreshTrigger()

    trigger.addEventListener("click", { ev: Event ->
        ev.stopPropagation()
        if (isReadonly) return@addEventListener
        openSectionSchemeMenu(
            anchor = trigger,
            sectionKey = sectionKey,
            selected = selected,
            getMainSchemeName = getMainSchemeName,
        ) { picked ->
            if (picked != selected) {
                selected = picked
                refreshTrigger()
                onChange(picked)
            }
        }
    })

    row.appendChild(trigger)
    return row to refreshTrigger
}

// ── Color-schemes tab (Tab B) ─────────────────────────────────────

/**
 * Render the left pane of Tab B: filter pills + a `+ New colour scheme` tile
 * followed by a grid of colour-scheme cards.
 */
private fun renderSchemesLeft(
    container: HTMLElement,
    filter: SchemeFilter,
    selected: String?,
    onFilter: (SchemeFilter) -> Unit,
    onSelect: (String) -> Unit,
) {
    val state = appVm.stateFlow.value

    val chips = document.createElement("div") as HTMLElement
    chips.className = "theme-manager-filters"
    for (f in SchemeFilter.values()) {
        val label = when (f) {
            SchemeFilter.All -> "All"
            SchemeFilter.Favorites -> "★ Favorites"
            SchemeFilter.Default -> "Default"
            SchemeFilter.Custom -> "Custom"
        }
        val chip = document.createElement("button") as HTMLElement
        chip.className = "theme-manager-chip" + if (f == filter) " selected" else ""
        chip.textContent = label
        chip.addEventListener("click", { onFilter(f) })
        chips.appendChild(chip)
    }
    container.appendChild(chips)

    val grid = document.createElement("div") as HTMLElement
    grid.className = "theme-manager-grid scheme-grid"

    data class Row(val scheme: ColorScheme, val isDefault: Boolean)
    val allRows = buildList {
        for ((_, c) in state.customSchemes) add(Row(c.toColorScheme(), isDefault = false))
        for (t in recommendedColorSchemes) add(Row(t, isDefault = true))
    }

    val filtered = allRows.filter { row ->
        when (filter) {
            SchemeFilter.All -> true
            SchemeFilter.Favorites -> row.scheme.name in state.favoriteSchemes
            SchemeFilter.Default -> row.isDefault
            SchemeFilter.Custom -> !row.isDefault
        }
    }

    // Favourites float to the top within whatever filter view is active, so
    // starred schemes are always surfaced first even in the All / Default /
    // Custom pills (stable sort keeps the internal order otherwise).
    val sorted = filtered.sortedByDescending { it.scheme.name in state.favoriteSchemes }

    for (row in sorted) {
        grid.appendChild(buildSchemeCard(row.scheme, row.isDefault, row.scheme.name == selected, onSelect))
    }

    // "+ New colour scheme" tile — sits at the very end as a regular card-sized row.
    if (filter != SchemeFilter.Default) {
        val newTile = document.createElement("div") as HTMLElement
        newTile.className = "theme-manager-card-item new-scheme-tile"
        newTile.innerHTML =
            """<span class="new-scheme-plus">+</span><span class="new-scheme-label">New colour scheme</span>"""
        newTile.addEventListener("click", {
            promptNewScheme { name -> onSelect(name) }
        })
        grid.appendChild(newTile)
    }

    container.appendChild(grid)

    if (filtered.isEmpty()) {
        val empty = document.createElement("div") as HTMLElement
        empty.className = "theme-manager-empty"
        empty.textContent = when (filter) {
            SchemeFilter.Favorites -> "No favorites yet — star a scheme to add it here."
            SchemeFilter.Custom -> "No custom schemes yet — click + New colour scheme to create one."
            else -> "No schemes match this filter."
        }
        container.appendChild(empty)
    }
}

/**
 * Apply per-card CSS variables so the absolutely-positioned star and kebab
 * buttons adapt to the card's preview palette instead of hardcoded colours.
 * Without these vars the star/kebab fall back to the global theme vars
 * (still legible, just not card-tuned) — see `.theme-manager-star` and
 * `.theme-manager-kebab` in styles.css.
 *
 * @param card           card element to style
 * @param previewScheme  the scheme shown inside the card (for theme cards,
 *                       the preset's main scheme; for scheme cards, the
 *                       scheme itself). Resolved against the active
 *                       appearance to pick dark- or light-mode colours.
 */
private fun applyCardPaletteVars(card: HTMLElement, previewScheme: ColorScheme) {
    val isDark = !isLightActive(appVm.stateFlow.value.appearance)
    val p = previewScheme.resolve(isDark)
    card.style.setProperty("--star-fg", argbToCss(p.text.secondary))
    card.style.setProperty("--star-active-fg", argbToCss(p.accent.primary))
    card.style.setProperty("--star-bg", argbToCss(p.surface.overlay))
    card.style.setProperty("--star-bg-hover", argbToCss(p.surface.sunken))
    card.style.setProperty("--kebab-fg", argbToCss(p.text.secondary))
    card.style.setProperty("--kebab-fg-hover", argbToCss(p.text.primary))
    card.style.setProperty("--kebab-bg", argbToCss(p.surface.overlay))
    card.style.setProperty("--kebab-bg-hover", argbToCss(p.surface.sunken))
}

/** Build a single scheme card: swatch + name + favourite star + kebab menu. */
private fun buildSchemeCard(
    scheme: ColorScheme,
    isDefault: Boolean,
    isSelected: Boolean,
    onSelect: (String) -> Unit,
): HTMLElement {
    val card = document.createElement("div") as HTMLElement
    // Schemes have no "active" state — a theme's section assignments drive
    // which scheme renders where, so a persistent .selected outline here
    // would imply selection state that doesn't exist.
    card.className = "theme-manager-card-item scheme-card" +
        if (isDefault) " default" else " custom"
    card.setAttribute("data-scheme-name", scheme.name)

    // Per-card CSS vars so the star/kebab pick up this scheme's fg/bg
    // instead of the hardcoded greys; readable across both light and dark
    // previews on every card.
    applyCardPaletteVars(card, scheme)

    val state = appVm.stateFlow.value
    val isFav = scheme.name in state.favoriteSchemes

    val star = document.createElement("button") as HTMLElement
    star.className = "theme-manager-star" + if (isFav) " active" else ""
    star.innerHTML = if (isFav) "★" else "☆"
    star.title = if (isFav) "Unfavorite" else "Favorite"
    star.addEventListener("click", { ev: Event ->
        ev.stopPropagation()
        GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
            appVm.toggleFavoriteScheme(scheme.name)
        }
        pokeManager()
    })
    card.appendChild(star)

    val kebab = document.createElement("button") as HTMLElement
    kebab.className = "theme-manager-kebab"
    kebab.innerHTML = "⋮"
    kebab.title = "More"
    kebab.addEventListener("click", { ev: Event ->
        ev.stopPropagation()
        showSchemeCardMenu(kebab, scheme, isDefault)
    })
    card.appendChild(kebab)

    val swatch = document.createElement("span") as HTMLElement
    swatch.className = "theme-manager-silhouette"
    swatch.innerHTML = renderThemeSwatch(scheme)
    card.appendChild(swatch)

    val nameRow = document.createElement("div") as HTMLElement
    nameRow.className = "theme-manager-card-name"
    val nameText = document.createElement("span") as HTMLElement
    nameText.textContent = scheme.name
    nameRow.appendChild(nameText)
    card.appendChild(nameRow)

    card.addEventListener("click", { onSelect(scheme.name) })
    return card
}

/** Per-card menu for schemes: clone, delete. */
private fun showSchemeCardMenu(
    anchor: HTMLElement,
    scheme: ColorScheme,
    isDefault: Boolean,
) {
    document.querySelectorAll(".theme-manager-menu").let { nl ->
        for (i in 0 until nl.length) (nl.item(i) as? HTMLElement)?.remove()
    }
    val menu = document.createElement("div") as HTMLElement
    menu.className = "theme-manager-menu"

    fun addItem(label: String, onClick: () -> Unit) {
        val item = document.createElement("button") as HTMLElement
        item.className = "theme-manager-menu-item"
        item.textContent = label
        item.addEventListener("click", { ev: Event ->
            ev.stopPropagation()
            onClick()
            menu.remove()
        })
        menu.appendChild(item)
    }

    addItem("Clone…") {
        showCloneSchemePrompt(scheme)
    }
    if (!isDefault) {
        addItem("Delete") {
            showConfirmDialog(
                title = "Delete scheme",
                message = "Delete colour scheme <b>${scheme.name}</b>?",
                confirmLabel = "Delete",
            ) {
                GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    appVm.deleteCustomScheme(scheme.name)
                }
                pokeManager()
            }
        }
    }

    val rect = anchor.getBoundingClientRect()
    menu.style.position = "fixed"
    menu.style.top = "${rect.bottom + 4}px"
    menu.style.left = "${rect.right - 200}px"
    document.body?.appendChild(menu)

    val dismiss = { _: Event -> menu.remove() }
    document.addEventListener("click", dismiss, js("({once:true})"))
}

/**
 * Prompt the user for a name and create a clone of [source] as a custom
 * scheme. After save, the manager (if open) is switched to the editor view
 * for the new scheme so the user can start tweaking it immediately.
 */
private fun showCloneSchemePrompt(source: ColorScheme) {
    val defaultName = "${source.name} Copy"
    val existing = buildSet {
        for (t in recommendedColorSchemes) add(t.name)
        for ((name, _) in appVm.stateFlow.value.customSchemes) add(name)
    }
    showNamePrompt(
        title = "Clone colour scheme",
        label = "New colour scheme name",
        initial = defaultName,
        validate = { candidate ->
            when {
                candidate.isBlank() -> "Name is required"
                candidate.equals("Default", ignoreCase = true) -> "\"Default\" is reserved"
                candidate in existing -> "A scheme with that name already exists"
                else -> null
            }
        },
    ) { newName ->
        val clone = CustomScheme(
            name = newName,
            darkFg = source.darkFg,
            lightFg = source.lightFg,
            darkBg = source.darkBg,
            lightBg = source.lightBg,
            overrides = source.overrides ?: emptyMap(),
        )
        GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
            appVm.saveCustomScheme(clone)
        }
        val focus = themeManagerFocusScheme
        if (focus != null) focus(newName) else pokeManager()
    }
}

/**
 * Prompt for a fresh custom colour scheme built from scratch (not cloned).
 *
 * The new scheme uses neutral black/white fg+bg defaults for both dark and
 * light appearance, with no semantic overrides. The user can tune every
 * colour in the right-pane editor after creation.
 *
 * @param onCreated called with the new scheme's name on success.
 */
private fun promptNewScheme(onCreated: (String) -> Unit) {
    val state = appVm.stateFlow.value
    val existing = buildSet {
        for (t in recommendedColorSchemes) add(t.name)
        for ((name, _) in state.customSchemes) add(name)
    }
    val defaultName = run {
        var n = 1
        var candidate = "New colour scheme"
        while (candidate in existing) {
            n += 1
            candidate = "New colour scheme $n"
        }
        candidate
    }
    showNamePrompt(
        title = "New colour scheme",
        label = "Scheme name",
        initial = defaultName,
        validate = { candidate ->
            when {
                candidate.isBlank() -> "Name is required"
                candidate.equals("Default", ignoreCase = true) -> "\"Default\" is reserved"
                candidate in existing -> "A scheme with that name already exists"
                else -> null
            }
        },
    ) { newName ->
        val fresh = CustomScheme(
            name = newName,
            darkBg = "#000000",
            darkFg = "#ffffff",
            lightBg = "#ffffff",
            lightFg = "#000000",
            overrides = emptyMap(),
        )
        GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
            appVm.saveCustomScheme(fresh)
        }
        onCreated(newName)
        pokeManager()
    }
}

// ── Scheme editor (right pane of Tab B) ───────────────────────────

/**
 * Right-hand editor for a single colour scheme. Default schemes are
 * displayed read-only with a prominent "Clone to edit" CTA; custom
 * schemes expose dark/light fg+bg pickers plus a flat list of semantic
 * override tokens (each editable for both appearance modes).
 */
private fun renderSchemeEditor(
    container: HTMLElement,
    selectedName: String?,
    refresh: () -> Unit,
) {
    container.innerHTML = ""
    val state = appVm.stateFlow.value
    val name = selectedName ?: return
    val custom = state.customSchemes[name]
    val source = custom?.toColorScheme() ?: recommendedColorSchemes.firstOrNull { it.name == name }
    if (source == null) {
        val empty = document.createElement("div") as HTMLElement
        empty.className = "theme-manager-empty"
        empty.textContent = "Scheme not found."
        container.appendChild(empty)
        return
    }
    val isDefault = custom == null
    // Fresh open — no pending edits. Input handlers below flip it on.
    isEditorDirty = false
    // Wired up after the Save/Revert buttons are created below so both
    // reflect [isEditorDirty] — stays a no-op until then and for default
    // (read-only) schemes, which never build those buttons.
    var syncActionsEnabled: () -> Unit = {}
    val markDirty: () -> Unit = {
        isEditorDirty = true
        syncActionsEnabled()
    }

    val header = document.createElement("div") as HTMLElement
    header.className = "theme-editor-header"

    val preview = document.createElement("div") as HTMLElement
    preview.className = "theme-editor-preview scheme-preview"
    preview.innerHTML = renderThemeSwatch(source)
    header.appendChild(preview)

    val info = document.createElement("div") as HTMLElement
    info.className = "theme-editor-info"

    val nameLabel = document.createElement("div") as HTMLElement
    nameLabel.className = "theme-editor-label"
    nameLabel.textContent = "Name"
    info.appendChild(nameLabel)

    val nameInput = document.createElement("input") as HTMLInputElement
    nameInput.className = "theme-editor-input"
    nameInput.type = "text"
    nameInput.value = source.name
    nameInput.disabled = isDefault
    nameInput.addEventListener("input", { _: Event -> markDirty() })
    info.appendChild(nameInput)

    header.appendChild(info)
    container.appendChild(header)

    // ── Dark / light fg+bg pickers ──
    val fgbg = document.createElement("div") as HTMLElement
    fgbg.className = "scheme-editor-fgbg"
    fgbg.innerHTML = ""

    data class ColorField(val label: String, val getter: () -> String, val setter: (String) -> Unit)

    val mutable = object {
        var darkFg = source.darkFg
        var lightFg = source.lightFg
        var darkBg = source.darkBg
        var lightBg = source.lightBg
        val overrides = (source.overrides ?: emptyMap()).toMutableMap()
    }

    // Set by the overrides grid below when [isDefault] is false; stays a
    // no-op for default (read-only) schemes, which skip the grid entirely.
    var refreshDerivedSwatches: () -> Unit = {}

    val fields = listOf(
        ColorField("Dark background", { mutable.darkBg }, { mutable.darkBg = it }),
        ColorField("Dark foreground", { mutable.darkFg }, { mutable.darkFg = it }),
        ColorField("Light background", { mutable.lightBg }, { mutable.lightBg = it }),
        ColorField("Light foreground", { mutable.lightFg }, { mutable.lightFg = it }),
    )
    for (f in fields) {
        val row = document.createElement("div") as HTMLElement
        row.className = "scheme-editor-fgbg-row"
        val lbl = document.createElement("label") as HTMLElement
        lbl.className = "scheme-editor-fgbg-label"
        lbl.textContent = f.label
        row.appendChild(lbl)
        val picker = document.createElement("input") as HTMLInputElement
        picker.className = "scheme-editor-fgbg-picker"
        picker.type = "color"
        picker.value = normalizeHex(f.getter())
        picker.disabled = isDefault
        picker.addEventListener("input", { _: Event ->
            f.setter(picker.value)
            markDirty()
            refreshDerivedSwatches()
        })
        row.appendChild(picker)
        fgbg.appendChild(row)
    }
    container.appendChild(fgbg)

    // ── Semantic overrides (flat list, per token × appearance) ──
    val ovrSection = document.createElement("div") as HTMLElement
    ovrSection.className = "scheme-editor-overrides"
    val ovrTitle = document.createElement("div") as HTMLElement
    ovrTitle.className = "scheme-editor-overrides-title"
    ovrTitle.textContent = "Semantic overrides"
    ovrSection.appendChild(ovrTitle)

    if (isDefault) {
        val hint = document.createElement("div") as HTMLElement
        hint.className = "theme-editor-hint"
        hint.textContent = "Clone to edit overrides."
        ovrSection.appendChild(hint)
    } else {
        val hint = document.createElement("div") as HTMLElement
        hint.className = "theme-editor-hint"
        hint.textContent =
            "Every swatch shows the colour that will be used. Swatches with a " +
                "highlighted border are explicit overrides — click × to clear " +
                "them and let the value derive from the scheme's fg/bg live."
        ovrSection.appendChild(hint)

        // Float tokens with at least one override (dark or light) to the top,
        // preserving relative order within each group. Sorted once at open
        // time — rows stay stable while the user edits, so clearing an
        // override doesn't cause the row to jump.
        val tokens = OVERRIDE_TOKENS.sortedByDescending { tok ->
            mutable.overrides.containsKey("$tok.dark") ||
                mutable.overrides.containsKey("$tok.light")
        }
        val grid = document.createElement("div") as HTMLElement
        grid.className = "scheme-editor-overrides-grid"

        val head = document.createElement("div") as HTMLElement
        head.className = "scheme-editor-overrides-head"
        head.innerHTML = "<span>Token</span><span>Dark</span><span>Light</span>"
        grid.appendChild(head)

        data class PickerSlot(val tok: String, val isDark: Boolean, val input: HTMLInputElement)
        val slots = mutableListOf<PickerSlot>()

        refreshDerivedSwatches = run@{
            val themeNow = ColorScheme(
                name = source.name,
                darkFg = mutable.darkFg,
                lightFg = mutable.lightFg,
                darkBg = mutable.darkBg,
                lightBg = mutable.lightBg,
                overrides = mutable.overrides.toMap().ifEmpty { null },
            )
            val darkPal = themeNow.resolve(isDark = true)
            val lightPal = themeNow.resolve(isDark = false)
            val darkBgArgb = hexToArgb(mutable.darkBg)
            val lightBgArgb = hexToArgb(mutable.lightBg)
            for (slot in slots) {
                // Pickers that carry an explicit override already show the
                // user's chosen colour — leave them alone.
                if (slot.input.getAttribute("data-set") == "1") continue
                val pal = if (slot.isDark) darkPal else lightPal
                val argb = derivedTokenValue(pal, slot.tok) ?: continue
                val bg = if (slot.isDark) darkBgArgb else lightBgArgb
                slot.input.value = flattenHexForPicker(argb, bg)
            }
        }

        for (tok in tokens) {
            val row = document.createElement("div") as HTMLElement
            row.className = "scheme-editor-overrides-row"

            val lbl = document.createElement("span") as HTMLElement
            lbl.className = "scheme-editor-overrides-label"
            lbl.textContent = tok
            row.appendChild(lbl)

            // Each appearance (dark/light) gets its own picker + × pair so
            // the user can clear one side without disturbing the other.
            fun cell(mode: String): Pair<HTMLElement, HTMLInputElement> {
                val key = "$tok.$mode"
                val wrap = document.createElement("div") as HTMLElement
                wrap.className = "scheme-editor-overrides-cell"

                val p = document.createElement("input") as HTMLInputElement
                p.type = "color"
                p.className = "scheme-editor-overrides-picker"
                val cur = mutable.overrides[key]
                if (cur != null) {
                    p.value = argbToHex(cur)
                    p.setAttribute("data-set", "1")
                }

                val clear = document.createElement("button") as HTMLElement
                clear.className = "scheme-editor-overrides-reset"
                clear.innerHTML = "&times;"
                clear.title = "Clear the $mode override for this token — the " +
                    "swatch will derive from the scheme's fg/bg"

                fun syncClearEnabled() {
                    val active = p.getAttribute("data-set") == "1"
                    clear.asDynamic().disabled = !active
                    clear.classList.toggle("is-inactive", !active)
                }

                p.addEventListener("input", { _: Event ->
                    val argb = 0xFF000000L or hexToArgb(p.value).and(0x00FFFFFFL)
                    mutable.overrides[key] = argb
                    p.setAttribute("data-set", "1")
                    syncClearEnabled()
                    markDirty()
                    // Overriding one token can cascade to tokens whose
                    // derivation references it (e.g. text.tertiary →
                    // syntax.comment), so re-derive every unset swatch.
                    refreshDerivedSwatches()
                })
                clear.addEventListener("click", {
                    mutable.overrides.remove(key)
                    p.removeAttribute("data-set")
                    syncClearEnabled()
                    markDirty()
                    refreshDerivedSwatches()
                })

                syncClearEnabled()
                wrap.appendChild(p)
                wrap.appendChild(clear)
                return wrap to p
            }

            val (darkCell, darkPick) = cell("dark")
            row.appendChild(darkCell)
            val (lightCell, lightPick) = cell("light")
            row.appendChild(lightCell)
            slots += PickerSlot(tok, isDark = true, input = darkPick)
            slots += PickerSlot(tok, isDark = false, input = lightPick)

            grid.appendChild(row)
        }
        ovrSection.appendChild(grid)
        // Prime every unset swatch with its derived value now that every
        // picker has been created and registered.
        refreshDerivedSwatches()
    }
    container.appendChild(ovrSection)

    // ── Actions ──
    val actions = document.createElement("div") as HTMLElement
    actions.className = "theme-editor-actions"

    if (isDefault) {
        val cloneHint = document.createElement("div") as HTMLElement
        cloneHint.className = "theme-editor-hint"
        cloneHint.textContent = "Default schemes are read-only. Clone to edit."
        actions.appendChild(cloneHint)

        val cloneBtn = document.createElement("button") as HTMLElement
        cloneBtn.className = "theme-editor-btn primary"
        cloneBtn.textContent = "Clone to edit"
        cloneBtn.addEventListener("click", { showCloneSchemePrompt(source) })
        actions.appendChild(cloneBtn)
    } else {
        val saveBtn = document.createElement("button") as HTMLElement
        saveBtn.className = "theme-editor-btn primary"
        saveBtn.textContent = "Save"
        saveBtn.addEventListener("click", { _: Event ->
            val newName = nameInput.value.trim()
            if (newName.isEmpty()) return@addEventListener
            // Rename path: delete old + save new.
            val next = CustomScheme(
                name = newName,
                darkFg = mutable.darkFg,
                lightFg = mutable.lightFg,
                darkBg = mutable.darkBg,
                lightBg = mutable.lightBg,
                overrides = mutable.overrides.toMap(),
            )
            GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
                if (newName != source.name) {
                    appVm.deleteCustomScheme(source.name)
                }
                appVm.saveCustomScheme(next)
            }
            isEditorDirty = false
            pokeManager()
        })
        actions.appendChild(saveBtn)

        val revertBtn = document.createElement("button") as HTMLElement
        revertBtn.className = "theme-editor-btn"
        revertBtn.textContent = "Revert"
        revertBtn.addEventListener("click", {
            isEditorDirty = false
            refresh()
        })
        actions.appendChild(revertBtn)

        syncActionsEnabled = {
            val clean = !isEditorDirty
            console.log("[theme-editor] syncActionsEnabled scheme clean=$clean isEditorDirty=$isEditorDirty")
            saveBtn.asDynamic().disabled = clean
            revertBtn.asDynamic().disabled = clean
            saveBtn.classList.toggle("is-disabled", clean)
            revertBtn.classList.toggle("is-disabled", clean)
        }
        syncActionsEnabled()

        val deleteBtn = document.createElement("button") as HTMLElement
        deleteBtn.className = "theme-editor-btn danger"
        deleteBtn.textContent = "Delete"
        deleteBtn.addEventListener("click", {
            showConfirmDialog(
                title = "Delete scheme",
                message = "Delete colour scheme <b>${source.name}</b>?",
                confirmLabel = "Delete",
            ) {
                GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    appVm.deleteCustomScheme(source.name)
                }
                isEditorDirty = false
                pokeManager()
            }
        })
        actions.appendChild(deleteBtn)
    }

    container.appendChild(actions)
}

/**
 * Flat list of semantic token keys used in the scheme editor's overrides
 * grid. Mirrors the tokens that [ColorScheme.resolve] consults in
 * [ThemeResolver.kt]; keys match the `"group.token"` portion (appearance
 * suffix is added per-row). Every entry here is an override key the
 * resolver actually reads, so edits in the grid always round-trip.
 */
private val OVERRIDE_TOKENS: List<String> = listOf(
    "surface.base", "surface.raised", "surface.sunken", "surface.overlay",
    "text.primary", "text.secondary", "text.tertiary", "text.disabled", "text.inverse",
    "border.subtle", "border.default", "border.strong", "border.focus", "border.focusGlow",
    "accent.primary", "accent.primarySoft", "accent.primaryGlow", "accent.onPrimary",
    "semantic.danger", "semantic.warn", "semantic.success", "semantic.info",
    "terminal.bg", "terminal.fg", "terminal.cursor", "terminal.selection", "terminal.selectionText",
    "chrome.titlebar", "chrome.titleText", "chrome.border", "chrome.shadow",
    "chrome.closeDot", "chrome.minDot", "chrome.maxDot",
    "sidebar.bg", "sidebar.text", "sidebar.textDim", "sidebar.activeBg", "sidebar.activeText",
    "diff.addBg", "diff.addFg", "diff.addGutter",
    "diff.removeBg", "diff.removeFg", "diff.removeGutter", "diff.contextFg",
    "syntax.keyword", "syntax.string", "syntax.number", "syntax.comment",
    "syntax.function", "syntax.type", "syntax.operator", "syntax.constant",
)

/**
 * Returns the derived value of [tok] in [palette], or null when [tok] is not
 * a known semantic token. Used by the scheme editor to show what the
 * resolver would produce for any token that isn't explicitly overridden.
 *
 * @param palette the resolved palette for a given appearance
 * @param tok     one of the keys from [OVERRIDE_TOKENS]
 * @return the ARGB value, or null if [tok] is unrecognised
 */
private fun derivedTokenValue(palette: ResolvedPalette, tok: String): Long? = when (tok) {
    "surface.base" -> palette.surface.base
    "surface.raised" -> palette.surface.raised
    "surface.sunken" -> palette.surface.sunken
    "surface.overlay" -> palette.surface.overlay
    "text.primary" -> palette.text.primary
    "text.secondary" -> palette.text.secondary
    "text.tertiary" -> palette.text.tertiary
    "text.disabled" -> palette.text.disabled
    "text.inverse" -> palette.text.inverse
    "border.subtle" -> palette.border.subtle
    "border.default" -> palette.border.default
    "border.strong" -> palette.border.strong
    "border.focus" -> palette.border.focus
    "border.focusGlow" -> palette.border.focusGlow
    "accent.primary" -> palette.accent.primary
    "accent.primarySoft" -> palette.accent.primarySoft
    "accent.primaryGlow" -> palette.accent.primaryGlow
    "accent.onPrimary" -> palette.accent.onPrimary
    "semantic.danger" -> palette.semantic.danger
    "semantic.warn" -> palette.semantic.warn
    "semantic.success" -> palette.semantic.success
    "semantic.info" -> palette.semantic.info
    "terminal.bg" -> palette.terminal.bg
    "terminal.fg" -> palette.terminal.fg
    "terminal.cursor" -> palette.terminal.cursor
    "terminal.selection" -> palette.terminal.selection
    "terminal.selectionText" -> palette.terminal.selectionText
    "chrome.titlebar" -> palette.chrome.titlebar
    "chrome.titleText" -> palette.chrome.titleText
    "chrome.border" -> palette.chrome.border
    "chrome.shadow" -> palette.chrome.shadow
    "chrome.closeDot" -> palette.chrome.closeDot
    "chrome.minDot" -> palette.chrome.minDot
    "chrome.maxDot" -> palette.chrome.maxDot
    "sidebar.bg" -> palette.sidebar.bg
    "sidebar.text" -> palette.sidebar.text
    "sidebar.textDim" -> palette.sidebar.textDim
    "sidebar.activeBg" -> palette.sidebar.activeBg
    "sidebar.activeText" -> palette.sidebar.activeText
    "diff.addBg" -> palette.diff.addBg
    "diff.addFg" -> palette.diff.addFg
    "diff.addGutter" -> palette.diff.addGutter
    "diff.removeBg" -> palette.diff.removeBg
    "diff.removeFg" -> palette.diff.removeFg
    "diff.removeGutter" -> palette.diff.removeGutter
    "diff.contextFg" -> palette.diff.contextFg
    "syntax.keyword" -> palette.syntax.keyword
    "syntax.string" -> palette.syntax.string
    "syntax.number" -> palette.syntax.number
    "syntax.comment" -> palette.syntax.comment
    "syntax.function" -> palette.syntax.function
    "syntax.type" -> palette.syntax.type
    "syntax.operator" -> palette.syntax.operator
    "syntax.constant" -> palette.syntax.constant
    else -> null
}

/**
 * Flatten an ARGB value that may carry partial alpha to an opaque hex
 * string suitable for an `<input type=color>` swatch, compositing against
 * [bg] when the source is translucent. Some derived tokens
 * (e.g. `border.subtle`, `terminal.selection`) use alpha to tint against
 * the background — showing their raw RGB would misrepresent how they
 * render in the app, so we pre-composite for display only.
 */
private fun flattenHexForPicker(argb: Long, bg: Long): String {
    val alphaByte = ((argb shr 24) and 0xFF).toInt()
    if (alphaByte == 0xFF) return argbToHex(argb)
    val alphaF = alphaByte / 255.0
    return argbToHex(mixColors(bg, argb, alphaF))
}

// ── Misc helpers ──────────────────────────────────────────────────

/** Normalise any hex colour string to `#rrggbb` for the native colour picker. */
private fun normalizeHex(raw: String): String {
    if (raw.startsWith("#") && raw.length == 7) return raw
    if (raw.startsWith("#") && raw.length == 4) {
        val r = raw[1]; val g = raw[2]; val b = raw[3]
        return "#$r$r$g$g$b$b"
    }
    return "#000000"
}

/**
 * Generic modal name prompt used for clone operations. Uses the same
 * styling as the existing save-theme dialog.
 *
 * @param title         modal title
 * @param label         input label
 * @param initial       initial input value
 * @param validate      returns an error string or `null` if valid
 * @param onCommit      called with the final, validated name
 */
private fun showNamePrompt(
    title: String,
    label: String,
    initial: String,
    validate: (String) -> String?,
    onCommit: (String) -> Unit,
) {
    val overlay = document.createElement("div") as HTMLElement
    overlay.className = "pane-modal-overlay name-prompt-overlay"
    val cardEl = document.createElement("div") as HTMLElement
    cardEl.className = "pane-modal confirm-modal theme-config-save-modal"
    cardEl.addEventListener("click", { ev: Event -> ev.stopPropagation() })

    val titleEl = document.createElement("h2") as HTMLElement
    titleEl.className = "pane-modal-title"
    titleEl.textContent = title
    cardEl.appendChild(titleEl)

    val lblEl = document.createElement("label") as HTMLElement
    lblEl.className = "worktree-label"
    lblEl.textContent = label
    cardEl.appendChild(lblEl)

    val input = document.createElement("input") as HTMLInputElement
    input.className = "worktree-input"
    input.type = "text"
    input.value = initial
    input.setAttribute("autocomplete", "off")
    input.setAttribute("spellcheck", "false")
    cardEl.appendChild(input)

    val errorEl = document.createElement("div") as HTMLElement
    errorEl.className = "worktree-error"
    errorEl.style.display = "none"
    cardEl.appendChild(errorEl)

    val btnRow = document.createElement("div") as HTMLElement
    btnRow.className = "confirm-buttons"

    val cancelBtn = document.createElement("button") as HTMLElement
    cancelBtn.className = "confirm-btn confirm-cancel"
    cancelBtn.textContent = "Cancel"
    cancelBtn.addEventListener("click", { overlay.remove() })
    btnRow.appendChild(cancelBtn)

    val okBtn = document.createElement("button") as HTMLElement
    okBtn.className = "confirm-btn confirm-ok"
    okBtn.textContent = "OK"

    // Re-run [validate] on every input change so OK reflects validity
    // upfront, rather than only surfacing the error after a click. We only
    // show the error text once the user has touched the field so the
    // initial prompt (which may start with a valid default) stays quiet.
    var dirty = false
    val syncValidity = {
        val err = validate(input.value.trim())
        okBtn.asDynamic().disabled = err != null
        if (dirty && err != null) {
            errorEl.textContent = err
            errorEl.style.display = ""
        } else {
            errorEl.textContent = ""
            errorEl.style.display = "none"
        }
    }
    input.addEventListener("input", { _: Event ->
        dirty = true
        syncValidity()
    })
    syncValidity()

    val doCommit = {
        val v = input.value.trim()
        val err = validate(v)
        if (err != null) {
            dirty = true
            syncValidity()
        } else {
            overlay.remove()
            onCommit(v)
        }
    }
    okBtn.addEventListener("click", { doCommit() })
    btnRow.appendChild(okBtn)

    cardEl.appendChild(btnRow)
    overlay.appendChild(cardEl)

    overlay.addEventListener("click", { ev: Event ->
        if (ev.target === overlay) overlay.remove()
    })
    input.addEventListener("keydown", { ev: Event ->
        val ke = ev as KeyboardEvent
        if (ke.key == "Enter") { ev.preventDefault(); doCommit() }
        else if (ke.key == "Escape") overlay.remove()
    })

    document.body?.appendChild(overlay)
    input.focus(); input.select()
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
 * @see openSectionSchemeMenu for the sibling theme-editor dropdown
 * @see buildSchemeGroups for the shared grouping/ordering
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
