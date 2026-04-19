/**
 * Dialog and helper functions for saving, loading, and deleting named theme
 * configurations.
 *
 * A "theme configuration" is a snapshot of the user's current main theme plus
 * all per-section overrides, stored under the `"themeConfigs"` key in the
 * generic `ui.settings.v1` JSON blob. This file provides:
 *
 * - [showSaveThemeConfigDialog]: a modal dialog that lets the user either
 *   pick an existing configuration to overwrite or enter a new name.
 * - [fetchThemeConfigs]: reads the saved configs from the server.
 * - [persistThemeConfigs]: writes the full configs map back to the server.
 * - [buildCurrentThemeConfigJson]: captures the current theme state from the
 *   [AppBackingViewModel].
 * - [applyThemeConfig]: loads a saved configuration into the running app.
 *
 * @see SettingsPanel where the config list is rendered
 * @see ConfirmDialog used for delete confirmation
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import kotlin.js.json

/** Key used inside the `ui.settings.v1` JSON blob to store theme configs. */
private const val THEME_CONFIGS_KEY = "themeConfigs"

/** Section keys matching [AppBackingViewModel.State] section theme properties. */
private val SECTION_KEYS = listOf(
    "sidebar", "terminal", "diff", "fileBrowser", "tabs", "chrome", "windows", "active"
)

/**
 * Fetch all saved theme configurations from the server by reading the
 * `themeConfigs` key from the `ui.settings.v1` blob.
 *
 * @param callback invoked with a map of config-name to theme-key objects,
 *   or an empty map if none exist
 */
fun fetchThemeConfigs(callback: (Map<String, dynamic>) -> Unit) {
    val init: dynamic = js("({})")
    init.headers = json(
        "X-Termtastic-Auth" to authTokenForSending(),
        "X-Termtastic-Client-Type" to clientTypeAtStart,
    )
    window.fetch("/api/ui-settings", init).then { resp ->
        val ok = resp.asDynamic().ok as Boolean
        if (!ok) return@then null
        resp.json()
    }.then { data ->
        if (data == null) {
            callback(emptyMap())
            return@then
        }
        val configs = data.asDynamic()[THEME_CONFIGS_KEY]
        if (configs == null || configs == undefined) {
            callback(emptyMap())
            return@then
        }
        val keys = js("Object.keys(configs)") as Array<String>
        val result = mutableMapOf<String, dynamic>()
        for (key in keys) {
            result[key] = configs[key]
        }
        callback(result)
    }
}

/**
 * Persist the full theme configurations map back to the server by POSTing
 * it as the `themeConfigs` key in the `ui.settings.v1` blob.
 *
 * @param configs the complete map of config-name to theme-key objects
 * @param callback invoked on success
 */
fun persistThemeConfigs(configs: Map<String, dynamic>, callback: () -> Unit) {
    val configsObj: dynamic = js("({})")
    for ((name, value) in configs) {
        configsObj[name] = value
    }
    val body = json(THEME_CONFIGS_KEY to configsObj)
    val init: dynamic = js("({})")
    init.method = "POST"
    init.headers = json(
        "Content-Type" to "application/json",
        "X-Termtastic-Auth" to authTokenForSending(),
        "X-Termtastic-Client-Type" to clientTypeAtStart,
    )
    init.body = JSON.stringify(body)
    window.fetch("/api/ui-settings", init).then { callback() }
}

/**
 * Build a JS object capturing the current theme state from the ViewModel.
 * Contains `"theme"` (main) and `"theme.<section>"` for each section override.
 *
 * @return a plain JS object suitable for storage in the configs map
 */
