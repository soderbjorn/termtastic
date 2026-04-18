/**
 * Shared helper functions for pane-level layout mutations (splitting, adding
 * tabs, finding leaves) that are used by both platform UI code and ViewModels.
 *
 * These functions operate on [WindowConfig] trees and send [WindowCommand]s
 * through a [WindowSocket] to let the server apply the canonical layout change.
 *
 * @see WindowSocket
 * @see se.soderbjorn.termtastic.WindowCommand
 */
package se.soderbjorn.termtastic.client

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import se.soderbjorn.termtastic.LeafNode
import se.soderbjorn.termtastic.PaneNode
import se.soderbjorn.termtastic.SplitDirection
import se.soderbjorn.termtastic.SplitNode
import se.soderbjorn.termtastic.WindowCommand
import se.soderbjorn.termtastic.WindowConfig

/**
 * Walk [config] and return every leaf ID (docked, floating, and popped-out)
 * across all tabs. Used by the sidebar to determine which PTY sockets need to
 * be open.
 *
 * @param config the current window layout snapshot, or `null`.
 * @return a set of all leaf pane IDs, or an empty set if [config] is `null`.
 */
fun collectLeafIds(config: WindowConfig?): Set<String> {
    if (config == null) return emptySet()
    val out = HashSet<String>()
    fun walk(node: PaneNode?) {
        when (node) {
            is LeafNode -> out.add(node.id)
            is SplitNode -> { walk(node.first); walk(node.second) }
            null -> Unit
        }
    }
    for (tab in config.tabs) {
        walk(tab.root)
        tab.floating.forEach { out.add(it.leaf.id) }
        tab.poppedOut.forEach { out.add(it.leaf.id) }
    }
    return out
}

/**
 * Return the pane ID for the leaf whose session ID matches [sessionId], or
 * `null` if no such leaf exists. Searches docked, floating, and popped-out
 * leaves across all tabs.
 *
 * @param config    the current window layout, or `null` (returns `null`).
 * @param sessionId the PTY session identifier to look up.
 * @return the matching pane ID, or `null`.
 */
fun findPaneIdBySession(config: WindowConfig?, sessionId: String): String? {
    if (config == null || sessionId.isEmpty()) return null
    fun walk(node: PaneNode?): String? = when (node) {
        is LeafNode -> if (node.sessionId == sessionId) node.id else null
        is SplitNode -> walk(node.first) ?: walk(node.second)
        null -> null
    }
    for (tab in config.tabs) {
        walk(tab.root)?.let { return it }
        tab.floating.firstOrNull { it.leaf.sessionId == sessionId }?.let { return it.leaf.id }
        tab.poppedOut.firstOrNull { it.leaf.sessionId == sessionId }?.let { return it.leaf.id }
    }
    return null
}

/**
 * Add a sibling terminal pane next to [anchorPaneId] by splitting rightwards.
 * Sends a [WindowCommand.SplitTerminal] to the server via [socket].
 *
 * @param socket        the live window WebSocket connection.
 * @param anchorPaneId  the pane ID to split next to.
 */
suspend fun addSiblingTerminal(
    socket: WindowSocket,
    anchorPaneId: String,
) {
    socket.send(WindowCommand.SplitTerminal(anchorPaneId, SplitDirection.Right))
}

/**
 * Add a sibling file-browser pane next to [anchorPaneId] by splitting rightwards.
 * Sends a [WindowCommand.SplitFileBrowser] to the server via [socket].
 *
 * @param socket        the live window WebSocket connection.
 * @param anchorPaneId  the pane ID to split next to.
 */
suspend fun addSiblingFileBrowser(
    socket: WindowSocket,
    anchorPaneId: String,
) {
    socket.send(WindowCommand.SplitFileBrowser(anchorPaneId, SplitDirection.Right))
}

/**
 * Add a sibling git pane next to [anchorPaneId] by splitting rightwards.
 * Sends a [WindowCommand.SplitGit] to the server via [socket].
 *
 * @param socket        the live window WebSocket connection.
 * @param anchorPaneId  the pane ID to split next to.
 */
suspend fun addSiblingGit(
    socket: WindowSocket,
    anchorPaneId: String,
) {
    socket.send(WindowCommand.SplitGit(anchorPaneId, SplitDirection.Right))
}

/**
 * Create a new tab, optionally renaming it to [name]. The server always
 * creates a fresh terminal pane inside the new tab. Returns `true` on
 * success, `false` if the server did not acknowledge within [timeoutMs].
 *
 * Waits for a [WindowConfig] update that contains a tab ID not present before
 * the command was sent, then optionally renames it.
 *
 * @param client    the [TermtasticClient] whose [WindowStateRepository] is
 *   observed for the new tab.
 * @param socket    the live window WebSocket used to send the command.
 * @param name      desired tab title; blank means keep the server default.
 * @param timeoutMs maximum time in milliseconds to wait for the server to
 *   push the updated config.
 * @return `true` if the tab was created (and renamed if requested), `false`
 *   on timeout.
 */
suspend fun addTab(
    client: TermtasticClient,
    socket: WindowSocket,
    name: String,
    timeoutMs: Long = 5_000,
): Boolean {
    val beforeIds = client.windowState.config.value?.tabs?.map { it.id }?.toSet() ?: emptySet()
    socket.send(WindowCommand.AddTab)
    val newTabId = withTimeoutOrNull(timeoutMs) {
        client.windowState.config
            .filterNotNull()
            .first { cfg -> (cfg.tabs.map { it.id }.toSet() - beforeIds).isNotEmpty() }
            .let { cfg -> (cfg.tabs.map { it.id }.toSet() - beforeIds).first() }
    } ?: return false
    if (name.isNotBlank()) {
        socket.send(WindowCommand.RenameTab(newTabId, name))
    }
    return true
}
