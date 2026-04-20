/**
 * Ktor server entry point and route configuration for Termtastic.
 *
 * This file bootstraps the entire backend: it initialises the SQLite persistence
 * layer, restores the persisted window layout, starts periodic scrollback and
 * AI-state polling coroutines, launches the Claude Code usage monitor, and
 * configures the Netty HTTP/WebSocket server.
 *
 * Key responsibilities:
 *  - [main] wires up persistence, scrollback saving, session-state polling,
 *    the [ClaudeUsageMonitor], and the Compose Desktop UI loop.
 *  - [Application.module] installs Ktor plugins (ContentNegotiation, WebSockets)
 *    and defines all HTTP routes and WebSocket endpoints.
 *  - `/pty/{id}` WebSocket: bidirectional terminal I/O for a single PTY session.
 *  - `/window` WebSocket: pushes the live [WindowConfig] tree and handles all
 *    UI commands (split, close, rename, file-browser, git, settings, etc.).
 *  - `/api/ui-settings` REST: get/merge the client-side UI preferences blob.
 *
 * Also contains [TerminalSessions] (the process-wide PTY registry) and
 * [TerminalSession] (a single PTY-backed session with ring-buffer replay,
 * headless screen emulation, and multi-client size negotiation).
 *
 * @see WindowState
 * @see SettingsRepository
 * @see DeviceAuth
 */
package se.soderbjorn.termtastic

import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.http.content.staticResources
import io.ktor.http.HttpStatusCode
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import se.soderbjorn.termtastic.auth.DeviceAuth
import se.soderbjorn.termtastic.persistence.AppPaths
import se.soderbjorn.termtastic.persistence.SettingsRepository
import se.soderbjorn.termtastic.ui.SettingsDialog
import se.soderbjorn.termtastic.pty.Osc7Scanner
import se.soderbjorn.termtastic.pty.ProcessCwdReader
import se.soderbjorn.termtastic.pty.ShellInitFiles
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

/**
 * Application entry point. Initialises the persistence layer, restores the
 * window layout, starts background coroutines for scrollback saving and
 * session-state polling, launches the Claude usage monitor, and starts the
 * Netty HTTP/WebSocket server.
 *
 * On non-headless systems, the Compose Desktop application loop owns the
 * main thread (required on macOS for AppKit) and renders the settings dialog
 * and device-approval dialog on demand.
 */
@OptIn(FlowPreview::class)
fun main() {
    // Bring up the persistence layer first so the loaded window config (if
    // any) is the very first value seen by the rest of the app — that way no
    // throwaway PTYs are created for a default layout we'd immediately
    // discard.
    val repo = SettingsRepository(AppPaths.databaseFile())
    WindowState.initialize(repo)
    ScreenStateManager.initialize(repo)

    // Debounced async saver: every config mutation eventually writes one row
    // to `settings`, but bursts (drag a splitter, retitle a tab) coalesce into
    // a single write. `drop(1)` skips the initial value StateFlow replays so
    // we don't immediately rewrite the row we just loaded from.
    val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    persistenceScope.launch {
        WindowState.config
            .drop(1)
            .debounce(2_000.milliseconds)
            .collectLatest { cfg ->
                runCatching { repo.saveWindowConfig(cfg.withBlankSessionIds()) }
                    .onFailure { LoggerFactory.getLogger("WindowPersistence").warn("Failed to persist window config", it) }
            }
    }

    // Debounced saver for per-screen view state: same pattern as window config.
    persistenceScope.launch {
        ScreenStateManager.screens
            .drop(1)
            .debounce(2_000.milliseconds)
            .collectLatest { states ->
                runCatching { repo.saveScreenStates(states) }
                    .onFailure { LoggerFactory.getLogger("ScreenPersistence").warn("Failed to persist screen states", it) }
            }
    }

    // Per-leaf last-saved bytesWritten counter. Lets the periodic scrollback
    // saver skip rows whose ring contents haven't advanced since the previous
    // pass, avoiding pointless SQLite writes on idle panes.
    val lastSavedBytes = ConcurrentHashMap<String, Long>()

    suspend fun saveAllScrollback(force: Boolean) {
        fun walk(node: PaneNode, out: MutableList<Pair<String, String>>) {
            when (node) {
                is LeafNode -> {
                    val content = node.content
                    val sid = when (content) {
                        is TerminalContent -> content.sessionId
                        null -> node.sessionId.takeIf { it.isNotEmpty() }
                        else -> null
                    }
                    if (sid != null && sid.isNotEmpty()) out.add(node.id to sid)
                }
                is SplitNode -> { walk(node.first, out); walk(node.second, out) }
            }
        }
        val pairs = mutableListOf<Pair<String, String>>()
        for (tab in WindowState.config.value.tabs) {
            tab.root?.let { walk(it, pairs) }
            tab.floating.forEach { walk(it.leaf, pairs) }
            tab.poppedOut.forEach { walk(it.leaf, pairs) }
        }
        for ((leafId, sessionId) in pairs) {
            val session = TerminalSessions.get(sessionId) ?: continue
            val current = session.bytesWritten()
            if (!force && lastSavedBytes[leafId] == current) continue
            val snapshot = session.snapshot()
            runCatching { repo.saveScrollback(leafId, snapshot) }
                .onSuccess { lastSavedBytes[leafId] = current }
                .onFailure { LoggerFactory.getLogger("ScrollbackPersistence").warn("Failed to save scrollback for $leafId", it) }
        }
    }

    // Periodic scrollback saver. 10 s cadence keeps the DB warm without
    // thrashing on busy shells; crashes lose at most that window.
    persistenceScope.launch {
        while (true) {
            delay(10_000)
            runCatching { saveAllScrollback(force = false) }
                .onFailure { LoggerFactory.getLogger("ScrollbackPersistence").warn("Periodic scrollback save failed", it) }
        }
    }

    // Poll all sessions for AI assistant state every 3 seconds and broadcast
    // to connected clients. Uses replay = 1 so newly connected clients get the
    // latest state snapshot immediately.
    val sessionStates = MutableSharedFlow<Map<String, String?>>(replay = 1)
    persistenceScope.launch {
        while (true) {
            delay(3_000)
            sessionStates.emit(TerminalSessions.resolveStates())
        }
    }

    // Claude Code usage monitor — scrapes /usage from a hidden claude session.
    val usageMonitor = ClaudeUsageMonitor()
    SettingsDialog.usageMonitor = usageMonitor
    if (repo.isClaudeUsagePollEnabled()) usageMonitor.start()

    Runtime.getRuntime().addShutdownHook(Thread {
        // Best-effort final flush so a clean Ctrl-C captures any unsaved
        // changes that landed inside the debounce window.
        runCatching {
            runBlocking {
                repo.saveWindowConfig(WindowState.config.value.withBlankSessionIds())
                repo.saveScreenStates(ScreenStateManager.allScreenStates())
                saveAllScrollback(force = true)
            }
        }
        usageMonitor.stop()
        persistenceScope.cancel()
        repo.close()
    })

    val port = System.getProperty("termtastic.port")?.toIntOrNull()
        ?: SERVER_PORT
    SettingsDialog.setListeningPort(port)

    // Bind to all interfaces so the "allow connections from other sources
    // than localhost" setting can be flipped at runtime without restarting
    // Netty. The default-off policy is enforced inside DeviceAuth.authorize,
    // which rejects non-loopback requests until the user opts in via
    // the settings dialog.
    val server = embeddedServer(
        Netty,
        port = port,
        host = "0.0.0.0",
        module = { module(repo, sessionStates, usageMonitor) }
    )

    if (java.awt.GraphicsEnvironment.isHeadless()) {
        // No display — run headless with the Ktor server blocking the main
        // thread as before. Compose dialogs silently no-op.
        server.start(wait = true)
    } else {
        // Non-headless: start the Ktor server in the background and let
        // Compose Desktop own the main thread (required on macOS for the
        // AppKit run loop). The Compose application stays alive for the
        // lifetime of the process, rendering the settings window and the
        // device-approval dialog on demand.
        server.start(wait = false)
        // Isolate Compose exceptions from Ktor: a bug in the composition (e.g.
        // a bad snapshot read) would otherwise propagate out of application()
        // on main and kill the JVM, taking the server down with it.
        try {
            androidx.compose.ui.window.application(exitProcessOnExit = false) {
                SettingsDialog.renderIfShowing()
                DeviceAuth.renderApprovalDialogIfShowing()
            }
        } catch (t: Throwable) {
            LoggerFactory.getLogger("Application")
                .error("Compose application loop crashed; server will stay up headless", t)
        }
        // If the Compose application loop exits (shouldn't normally happen),
        // keep the process alive while the Ktor server is running.
        Thread.currentThread().join()
    }
}

