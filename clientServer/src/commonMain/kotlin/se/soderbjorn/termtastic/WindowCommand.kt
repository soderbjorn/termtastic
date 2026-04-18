/**
 * Client-to-server mutation commands and PTY control messages for the
 * Termtastic terminal emulator. These sealed hierarchies define every
 * structured JSON message a client can send to the server over the
 * `/window` and `/pty/{id}` WebSocket connections.
 *
 * @see WindowEnvelope for the server-to-client direction
 * @see windowJson for the shared serialization configuration
 */
package se.soderbjorn.termtastic

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Mutation commands sent client → server over the `/window` websocket as
 * JSON Text frames. Each subclass maps 1:1 to a branch of the server's old
 * `handleWindowCommand` `when` block. The discriminator is `"type"` (not the
 * global `"kind"` [windowJson] uses for nested pane hierarchies) — preserved
 * via [JsonClassDiscriminator] so the encoded form matches the pre-refactor
 * wire format byte-for-byte.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class WindowCommand {
    /**
     * Split an existing pane and place a new terminal in the created region.
     *
     * @param paneId the id of the pane to split
     * @param direction which side of the existing pane the new terminal appears on
     */
    @Serializable
    @SerialName("splitTerminal")
    data class SplitTerminal(val paneId: String, val direction: SplitDirection) : WindowCommand()

    /**
     * Split an existing pane and place a new file browser in the created region.
     *
     * @param paneId the id of the pane to split
     * @param direction which side of the existing pane the new file browser appears on
     */
    @Serializable
    @SerialName("splitFileBrowser")
    data class SplitFileBrowser(val paneId: String, val direction: SplitDirection) : WindowCommand()

    /**
     * Split an existing pane and place a new git overview in the created region.
     *
     * @param paneId the id of the pane to split
     * @param direction which side of the existing pane the new git pane appears on
     */
    @Serializable
    @SerialName("splitGit")
    data class SplitGit(val paneId: String, val direction: SplitDirection) : WindowCommand()

    /**
     * Split an existing pane and place a linked view of another terminal session
     * in the created region. The linked view shares the PTY session with the
     * original pane, allowing side-by-side views of the same terminal.
     *
     * @param paneId the id of the pane to split
     * @param direction which side of the existing pane the linked view appears on
     * @param targetSessionId the PTY session id to link to
     * @see LeafNode.isLink
     */
    @Serializable
    @SerialName("splitLink")
    data class SplitLink(val paneId: String, val direction: SplitDirection, val targetSessionId: String) : WindowCommand()

    /**
     * Request a one-level directory listing from the server for the file browser.
     * The server responds with a [WindowEnvelope.FileBrowserDir] message.
     *
     * @param paneId the file browser pane requesting the listing
     * @param dirRelPath relative path of the directory to list (`""` for root)
     */
    @Serializable
    @SerialName("fileBrowserListDir")
    data class FileBrowserListDir(val paneId: String, val dirRelPath: String) : WindowCommand()

    /**
     * Request rendered content for a file selected in the file browser.
     * The server responds with a [WindowEnvelope.FileBrowserContentMsg].
     *
     * @param paneId the file browser pane requesting the content
     * @param relPath relative path of the file to open and render
     */
    @Serializable
    @SerialName("fileBrowserOpenFile")
    data class FileBrowserOpenFile(val paneId: String, val relPath: String) : WindowCommand()

    /**
     * Toggle the expanded/collapsed state of a directory in the file browser tree.
     *
     * @param paneId the file browser pane containing the directory
     * @param dirRelPath relative path of the directory to expand or collapse
     * @param expanded `true` to expand, `false` to collapse
     */
    @Serializable
    @SerialName("fileBrowserSetExpanded")
    data class FileBrowserSetExpanded(
        val paneId: String,
        val dirRelPath: String,
        val expanded: Boolean,
    ) : WindowCommand()

    /**
     * Persist a new width for the file browser's left tree column after a drag resize.
     *
     * @param paneId the file browser pane being resized
     * @param px new width of the left column in pixels
     */
    @Serializable
    @SerialName("setFileBrowserLeftWidth")
    data class SetFileBrowserLeftWidth(val paneId: String, val px: Int) : WindowCommand()

    /**
     * Enable or disable automatic refresh of the file browser tree when the
     * filesystem changes.
     *
     * @param paneId the file browser pane to configure
     * @param enabled `true` to enable auto-refresh, `false` to disable
     */
    @Serializable
    @SerialName("setFileBrowserAutoRefresh")
    data class SetFileBrowserAutoRefresh(val paneId: String, val enabled: Boolean) : WindowCommand()

    /**
     * Set a filename filter for directory listings in the file browser.
     * Supports glob syntax (`*`, `?`). An empty string clears the filter.
     *
     * @param paneId the file browser pane to configure
     * @param filter glob pattern to apply, or `""` to show all files
     * @see FileBrowserContent.fileFilter
     */
    @Serializable
    @SerialName("setFileBrowserFilter")
    data class SetFileBrowserFilter(val paneId: String, val filter: String) : WindowCommand()

    /**
     * Change the sort order of entries within each directory listing in the file browser.
     *
     * @param paneId the file browser pane to configure
     * @param sort the desired sort order
     * @see FileBrowserSort
     */
    @Serializable
    @SerialName("setFileBrowserSort")
    data class SetFileBrowserSort(val paneId: String, val sort: FileBrowserSort) : WindowCommand()

    /**
     * Recursively list every directory under the pane's cwd (subject to caps)
     * and mark them as expanded. The server replies with one [WindowEnvelope.FileBrowserDir]
     * per visited directory; the resulting expandedDirs set is pushed back via
     * the regular WindowConfig flow.
     */
    @Serializable
    @SerialName("fileBrowserExpandAll")
    data class FileBrowserExpandAll(val paneId: String) : WindowCommand()

    /**
     * Collapse every expanded directory in the file browser tree, resetting
     * the expanded set to empty.
     *
     * @param paneId the file browser pane to collapse
     */
    @Serializable
    @SerialName("fileBrowserCollapseAll")
    data class FileBrowserCollapseAll(val paneId: String) : WindowCommand()

    /**
     * Change the font size used in the file browser's preview pane.
     *
     * @param paneId the file browser pane to configure
     * @param size font size in pixels
     */
    @Serializable
    @SerialName("setFileBrowserFontSize")
    data class SetFileBrowserFontSize(val paneId: String, val size: Int) : WindowCommand()

    /**
     * Request the list of uncommitted changes for the git pane's working directory.
     * The server responds with a [WindowEnvelope.GitList] message.
     *
     * @param paneId the git pane requesting the file list
     */
    @Serializable
    @SerialName("gitList")
    data class GitList(val paneId: String) : WindowCommand()

    /**
     * Request the diff for a specific file in the git pane.
     * The server responds with a [WindowEnvelope.GitDiffResult] message.
     *
     * @param paneId the git pane requesting the diff
     * @param filePath path of the file to diff (relative to the working directory)
     */
    @Serializable
    @SerialName("gitDiff")
    data class GitDiff(val paneId: String, val filePath: String) : WindowCommand()

    /**
     * Persist a new width for the git pane's left file list column after a drag resize.
     *
     * @param paneId the git pane being resized
     * @param px new width of the left column in pixels
     */
    @Serializable
    @SerialName("setGitLeftWidth")
    data class SetGitLeftWidth(val paneId: String, val px: Int) : WindowCommand()

    /**
     * Switch the git pane between inline (unified) and split (side-by-side) diff modes.
     *
     * @param paneId the git pane to configure
     * @param mode the desired diff display mode
     * @see GitDiffMode
     */
    @Serializable
    @SerialName("setGitDiffMode")
    data class SetGitDiffMode(val paneId: String, val mode: GitDiffMode) : WindowCommand()

    /**
     * Enable or disable the graphical diff view (P4Merge-style with SVG connector
     * lines) when the git pane is in [GitDiffMode.Split] mode.
     *
     * @param paneId the git pane to configure
     * @param enabled `true` to enable the graphical view, `false` for plain text
     */
    @Serializable
    @SerialName("setGitGraphicalDiff")
    data class SetGitGraphicalDiff(val paneId: String, val enabled: Boolean) : WindowCommand()

    /**
     * Change the font size used in the git pane's diff content area.
     *
     * @param paneId the git pane to configure
     * @param size font size in pixels
     */
    @Serializable
    @SerialName("setGitDiffFontSize")
    data class SetGitDiffFontSize(val paneId: String, val size: Int) : WindowCommand()

    /**
     * Enable or disable automatic refresh of the git file list when the
     * working tree changes.
     *
     * @param paneId the git pane to configure
     * @param enabled `true` to enable auto-refresh, `false` to disable
     */
    @Serializable
    @SerialName("setGitAutoRefresh")
    data class SetGitAutoRefresh(val paneId: String, val enabled: Boolean) : WindowCommand()

    /**
     * Change the font size used in a terminal pane.
     *
     * @param paneId the terminal pane to configure
     * @param size font size in pixels
     */
    @Serializable
    @SerialName("setTerminalFontSize")
    data class SetTerminalFontSize(val paneId: String, val size: Int) : WindowCommand()

    /**
     * Close a single pane. If the pane is a terminal, its PTY session is
     * terminated (unless other linked views still reference it).
     *
     * @param paneId the pane to close
     */
    @Serializable
    @SerialName("close")
    data class Close(val paneId: String) : WindowCommand()

    /** Close every pane that references [sessionId]. Used when closing a
     *  terminal that has linked panes — all views of the session go away. */
    @Serializable
    @SerialName("closeSession")
    data class CloseSession(val sessionId: String) : WindowCommand()

    /**
     * Set a custom display name for a pane, overriding the default cwd-based title.
     *
     * @param paneId the pane to rename
     * @param title the new custom display name
     * @see LeafNode.customName
     */
    @Serializable
    @SerialName("rename")
    data class Rename(val paneId: String, val title: String) : WindowCommand()

    /** Create a new empty tab and append it to the tab bar. */
    @Serializable
    @SerialName("addTab")
    object AddTab : WindowCommand()

    /**
     * Close a tab and all panes within it (docked, floating, and popped out).
     *
     * @param tabId the id of the tab to close
     */
    @Serializable
    @SerialName("closeTab")
    data class CloseTab(val tabId: String) : WindowCommand()

    /**
     * Change the display title of a tab in the tab bar.
     *
     * @param tabId the id of the tab to rename
     * @param title the new display title
     */
    @Serializable
    @SerialName("renameTab")
    data class RenameTab(val tabId: String, val title: String) : WindowCommand()

    /**
     * Reorder a tab by moving it before or after another tab via drag-and-drop.
     *
     * @param tabId the id of the tab being moved
     * @param targetTabId the id of the reference tab
     * @param before `true` to place [tabId] before [targetTabId], `false` to place it after
     */
    @Serializable
    @SerialName("moveTab")
    data class MoveTab(
        val tabId: String,
        val targetTabId: String,
        val before: Boolean,
    ) : WindowCommand()

    /**
     * Update the split ratio of a [SplitNode] after a drag resize of the divider.
     *
     * @param splitId the id of the split node
     * @param ratio new relative size of the first child, in `(0, 1)`
     * @see SplitNode.ratio
     */
    @Serializable
    @SerialName("setRatio")
    data class SetRatio(val splitId: String, val ratio: Double) : WindowCommand()

    /**
     * Incrementally resize a pane by adjusting its parent split's ratio by [delta].
     * Used for keyboard-driven resize (as opposed to drag-based [SetRatio]).
     *
     * @param paneId the pane to grow or shrink
     * @param delta fractional change to apply to the parent split ratio (positive = grow)
     */
    @Serializable
    @SerialName("resizePane")
    data class ResizePane(val paneId: String, val delta: Double) : WindowCommand()

    /**
     * Add a new terminal pane to an existing tab (creates a new PTY session).
     *
     * @param tabId the id of the tab to add the pane to
     */
    @Serializable
    @SerialName("addPaneToTab")
    data class AddPaneToTab(val tabId: String) : WindowCommand()

    /**
     * Add a new file browser pane to an existing tab.
     *
     * @param tabId the id of the tab to add the file browser to
     */
    @Serializable
    @SerialName("addFileBrowserToTab")
    data class AddFileBrowserToTab(val tabId: String) : WindowCommand()

    /**
     * Add a new git overview pane to an existing tab.
     *
     * @param tabId the id of the tab to add the git pane to
     */
    @Serializable
    @SerialName("addGitToTab")
    data class AddGitToTab(val tabId: String) : WindowCommand()

    /**
     * Add a linked view of an existing terminal session to a tab.
     *
     * @param tabId the id of the tab to add the link to
     * @param targetSessionId the PTY session id to create a linked view of
     * @see LeafNode.isLink
     */
    @Serializable
    @SerialName("addLinkToTab")
    data class AddLinkToTab(val tabId: String, val targetSessionId: String) : WindowCommand()

    /**
     * Toggle a pane between docked (in the split tree) and floating (free-positioned
     * overlay) states.
     *
     * @param paneId the pane to toggle
     * @see FloatingPane
     */
    @Serializable
    @SerialName("toggleFloating")
    data class ToggleFloating(val paneId: String) : WindowCommand()

    /**
     * Update the position and size of a floating pane after the user drags or
     * resizes it. All values are fractions of the tab area dimensions.
     *
     * @param paneId the floating pane to reposition
     * @param x top-left x as a fraction of the tab area's width
     * @param y top-left y as a fraction of the tab area's height
     * @param width width as a fraction of the tab area's width
     * @param height height as a fraction of the tab area's height
     * @see FloatingPane
     */
    @Serializable
    @SerialName("setFloatingGeom")
    data class SetFloatingGeom(
        val paneId: String,
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
    ) : WindowCommand()

    /**
     * Bring a floating pane to the top of the stacking order (raise its z-index).
     *
     * @param paneId the floating pane to raise
     * @see FloatingPane.z
     */
    @Serializable
    @SerialName("raiseFloating")
    data class RaiseFloating(val paneId: String) : WindowCommand()

    /**
     * Detach a pane from the main window and display it in a separate OS-level
     * window (Electron BrowserWindow).
     *
     * @param paneId the pane to pop out
     * @see PoppedOutPane
     */
    @Serializable
    @SerialName("popOut")
    data class PopOut(val paneId: String) : WindowCommand()

    /**
     * Return a previously popped-out pane back into the main window's split tree.
     *
     * @param paneId the popped-out pane to dock back
     * @see PopOut
     */
    @Serializable
    @SerialName("dockPoppedOut")
    data class DockPoppedOut(val paneId: String) : WindowCommand()

    /**
     * Move a pane from its current tab to a different tab.
     *
     * @param paneId the pane to move
     * @param targetTabId the id of the destination tab
     */
    @Serializable
    @SerialName("movePaneToTab")
    data class MovePaneToTab(val paneId: String, val targetTabId: String) : WindowCommand()

    /**
     * Switch the active (visible) tab. Persisted so tab selection survives restarts.
     *
     * @param tabId the id of the tab to activate
     * @see WindowConfig.activeTabId
     */
    @Serializable
    @SerialName("setActiveTab")
    data class SetActiveTab(val tabId: String) : WindowCommand()

    /**
     * Record which pane has keyboard focus within a tab. Persisted so focus
     * is restored when the user switches back to this tab.
     *
     * @param tabId the tab containing the pane
     * @param paneId the pane that received focus
     * @see TabConfig.focusedPaneId
     */
    @Serializable
    @SerialName("setFocusedPane")
    data class SetFocusedPane(val tabId: String, val paneId: String) : WindowCommand()

    /**
     * Override the display state mode for a terminal session (e.g. force a
     * particular rendering mode). The server broadcasts the updated state
     * to all clients viewing this session.
     *
     * @param sessionId the PTY session to override
     * @param mode the state mode string to set
     */
    @Serializable
    @SerialName("setStateOverride")
    data class SetStateOverride(val sessionId: String, val mode: String) : WindowCommand()

    /** Request the server to send the current UI settings to the client. */
    @Serializable
    @SerialName("openSettings")
    object OpenSettings : WindowCommand()

    /** Request a fresh Claude API usage snapshot from the server. */
    @Serializable
    @SerialName("refreshUsage")
    object RefreshUsage : WindowCommand()
}

