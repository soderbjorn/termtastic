/**
 * Rendering-only client-local state: which tab is active, the most recent
 * (synchronously set) `WindowConfig` snapshot, collapsed-tab and
 * collapsed-git-section bookkeeping, and DOM bookkeeping for the modal
 * overlays (settings panel, theme manager, pane-type modal).
 *
 * Lives next to [WebState] in the same package so unqualified call sites
 * still resolve.
 */
package se.soderbjorn.termtastic

import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/** Currently-selected tab id, or null before the first config arrives. */
internal var activeTabId: String? = null

/**
 * The most recent config received over the `/window` socket, set
 * synchronously by `renderConfig` before the AppBackingViewModel coroutine
 * gets a chance to process the StateFlow update. Read by code that needs
 * the freshest tabs/focus state without lag.
 */
internal var currentConfig: dynamic = null

/** Tab ids the user has collapsed in the sidebar tree. */
internal val collapsedTabs = HashSet<String>()

/** Tab ids that existed before the latest re-render. */
internal val previousTabIds = HashSet<String>()

/** Pending tab-flip animation map (tab id → start time), or null when idle. */
internal var pendingTabFlip: Map<String, Double>? = null

/** Whether this is the very first render after page load. */
internal var firstRender = true

/** Whether the in-app dev-tools menu is enabled (only in localhost dev). */
internal var devToolsEnabled = false

/** Last seen per-session state map; used to detect notification transitions. */
internal val previousSessionStates = HashMap<String, String?>()

/** Settings panel root element, or null when the panel is closed. */
internal var settingsPanel: HTMLElement? = null

/** ESC key handler installed when the settings panel is open. */
internal var settingsEscHandler: ((Event) -> Unit)? = null

/**
 * Theme manager (right sidebar) root element. Only one right sidebar —
 * settings OR theme manager — is visible at a time; opening either closes
 * the other via `closeSettingsPanel` / `closeThemeManager`.
 */
internal var themeManagerPanel: HTMLElement? = null

/** ESC key handler installed when the theme manager is open. */
internal var themeManagerEscHandler: ((Event) -> Unit)? = null

/** Pane-type modal preview entries. */
internal val previewEntries = mutableListOf<dynamic>()

/** ESC key handler installed when the pane-type modal is open. */
internal var modalEscHandler: ((Event) -> Unit)? = null

/** Git pane sections collapsed by the user (e.g. unstaged, staged, …). */
internal val collapsedGitSections = HashSet<String>()