/**
 * Ktor application module: installs plugins and defines all routes.
 *
 * Configures [ContentNegotiation] with JSON and [WebSockets], then sets up:
 *  - Static file serving (dev: on-disk dist; prod: classpath `/web` resources).
 *  - `GET /api/ui-settings` and `POST /api/ui-settings` REST endpoints.
 *  - `/pty/{id}` WebSocket for bidirectional terminal I/O.
 *  - `/window` WebSocket for the live window-state protocol.
 *
 * @param settingsRepo the SQLite-backed settings store, shared across all routes
 * @param sessionStates flow of per-session AI assistant states, polled every 3 s
 * @param usageMonitor the Claude CLI usage monitor whose data is pushed to clients
 *
 * @see handleWindowCommand
 * @see handleControl
 */
fun Application.module(settingsRepo: SettingsRepository, sessionStates: MutableSharedFlow<Map<String, String?>>, usageMonitor: ClaudeUsageMonitor) {
    install(ContentNegotiation) { json() }
    install(WebSockets) {
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    val webDistPath = System.getProperty("termtastic.webDist")

    routing {
        if (webDistPath != null) {
            // Dev flow: serve from the on-disk web dist so edits hot-reload without re-jarring.
            staticFiles("/", File(webDistPath)) {
                default("index.html")
            }
        } else {
            // Packaged flow: the web bundle is embedded in the server jar under /web.
            staticResources("/", "web") {
                default("index.html")
            }
        }

        // UI preferences (terminal theme, dark/light/auto, sidebar state, …).
        // Stored as a single JSON blob so adding a new setting never requires
        // server changes — the client just uses a new key.
        get("/api/ui-settings") {
            val token = call.readAuthToken()
            val info = call.readClientInfo()
            when (DeviceAuth.authorize(token, info, settingsRepo)) {
                DeviceAuth.Decision.APPROVED -> call.respond(settingsRepo.getUiSettings())
                DeviceAuth.Decision.REJECTED,
                DeviceAuth.Decision.HEADLESS -> call.respond(HttpStatusCode.Unauthorized)
            }
        }
        post("/api/ui-settings") {
            val token = call.readAuthToken()
            val info = call.readClientInfo()
            when (DeviceAuth.authorize(token, info, settingsRepo)) {
                DeviceAuth.Decision.APPROVED -> {
                    val incoming = call.receive<JsonObject>()
                    settingsRepo.mergeUiSettings(incoming)
                    call.respond(HttpStatusCode.NoContent)
                }
                DeviceAuth.Decision.REJECTED,
                DeviceAuth.Decision.HEADLESS -> call.respond(HttpStatusCode.Unauthorized)
            }
        }

        // Per-screen view state for multi-window support. Electron fetches
        // this on startup to decide how many windows to create and where to
        // position them.
        get("/api/screen-states") {
            val token = call.readAuthToken()
            val info = call.readClientInfo()
            when (DeviceAuth.authorize(token, info, settingsRepo)) {
                DeviceAuth.Decision.APPROVED ->
                    call.respond(ScreenStateManager.allScreenStates())
                DeviceAuth.Decision.REJECTED,
                DeviceAuth.Decision.HEADLESS -> call.respond(HttpStatusCode.Unauthorized)
            }
        }

        webSocket("/pty/{id}") {
            val token = call.readAuthToken()
            val info = call.readClientInfo()
            when (DeviceAuth.authorize(token, info, settingsRepo)) {
                DeviceAuth.Decision.APPROVED -> Unit
                DeviceAuth.Decision.REJECTED -> {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "device not approved"))
                    return@webSocket
                }
                DeviceAuth.Decision.HEADLESS -> {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "server cannot prompt (headless)"))
                    return@webSocket
                }
            }
            val id = call.parameters["id"]
            val session = if (id != null) TerminalSessions.get(id) else null
            if (session == null) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "unknown session id"))
                return@webSocket
            }

            // Unique per-socket id so the session can track each attached
            // client's declared (cols, rows) and apply min() across all of
            // them to the PTY. Removed in the finally block so a
            // disconnect widens the PTY back for the remaining clients.
            val clientId = java.util.UUID.randomUUID().toString()

            // Push the authoritative PTY size to the client BEFORE the
            // snapshot. StateFlow replays its current value on first collect
            // so a fresh socket immediately learns the cols/rows it should
            // size its local emulator grid to. Sending Size first ensures
            // the snapshot bytes (which encode cursor positions for the
            // current PTY dimensions) render into an xterm grid of matching
            // size — otherwise cursor-addressing escapes in the replay land
            // in the wrong cells and the terminal appears garbled until the
            // user manually reformats.
            val (initialCols, initialRows) = session.sizeEvents.value
            send(
                Frame.Text(
                    windowJson.encodeToString<PtyServerMessage>(
                        PtyServerMessage.Size(initialCols, initialRows)
                    )
                )
            )

            // Replay recent output so a reconnecting client sees context.
            val snapshot = session.snapshot()
            if (snapshot.isNotEmpty()) {
                send(Frame.Binary(true, snapshot))
            }

            // Pump PTY output → this socket.
            val outJob = launch {
                session.output.collect { chunk ->
                    send(Frame.Binary(true, chunk))
                }
            }

            // Subsequent size changes (another client (dis)connects or
            // resizes) are pushed as they happen. Skip the replayed initial
            // value since we already sent it above.
            val sizeJob = launch {
                session.sizeEvents.drop(1).collect { (cols, rows) ->
                    val payload = windowJson.encodeToString<PtyServerMessage>(
                        PtyServerMessage.Size(cols, rows)
                    )
                    send(Frame.Text(payload))
                }
            }

            try {
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Binary -> session.write(frame.readBytes())
                        is Frame.Text -> handleControl(session, clientId, frame.readText())
                        else -> Unit
                    }
                }
            } finally {
                outJob.cancel()
                sizeJob.cancel()
                session.removeClient(clientId)
            }
        }

        webSocket("/window") {
            val token = call.readAuthToken()
            val info = call.readClientInfo()
            // Two-phase auth: the fast path resolves known/denied tokens
            // without blocking. Unknown tokens require the interactive
            // dialog, so we send a PendingApproval frame first — this lets
            // the client show "Waiting for approval…" instead of timing out.
            val decision = DeviceAuth.checkFastPath(token, info, settingsRepo)
                ?: run {
                    val pending = windowJson.encodeToString<WindowEnvelope>(
                        WindowEnvelope.PendingApproval("Waiting for approval on the server…")
                    )
                    send(Frame.Text(pending))
                    DeviceAuth.authorize(token, info, settingsRepo)
                }
            when (decision) {
                DeviceAuth.Decision.APPROVED -> Unit
                DeviceAuth.Decision.REJECTED -> {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "device not approved"))
                    return@webSocket
                }
                DeviceAuth.Decision.HEADLESS -> {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "server cannot prompt (headless)"))
                    return@webSocket
                }
            }
            // Parse the optional ?screen=N query parameter for multi-window
            // support. Defaults to screen 0 (primary window). Popout windows
            // don't pass a screen param and get screen 0.
            val screenIndex = call.request.queryParameters["screen"]?.toIntOrNull() ?: 0

            // Push the current config and every subsequent change. StateFlow
            // replays its current value to new collectors, so a fresh client
            // sees the latest snapshot immediately.
            val pushJob = launch {
                WindowState.config.collect { cfg ->
                    val payload = windowJson.encodeToString<WindowEnvelope>(WindowEnvelope.Config(cfg))
                    send(Frame.Text(payload))
                }
            }

            // Push per-screen view state (active tab, focused pane, sidebar,
            // theme overrides, window bounds) so each screen gets its own
            // independent view-layer configuration.
            val screenPushJob = launch {
                ScreenStateManager.screenStateFlow(screenIndex)
                    .distinctUntilChanged()
                    .collect { state ->
                        val payload = windowJson.encodeToString<WindowEnvelope>(
                            WindowEnvelope.ScreenStateMsg(state)
                        )
                        send(Frame.Text(payload))
                    }
            }

            // Push AI assistant state (working/waiting/idle) for every
            // session. Sent as a separate message type so state changes
            // (every 2 s) don't trigger full config re-renders.
            val statePushJob = launch {
                sessionStates.collect { states ->
                    val payload = windowJson.encodeToString<WindowEnvelope>(WindowEnvelope.State(states))
                    send(Frame.Text(payload))
                }
            }

            // Push Claude Code usage data when the monitor is active.
            val usagePushJob = launch {
                usageMonitor.usageData.collect { data ->
                    val payload = windowJson.encodeToString<WindowEnvelope>(WindowEnvelope.ClaudeUsage(data))
                    send(Frame.Text(payload))
                }
            }

            // Push UI settings (theme, appearance, sidebar width) as a global
            // fallback. Screens with non-null overrides in their ScreenState
            // take precedence client-side.
            val uiSettingsPushJob = launch {
                settingsRepo.uiSettings.collect { s ->
                    val payload = windowJson.encodeToString<WindowEnvelope>(WindowEnvelope.UiSettings(s))
                    send(Frame.Text(payload))
                }
            }

            // Per-connection context: replies (fileBrowserDir/fileBrowserContent/
            // fileBrowserError) need to go only to the requesting socket, and
            // each connection owns the lifetime of any auto-refresh watchers
            // it registered. The context is destroyed in the finally block,
            // which closes every watcher.
            val ctx = WindowConnectionContext(
                send = { env -> send(Frame.Text(windowJson.encodeToString<WindowEnvelope>(env))) },
                scope = this,
                settingsRepo = settingsRepo,
                usageMonitor = usageMonitor,
                screenIndex = screenIndex,
            )

            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) handleWindowCommand(frame.readText(), ctx)
                }
            } finally {
                ctx.closeAll()
                pushJob.cancel()
                screenPushJob.cancel()
                statePushJob.cancel()
                usagePushJob.cancel()
                uiSettingsPushJob.cancel()
            }
        }
    }
}

