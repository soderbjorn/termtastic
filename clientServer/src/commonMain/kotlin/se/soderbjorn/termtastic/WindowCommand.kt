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
    @Serializable
    @SerialName("splitTerminal")
    data class SplitTerminal(val paneId: String, val direction: SplitDirection) : WindowCommand()

    @Serializable
    @SerialName("splitFileBrowser")
    data class SplitFileBrowser(val paneId: String, val direction: SplitDirection) : WindowCommand()

    @Serializable
    @SerialName("splitGit")
    data class SplitGit(val paneId: String, val direction: SplitDirection) : WindowCommand()

    @Serializable
    @SerialName("splitLink")
    data class SplitLink(val paneId: String, val direction: SplitDirection, val targetSessionId: String) : WindowCommand()

    @Serializable
    @SerialName("fileBrowserListDir")
    data class FileBrowserListDir(val paneId: String, val dirRelPath: String) : WindowCommand()

    @Serializable
    @SerialName("fileBrowserOpenFile")
    data class FileBrowserOpenFile(val paneId: String, val relPath: String) : WindowCommand()

    @Serializable
    @SerialName("fileBrowserSetExpanded")
    data class FileBrowserSetExpanded(
        val paneId: String,
        val dirRelPath: String,
        val expanded: Boolean,
    ) : WindowCommand()

    @Serializable
    @SerialName("setFileBrowserLeftWidth")
    data class SetFileBrowserLeftWidth(val paneId: String, val px: Int) : WindowCommand()

    @Serializable
    @SerialName("setFileBrowserAutoRefresh")
    data class SetFileBrowserAutoRefresh(val paneId: String, val enabled: Boolean) : WindowCommand()

    @Serializable
    @SerialName("setFileBrowserFilter")
    data class SetFileBrowserFilter(val paneId: String, val filter: String) : WindowCommand()

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

    @Serializable
    @SerialName("fileBrowserCollapseAll")
    data class FileBrowserCollapseAll(val paneId: String) : WindowCommand()

    @Serializable
    @SerialName("setFileBrowserFontSize")
    data class SetFileBrowserFontSize(val paneId: String, val size: Int) : WindowCommand()

    @Serializable
    @SerialName("gitList")
    data class GitList(val paneId: String) : WindowCommand()

    @Serializable
    @SerialName("gitDiff")
    data class GitDiff(val paneId: String, val filePath: String) : WindowCommand()

    @Serializable
    @SerialName("setGitLeftWidth")
    data class SetGitLeftWidth(val paneId: String, val px: Int) : WindowCommand()

    @Serializable
    @SerialName("setGitDiffMode")
    data class SetGitDiffMode(val paneId: String, val mode: GitDiffMode) : WindowCommand()

    @Serializable
    @SerialName("setGitGraphicalDiff")
    data class SetGitGraphicalDiff(val paneId: String, val enabled: Boolean) : WindowCommand()

    @Serializable
    @SerialName("setGitDiffFontSize")
    data class SetGitDiffFontSize(val paneId: String, val size: Int) : WindowCommand()

    @Serializable
    @SerialName("setGitAutoRefresh")
    data class SetGitAutoRefresh(val paneId: String, val enabled: Boolean) : WindowCommand()

    @Serializable
    @SerialName("setTerminalFontSize")
    data class SetTerminalFontSize(val paneId: String, val size: Int) : WindowCommand()

    @Serializable
    @SerialName("close")
    data class Close(val paneId: String) : WindowCommand()

    /** Close every pane that references [sessionId]. Used when closing a
     *  terminal that has linked panes — all views of the session go away. */
    @Serializable
    @SerialName("closeSession")
    data class CloseSession(val sessionId: String) : WindowCommand()

    @Serializable
    @SerialName("rename")
    data class Rename(val paneId: String, val title: String) : WindowCommand()

    @Serializable
    @SerialName("addTab")
    object AddTab : WindowCommand()

    @Serializable
    @SerialName("closeTab")
    data class CloseTab(val tabId: String) : WindowCommand()

    @Serializable
    @SerialName("renameTab")
    data class RenameTab(val tabId: String, val title: String) : WindowCommand()

    @Serializable
    @SerialName("moveTab")
    data class MoveTab(
        val tabId: String,
        val targetTabId: String,
        val before: Boolean,
    ) : WindowCommand()

    @Serializable
    @SerialName("setRatio")
    data class SetRatio(val splitId: String, val ratio: Double) : WindowCommand()

    @Serializable
    @SerialName("resizePane")
    data class ResizePane(val paneId: String, val delta: Double) : WindowCommand()

    @Serializable
    @SerialName("addPaneToTab")
    data class AddPaneToTab(val tabId: String) : WindowCommand()

    @Serializable
    @SerialName("addFileBrowserToTab")
    data class AddFileBrowserToTab(val tabId: String) : WindowCommand()

    @Serializable
    @SerialName("addGitToTab")
    data class AddGitToTab(val tabId: String) : WindowCommand()

    @Serializable
    @SerialName("addLinkToTab")
    data class AddLinkToTab(val tabId: String, val targetSessionId: String) : WindowCommand()

    @Serializable
    @SerialName("toggleFloating")
    data class ToggleFloating(val paneId: String) : WindowCommand()

    @Serializable
    @SerialName("setFloatingGeom")
    data class SetFloatingGeom(
        val paneId: String,
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
    ) : WindowCommand()

    @Serializable
    @SerialName("raiseFloating")
    data class RaiseFloating(val paneId: String) : WindowCommand()

    @Serializable
    @SerialName("popOut")
    data class PopOut(val paneId: String) : WindowCommand()

    @Serializable
    @SerialName("dockPoppedOut")
    data class DockPoppedOut(val paneId: String) : WindowCommand()

    @Serializable
    @SerialName("movePaneToTab")
    data class MovePaneToTab(val paneId: String, val targetTabId: String) : WindowCommand()

    @Serializable
    @SerialName("setActiveTab")
    data class SetActiveTab(val tabId: String) : WindowCommand()

    @Serializable
    @SerialName("setFocusedPane")
    data class SetFocusedPane(val tabId: String, val paneId: String) : WindowCommand()

    @Serializable
    @SerialName("setStateOverride")
    data class SetStateOverride(val sessionId: String, val mode: String) : WindowCommand()

    @Serializable
    @SerialName("openSettings")
    object OpenSettings : WindowCommand()

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
    @Serializable
    @SerialName("size")
    data class Size(val cols: Int, val rows: Int) : PtyServerMessage()
}