/**
 * Control-frame message sent client → server over the `/pty/{id}` websocket
 * as a JSON Text frame. The only control message today is `resize`. Uses
 * `"type"` as the discriminator (same reasoning as [WindowCommand]).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class PtyControl {
    /**
     * Normal resize from the client's layout: updates this client's
     * contribution to the server's min()-across-clients aggregation.
     * Other clients' sizes are left alone, so the smallest attached
     * viewport still dictates the PTY's wrap width.
     */
    @Serializable
    @SerialName("resize")
    data class Resize(val cols: Int, val rows: Int) : PtyControl()

    /**
     * "Reformat" from the client's UI: force the PTY to this client's
     * cols/rows, evicting every *other* client's size entry from the
     * aggregation. The next auto-resize those clients send will
     * re-populate the map and min() takes over again — this is a
     * momentary override, not a permanent mode switch.
     */
    @Serializable
    @SerialName("forceResize")
    data class ForceResize(val cols: Int, val rows: Int) : PtyControl()
}

/**
 * Control-frame message sent server → client over the `/pty/{id}` websocket
 * as a JSON Text frame. Lets clients know the authoritative PTY size so they
 * can highlight any grid area that falls outside it.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class PtyServerMessage {
    /**
     * Informs the client of the current authoritative PTY grid dimensions.
     * The client uses this to visually indicate which portion of its viewport
     * falls within the active PTY area.
     *
     * @param cols number of columns in the PTY grid
     * @param rows number of rows in the PTY grid
     */
    @Serializable
    @SerialName("size")
    data class Size(val cols: Int, val rows: Int) : PtyServerMessage()
}