/**
 * Read the device-auth token from any of three places, in order:
 *
 *  1. `termtastic_auth` cookie (primary for REST calls the browser loads via
 *     same-origin fetches),
 *  2. `auth` query parameter (primary for WebSocket upgrades — the JS
 *     WebSocket API can't set custom headers, and cookies can behave oddly
 *     in some Electron partitions, so a query param is the most reliable
 *     channel),
 *  3. `X-Termtastic-Auth` header (used by REST fetches as a belt-and-
 *     suspenders fallback when cookies get blocked).
 *
 * Returning null lets [DeviceAuth.authorize] treat the request as unknown.
 */
private fun io.ktor.server.application.ApplicationCall.readAuthToken(): String? {
    val cookie = request.cookies["termtastic_auth"]
    if (!cookie.isNullOrBlank()) return cookie
    val query = request.queryParameters["auth"]
    if (!query.isNullOrBlank()) return query
    val header = request.header("X-Termtastic-Auth")
    if (!header.isNullOrBlank()) return header
    return null
}

/**
 * Build a [DeviceAuth.ClientInfo] from the incoming request. Client-reported
 * type/hostname/ip are read from either query params (WS upgrades) or
 * `X-Termtastic-Client-*` headers (REST). Type defaults to "Unknown" when the
 * client didn't label itself — legacy clients that haven't been rebuilt still
 * authenticate, they just show up anonymously in the dialog.
 */
private fun io.ktor.server.application.ApplicationCall.readClientInfo(): DeviceAuth.ClientInfo {
    fun first(headerName: String, queryName: String): String? {
        val q = request.queryParameters[queryName]
        if (!q.isNullOrBlank()) return q
        val h = request.header(headerName)
        if (!h.isNullOrBlank()) return h
        return null
    }
    val type = first("X-Termtastic-Client-Type", "clientType")?.takeIf { it.isNotBlank() } ?: "Unknown"
    val hostname = first("X-Termtastic-Client-Host", "clientHost")
    val selfIp = first("X-Termtastic-Client-Ip", "clientIp")
    return DeviceAuth.ClientInfo(
        type = type,
        hostname = hostname,
        selfReportedIp = selfIp,
        remoteAddress = request.origin.remoteHost,
    )
}

private val controlJson = Json { ignoreUnknownKeys = true }

/**
 * Per-`/window`-connection state passed into [handleWindowCommand]. Holds
 * the unicast reply channel (so e.g. a `fileBrowserDir` reply lands only on
 * the socket that asked) and the connection's owned watcher handles, keyed
 * by paneId. The connection's `finally` block calls [closeAll] to make sure
 * watch services don't leak when the user closes a tab or the websocket
 * drops.
 */
internal class WindowConnectionContext(
    val send: suspend (WindowEnvelope) -> Unit,
    val scope: kotlinx.coroutines.CoroutineScope,
    val settingsRepo: SettingsRepository,
    val usageMonitor: ClaudeUsageMonitor,
    /** The screen index for this connection, parsed from `?screen=N`. */
    val screenIndex: Int = 0,
) {
    private val gitWatchers = ConcurrentHashMap<String, GitWatchHandle>()

    /**
     * Register (or replace) the git auto-refresh watcher for [paneId].
     * If a previous watcher existed for this pane, it is closed first.
     *
     * @param paneId the leaf pane id owning the watcher
     * @param handle the new watcher handle, or null to remove the existing one
     */
    fun setGitWatcher(paneId: String, handle: GitWatchHandle?) {
        val previous = if (handle == null) gitWatchers.remove(paneId) else gitWatchers.put(paneId, handle)
        previous?.close()
    }

    /**
     * Remove and close the git auto-refresh watcher for [paneId], if any.
     *
     * @param paneId the leaf pane id whose watcher should be cancelled
     */
    fun cancelGitWatcher(paneId: String) {
        gitWatchers.remove(paneId)?.close()
    }

    /**
     * Close all git watchers owned by this connection. Called from the
     * `/window` WebSocket's `finally` block on disconnect.
     */
    fun closeAll() {
        for (handle in gitWatchers.values) handle.close()
        gitWatchers.clear()
    }
}

