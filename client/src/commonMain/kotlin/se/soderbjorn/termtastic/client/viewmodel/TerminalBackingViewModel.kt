package se.soderbjorn.termtastic.client.viewmodel

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import se.soderbjorn.termtastic.TerminalContent
import se.soderbjorn.termtastic.WindowCommand
import se.soderbjorn.termtastic.client.PtySocket
import se.soderbjorn.termtastic.client.WindowSocket

/**
 * Lightweight ViewModel for terminal pane metadata. Does NOT wrap
 * [PtySocket.output] — platform code subscribes to that directly to
 * feed bytes into xterm.js / native terminal views. This VM exists so
 * pane headers, tab status dots, and sidebar entries can reactively
 * observe per-terminal state.
 */
class TerminalBackingViewModel(
    private val paneId: String,
    private val sessionId: String,
    private val ptySocket: PtySocket,
    private val windowSocket: WindowSocket,
) {
    private val _stateFlow = MutableStateFlow(State())
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    data class State(
        val ptySize: Pair<Int, Int>? = null,
        val sessionState: String? = null,
        val title: String = "",
        val fontSize: Int? = null,
    )

    suspend fun run() {
        coroutineScope {
            launch {
                ptySocket.ptySize.collect { size ->
                    emit(_stateFlow.value.copy(ptySize = size))
                }
            }
            launch {
                windowSocket.states.collect { states ->
                    emit(_stateFlow.value.copy(sessionState = states[sessionId]))
                }
            }
            launch {
                windowSocket.config
                    .filterNotNull()
                    .collect { config ->
                        val leaf = findLeafById(config, paneId) ?: return@collect
                        val tc = leaf.content as? TerminalContent
                        emit(_stateFlow.value.copy(
                            title = leaf.title,
                            fontSize = tc?.fontSize,
                        ))
                    }
            }
        }
    }

    // ── Actions ─────────────────────────────────────────────────────

    suspend fun resize(cols: Int, rows: Int) {
        ptySocket.resize(cols, rows)
    }

    suspend fun forceResize(cols: Int, rows: Int) {
        ptySocket.forceResize(cols, rows)
    }

    suspend fun setFontSize(size: Int) {
        emit(_stateFlow.value.copy(fontSize = size))
        windowSocket.send(WindowCommand.SetTerminalFontSize(paneId = paneId, size = size))
    }

    private fun emit(state: State) {
        _stateFlow.value = state
    }
}
