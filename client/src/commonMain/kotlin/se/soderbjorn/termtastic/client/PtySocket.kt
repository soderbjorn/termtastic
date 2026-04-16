package se.soderbjorn.termtastic.client

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import se.soderbjorn.termtastic.PtyControl
import se.soderbjorn.termtastic.PtyServerMessage

/**
 * Live connection to `/pty/{sessionId}`. Consumers:
 *   - subscribe to [output] for binary frames from the remote PTY (the first
 *     frames are the 64 KB ring-buffer replay, then live output — the caller
 *     doesn't need to special-case them, it's just a stream of bytes to feed
 *     into whatever terminal renderer is in use);
 *   - call [send] to write raw user-input bytes back to the PTY;
 *   - call [resize] whenever the renderer's cell grid changes to push a
 *     `{"type":"resize","cols":N,"rows":N}` control frame to the server.
 */
class PtySocket internal constructor(
    private val client: TermtasticClient,
    val sessionId: String,
) {
    private val _output = MutableSharedFlow<ByteArray>(
        replay = 64,
        extraBufferCapacity = 64,
    )
    val output: SharedFlow<ByteArray> = _output.asSharedFlow()

    // Latest authoritative PTY size as reported by the server's
    // PtyServerMessage.Size frames. Null until the first frame arrives.
    // StateFlow so late subscribers (e.g. a Compose `collectAsState` that
    // binds after the socket's first frame) see the current value.
    private val _ptySize = MutableStateFlow<Pair<Int, Int>?>(null)
    val ptySize: StateFlow<Pair<Int, Int>?> = _ptySize.asStateFlow()

    private val sessionReady = CompletableDeferred<DefaultClientWebSocketSession>()
    private var runJob: Job? = null

    init {
        start()
    }

    private fun start() {
        runJob = client.scope.launch {
            try {
                val session = client.httpClient.webSocketSession(
                    client.wsUrlWithAuth("/pty/$sessionId")
                )
                sessionReady.complete(session)
                session.incoming.consumeEach { frame ->
                    when (frame) {
                        is Frame.Binary -> _output.emit(frame.readBytes())
                        is Frame.Text -> runCatching {
                            val msg = client.json.decodeFromString<PtyServerMessage>(
                                frame.readText()
                            )
                            when (msg) {
                                is PtyServerMessage.Size ->
                                    _ptySize.value = Pair(msg.cols, msg.rows)
                            }
                        }
                        else -> Unit
                    }
                }
            } catch (t: Throwable) {
                if (!sessionReady.isCompleted) sessionReady.completeExceptionally(t)
            }
        }
    }

    suspend fun send(bytes: ByteArray) {
        val session = sessionReady.await()
        session.send(Frame.Binary(true, bytes))
    }

    suspend fun resize(cols: Int, rows: Int) {
        val session = sessionReady.await()
        val payload = client.json.encodeToString<PtyControl>(PtyControl.Resize(cols, rows))
        session.send(Frame.Text(payload))
    }

    /**
     * "Reformat" from the client's UI: ask the server to evict every
     * other attached client's size entry and pin the PTY to our
     * cols/rows. The next auto-resize those clients send re-enters them
     * into the min() aggregation, so this is a momentary override.
     */
    suspend fun forceResize(cols: Int, rows: Int) {
        val session = sessionReady.await()
        val payload = client.json.encodeToString<PtyControl>(PtyControl.ForceResize(cols, rows))
        session.send(Frame.Text(payload))
    }

    suspend fun close() {
        runCatching {
            if (sessionReady.isCompleted) sessionReady.await().close()
        }
        runJob?.cancel()
    }

    /**
     * Fire-and-forget variant of [close] that runs on the long-lived
     * [TermtasticClient.scope] instead of whatever scope the caller happens
     * to be in. Callers in Android composables use `rememberCoroutineScope`
     * which is cancelled as the screen leaves composition — a `scope.launch
     * { close() }` there can be cancelled before the suspending close() ever
     * reaches the server, leaving the server-side socket handler blocked in
     * its `incoming` loop and the attached client's per-session dims pinned
     * on the server until the TCP connection finally times out.
     */
    fun closeDetached() {
        client.scope.launch { close() }
    }
}