/**
 * Handle a JSON control message received on a `/pty/{id}` WebSocket.
 *
 * Deserialises the text as a [PtyControl] and dispatches resize commands
 * to the [TerminalSession]. Unknown or malformed messages are silently
 * dropped.
 *
 * @param session the terminal session this WebSocket is attached to
 * @param clientId unique id for this socket connection (for multi-client size negotiation)
 * @param text the raw JSON text from the WebSocket frame
 */
private fun handleControl(session: TerminalSession, clientId: String, text: String) {
    val control = runCatching {
        windowJson.decodeFromString<PtyControl>(text)
    }.getOrNull() ?: return
    when (control) {
        is PtyControl.Resize -> session.setClientSize(clientId, control.cols, control.rows)
        is PtyControl.ForceResize -> session.forceClientSize(clientId, control.cols, control.rows)
    }
}

/**
 * Build a `fileBrowserDir` envelope for [paneId] by listing [dirRelPath] under
 * the leaf's cwd. `""` lists the root. Empty list when the leaf has no cwd.
 */
private fun buildFileBrowserDirEnvelope(
    paneId: String,
    cwd: String?,
    dirRelPath: String,
    filter: String? = null,
    sort: FileBrowserSort = FileBrowserSort.NAME,
): WindowEnvelope.FileBrowserDir {
    val entries = if (cwd.isNullOrBlank()) emptyList()
    else FileBrowserCatalog.listDir(java.nio.file.Paths.get(cwd), dirRelPath, filter, sort)
    return WindowEnvelope.FileBrowserDir(paneId = paneId, dirRelPath = dirRelPath, entries = entries)
}

/**
 * Build a `fileBrowserError` envelope for [paneId] with the given error [message].
 *
 * @param paneId the leaf pane id the error relates to
 * @param message human-readable error description
 * @return an error envelope ready to send over the `/window` WebSocket
 */
private fun buildFileBrowserErrorEnvelope(paneId: String, message: String): WindowEnvelope.FileBrowserError =
    WindowEnvelope.FileBrowserError(paneId = paneId, message = message)

/**
 * Build a `gitList` envelope for [paneId] by listing uncommitted changes
 * in the git working tree at [cwd].
 *
 * @param paneId the leaf pane id requesting the git file list
 * @param cwd the working directory of the pane (may be null if not yet known)
 * @return a git list envelope with the changed files, or an empty list if [cwd] is null
 */
private fun buildGitListEnvelope(paneId: String, cwd: String?): WindowEnvelope.GitList {
    val entries = if (cwd.isNullOrBlank()) emptyList()
    else GitCatalog.listChanges(java.nio.file.Paths.get(cwd)) ?: emptyList()
    return WindowEnvelope.GitList(paneId = paneId, entries = entries)
}

/**
 * Dispatch a JSON command received on the `/window` WebSocket.
 *
 * Deserialises [text] as a [WindowCommand] and routes it to the appropriate
 * [WindowState] mutation or file/git catalog operation. Results (directory
 * listings, file content, diff hunks, errors) are sent back to the requesting
 * client via [ctx]'s unicast reply channel.
 *
 * @param text the raw JSON text from the WebSocket frame
 * @param ctx per-connection state holding the reply channel and owned watchers
 *
 * @see WindowCommand
 * @see WindowConnectionContext
 */
