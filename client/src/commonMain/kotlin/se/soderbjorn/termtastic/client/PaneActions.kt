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
 * Walk [config] and return every leaf id (docked, floating and popped-out).
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

/** Return the `paneId` for the leaf whose `sessionId == sessionId`, or null. */
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
 */
suspend fun addSiblingTerminal(
    socket: WindowSocket,
    anchorPaneId: String,
) {
    socket.send(WindowCommand.SplitTerminal(anchorPaneId, SplitDirection.Right))
}

/**
 * Add a sibling file-browser pane next to [anchorPaneId] by splitting rightwards.
 */
suspend fun addSiblingFileBrowser(
    socket: WindowSocket,
    anchorPaneId: String,
) {
    socket.send(WindowCommand.SplitFileBrowser(anchorPaneId, SplitDirection.Right))
}

/**
 * Add a sibling git pane next to [anchorPaneId] by splitting rightwards.
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
 * success.
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
