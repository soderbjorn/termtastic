/**
 * Shared helper functions for pane-level layout mutations (adding panes/tabs,
 * finding leaves) that are used by both platform UI code and ViewModels.
 *
 * These functions operate on [WindowConfig] snapshots and send [WindowCommand]s
 * through a [WindowSocket] to let the server apply the canonical layout change.
 *
 * @see WindowSocket
 * @see se.soderbjorn.termtastic.WindowCommand
 */
package se.soderbjorn.termtastic.client

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import se.soderbjorn.termtastic.WindowCommand
import se.soderbjorn.termtastic.WindowConfig

/**
 * Walk [config] and return every leaf ID (visible and popped-out) across all
 * tabs. Used by the sidebar to determine which PTY sockets need to be open.
 *
 * @param config the current window layout snapshot, or `null`.
 * @return a set of all leaf pane IDs, or an empty set if [config] is `null`.
 */
fun collectLeafIds(config: WindowConfig?): Set<String> {
    if (config == null) return emptySet()
    val out = HashSet<String>()
    for (tab in config.tabs) {
        tab.panes.forEach { out.add(it.leaf.id) }
        tab.poppedOut.forEach { out.add(it.leaf.id) }
    }
    return out
}

/**
 * Return the pane ID for the leaf whose session ID matches [sessionId], or
 * `null` if no such leaf exists. Searches visible and popped-out leaves
 * across all tabs.
 *
 * @param config    the current window layout, or `null` (returns `null`).
 * @param sessionId the PTY session identifier to look up.
 * @return the matching pane ID, or `null`.
 */
fun findPaneIdBySession(config: WindowConfig?, sessionId: String): String? {
    if (config == null || sessionId.isEmpty()) return null
    for (tab in config.tabs) {
        tab.panes.firstOrNull { it.leaf.sessionId == sessionId }?.let { return it.leaf.id }
        tab.poppedOut.firstOrNull { it.leaf.sessionId == sessionId }?.let { return it.leaf.id }
    }
    return null
}

/**
 * Return the tab ID that currently holds [paneId], or `null` if the pane
 * isn't found. Searches visible and popped-out panes across all tabs.
 *
 * @param config the current window layout, or `null`.
 * @param paneId the pane ID to locate.
 * @return the owning tab ID, or `null`.
 */
fun findTabIdOfPane(config: WindowConfig?, paneId: String): String? {
    if (config == null || paneId.isEmpty()) return null
    for (tab in config.tabs) {
        if (tab.panes.any { it.leaf.id == paneId } ||
            tab.poppedOut.any { it.leaf.id == paneId }
        ) return tab.id
    }
    return null
}

/**
 * Add a new terminal pane to the tab that currently holds [anchorPaneId].
 * Sends a [WindowCommand.AddPaneToTab] to the server via [socket]. No-op if
 * the anchor pane can't be located.
 *
 * @param socket         the live window WebSocket connection.
 * @param anchorPaneId   the pane ID whose tab should receive the new pane.
 * @param config         the current window layout snapshot used to resolve
 *                       the anchor pane's tab id.
 */
suspend fun addSiblingTerminal(
    socket: WindowSocket,
    anchorPaneId: String,
    config: WindowConfig?,
) {
    val tabId = findTabIdOfPane(config, anchorPaneId) ?: return
    socket.send(WindowCommand.AddPaneToTab(tabId))
}

/**
 * Add a new file-browser pane to the tab that currently holds [anchorPaneId].
 * Sends a [WindowCommand.AddFileBrowserToTab] to the server via [socket].
 *
 * @param socket         the live window WebSocket connection.
 * @param anchorPaneId   the pane ID whose tab should receive the new pane.
 * @param config         the current window layout snapshot.
 */
suspend fun addSiblingFileBrowser(
    socket: WindowSocket,
    anchorPaneId: String,
    config: WindowConfig?,
) {
    val tabId = findTabIdOfPane(config, anchorPaneId) ?: return
    socket.send(WindowCommand.AddFileBrowserToTab(tabId))
}

/**
 * Add a new git pane to the tab that currently holds [anchorPaneId].
 * Sends a [WindowCommand.AddGitToTab] to the server via [socket].
 *
 * @param socket         the live window WebSocket connection.
 * @param anchorPaneId   the pane ID whose tab should receive the new pane.
 * @param config         the current window layout snapshot.
 */
suspend fun addSiblingGit(
    socket: WindowSocket,
    anchorPaneId: String,
    config: WindowConfig?,
) {
    val tabId = findTabIdOfPane(config, anchorPaneId) ?: return
    socket.send(WindowCommand.AddGitToTab(tabId))
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
