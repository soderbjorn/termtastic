/**
 * Renderer-side client for the server's `/api/windows` multi-window registry.
 *
 * In Electron builds, every main BrowserWindow carries a unique id passed in
 * via the URL (`?window=<id>`). The renderer on startup reports its current
 * screen geometry to the server so a headless admin / future UI / external
 * tooling can enumerate the known windows. Geometry changes (resize) are
 * reported on `window.resize`; lifecycle cleanup is done on `beforeunload`.
 *
 * The authoritative cold-start restore path lives in Electron's main process
 * and uses a local JSON mirror rather than this endpoint — see
 * `electron/main.js` (`loadWindowsMirror`). This file's job is only to keep
 * the server-side view coherent with what the user actually has open.
 *
 * Not used in the plain web (non-Electron) client: the `File > New Window`
 * affordance and the whole multi-window feature are intentionally scoped to
 * the packaged Electron app per issue #18.
 *
 * @see reportWindowGeometry
 * @see installWindowRegistryReporter
 */
package se.soderbjorn.termtastic

import kotlinx.browser.window
import kotlin.js.json

/**
 * Read the `?window=<id>` query parameter set by Electron's main process
 * when it spawned this BrowserWindow. Falls back to `"primary"` when the
 * parameter is missing — typically because the app is running in a plain
 * browser (not Electron), or an older Electron main that hadn't shipped
 * the query-param handshake yet.
 *
 * Called from [start] during bootstrap and by [reportWindowGeometry] to
 * tag every registry update.
 *
 * @return the window id string
 */
internal fun readWindowIdFromUrl(): String {
    return try {
        val url = js("new URL(window.location.href)")
        val id = url.searchParams.get("window") as? String
        if (id.isNullOrBlank()) "primary" else id
    } catch (_: Throwable) {
        "primary"
    }
}

/**
 * Send this window's current screen geometry to the server's `/api/windows`
 * endpoint so the registry entry stays coherent with what the user sees.
 *
 * Fire-and-forget: the server does not need to acknowledge before the
 * renderer continues. Failures are swallowed — the next call will retry,
 * and the Electron local mirror is the authoritative source for the
 * cold-start restore path.
 *
 * @param id the client-assigned window id (see [readWindowIdFromUrl])
 */
internal fun reportWindowGeometry(id: String) {
    // window.screenX/screenY and outerWidth/outerHeight give the full
    // BrowserWindow bounds in screen pixels. Electron exposes these in
    // the renderer context without further plumbing.
    val payload = json(
        "id" to id,
        "x" to (window.asDynamic().screenX as? Number ?: 0),
        "y" to (window.asDynamic().screenY as? Number ?: 0),
        "width" to (window.asDynamic().outerWidth as? Number ?: window.innerWidth),
        "height" to (window.asDynamic().outerHeight as? Number ?: window.innerHeight),
        "displayId" to null,
        "updatedAt" to 0,
    )
    val init: dynamic = js("({})")
    init.method = "POST"
    init.headers = json(
        "Content-Type" to "application/json",
        "X-Termtastic-Auth" to authTokenForSending(),
        "X-Termtastic-Client-Type" to clientTypeAtStart,
    )
    init.body = JSON.stringify(payload)
    init.keepalive = true
    try {
        window.fetch("/api/windows", init)
    } catch (_: Throwable) {
        // Cosmetic — the server-side registry is best-effort from the
        // renderer; Electron's local mirror is the restore source of truth.
    }
}

/**
 * Tell the server to drop this window's registry entry. Called from a
 * `beforeunload` listener so the registry reflects that this window has
 * closed. `keepalive` keeps the request alive past renderer teardown.
 *
 * @param id the client-assigned window id
 */
internal fun reportWindowClosed(id: String) {
    val init: dynamic = js("({})")
    init.method = "DELETE"
    init.headers = json(
        "X-Termtastic-Auth" to authTokenForSending(),
        "X-Termtastic-Client-Type" to clientTypeAtStart,
    )
    init.keepalive = true
    try {
        window.fetch("/api/windows/" + encodeUriComponent(id), init)
    } catch (_: Throwable) {
        // Cosmetic — see reportWindowGeometry.
    }
}

/**
 * Install the window-registry reporter: registers initial geometry, hooks
 * resize, and cleans up on unload. Idempotent — calling twice installs
 * two sets of listeners, which would only mean two POSTs per event.
 * No-op outside Electron (where the feature is not active).
 *
 * Called once from [start] at the tail end of bootstrap, after auth is
 * settled.
 */
internal fun installWindowRegistryReporter() {
    if (!isElectronClient) return
    val id = readWindowIdFromUrl()
    reportWindowGeometry(id)
    // Debounced re-report on resize. We don't get a "move" event in the
    // renderer, so geometry changes from dragging the window aren't caught
    // here — the Electron main process keeps the local mirror in sync via
    // its own `move` listener, and the server registry eventually catches
    // up on the next resize or reload.
    var pending = 0
    window.addEventListener("resize", {
        if (pending != 0) window.clearTimeout(pending)
        pending = window.setTimeout({
            pending = 0
            reportWindowGeometry(id)
        }, 500)
    })
    // `beforeunload` is the right hook on Electron: a window close fires
    // it before the renderer tears down, and `keepalive` on the fetch
    // keeps the DELETE alive long enough to reach the server.
    window.addEventListener("beforeunload", {
        reportWindowClosed(id)
    })
}