private suspend fun handleWindowCommand(text: String, ctx: WindowConnectionContext) {
    val cmd = runCatching { windowJson.decodeFromString<WindowCommand>(text) }.getOrNull() ?: return
    when (cmd) {
        is WindowCommand.SplitTerminal -> WindowState.splitTerminal(cmd.paneId, cmd.direction)
        is WindowCommand.SplitFileBrowser -> {
            val newLeaf = WindowState.splitFileBrowser(cmd.paneId, cmd.direction)
            if (newLeaf != null) {
                ctx.send(buildFileBrowserDirEnvelope(newLeaf.id, newLeaf.cwd, ""))
            }
        }
        is WindowCommand.SplitGit -> {
            val newLeaf = WindowState.splitGit(cmd.paneId, cmd.direction)
            if (newLeaf != null) {
                ctx.send(buildGitListEnvelope(newLeaf.id, newLeaf.cwd))
            }
        }
        is WindowCommand.SplitLink -> WindowState.splitLink(cmd.paneId, cmd.direction, cmd.targetSessionId)
        is WindowCommand.FileBrowserListDir -> {
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            val content = leaf.content as? FileBrowserContent ?: return
            ctx.send(buildFileBrowserDirEnvelope(cmd.paneId, leaf.cwd, cmd.dirRelPath, content.fileFilter, content.sortBy))
        }
        is WindowCommand.FileBrowserOpenFile -> {
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            if (leaf.content !is FileBrowserContent) return
            val cwd = leaf.cwd
            if (cwd.isNullOrBlank()) {
                ctx.send(buildFileBrowserErrorEnvelope(cmd.paneId, "No cwd for pane"))
                return
            }
            val read = FileBrowserCatalog.readFile(java.nio.file.Paths.get(cwd), cmd.relPath)
            if (read == null) {
                // Clear the persisted selection so a reconnect/reload doesn't
                // keep trying to open a file that no longer exists.
                WindowState.setFileBrowserSelected(cmd.paneId, null)
                ctx.send(buildFileBrowserErrorEnvelope(cmd.paneId, "Cannot read ${cmd.relPath}"))
                return
            }
            WindowState.setFileBrowserSelected(cmd.paneId, cmd.relPath)
            val envelope: WindowEnvelope = when (read) {
                is FileBrowserCatalog.FileRead.Binary -> WindowEnvelope.FileBrowserContentMsg(
                    paneId = cmd.paneId,
                    relPath = cmd.relPath,
                    kind = FileContentKind.Binary,
                    html = "",
                    language = null,
                )
                is FileBrowserCatalog.FileRead.Text -> {
                    val isMarkdown = cmd.relPath.substringAfterLast('.', "").equals("md", ignoreCase = true)
                    if (isMarkdown) {
                        val html = runCatching { MarkdownRenderer.render(read.content) }.getOrElse {
                            ctx.send(buildFileBrowserErrorEnvelope(cmd.paneId, "Render failed"))
                            return
                        }
                        WindowEnvelope.FileBrowserContentMsg(
                            paneId = cmd.paneId,
                            relPath = cmd.relPath,
                            kind = FileContentKind.Markdown,
                            html = html,
                            language = null,
                        )
                    } else {
                        val (html, lang) = SyntaxHighlighter.highlightFile(read.content, cmd.relPath)
                        WindowEnvelope.FileBrowserContentMsg(
                            paneId = cmd.paneId,
                            relPath = cmd.relPath,
                            kind = FileContentKind.Text,
                            html = html,
                            language = lang,
                        )
                    }
                }
            }
            ctx.send(envelope)
        }
        is WindowCommand.FileBrowserSetExpanded ->
            WindowState.setFileBrowserExpanded(cmd.paneId, cmd.dirRelPath, cmd.expanded)
        is WindowCommand.SetFileBrowserLeftWidth -> WindowState.setFileBrowserLeftWidth(cmd.paneId, cmd.px)
        is WindowCommand.SetFileBrowserFontSize -> WindowState.setFileBrowserFontSize(cmd.paneId, cmd.size)
        is WindowCommand.SetTerminalFontSize -> WindowState.setTerminalFontSize(cmd.paneId, cmd.size)
        is WindowCommand.SetFileBrowserAutoRefresh -> {
            // Filesystem watching is out of scope for the initial file-browser
            // cut — we just persist the flag. Reinstate a watcher here when it's
            // ported across from the old markdown implementation.
            WindowState.setFileBrowserAutoRefresh(cmd.paneId, cmd.enabled)
        }
        is WindowCommand.SetFileBrowserFilter -> {
            val updated = WindowState.setFileBrowserFilter(cmd.paneId, cmd.filter)
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            // Re-push every currently-expanded dir (plus root) under the new
            // filter so the client repaints without a follow-up round-trip.
            val toRefresh = (updated?.expandedDirs ?: emptySet()) + ""
            for (dir in toRefresh) {
                ctx.send(buildFileBrowserDirEnvelope(
                    cmd.paneId, leaf.cwd, dir, updated?.fileFilter,
                    updated?.sortBy ?: FileBrowserSort.NAME,
                ))
            }
        }
        is WindowCommand.SetFileBrowserSort -> {
            val updated = WindowState.setFileBrowserSort(cmd.paneId, cmd.sort) ?: return
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            val toRefresh = updated.expandedDirs + ""
            for (dir in toRefresh) {
                ctx.send(buildFileBrowserDirEnvelope(
                    cmd.paneId, leaf.cwd, dir, updated.fileFilter, updated.sortBy,
                ))
            }
        }
        is WindowCommand.FileBrowserExpandAll -> {
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            val content = leaf.content as? FileBrowserContent ?: return
            val cwd = leaf.cwd
            if (cwd.isNullOrBlank()) return
            val walk = FileBrowserCatalog.listAll(java.nio.file.Paths.get(cwd), content.fileFilter, content.sortBy)
            // Push every visited level so the client's cache is fully populated.
            for ((dir, entries) in walk.listings) {
                ctx.send(WindowEnvelope.FileBrowserDir(
                    paneId = cmd.paneId, dirRelPath = dir, entries = entries,
                ))
            }
            WindowState.setFileBrowserExpandedAll(cmd.paneId, walk.expandedDirs)
            // Truncation (cap on entries / depth) is silent so the right-pane
            // file content doesn't get clobbered by an error envelope.
        }
        is WindowCommand.FileBrowserCollapseAll -> {
            WindowState.clearFileBrowserExpanded(cmd.paneId)
        }
        is WindowCommand.GitList -> {
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            if (leaf.content !is GitContent) return
            ctx.send(buildGitListEnvelope(cmd.paneId, leaf.cwd))
        }
        is WindowCommand.GitDiff -> {
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            if (leaf.content !is GitContent) return
            val cwd = leaf.cwd
            if (cwd.isNullOrBlank()) {
                ctx.send(WindowEnvelope.GitError(cmd.paneId, "No cwd for pane"))
                return
            }
            val cwdPath = java.nio.file.Paths.get(cwd)
            val hunks = GitCatalog.readDiff(cwdPath, cmd.filePath)
            if (hunks == null) {
                WindowState.setGitSelected(cmd.paneId, null)
                ctx.send(WindowEnvelope.GitError(cmd.paneId, "Cannot read diff for ${cmd.filePath}"))
                return
            }
            val language = GitCatalog.detectLanguage(cmd.filePath)
            // Highlight diff line content server-side.
            val highlightedHunks = hunks.map { hunk ->
                hunk.copy(lines = hunk.lines.map { line ->
                    line.copy(content = SyntaxHighlighter.highlight(line.content, language))
                })
            }
            // For split mode, supply old and new file content.
            val oldContent = GitCatalog.readGitFile(cwdPath, cmd.filePath, "HEAD")
            val newContent = GitCatalog.readWorkingFile(cwdPath, cmd.filePath)
            WindowState.setGitSelected(cmd.paneId, cmd.filePath)
            ctx.send(WindowEnvelope.GitDiffResult(
                paneId = cmd.paneId,
                filePath = cmd.filePath,
                hunks = highlightedHunks,
                language = language,
                oldContent = oldContent,
                newContent = newContent,
            ))
        }
        is WindowCommand.SetGitLeftWidth -> WindowState.setGitLeftWidth(cmd.paneId, cmd.px)
        is WindowCommand.SetGitDiffMode -> WindowState.setGitDiffMode(cmd.paneId, cmd.mode)
        is WindowCommand.SetGitGraphicalDiff -> WindowState.setGitGraphicalDiff(cmd.paneId, cmd.enabled)
        is WindowCommand.SetGitDiffFontSize -> WindowState.setGitDiffFontSize(cmd.paneId, cmd.size)
        is WindowCommand.SetGitAutoRefresh -> {
            val newState = WindowState.setGitAutoRefresh(cmd.paneId, cmd.enabled) ?: return
            if (!cmd.enabled) {
                ctx.cancelGitWatcher(cmd.paneId)
                return
            }
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            val cwd = leaf.cwd ?: return
            val handle = GitWatcher.register(
                scope = ctx.scope,
                root = java.nio.file.Paths.get(cwd),
            ) {
                val current = WindowState.findLeaf(cmd.paneId) ?: return@register
                val gitContent = current.content as? GitContent ?: return@register
                ctx.send(buildGitListEnvelope(cmd.paneId, current.cwd))
                // Also re-send the diff for the currently selected file.
                val selPath = gitContent.selectedFilePath
                if (selPath != null && !current.cwd.isNullOrBlank()) {
                    val cwdPath = java.nio.file.Paths.get(current.cwd)
                    val diffHunks = GitCatalog.readDiff(cwdPath, selPath)
                    if (diffHunks != null) {
                        val lang = GitCatalog.detectLanguage(selPath)
                        val oldC = GitCatalog.readGitFile(cwdPath, selPath, "HEAD")
                        val newC = GitCatalog.readWorkingFile(cwdPath, selPath)
                        ctx.send(WindowEnvelope.GitDiffResult(
                            paneId = cmd.paneId,
                            filePath = selPath,
                            hunks = diffHunks,
                            language = lang,
                            oldContent = oldC,
                            newContent = newC,
                        ))
                    }
                }
            }
            ctx.setGitWatcher(cmd.paneId, handle)
        }
        is WindowCommand.AddGitToTab -> {
            val newLeaf = WindowState.addGitToEmptyTab(cmd.tabId)
            if (newLeaf != null) {
                ctx.send(buildGitListEnvelope(newLeaf.id, newLeaf.cwd))
            }
        }
        is WindowCommand.Close -> {
            // Tear down any auto-refresh watcher this connection holds for
            // the pane being closed; closeAll() in the WS finally block
            // would catch leaks on disconnect, but doing it eagerly here
            // releases file descriptors as soon as the user expects.
            ctx.cancelGitWatcher(cmd.paneId)
            WindowState.closePane(cmd.paneId)
        }
        is WindowCommand.CloseSession -> {
            WindowState.closeSession(cmd.sessionId)
        }
        is WindowCommand.Rename -> WindowState.renamePane(cmd.paneId, cmd.title)
        is WindowCommand.AddTab -> WindowState.addTab()
        is WindowCommand.CloseTab -> WindowState.closeTab(cmd.tabId)
        is WindowCommand.RenameTab -> WindowState.renameTab(cmd.tabId, cmd.title)
        is WindowCommand.MoveTab -> WindowState.moveTab(cmd.tabId, cmd.targetTabId, cmd.before)
        is WindowCommand.SetRatio -> WindowState.setSplitRatio(cmd.splitId, cmd.ratio)
        is WindowCommand.ResizePane -> WindowState.resizePane(cmd.paneId, cmd.delta)
        is WindowCommand.AddPaneToTab -> WindowState.addPaneToEmptyTab(cmd.tabId)
        is WindowCommand.AddFileBrowserToTab -> {
            val newLeaf = WindowState.addFileBrowserToEmptyTab(cmd.tabId)
            if (newLeaf != null) {
                ctx.send(buildFileBrowserDirEnvelope(newLeaf.id, newLeaf.cwd, ""))
            }
        }
        is WindowCommand.AddLinkToTab -> WindowState.addLinkToEmptyTab(cmd.tabId, cmd.targetSessionId)
        is WindowCommand.ToggleFloating -> WindowState.toggleFloating(cmd.paneId)
        is WindowCommand.SetFloatingGeom ->
            WindowState.setFloatingGeometry(cmd.paneId, cmd.x, cmd.y, cmd.width, cmd.height)
        is WindowCommand.RaiseFloating -> WindowState.raiseFloating(cmd.paneId)
        is WindowCommand.PopOut -> WindowState.popOutPane(cmd.paneId)
        is WindowCommand.DockPoppedOut -> WindowState.dockPoppedOut(cmd.paneId)
        is WindowCommand.MovePaneToTab -> WindowState.movePaneToTab(cmd.paneId, cmd.targetTabId)
        is WindowCommand.SetActiveTab -> {
            // Legacy command: update both the global config (backward compat)
            // and the connection's screen state.
            WindowState.setActiveTab(cmd.tabId)
            ScreenStateManager.setActiveTab(ctx.screenIndex, cmd.tabId)
        }
        is WindowCommand.SetFocusedPane -> {
            // Legacy command: update both the global config and the screen.
            WindowState.setFocusedPane(cmd.tabId, cmd.paneId)
            ScreenStateManager.setFocusedPane(ctx.screenIndex, cmd.tabId, cmd.paneId)
        }
        is WindowCommand.SetStateOverride -> TerminalSessions.setStateOverride(cmd.sessionId, cmd.mode)
        is WindowCommand.OpenSettings -> SettingsDialog.show(ctx.settingsRepo)
        is WindowCommand.RefreshUsage -> ctx.usageMonitor.requestRefresh()
        is WindowCommand.GetWorktreeDefaults -> {
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            val cwd = leaf.cwd
            if (cwd.isNullOrBlank()) {
                ctx.send(WindowEnvelope.WorktreeError(cmd.paneId, "No working directory for this pane"))
                return
            }
            val cwdPath = java.nio.file.Paths.get(cwd)
            val repoRoot = GitCatalog.getRepoRoot(cwdPath)
            if (repoRoot == null) {
                ctx.send(WindowEnvelope.WorktreeError(cmd.paneId, "Not inside a git repository"))
                return
            }
            val repoName = GitCatalog.getRepoNameFromRemote(cwdPath)
                ?: repoRoot.fileName?.toString()
                ?: "repo"
            val parentDir = repoRoot.parent?.toString() ?: cwd
            val siblingPath = "$parentDir/$repoName-"
            val dotWorktreesPath = "$parentDir/.worktrees/$repoName-"
            val hasChanges = GitCatalog.hasUncommittedChanges(cwdPath)
            ctx.send(WindowEnvelope.WorktreeDefaults(
                paneId = cmd.paneId,
                repoName = repoName,
                siblingPath = siblingPath,
                dotWorktreesPath = dotWorktreesPath,
                hasUncommittedChanges = hasChanges,
            ))
        }
        is WindowCommand.CreateWorktree -> {
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            val cwd = leaf.cwd
            if (cwd.isNullOrBlank()) {
                ctx.send(WindowEnvelope.WorktreeError(cmd.paneId, "No working directory for this pane"))
                return
            }
            val cwdPath = java.nio.file.Paths.get(cwd)
            val repoRoot = GitCatalog.getRepoRoot(cwdPath)
            if (repoRoot == null) {
                ctx.send(WindowEnvelope.WorktreeError(cmd.paneId, "Not inside a git repository"))
                return
            }
            if (!GitCatalog.isValidBranchName(cmd.branchName)) {
                ctx.send(WindowEnvelope.WorktreeError(cmd.paneId, "Invalid branch name: ${cmd.branchName}"))
                return
            }
            val worktreePath = java.nio.file.Paths.get(cmd.worktreePath)
            if (java.nio.file.Files.exists(worktreePath)) {
                ctx.send(WindowEnvelope.WorktreeError(cmd.paneId, "Path already exists: ${cmd.worktreePath}"))
                return
            }

            // Optionally stash uncommitted changes before creating the worktree.
            var stashed = false
            if (cmd.migrateChanges && GitCatalog.hasUncommittedChanges(repoRoot)) {
                stashed = GitCatalog.stashPush(repoRoot, "termtastic-worktree-migrate")
                if (!stashed) {
                    ctx.send(WindowEnvelope.WorktreeError(cmd.paneId, "Failed to stash uncommitted changes"))
                    return
                }
            }

            val createError = GitCatalog.createWorktree(repoRoot, cmd.branchName, worktreePath)
            if (createError != null) {
                // Restore stashed changes on failure.
                if (stashed) GitCatalog.stashPop(repoRoot)
                ctx.send(WindowEnvelope.WorktreeError(cmd.paneId, createError))
                return
            }

            // Apply stashed changes in the new worktree.
            if (stashed) {
                val popError = GitCatalog.stashPop(worktreePath)
                if (popError != null) {
                    // Worktree was created but stash pop failed — report partial success.
                    ctx.send(WindowEnvelope.WorktreeError(
                        cmd.paneId,
                        "Worktree created but failed to migrate changes: $popError\n" +
                            "Your changes are still in the stash. Run 'git stash pop' manually."
                    ))
                    return
                }
            }

            // Switch the pane's cwd to the new worktree.
            val newCwd = worktreePath.toAbsolutePath().toString()
            if (leaf.sessionId != null) {
                // Terminal pane: write cd command to the PTY shell.
                val cdCmd = "cd '${newCwd.replace("'", "'\\''")}'\n"
                TerminalSessions.get(leaf.sessionId)?.write(cdCmd.toByteArray())
            } else {
                // File browser / git pane: update cwd directly.
                WindowState.updateLeafCwd(cmd.paneId, newCwd)
            }
            ctx.send(WindowEnvelope.WorktreeCreated(paneId = cmd.paneId, newCwd = newCwd))
        }

        // --- Per-screen commands (multi-window support) ---------------------
        is WindowCommand.SetScreenActiveTab ->
            ScreenStateManager.setActiveTab(cmd.screenIndex, cmd.tabId)
        is WindowCommand.SetScreenFocusedPane ->
            ScreenStateManager.setFocusedPane(cmd.screenIndex, cmd.tabId, cmd.paneId)
        is WindowCommand.SetScreenSidebar ->
            ScreenStateManager.setSidebar(cmd.screenIndex, cmd.collapsed, cmd.width)
        is WindowCommand.SetScreenBounds ->
            ScreenStateManager.setBounds(cmd.screenIndex, cmd.bounds)
        is WindowCommand.SetScreenTheme ->
            ScreenStateManager.setTheme(cmd.screenIndex, cmd.settings)
        is WindowCommand.CloseScreen ->
            ScreenStateManager.closeScreen(cmd.screenIndex)
        is WindowCommand.OpenScreen ->
            ScreenStateManager.openScreen(cmd.screenIndex)
    }
}

