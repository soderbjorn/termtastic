package se.soderbjorn.termtastic.client.viewmodel

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import se.soderbjorn.termtastic.DiffHunk
import se.soderbjorn.termtastic.GitContent
import se.soderbjorn.termtastic.GitDiffMode
import se.soderbjorn.termtastic.GitFileEntry
import se.soderbjorn.termtastic.WindowCommand
import se.soderbjorn.termtastic.WindowEnvelope
import se.soderbjorn.termtastic.client.WindowSocket

class GitPaneBackingViewModel(
    private val paneId: String,
    private val windowSocket: WindowSocket,
) {
    private val _stateFlow = MutableStateFlow(State())
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    data class State(
        val entries: List<GitFileEntry>? = null,
        val selectedFilePath: String? = null,
        val diffHunks: List<DiffHunk>? = null,
        val diffLanguage: String? = null,
        val oldContent: String? = null,
        val newContent: String? = null,
        val diffMode: GitDiffMode = GitDiffMode.Inline,
        val graphicalDiff: Boolean = false,
        val autoRefresh: Boolean = false,
        val leftColumnWidthPx: Int? = null,
        val collapsedSections: Set<String> = emptySet(),
        val searchQuery: String = "",
        val searchMatchIndex: Int = 0,
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
                            is WindowEnvelope.GitList -> {
                                emit(_stateFlow.value.copy(
                                    entries = envelope.entries,
                                    isLoading = false,
                                    errorMessage = null,
                                ))
                            }
                            is WindowEnvelope.GitDiffResult -> {
                                emit(_stateFlow.value.copy(
                                    selectedFilePath = envelope.filePath,
                                    diffHunks = envelope.hunks,
                                    diffLanguage = envelope.language,
                                    oldContent = envelope.oldContent,
                                    newContent = envelope.newContent,
                                    isLoading = false,
                                    errorMessage = null,
                                ))
                            }
                            is WindowEnvelope.GitError -> {
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
                        val content = leaf.content as? GitContent ?: return@collect
                        val cur = _stateFlow.value
                        emit(cur.copy(
                            selectedFilePath = content.selectedFilePath ?: cur.selectedFilePath,
                            diffMode = content.diffMode,
                            graphicalDiff = content.graphicalDiff,
                            leftColumnWidthPx = content.leftColumnWidthPx,
                            autoRefresh = content.autoRefresh,
                        ))
                    }
            }
            // Initial load
            refreshList()
        }
    }

    // ── Actions ─────────────────────────────────────────────────────

    suspend fun refreshList() {
        emit(_stateFlow.value.copy(isLoading = true, errorMessage = null))
        windowSocket.send(WindowCommand.GitList(paneId = paneId))
    }

    suspend fun selectFile(filePath: String) {
        emit(_stateFlow.value.copy(
            selectedFilePath = filePath,
            isLoading = true,
            diffHunks = null,
            oldContent = null,
            newContent = null,
            errorMessage = null,
        ))
        windowSocket.send(WindowCommand.GitDiff(paneId = paneId, filePath = filePath))
    }

    suspend fun setDiffMode(mode: GitDiffMode) {
        emit(_stateFlow.value.copy(diffMode = mode))
        windowSocket.send(WindowCommand.SetGitDiffMode(paneId = paneId, mode = mode))
        _stateFlow.value.selectedFilePath?.let { selectFile(it) }
    }

    suspend fun setGraphicalDiff(enabled: Boolean) {
        emit(_stateFlow.value.copy(graphicalDiff = enabled))
        windowSocket.send(WindowCommand.SetGitGraphicalDiff(paneId = paneId, enabled = enabled))
        _stateFlow.value.selectedFilePath?.let { selectFile(it) }
    }

    suspend fun setAutoRefresh(enabled: Boolean) {
        emit(_stateFlow.value.copy(autoRefresh = enabled))
        windowSocket.send(WindowCommand.SetGitAutoRefresh(paneId = paneId, enabled = enabled))
    }

    suspend fun setLeftColumnWidth(px: Int) {
        emit(_stateFlow.value.copy(leftColumnWidthPx = px))
        windowSocket.send(WindowCommand.SetGitLeftWidth(paneId = paneId, px = px))
    }

    suspend fun toggleSection(sectionKey: String) {
        val cur = _stateFlow.value
        val updated = if (sectionKey in cur.collapsedSections)
            cur.collapsedSections - sectionKey
        else
            cur.collapsedSections + sectionKey
        emit(cur.copy(collapsedSections = updated))
    }

    suspend fun setSearchQuery(query: String) {
        emit(_stateFlow.value.copy(searchQuery = query, searchMatchIndex = 0))
    }

    suspend fun setSearchMatchIndex(index: Int) {
        emit(_stateFlow.value.copy(searchMatchIndex = index))
    }

    private fun emit(state: State) {
        _stateFlow.value = state
    }
}