fun buildCurrentThemeConfigJson(): dynamic {
    val state = appVm.stateFlow.value
    val obj: dynamic = js("({})")
    obj["theme"] = state.theme.name
    obj["theme.sidebar"] = state.sidebarTheme?.name ?: ""
    obj["theme.terminal"] = state.terminalTheme?.name ?: ""
    obj["theme.diff"] = state.diffTheme?.name ?: ""
    obj["theme.fileBrowser"] = state.fileBrowserTheme?.name ?: ""
    obj["theme.tabs"] = state.tabsTheme?.name ?: ""
    obj["theme.chrome"] = state.chromeTheme?.name ?: ""
    obj["theme.windows"] = state.windowsTheme?.name ?: ""
    obj["theme.active"] = state.activeTheme?.name ?: ""
    return obj
}

/**
 * Apply a saved theme configuration by setting the main theme and all
 * section overrides on the ViewModel. Each call persists individually
 * via the existing [SettingsPersister].
 *
 * @param config the saved JS object with `"theme"` and `"theme.<section>"` keys
 */
fun applyThemeConfig(config: dynamic) {
    val mainName = config["theme"] as? String
    val mainTheme = mainName?.let { n -> recommendedThemes.firstOrNull { it.name == n } }
        ?: return
    val sections = mutableMapOf<String, TerminalTheme?>()
    for (section in SECTION_KEYS) {
        val name = config["theme.$section"] as? String
        sections[section] = if (name.isNullOrEmpty()) null
            else recommendedThemes.firstOrNull { it.name == name }
    }
    appVm.applyThemeConfiguration(mainTheme, sections)
    applyAll()
}

/**
 * Show a modal dialog to save the current theme combination as a named
 * configuration. The user can either select an existing configuration to
 * overwrite, or type a new name to create a fresh entry. On save, the
 * configuration is persisted to the server and [onSaved] is called so the
 * caller can refresh its list.
 *
 * Only one instance can be open at a time (guarded by element ID).
 * Dismissed by Cancel, Escape, or backdrop click.
 *
 * @param existingNames set of already-saved configuration names
 * @param onSaved callback invoked with the chosen name after a successful save
 */