/**
 * Registry of process-wide PTYs. Sessions are created on demand by the window
 * state machine and torn down when the last referencing pane is closed.
 *
 * Each created session also gets a long-lived watcher coroutine that listens
 * for cwd changes coming out of [TerminalSession.cwd] and forwards them
 * (debounced) into [WindowState.updatePaneCwd]. The 750 ms debounce coalesces
 * `cd` bursts before they ever touch the WindowConfig flow, and the existing
 * 2 s persistence debouncer in `main()` then coalesces *those* updates into
 * one SQLite write.
 */
@OptIn(FlowPreview::class)
object TerminalSessions {
    private val sessions = ConcurrentHashMap<String, TerminalSession>()
    private val watchJobs = ConcurrentHashMap<String, Job>()
    private val idCounter = AtomicLong(0)
    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // State overrides: key present → override active, key absent → auto-detect.
    // Value of null means forced idle; "working"/"waiting" forces that state.
    private val stateOverrides = ConcurrentHashMap<String, String?>()

    // Grace-period debounce for auto-detected states: when a session
    // transitions from a non-null state ("working"/"waiting") to null, we
    // keep the previous state alive for [STATE_GRACE_POLLS] consecutive null
    // polls before actually clearing it. This avoids false idle flickers
    // caused by PTY resizes that temporarily clear the screen during a
    // TUI redraw (the "esc to interrupt" marker vanishes for one or two
    // frames until the app re-renders at the new size).
    private val lastNonNullState = ConcurrentHashMap<String, String>()
    private val nullStreak = ConcurrentHashMap<String, Int>()
    private const val STATE_GRACE_POLLS = 3  // 3 polls × 3 s = 9 s grace

