package se.soderbjorn.termtastic.client

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import se.soderbjorn.termtastic.WindowConfig

/**
 * Process-lifetime cache of the latest [WindowConfig] and per-session state
 * map pushed by the server over `/window`. Held by [TermtasticClient] so it
 * survives [WindowSocket] reconnects and, on Android, any Compose navigation
 * that tears down and rebuilds the list/terminal screens.
 *
 * Subscribers get the last-known snapshot immediately (StateFlow replay), so
 * returning to the tree view never shows a "Connecting…" flash or empty dots
 * as long as the server has pushed at least one config envelope this session.
 */
class WindowStateRepository {
    private val _config = MutableStateFlow<WindowConfig?>(null)
    val config: StateFlow<WindowConfig?> = _config.asStateFlow()

    private val _states = MutableStateFlow<Map<String, String?>>(emptyMap())
    val states: StateFlow<Map<String, String?>> = _states.asStateFlow()

    private val _pendingApproval = MutableStateFlow(false)
    val pendingApproval: StateFlow<Boolean> = _pendingApproval.asStateFlow()

    fun updateConfig(config: WindowConfig) {
        _pendingApproval.value = false
        _config.value = config
    }

    fun updateStates(states: Map<String, String?>) {
        _states.value = states
    }

    fun setPendingApproval() {
        _pendingApproval.value = true
    }
}
