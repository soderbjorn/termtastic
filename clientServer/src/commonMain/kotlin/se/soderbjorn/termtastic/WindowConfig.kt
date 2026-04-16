package se.soderbjorn.termtastic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Window configuration: an ordered list of tabs, each holding a binary tree of
 * panes. Leaves are bound to a terminal session id; splits hold an orientation
 * and the relative size of the first child.
 *
 * The server is the single source of truth for this structure. Clients render
 * it and send mutation commands; the server pushes the updated config back to
 * every connected client.
 */
@Serializable
data class WindowConfig(
    val tabs: List<TabConfig>,
    /**
     * The id of the currently-selected tab. Persisted so a fresh process boot
     * restores the user's last view instead of always defaulting to the first
     * tab. `null` (or an unknown id) means "let the client default to the
     * first tab".
     */
    val activeTabId: String? = null,
)

@Serializable
data class TabConfig(
    val id: String,
    val title: String,
    /**
     * The split tree for this tab, or `null` if the tab currently has no docked
     * panes (e.g. the user closed every pane in the tree). An empty tab is
     * still represented in [WindowConfig.tabs] — it just shows an empty-state
     * placeholder client-side. A tab with `root == null` may still hold
     * [floating] panes; conversely, a tab with no root *and* no floating panes
     * is fine and renders the "New pane" placeholder.
     */
    val root: PaneNode?,
    /**
     * Free-floating panes that live on top of [root] within the tab area.
     * Each entry stores its position/size as fractions of the tab's bounding
     * box so window resizes preserve relative geometry. Z-order is the entry
     * order (last entry renders on top); [FloatingPane.z] is the persisted
     * stacking key used to compute that order on rehydrate.
     */
    val floating: List<FloatingPane> = emptyList(),
    /**
     * Panes that have been detached from both the split tree and the floating
     * layer and are now displayed in a separate OS window (a new Electron
     * BrowserWindow). The server only tracks which panes are popped out so
     * that (a) the main window can omit them from the layout and (b) their
     * PTY sessions are kept alive. The new window's size/position is managed
     * by the OS and not persisted here — popped-out panes dock back into the
     * tree on server rehydrate, since the Electron windows don't survive a
     * server restart.
     */
    val poppedOut: List<PoppedOutPane> = emptyList(),
    /**
     * The id of the pane that was last focused in this tab. Used by the
     * client to restore focus when the user switches back to this tab, and
     * persisted across server restarts. `null` (or an unknown id) means
     * "let the client default to the first pane in DOM order".
     */
    val focusedPaneId: String? = null,
)

/**
 * A pane that has been detached from both the split tree and the floating
 * layer and is being rendered in its own OS-level window (a separate
 * Electron BrowserWindow). Only the [leaf] is tracked server-side; window
 * geometry is owned by the OS / Electron.
 */
@Serializable
data class PoppedOutPane(val leaf: LeafNode)

/**
 * A pane that has been detached from its tab's split tree and is rendered as
 * a draggable, free-positioned window inside the tab area. Geometry is stored
 * in *relative* units (fractions of the tab area, all in `[0, 1]`) so the
 * pane keeps its visual position when the browser window is resized.
 */
@Serializable
data class FloatingPane(
    val leaf: LeafNode,
    /** Top-left x as a fraction of the tab area's width. */
    val x: Double,
    /** Top-left y as a fraction of the tab area's height. */
    val y: Double,
    /** Width as a fraction of the tab area's width. */
    val width: Double,
    /** Height as a fraction of the tab area's height. */
    val height: Double,
    /** Stacking order key — higher renders on top. Updated by raiseFloating. */
    val z: Long,
)

@Serializable
sealed class PaneNode {
    abstract val id: String
}

@Serializable
@SerialName("leaf")
data class LeafNode(
    override val id: String,
    /**
     * For terminal leaves, the live PTY session id. Kept as a top-level field
     * (rather than only inside [TerminalContent]) so persisted blobs from
     * before the [content] field existed still deserialize, and so existing
     * code paths that read `leaf.sessionId` keep working unchanged. New code
     * that needs to dispatch on pane kind should read [content] instead.
     *
     * Empty for non-terminal leaves.
     */
    val sessionId: String,
    /**
     * Display title shown in the pane header. Always equals
     * `customName ?: prettifyPath(cwd) ?: defaultLabel` — kept denormalized so
     * the websocket payload doesn't have to compute it client-side and so
     * persisted blobs round-trip without recomputation.
     */
    val title: String,
    /** User-set name. `null` means "fall back to the cwd-based title". */
    val customName: String? = null,
    /** Last known shell working directory, learned from OSC 7 / proc polling. */
    val cwd: String? = null,
    /**
     * What this pane currently displays. `null` on persisted blobs predating
     * the field — server rehydrate synthesizes a [TerminalContent] from the
     * legacy top-level [sessionId] in that case.
     */
    val content: LeafContent? = null,
    /**
     * `true` when this pane is a linked view into another pane's session
     * (created via [SplitLink] or [AddLinkToTab]). Closing a link only
     * removes that single view; closing the *original* (non-link) pane
     * cascades to close all linked views of the same session.
     */
    val isLink: Boolean = false,
) : PaneNode()