    /** Create a fresh session and return its newly minted id. */
    fun create(initialCwd: String? = null, initialScrollback: ByteArray? = null): String {
        val id = "s${idCounter.incrementAndGet()}"
        val session = TerminalSession.create(initialCwd, initialScrollback)
        sessions[id] = session
        watchJobs[id] = watchScope.launch {
            session.cwd
                .filterNotNull()
                .debounce(750.milliseconds)
                .distinctUntilChanged()
                .collect { newCwd -> WindowState.updatePaneCwd(id, newCwd) }
        }
        return id
    }

    /**
     * Look up a live session by its id.
     *
     * @param id the session id (e.g. `"s1"`)
     * @return the session, or null if no session with that id exists
     */
    fun get(id: String): TerminalSession? = sessions[id]

    /**
     * Override the detected state for a session.
     *
     * @param mode one of `"working"`, `"waiting"`, `"idle"` (forces idle),
     *             or `"auto"` (clears the override, resuming auto-detection).
     */
    fun setStateOverride(sessionId: String, mode: String) {
        when (mode) {
            "auto" -> stateOverrides.remove(sessionId)
            "idle" -> stateOverrides[sessionId] = null
            else -> stateOverrides[sessionId] = mode
        }
    }

    /** Resolve the current state for every live session. */
    fun resolveStates(): Map<String, String?> {
        val result = HashMap<String, String?>()
        for ((id, session) in sessions) {
            if (stateOverrides.containsKey(id)) {
                result[id] = stateOverrides[id]
                continue
            }
            val detected = session.detectState()?.state
            if (detected != null) {
                // Active state detected — record it and reset the null streak.
                lastNonNullState[id] = detected
                nullStreak.remove(id)
                result[id] = detected
            } else {
                // Nothing detected this poll. If the session was recently
                // active, hold the previous state for a few more polls to
                // ride out transient gaps caused by TUI redraws / resizes.
                val prev = lastNonNullState[id]
                if (prev != null) {
                    val streak = (nullStreak[id] ?: 0) + 1
                    if (streak < STATE_GRACE_POLLS) {
                        nullStreak[id] = streak
                        result[id] = prev   // hold previous state
                    } else {
                        // Grace period expired — truly idle now.
                        lastNonNullState.remove(id)
                        nullStreak.remove(id)
                        result[id] = null
                    }
                } else {
                    result[id] = null
                }
            }
        }
        return result
    }

    /**
     * Tear down a session: cancel its cwd watcher, shut down the PTY, and
     * remove all tracking state. Called when the last pane referencing this
     * session is closed.
     *
     * @param id the session id to destroy
     */
    fun destroy(id: String) {
        stateOverrides.remove(id)
        lastNonNullState.remove(id)
        nullStreak.remove(id)
        watchJobs.remove(id)?.cancel()
        sessions.remove(id)?.shutdown()
    }
}

/**
 * A single PTY-backed session.
 *
 *  - Output is broadcast to all connected WebSockets via [output].
 *  - A small ring buffer of recent bytes is replayed to new subscribers.
 */
