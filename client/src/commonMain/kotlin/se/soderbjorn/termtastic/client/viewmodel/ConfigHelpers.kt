package se.soderbjorn.termtastic.client.viewmodel

import se.soderbjorn.termtastic.LeafNode
import se.soderbjorn.termtastic.PaneNode
import se.soderbjorn.termtastic.SplitNode
import se.soderbjorn.termtastic.WindowConfig
import se.soderbjorn.termtastic.WindowEnvelope

fun findLeafById(config: WindowConfig, paneId: String): LeafNode? {
    fun walk(node: PaneNode?): LeafNode? = when (node) {
        is LeafNode -> if (node.id == paneId) node else null
        is SplitNode -> walk(node.first) ?: walk(node.second)
        null -> null
    }
    for (tab in config.tabs) {
        walk(tab.root)?.let { return it }
        tab.floating.firstOrNull { it.leaf.id == paneId }?.let { return it.leaf }
        tab.poppedOut.firstOrNull { it.leaf.id == paneId }?.let { return it.leaf }
    }
    return null
}

fun WindowEnvelope.belongsToPane(paneId: String): Boolean = when (this) {
    is WindowEnvelope.FileBrowserDir -> this.paneId == paneId
    is WindowEnvelope.FileBrowserContentMsg -> this.paneId == paneId
    is WindowEnvelope.FileBrowserError -> this.paneId == paneId
    is WindowEnvelope.GitList -> this.paneId == paneId
    is WindowEnvelope.GitDiffResult -> this.paneId == paneId
    is WindowEnvelope.GitError -> this.paneId == paneId
    else -> false
}