/**
 * What a [LeafNode] is actually showing. The discriminator is the shared
 * `"kind"` field configured in [windowJson], which is fine because
 * [LeafContent] always appears nested inside a [LeafNode] — not at the same
 * object level as [PaneNode]'s discriminator.
 */
@Serializable
sealed class LeafContent

@Serializable
@SerialName("terminal")
data class TerminalContent(
    /** Live PTY session id. Mirrors [LeafNode.sessionId] for terminal leaves. */
    val sessionId: String,
    /** Font size in pixels. Null = client default (13). */
    val fontSize: Int? = null,
) : LeafContent()

/**
 * File browser pane: a collapsible tree of every file under the leaf's cwd
 * plus a rendered/highlighted preview pane on the right. Folders start
 * collapsed; expanding one triggers a one-level directory listing from the
 * server. Selecting a file loads its content (markdown-rendered, syntax-
 * highlighted, or a binary placeholder). `""` represents the root directory
 * in [expandedDirs] and in listing requests.
 */
@Serializable
@SerialName("fileBrowser")
data class FileBrowserContent(
    /** Relative path of the file currently shown on the right side, or null. */
    val selectedRelPath: String? = null,
    /** Relative paths of directories the user has expanded in the tree. */
    val expandedDirs: Set<String> = emptySet(),
    /** Width of the left file list in pixels. Null = client default (240). */
    val leftColumnWidthPx: Int? = null,
    /** Reserved for future filesystem-watch support. */
    val autoRefresh: Boolean = false,
    /** Font size in pixels for the rendered preview. Null = client default. */
    val fontSize: Int? = null,
    /**
     * Filename filter applied server-side when listing directories. Glob
     * syntax (`*`, `?`); empty/null means "show all files". Directories are
     * always shown regardless of filter so the user can navigate into them.
     */
    val fileFilter: String? = null,
    /** Sort order for entries within each directory listing. */
    val sortBy: FileBrowserSort = FileBrowserSort.NAME,
) : LeafContent()

/** How the file-browser tree orders entries inside each directory. */
@Serializable
enum class FileBrowserSort {
    @SerialName("name") NAME,
    @SerialName("mtime") MTIME,
}

/**
 * Git overview pane: a list of uncommitted changes on the left and a diff
 * viewer on the right. Mirrors the [FileBrowserContent] two-pane pattern.
 */
@Serializable
@SerialName("git")
data class GitContent(
    /** Path of the file currently shown in the diff viewer, or null. */
    val selectedFilePath: String? = null,
    /** Width of the left file list in pixels. Null = client default (280). */
    val leftColumnWidthPx: Int? = null,
    /** Inline (unified) vs. split (side-by-side P4Diff-style) diff mode. */
    val diffMode: GitDiffMode = GitDiffMode.Inline,
    /** When true and [diffMode] is [GitDiffMode.Split], render the P4Merge-style
     *  graphical view with independent scrolling and SVG connector lines. */
    val graphicalDiff: Boolean = false,
    /** Font size in pixels for the diff content area. Null = client default. */
    val diffFontSize: Int? = null,
    /** If true, the server pushes a fresh file list on working tree changes. */
    val autoRefresh: Boolean = false,
) : LeafContent()

@Serializable
enum class GitDiffMode { Inline, Split }

@Serializable
@SerialName("split")
data class SplitNode(
    override val id: String,
    val orientation: SplitOrientation,
    /** Relative size of [first], in (0, 1). [second] gets `1 - ratio`. */
    val ratio: Double,
    val first: PaneNode,
    val second: PaneNode
) : PaneNode()

@Serializable
enum class SplitOrientation {
    /** Children laid out side-by-side (CSS `flex-direction: row`). */
    Horizontal,
    /** Children stacked top-to-bottom (CSS `flex-direction: column`). */
    Vertical
}

enum class SplitDirection { Left, Right, Up, Down }