fun showSaveThemeConfigDialog(
    existingNames: Set<String>,
    onSaved: (String) -> Unit,
) {
    if (document.getElementById("theme-config-dialog") != null) return

    /** Tracks which existing config is selected for overwrite, if any. */
    var selectedExisting: String? = null

    val overlay = document.createElement("div") as HTMLElement
    overlay.id = "theme-config-dialog"
    overlay.className = "pane-modal-overlay"

    val card = document.createElement("div") as HTMLElement
    card.className = "pane-modal confirm-modal theme-config-save-modal"
    card.addEventListener("click", { ev: Event -> ev.stopPropagation() })

    val titleEl = document.createElement("h2") as HTMLElement
    titleEl.className = "pane-modal-title"
    titleEl.textContent = "Save Theme Configuration"
    card.appendChild(titleEl)

    val errorEl = document.createElement("div") as HTMLElement
    errorEl.className = "worktree-error"
    errorEl.style.display = "none"

    // ── New-name section ──
    val newLabel = document.createElement("label") as HTMLElement
    newLabel.className = "worktree-label"
    newLabel.textContent = "Create new"
    card.appendChild(newLabel)

    val nameInput = document.createElement("input") as HTMLInputElement
    nameInput.className = "worktree-input"
    nameInput.type = "text"
    nameInput.placeholder = "My theme setup"
    nameInput.setAttribute("autocomplete", "off")
    nameInput.setAttribute("spellcheck", "false")
    card.appendChild(nameInput)

    /** Callback to enable/disable the Save button; assigned once the button exists. */
    var updateSaveEnabled: () -> Unit = {}

    // ── Existing-configs list (only shown when there are saved configs) ──
    val listItems = mutableListOf<HTMLElement>()
    if (existingNames.isNotEmpty()) {
        val existLabel = document.createElement("label") as HTMLElement
        existLabel.className = "worktree-label"
        existLabel.textContent = "Or overwrite existing"
        card.appendChild(existLabel)

        val listContainer = document.createElement("div") as HTMLElement
        listContainer.className = "theme-config-list"
        for (name in existingNames.sorted()) {
            val item = document.createElement("div") as HTMLElement
            item.className = "theme-config-list-item"
            item.textContent = name
            item.addEventListener("click", { _: Event ->
                if (selectedExisting == name) {
                    // Deselect
                    selectedExisting = null
                    item.classList.remove("selected")
                } else {
                    // Deselect previous
                    listItems.forEach { it.classList.remove("selected") }
                    selectedExisting = name
                    item.classList.add("selected")
                    // Clear the new-name input when picking an existing one
                    nameInput.value = ""
                }
                errorEl.style.display = "none"
                updateSaveEnabled()
            })
            listContainer.appendChild(item)
            listItems.add(item)
        }
        card.appendChild(listContainer)
    }

    // Clear the existing selection when typing a new name
    nameInput.addEventListener("input", { _: Event ->
        if (nameInput.value.isNotEmpty() && selectedExisting != null) {
            selectedExisting = null
            listItems.forEach { it.classList.remove("selected") }
        }
        errorEl.style.display = "none"
        updateSaveEnabled()
    })

    card.appendChild(errorEl)

    val btnRow = document.createElement("div") as HTMLElement
    btnRow.className = "confirm-buttons"

    val cancelBtn = document.createElement("button") as HTMLElement
    cancelBtn.className = "confirm-btn confirm-cancel"
    (cancelBtn.asDynamic()).type = "button"
    cancelBtn.textContent = "Cancel"
    cancelBtn.addEventListener("click", { _: Event -> overlay.remove() })
    btnRow.appendChild(cancelBtn)

    val saveBtn = document.createElement("button") as HTMLElement
    saveBtn.className = "confirm-btn confirm-ok"
    (saveBtn.asDynamic()).type = "button"
    saveBtn.textContent = "Save"
    updateSaveEnabled = {
        val enabled = nameInput.value.trim().isNotEmpty() || selectedExisting != null
        saveBtn.asDynamic().disabled = !enabled
    }
    updateSaveEnabled()
    saveBtn.addEventListener("click", { _: Event ->
        val newName = nameInput.value.trim()
        val targetName: String
        if (selectedExisting != null) {
            // Overwriting an existing configuration
            targetName = selectedExisting!!
        } else if (newName.isNotEmpty()) {
            // Creating a new configuration — reject if the name is already taken
            if (newName in existingNames) {
                errorEl.textContent = "Name already exists — select it above to overwrite"
                errorEl.style.display = ""
                return@addEventListener
            }
            targetName = newName
        } else {
            errorEl.textContent = "Enter a new name or select an existing configuration"
            errorEl.style.display = ""
            return@addEventListener
        }
        val configData = buildCurrentThemeConfigJson()
        // Re-fetch current configs to avoid clobbering concurrent changes,
        // then add/update the entry and persist.
        fetchThemeConfigs { current ->
            val updated = current.toMutableMap()
            updated[targetName] = configData
            persistThemeConfigs(updated) {
                onSaved(targetName)
            }
        }
        overlay.remove()
    })
    btnRow.appendChild(saveBtn)

    card.appendChild(btnRow)
    overlay.appendChild(card)

    overlay.addEventListener("click", { ev: Event ->
        if (ev.target === overlay) overlay.remove()
    })
    var escHandler: ((Event) -> Unit)? = null
    escHandler = { ev: Event ->
        if ((ev as KeyboardEvent).key == "Escape") {
            overlay.remove()
            escHandler?.let { document.removeEventListener("keydown", it) }
        }
    }
    document.addEventListener("keydown", escHandler)

    nameInput.addEventListener("keydown", { ev: Event ->
        if ((ev as KeyboardEvent).key == "Enter") {
            ev.preventDefault()
            saveBtn.asDynamic().click()
        }
    })

    document.body?.appendChild(overlay)
    nameInput.focus()
}
