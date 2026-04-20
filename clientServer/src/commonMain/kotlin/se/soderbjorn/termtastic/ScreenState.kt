/**
 * Per-screen (per-window) view state for Termtastic's multi-window support.
 *
 * Each main window (called a "screen") has its own active tab, focused pane,
 * sidebar configuration, theme/appearance overrides, and OS window bounds.
 * All screens share the same [WindowConfig] tab/pane tree and PTY sessions;
 * only the view-layer state differs.
 *
 * The server persists a list of [ScreenState] objects alongside the shared
 * [WindowConfig]. Screens are identified by a stable integer index (0, 1, 2, …).
 * Screen 0 always exists and is the primary window. Additional screens are
 * created via the "New Window" menu or keyboard shortcut. Closing a non-primary
 * screen sets [ScreenState.open] to `false` but preserves its state so it can
 * be restored later.
 *
 * @see WindowEnvelope.ScreenStateMsg for the server-to-client push
 * @see WindowCommand.SetScreenActiveTab for per-screen tab switching
 * @see WindowCommand.SetScreenBounds for window position persistence
 */
package se.soderbjorn.termtastic

import kotlinx.serialization.Serializable

/**
 * View state for a single screen (OS window). Fields with `null` values
 * inherit from the global UI settings; non-null values override them for
 * this screen only.
 *
 * @param screenIndex stable integer identifier for this screen (0 = primary)
 * @param open whether this screen's window is currently open; `false` means
 *   the window was closed but its state is preserved for future reopening
 * @param activeTabId the id of the currently-selected tab in this screen,
 *   or `null` to fall back to [WindowConfig.activeTabId]
 * @param focusedPaneIds per-tab focused pane overrides for this screen;
 *   keys are tab ids, values are the last-focused pane id within that tab
 * @param sidebarCollapsed whether the left sidebar is collapsed in this screen
 * @param sidebarWidth width of the left sidebar in pixels, or `null` for default
 * @param appearance appearance mode override ("Auto", "Dark", "Light"), or `null`
 *   to inherit from global settings
 * @param themeName primary theme name override, or `null` to inherit
 * @param sidebarThemeName sidebar section theme override
 * @param terminalThemeName terminal section theme override
 * @param diffThemeName diff viewer section theme override
 * @param fileBrowserThemeName file browser section theme override
 * @param tabsThemeName tab bar section theme override
 * @param chromeThemeName chrome (header/borders) section theme override
 * @param windowsThemeName floating windows section theme override
 * @param activeThemeName active pane highlight section theme override
 * @param paneFontSize font size override for panes, or `null` for default
 * @param bounds OS window position, size, and target display
 */
@Serializable
data class ScreenState(
    val screenIndex: Int,
    val open: Boolean = true,
    val activeTabId: String? = null,
    val focusedPaneIds: Map<String, String> = emptyMap(),
    val sidebarCollapsed: Boolean = false,
    val sidebarWidth: Int? = null,
    val appearance: String? = null,
    val themeName: String? = null,
    val sidebarThemeName: String? = null,
    val terminalThemeName: String? = null,
    val diffThemeName: String? = null,
    val fileBrowserThemeName: String? = null,
    val tabsThemeName: String? = null,
    val chromeThemeName: String? = null,
    val windowsThemeName: String? = null,
    val activeThemeName: String? = null,
    val paneFontSize: Int? = null,
    val bounds: ScreenBounds? = null,
)

/**
 * OS window position, size, and target display for a screen. Persisted so
 * windows reopen at their last location, even across application restarts.
 *
 * @param x horizontal position of the window's top-left corner in screen coordinates
 * @param y vertical position of the window's top-left corner in screen coordinates
 * @param width window width in pixels
 * @param height window height in pixels
 * @param displayId Electron `Display.id` identifying which monitor the window
 *   belongs to, or `null` if unknown. Used to restore the window onto the
 *   correct monitor when multiple displays are connected.
 */
@Serializable
data class ScreenBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val displayId: String? = null,
)