class TerminalSession private constructor(
    private val pty: PtyProcess,
    initialScrollback: ByteArray? = null,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _output = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val output = _output.asSharedFlow()

    // Most recent shell working directory we've observed for this pane. Fed by
    // both the inline OSC 7 scanner (instant) and the proc-cwd poller
    // (fallback). Subscribers are expected to apply their own debouncing.
    private val _cwd = MutableStateFlow<String?>(null)
    val cwd: StateFlow<String?> = _cwd.asStateFlow()

    private val osc7 = Osc7Scanner { path -> _cwd.value = path }

    // Headless VT emulator that mirrors what xterm.js renders client-side.
    // Fed in parallel with the ring buffer on every PTY read and queried by
    // [detectState] so AI-assistant state matching runs against the actual
    // on-screen text rather than the raw byte history. Ink-based TUIs
    // (Claude Code) emit the "esc to interrupt" footer once and then
    // cursor-position around it, so a regex over bytes eventually loses the
    // marker while it is still visually on screen — the emulator keeps it
    // for as long as it is rendered. See ScreenEmulator.kt for details.
    private val screen = ScreenEmulator(initialCols = 120, initialRows = 32)

    // Ring buffer of recent bytes for reconnect replay.
    private val ringCapacity = 64 * 1024
    private val ring = ByteArray(ringCapacity)
    private var ringSize = 0
    private var ringStart = 0
    private val ringLock = Any()

    // Monotonically-increasing count of bytes that have passed through the
    // ring (including evicted ones). The periodic scrollback saver snapshots
    // this per-leaf to skip writes for sessions whose output hasn't advanced.
    @Volatile
    private var bytesWritten: Long = 0

    fun bytesWritten(): Long = bytesWritten

    // Must be declared BEFORE [readJob] so preloaded bytes land in the ring
    // before the PTY reader starts emitting. Property initializers and init
    // blocks execute in source order.
    init {
        if (initialScrollback != null && initialScrollback.isNotEmpty()) {
            appendToRing(initialScrollback)
            val marker = "\r\n\r\n\u001b[2m[session restored \u2014 previous process ended]\u001b[0m\r\n\r\n"
                .toByteArray(Charsets.UTF_8)
            appendToRing(marker)
        }
    }

    private val readJob: Job = scope.launch {
        val input = pty.inputStream
        val buf = ByteArray(4096)
        while (isActive) {
            val n = try {
                input.read(buf)
            } catch (_: Throwable) {
                break
            }
            if (n <= 0) break
            // Feed the raw bytes to the scanner BEFORE copying — the scanner
            // doesn't retain references and is byte-for-byte cheap.
            osc7.feed(buf, n)
            // Also feed the emulator so detectState() can scan the rendered
            // screen instead of the raw byte history.
            screen.feed(buf, n)
            val chunk = buf.copyOf(n)
            appendToRing(chunk)
            _output.emit(chunk)
        }
    }

    // Polling fallback for shells that don't emit OSC 7 (or for when the
    // bootstrap rc file didn't take). Cheap enough to run unconditionally —
    // the StateFlow's distinctness keeps redundant updates from propagating.
    private val pollJob: Job = scope.launch {
        while (isActive) {
            delay(3_000)
            val pid = try { pty.pid() } catch (_: Throwable) { continue }
            val polled = ProcessCwdReader.read(pid)
            if (polled != null && polled != _cwd.value) {
                _cwd.value = polled
            }
        }
    }

    /**
     * Write raw bytes to the PTY's stdin. Called when a `/pty/{id}` WebSocket
     * receives a binary frame containing user keystrokes.
     *
     * @param bytes the raw bytes to send to the shell process
     */
    fun write(bytes: ByteArray) {
        try {
            pty.outputStream.write(bytes)
            pty.outputStream.flush()
        } catch (_: Throwable) {
            // PTY may have died — ignore; next read will close things down.
        }
    }

    /**
     * Destroy the underlying PTY process and cancel all associated coroutines.
     * Called by [TerminalSessions.destroy] when the last referencing pane is closed.
     */
    fun shutdown() {
        try {
            pty.destroy()
        } catch (_: Throwable) {
            // Best effort.
        }
        scope.cancel()
    }

    // Per-attached-client (cols, rows). The PTY's actual winsize is
    // min(cols), min(rows) across every entry here — tmux-style semantics,
    // so the smallest attached viewport dictates the shell's wrap width.
    // On disconnect, [removeClient] drops the entry and recomputes, letting
    // the PTY widen back to the next-smallest client. The effective size
    // is also broadcast over [sizeEvents] so attached clients can resize
    // their local emulator grid to match — otherwise cursor addressing
    // from the shell lands in cells the client doesn't know are "live".
    private val clientSizes = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val _sizeEvents = MutableStateFlow(Pair(120, 32))
    val sizeEvents: StateFlow<Pair<Int, Int>> = _sizeEvents.asStateFlow()

    /**
     * Register the declared terminal size for [clientId]. The PTY's actual
     * winsize is recomputed as `min(cols)` x `min(rows)` across all attached
     * clients (tmux-style semantics).
     *
     * @param clientId unique per-socket identifier
     * @param cols the client's terminal width in columns
     * @param rows the client's terminal height in rows
     */
    fun setClientSize(clientId: String, cols: Int, rows: Int) {
        clientSizes[clientId] = Pair(max(1, cols), max(1, rows))
        applyEffectiveSize()
    }

    /**
     * "Reformat" handler: evict every *other* client's entry, pin this
     * client's cols/rows, and apply immediately. Used by the web pane
     * dropdown's Reformat item and the Android terminal toolbar's
     * refresh button so the user can momentarily reclaim the grid from
     * a smaller client that clamped the PTY narrow.
     */
    fun forceClientSize(clientId: String, cols: Int, rows: Int) {
        val only = Pair(max(1, cols), max(1, rows))
        clientSizes.clear()
        clientSizes[clientId] = only
        applyEffectiveSize()
    }

    /**
     * Unregister a client's size entry when its WebSocket disconnects.
     * Recomputes the effective PTY size so the remaining clients can use
     * the full available width/height.
     *
     * @param clientId the disconnecting client's unique identifier
     */
    fun removeClient(clientId: String) {
        if (clientSizes.remove(clientId) != null) applyEffectiveSize()
    }

    /**
     * Recompute the PTY's winsize as the minimum cols/rows across all
     * attached clients and apply it. Also updates the [sizeEvents] flow
     * and resizes the headless [ScreenEmulator].
     */
    private fun applyEffectiveSize() {
        val sizes = clientSizes.values
        if (sizes.isEmpty()) return
        val c = sizes.minOf { it.first }
        val r = sizes.minOf { it.second }
        try {
            pty.winSize = WinSize(c, r)
        } catch (_: Throwable) {
            // Ignore; resize races are benign.
        }
        screen.resize(c, r)
        _sizeEvents.value = Pair(c, r)
    }

    /** Check the currently-rendered screen for AI assistant state markers. */
    fun detectState(): SessionState? {
        val text = screen.snapshotVisibleText()
        if (text.isEmpty()) return null
        return StateDetector.detectState(text)
    }

    /**
     * Return a copy of the ring buffer contents for reconnect replay.
     * Appends a DECTCEM "show cursor" suffix to prevent cursor-hidden
     * artifacts when the ring was captured mid-TUI-render.
     *
     * @return the ring buffer bytes with a trailing show-cursor escape
     */
    fun snapshot(): ByteArray {
        val ringBytes = synchronized(ringLock) {
            if (ringSize == 0) return@synchronized ByteArray(0)
            val out = ByteArray(ringSize)
            if (ringStart + ringSize <= ringCapacity) {
                System.arraycopy(ring, ringStart, out, 0, ringSize)
            } else {
                val tail = ringCapacity - ringStart
                System.arraycopy(ring, ringStart, out, 0, tail)
                System.arraycopy(ring, 0, out, tail, ringSize - tail)
            }
            out
        }
        // Ring-buffer replay can end mid-render with a trailing DECTCEM hide
        // (ESC[?25l) and no matching show, because the app's last "show cursor"
        // scrolled out of the ring. The running process has no reason to
        // re-emit ESC[?25h (from its perspective the cursor is already shown),
        // so reconnected clients would never see a cursor again. Append a show
        // to the replay tail; a TUI that genuinely wants the cursor hidden
        // will re-hide it on its next frame.
        return ringBytes + SHOW_CURSOR_SUFFIX
    }

    /**
     * Append [chunk] to the circular ring buffer, evicting oldest bytes
     * when capacity is exceeded. Also increments [bytesWritten].
     *
     * @param chunk the raw PTY output bytes to buffer
     */
    private fun appendToRing(chunk: ByteArray) = synchronized(ringLock) {
        for (b in chunk) {
            val writeIdx = (ringStart + ringSize) % ringCapacity
            ring[writeIdx] = b
            if (ringSize < ringCapacity) {
                ringSize++
            } else {
                ringStart = (ringStart + 1) % ringCapacity
            }
        }
        bytesWritten += chunk.size
    }

    companion object {
        private val SHOW_CURSOR_SUFFIX = "\u001b[?25h".toByteArray(Charsets.US_ASCII)

        fun create(initialCwd: String? = null, initialScrollback: ByteArray? = null): TerminalSession {
            val shell = System.getenv("SHELL") ?: "/bin/bash"
            val home = System.getProperty("user.home")
            // Honour the persisted cwd if it still exists; otherwise fall back
            // to $HOME so a deleted directory doesn't kill the spawn.
            val startDir = initialCwd
                ?.takeIf { it.isNotBlank() }
                ?.let { File(it) }
                ?.takeIf { it.isDirectory }
                ?.absolutePath
                ?: home
            val env = HashMap(System.getenv()).apply {
                put("TERM", "xterm-256color")
                // Hide zsh's inverted-"%" PROMPT_SP marker that appears on the first prompt.
                put("PROMPT_EOL_MARK", "")
            }
            // Inject OSC 7 emitters into bash/zsh if needed. No-op for fish and
            // for shells we don't recognise; the polling fallback covers the
            // rest.
            ShellInitFiles.configureEnv(shell, env)
            val pty = PtyProcessBuilder(arrayOf(shell, "-l"))
                .setDirectory(startDir)
                .setEnvironment(env)
                .setInitialColumns(120)
                .setInitialRows(32)
                .start()
            return TerminalSession(pty, initialScrollback)
        }
    }
}
