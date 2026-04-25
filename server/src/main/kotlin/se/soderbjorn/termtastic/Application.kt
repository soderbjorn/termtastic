/**
 * Ktor server entry point and module wiring for Termtastic.
 *
 * This file is now a thin orchestrator:
 *  - [main] brings up the SQLite persistence layer, restores the window
 *    layout, kicks off the persistence/scrollback/state-poller coroutines
 *    via [ServerInitializer] helpers, launches the Claude usage monitor,
 *    and starts the Netty HTTP/WebSocket server.
 *  - [Application.module] installs Ktor plugins and mounts route
 *    extensions defined in [PtyRoutes], [WindowRoutes], plus static
 *    files.
 *
 * Route, dispatch, and PTY-session logic lives in their own files.
 *
 * @see ServerInitializer
 * @see PtyRoutes
 * @see WindowRoutes
 * @see TerminalSessionManager
 * @see WindowState
 */
package se.soderbjorn.termtastic

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import se.soderbjorn.termtastic.persistence.AppPaths
import se.soderbjorn.termtastic.persistence.SettingsRepository
import se.soderbjorn.termtastic.ui.SettingsDialog
import java.io.File

/**
 * Application entry point. Initialises the persistence layer, restores the
 * window layout, starts background coroutines, launches the Claude usage
 * monitor, and starts the Netty HTTP/WebSocket server.
 */
fun main() {
    // Persistence first so the loaded window config (if any) is the very
    // first value seen by the rest of the app — that way no throwaway PTYs
    // are created for a default layout we'd immediately discard.
    val repo = SettingsRepository(AppPaths.databaseFile())
    WindowState.initialize(repo)

    val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    installWindowConfigPersister(persistenceScope, repo)
    val scrollbackSaver = installScrollbackSaver(persistenceScope, repo)
    val sessionStates = installSessionStatePoller(persistenceScope)
    val sharedThemesWatch = installSharedThemesWatcher(repo)

    val usageMonitor = ClaudeUsageMonitor()
    SettingsDialog.usageMonitor = usageMonitor
    if (repo.isClaudeUsagePollEnabled()) usageMonitor.start()

    Runtime.getRuntime().addShutdownHook(Thread {
        // Best-effort final flush so a clean Ctrl-C captures any unsaved
        // changes that landed inside the debounce window.
        runCatching {
            runBlocking {
                repo.saveWindowConfig(WindowState.config.value.withBlankSessionIds())
                scrollbackSaver.saveAll(force = true)
            }
        }
        usageMonitor.stop()
        runCatching { sharedThemesWatch.close() }
        persistenceScope.cancel()
        repo.close()
    })

    val port = System.getProperty("termtastic.port")?.toIntOrNull()
        ?: SERVER_PORT
    SettingsDialog.setListeningPort(port)

    // Bind to all interfaces so the "allow connections from other sources
    // than localhost" setting can be flipped at runtime without restarting
    // Netty. The default-off policy is enforced inside DeviceAuth.authorize.
    val server = embeddedServer(
        Netty,
        port = port,
        host = "0.0.0.0",
        module = { module(repo, sessionStates, usageMonitor) }
    )

    if (java.awt.GraphicsEnvironment.isHeadless()) {
        server.start(wait = true)
    } else {
        // Non-headless: start the Ktor server in the background and let
        // Compose Desktop own the main thread (required on macOS for the
        // AppKit run loop).
        server.start(wait = false)
        try {
            androidx.compose.ui.window.application(exitProcessOnExit = false) {
                SettingsDialog.renderIfShowing()
                se.soderbjorn.termtastic.auth.DeviceAuth.renderApprovalDialogIfShowing()
            }
        } catch (t: Throwable) {
            LoggerFactory.getLogger("Application")
                .error("Compose application loop crashed; server will stay up headless", t)
        }
        Thread.currentThread().join()
    }
}

/**
 * Ktor application module: installs plugins and mounts the route
 * extensions defined in [PtyRoutes] and [WindowRoutes].
 *
 * @param settingsRepo  the SQLite-backed settings store, shared across all routes
 * @param sessionStates flow of per-session AI assistant states, polled every 3 s
 * @param usageMonitor  the Claude CLI usage monitor whose data is pushed to clients
 */
fun Application.module(
    settingsRepo: SettingsRepository,
    sessionStates: MutableSharedFlow<Map<String, String?>>,
    usageMonitor: ClaudeUsageMonitor,
) {
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
        uiSettingsRoutes(settingsRepo)
        ptyRoutes(settingsRepo)
        windowRoutes(settingsRepo, sessionStates, usageMonitor)
    }
}
