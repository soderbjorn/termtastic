package se.soderbjorn.termtastic.client

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlinx.serialization.encodeToString
import se.soderbjorn.termtastic.FileBrowserEntry
import se.soderbjorn.termtastic.GitFileEntry
import se.soderbjorn.termtastic.WindowCommand
import se.soderbjorn.termtastic.WindowConfig
import se.soderbjorn.termtastic.WindowEnvelope

/**
 * Live connection to `/window`. Exposes the latest [WindowConfig] and the
 * per-session AI-assistant state map as hot [StateFlow]s. File-browser round-
 * trips (list/open) are modelled as suspend functions that write a
 * [WindowCommand] and await the matching [WindowEnvelope] reply.
 *
 * The socket opens lazily on the first subscription and stays live until
 * [close] is called or the owning [TermtasticClient.scope] is cancelled.
 */
class WindowSocket internal constructor(
    private val client: TermtasticClient,
    private val path: String,
) {
    /**
     * Forwarded from [TermtasticClient.windowState] so existing call sites
     * (`socket.config` / `socket.states`) keep compiling. The actual storage
     * lives on the client, so these flows survive socket reconnects.
     */
    val config: StateFlow<WindowConfig?> get() = client.windowState.config
    val states: StateFlow<Map<String, String?>> get() = client.windowState.states

    private val _envelopes = MutableSharedFlow<WindowEnvelope>(extraBufferCapacity = 64)
    val envelopes = _envelopes.asSharedFlow()

    private val sessionReady = CompletableDeferred<DefaultClientWebSocketSession>()

    /**
     * Completes when the socket's run loop exits for the first time, whether
     * cleanly (server closed the WebSocket with a close frame) or
     * exceptionally. Used by [awaitInitialConfig] to detect a post-handshake
     * rejection — the server completes the WS upgrade, then immediately
     * closes because [DeviceAuth] returned REJECTED/HEADLESS, and without
     * this the client would sit at "Connecting…" forever waiting for a Config
     * envelope that will never arrive.
     */
    private val sessionClosed = CompletableDeferred<Unit>()

    /** The current live session, if any. Used by [send]. */
    private val _activeSession = MutableStateFlow<DefaultClientWebSocketSession?>(null)

    private var runJob: Job? = null
    @Volatile private var closed = false

    companion object {
        private const val RECONNECT_BASE_MS = 1_000L
        private const val RECONNECT_MAX_MS = 30_000L
    }

    init {
        start()
    }

    private fun start() {
        runJob = client.scope.launch {
            var attempt = 0
            while (!closed) {
                try {
                    val url = client.wsUrlWithAuth(path)
                    println("WindowSocket: opening $url (attempt ${attempt + 1})")
                    val session = client.httpClient.webSocketSession(url)
                    println("WindowSocket: handshake complete for $url")
                    _activeSession.value = session
                    if (!sessionReady.isCompleted) sessionReady.complete(session)
                    attempt = 0 // reset backoff on successful connection
                    session.incoming.consumeEach { frame ->
                        if (frame !is Frame.Text) return@consumeEach
                        val envelope = runCatching {
                            client.json.decodeFromString<WindowEnvelope>(frame.readText())
                        }.getOrNull() ?: return@consumeEach
                        when (envelope) {
                            is WindowEnvelope.Config -> client.windowState.updateConfig(envelope.config)
                            is WindowEnvelope.State -> client.windowState.updateStates(envelope.states)
                            is WindowEnvelope.PendingApproval -> client.windowState.setPendingApproval()
                            is WindowEnvelope.FileBrowserDir,
                            is WindowEnvelope.FileBrowserContentMsg,
                            is WindowEnvelope.FileBrowserError,
                            is WindowEnvelope.ClaudeUsage,
                            is WindowEnvelope.GitList,
                            is WindowEnvelope.GitDiffResult,
                            is WindowEnvelope.GitError,
                            is WindowEnvelope.UiSettings -> Unit
                        }
                        _envelopes.emit(envelope)
                    }
                    // consumeEach finished — server closed the connection
                    println("WindowSocket: server closed connection")
                    _activeSession.value = null
                } catch (t: Throwable) {
                    _activeSession.value = null
                    println("WindowSocket: connection to ${client.wsUrlWithAuth(path)} failed: $t")
                    if (!sessionReady.isCompleted) {
                        // First connection failed — propagate to awaitInitialConfig
                        sessionReady.completeExceptionally(t)
                        sessionClosed.complete(Unit)
                        return@launch
                    }
                } finally {
                    if (!sessionClosed.isCompleted) sessionClosed.complete(Unit)
                }
                if (closed) break
                // Exponential backoff before reconnecting
                attempt++
                val backoffMs = (RECONNECT_BASE_MS * (1L shl (attempt - 1).coerceAtMost(4)))
                    .coerceAtMost(RECONNECT_MAX_MS)
                println("WindowSocket: reconnecting in ${backoffMs}ms")
                delay(backoffMs)
            }
        }
    }

    /**
     * Waits for either the first [WindowEnvelope.Config] to arrive or the
     * socket to close post-handshake. Returns normally on config, throws
     * [IllegalStateException] if the socket closed before any config was
     * received (the server rejected us after the WS upgrade — typically
     * because device auth returned REJECTED or HEADLESS).
     */
    suspend fun awaitInitialConfig() {
        sessionReady.await()
        if (client.windowState.config.value != null) return
        coroutineScope {
            val configDeferred = async {
                client.windowState.config.filter { it != null }.first()
                Unit
            }
            val closedDeferred = async { sessionClosed.await() }
            select<Unit> {
                configDeferred.onAwait { }
                closedDeferred.onAwait { }
            }
            configDeferred.cancel()
            closedDeferred.cancel()
        }
        if (client.windowState.config.value == null) {
            throw IllegalStateException(
                "Server closed the /window socket before sending a config — " +
                    "likely device-auth rejection. Check the server's log for " +
                    "\"rejecting non-loopback\" or \"headless\".",
            )
        }
    }

    /**
     * Suspends until the underlying WebSocket handshake has completed (or
     * rethrows the failure if it didn't). Callers that want to surface
     * connection errors up to the UI — instead of quietly falling back to a
     * "no config yet" screen — should await this before navigating.
     */
    suspend fun awaitSessionReady() {
        sessionReady.await()
    }

    suspend fun send(command: WindowCommand) {
        val session = _activeSession.value ?: sessionReady.await()
        val payload = client.json.encodeToString<WindowCommand>(command)
        session.send(Frame.Text(payload))
    }

    /** List one directory for [paneId]. [dirRelPath] is `""` for the root. */
    suspend fun fileBrowserListDir(
        paneId: String,
        dirRelPath: String,
        timeoutMs: Long = 10_000,
    ): List<FileBrowserEntry>? {
        send(WindowCommand.FileBrowserListDir(paneId, dirRelPath))
        val reply = withTimeoutOrNull(timeoutMs) {
            envelopes.filter {
                (it is WindowEnvelope.FileBrowserDir && it.paneId == paneId && it.dirRelPath == dirRelPath) ||
                    (it is WindowEnvelope.FileBrowserError && it.paneId == paneId)
            }.first()
        } ?: return null
        return when (reply) {
            is WindowEnvelope.FileBrowserDir -> reply.entries
            is WindowEnvelope.FileBrowserError -> null
            else -> null
        }
    }

    /** Open [relPath] in [paneId] and await the rendered/highlighted HTML. */
    suspend fun fileBrowserOpenFile(
        paneId: String,
        relPath: String,
        timeoutMs: Long = 10_000,
    ): WindowEnvelope? {
        send(WindowCommand.FileBrowserOpenFile(paneId, relPath))
        return withTimeoutOrNull(timeoutMs) {
            envelopes.filter {
                (it is WindowEnvelope.FileBrowserContentMsg && it.paneId == paneId && it.relPath == relPath) ||
                    (it is WindowEnvelope.FileBrowserError && it.paneId == paneId)
            }.first()
        }
    }

    /** Fire-and-forget: update the persisted expansion state for [dirRelPath]. */
    suspend fun fileBrowserSetExpanded(paneId: String, dirRelPath: String, expanded: Boolean) {
        send(WindowCommand.FileBrowserSetExpanded(paneId, dirRelPath, expanded))
    }

    /** Request a git file list for [paneId] and await the reply. */
    suspend fun gitList(paneId: String, timeoutMs: Long = 10_000): List<GitFileEntry>? {
        send(WindowCommand.GitList(paneId))
        val reply = withTimeoutOrNull(timeoutMs) {
            envelopes.filter {
                (it is WindowEnvelope.GitList && it.paneId == paneId) ||
                    (it is WindowEnvelope.GitError && it.paneId == paneId)
            }.first()
        } ?: return null
        return when (reply) {
            is WindowEnvelope.GitList -> reply.entries
            is WindowEnvelope.GitError -> null
            else -> null
        }
    }

    /** Request a diff for [filePath] in [paneId] and await the result. */
    suspend fun gitDiff(
        paneId: String,
        filePath: String,
        timeoutMs: Long = 10_000,
    ): WindowEnvelope? {
        send(WindowCommand.GitDiff(paneId, filePath))
        return withTimeoutOrNull(timeoutMs) {
            envelopes.filter {
                (it is WindowEnvelope.GitDiffResult && it.paneId == paneId && it.filePath == filePath) ||
                    (it is WindowEnvelope.GitError && it.paneId == paneId)
            }.first()
        }
    }

    suspend fun close() {
        closed = true
        runCatching { _activeSession.value?.close() }
        runJob?.cancel()
    }
}
