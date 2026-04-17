package se.soderbjorn.termtastic.client.viewmodel

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import se.soderbjorn.termtastic.FileBrowserContent
import se.soderbjorn.termtastic.FileBrowserEntry
import se.soderbjorn.termtastic.FileBrowserSort
import se.soderbjorn.termtastic.FileContentKind
import se.soderbjorn.termtastic.WindowCommand
import se.soderbjorn.termtastic.WindowEnvelope
import se.soderbjorn.termtastic.client.WindowSocket

class FileBrowserBackingViewModel(
    private val paneId: String,
    private val windowSocket: WindowSocket,
) {
    private val _stateFlow = MutableStateFlow(State())
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    data class State(
        val dirListings: Map<String, List<FileBrowserEntry>> = emptyMap(),
        val expandedDirs: Set<String> = emptySet(),
        val selectedRelPath: String? = null,
        val fileContentHtml: String? = null,
        val fileContentKind: FileContentKind? = null,
        val fileFilter: String = "",
        val sortBy: FileBrowserSort = FileBrowserSort.NAME,
        val leftColumnWidthPx: Int? = null,
        val isLoading: Boolean = false,
        val errorMessage: String? = null,
    )

    suspend fun run() {
        coroutineScope {
            launch {
                windowSocket.envelopes
                    .collect { envelope ->
                        if (!envelope.belongsToPane(paneId)) return@collect
                        when (envelope) {
                            is WindowEnvelope.FileBrowserDir -> {
                                val cur = _stateFlow.value
                                val updated = cur.dirListings.toMutableMap()
                                updated[envelope.dirRelPath] = envelope.entries
                                emit(cur.copy(
                                    dirListings = updated,
                                    isLoading = false,
                                    errorMessage = null,
                                ))
                            }
                            is WindowEnvelope.FileBrowserContentMsg -> {
                                emit(_stateFlow.value.copy(
                                    selectedRelPath = envelope.relPath,
                                    fileContentHtml = envelope.html,
                                    fileContentKind = envelope.kind,
                                    isLoading = false,
                                    errorMessage = null,
                                ))
                            }
                            is WindowEnvelope.FileBrowserError -> {
                                emit(_stateFlow.value.copy(
                                    errorMessage = envelope.message,
                                    isLoading = false,
                                ))
                            }
                            else -> Unit
                        }
                    }
            }
            launch {
                windowSocket.config
                    .filterNotNull()
                    .collect { config ->
                        val leaf = findLeafById(config, paneId) ?: return@collect
                        val content = leaf.content as? FileBrowserContent ?: return@collect
                        val cur = _stateFlow.value
                        emit(cur.copy(
                            expandedDirs = content.expandedDirs,
                            selectedRelPath = content.selectedRelPath ?: cur.selectedRelPath,
                            fileFilter = content.fileFilter ?: "",
                            sortBy = content.sortBy,
                            leftColumnWidthPx = content.leftColumnWidthPx,
                        ))
                    }
            }
            // Initial load
            listDir("")
        }
    }

    // ── Actions ─────────────────────────────────────────────────────

    suspend fun listDir(dirRelPath: String) {
        emit(_stateFlow.value.copy(isLoading = true, errorMessage = null))
        windowSocket.send(WindowCommand.FileBrowserListDir(paneId = paneId, dirRelPath = dirRelPath))
    }

    suspend fun toggleDir(dirRelPath: String) {
        val cur = _stateFlow.value
        val nowExpanded = dirRelPath !in cur.expandedDirs
        val newExpanded = if (nowExpanded) cur.expandedDirs + dirRelPath else cur.expandedDirs - dirRelPath
        emit(cur.copy(expandedDirs = newExpanded))
        windowSocket.send(WindowCommand.FileBrowserSetExpanded(paneId = paneId, dirRelPath = dirRelPath, expanded = nowExpanded))
        if (nowExpanded && cur.dirListings[dirRelPath] == null) {
            listDir(dirRelPath)
        }
    }

    suspend fun openFile(relPath: String) {
        emit(_stateFlow.value.copy(
            selectedRelPath = relPath,
            isLoading = true,
            fileContentHtml = null,
            fileContentKind = null,
            errorMessage = null,
        ))
        windowSocket.send(WindowCommand.FileBrowserOpenFile(paneId = paneId, relPath = relPath))
    }

    suspend fun expandAll() {
        windowSocket.send(WindowCommand.FileBrowserExpandAll(paneId = paneId))
    }

    suspend fun collapseAll() {
        emit(_stateFlow.value.copy(expandedDirs = emptySet()))
        windowSocket.send(WindowCommand.FileBrowserCollapseAll(paneId = paneId))
    }

    suspend fun setFilter(filter: String) {
        emit(_stateFlow.value.copy(fileFilter = filter))
        windowSocket.send(WindowCommand.SetFileBrowserFilter(paneId = paneId, filter = filter))
    }

    suspend fun setSort(sort: FileBrowserSort) {
        emit(_stateFlow.value.copy(sortBy = sort))
        windowSocket.send(WindowCommand.SetFileBrowserSort(paneId = paneId, sort = sort))
    }

    suspend fun setLeftColumnWidth(px: Int) {
        emit(_stateFlow.value.copy(leftColumnWidthPx = px))
        windowSocket.send(WindowCommand.SetFileBrowserLeftWidth(paneId = paneId, px = px))
    }

    suspend fun refresh() {
        val cur = _stateFlow.value
        listDir("")
        for (dir in cur.expandedDirs) {
            windowSocket.send(WindowCommand.FileBrowserListDir(paneId = paneId, dirRelPath = dir))
        }
    }

    private fun emit(state: State) {
        _stateFlow.value = state
    }
}
