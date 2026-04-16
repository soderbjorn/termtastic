package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import kotlin.js.json
import se.soderbjorn.termtastic.client.LocalStorageAuthTokenStore
import se.soderbjorn.termtastic.client.encodeBase64Url
import se.soderbjorn.termtastic.client.getOrCreateToken
import se.soderbjorn.termtastic.client.secureRandomBytes

// Flip to false to return dark mode to its plain, untinted look.
// When true, adds the `.dark-spiced` body class which layers a cool blue
// tint, a differentiated sidebar, an accent stripe on the header, an
// orange glow on the active tab, and deeper card shadows.
private const val DARK_SPICED = false

// Show the debug menu icon in the toolbar (only on localhost).
private const val SHOW_DEBUG_MENU = false

/** How pane status (working/waiting) is visualised on pane headers and tabs. */
private enum class PaneStatusDisplay { Dots, Glow, Both, None }

/** Decode from the persisted string, defaulting to [PaneStatusDisplay.Glow]. */
private fun parsePaneStatusDisplay(s: String?): PaneStatusDisplay =
    when (s) {
        "Dots" -> PaneStatusDisplay.Dots
        "Both" -> PaneStatusDisplay.Both
        "None" -> PaneStatusDisplay.None
        else -> PaneStatusDisplay.Glow
    }

fun main() {
    // Force the CSS import so webpack bundles it.
    @Suppress("UNUSED_VARIABLE")
    val css = xtermCss

    window.onload = { start() }
}

/**
 * Live xterm + WebSocket pair for one server-side session. The same instance
 * is reused across re-renders of the window configuration: its `container`
 * element is moved into the new layout via `appendChild`, which preserves
 * xterm state and avoids reconnecting the PTY.
 */
@JsName("ResizeObserver")
private external class ResizeObserver(callback: (dynamic, dynamic) -> Unit) {
    fun observe(target: HTMLElement)
    fun disconnect()
}

private class TerminalEntry(
    val paneId: String,
    val sessionId: String,
    val term: Terminal,
    val fit: FitAddon,
    val container: HTMLElement,
    var socket: WebSocket? = null,
    var connected: Boolean = false,
    var resizeObserver: dynamic = null,
    // Authoritative PTY cols/rows as last reported by the server. When
    // another client asserts a smaller size, [term] is resized down to
    // match (so the shell's cursor addressing lands in the right cells)
    // and the unused container space is tinted via [oobOverlayRight] /
    // [oobOverlayBottom].
    var ptyCols: Int? = null,
    var ptyRows: Int? = null,
    // Set to true while we're applying a server-pushed resize to [term],
    // so the resulting onResize → sendResize callback doesn't bounce the
    // same value back to the server.
    var applyingServerSize: Boolean = false,
    var oobOverlayRight: HTMLElement? = null,
    var oobOverlayBottom: HTMLElement? = null,
    var sendInput: ((String) -> Unit)? = null
)

/**
 * Run [fit].fit(), but if the viewport was scrolled up into the scrollback,
 * keep the user roughly anchored to the same content. xterm.js reflows the
 * buffer on resize and resets the viewport to the top of the scrollback,
 * which feels like the terminal jumping to the beginning of history. If the
 * viewport was already at the bottom we explicitly snap back there; otherwise
 * we restore the previous distance from the bottom so the user stays near
 * what they were reading.
 */
private fun fitPreservingScroll(term: Terminal, fit: FitAddon) {
    val buffer = term.asDynamic().buffer.active
    val baseYBefore = (buffer.baseY as? Number)?.toInt() ?: 0
    val viewportYBefore = (buffer.viewportY as? Number)?.toInt() ?: 0
    val distanceFromBottom = baseYBefore - viewportYBefore
    safeFit(term, fit)
    val bufferAfter = term.asDynamic().buffer.active
    val baseYAfter = (bufferAfter.baseY as? Number)?.toInt() ?: 0
    val viewportYAfter = (bufferAfter.viewportY as? Number)?.toInt() ?: 0
    val targetViewportY = (baseYAfter - distanceFromBottom).coerceIn(0, baseYAfter)
    val delta = targetViewportY - viewportYAfter
    if (delta != 0) {
        term.asDynamic().scrollLines(delta)
    }
}

/**
 * Wrapper around [fit].fit() that corrects a subpixel overflow that the
 * DomRenderer can introduce on Retina/HiDPI displays. xterm calculates
 * css.canvas.height via Math.round(deviceCanvasHeight / dpr), which can
 * round UP by 0.5 CSS px — making .xterm-screen fractionally taller than
 * the viewport. When that happens the bottom row is partially clipped by
 * the viewport's overflow. Detect and correct by dropping one row.
 */
/**
 * Show or hide the out-of-bounds overlay on an entry. The xterm grid is
 * resized to match the server's PTY size (via [applyServerSize]), so any
 * *container* space that isn't occupied by the live xterm screen is
 * "unused" — another client is pinning the PTY narrower/shorter than our
 * natural fit. We tint that container space so the user sees the dead
 * area and can press Reformat to reclaim it.
 *
 * Creates two overlay divs lazily on the container (right strip and bottom
 * strip) and positions them to cover whatever lies outside .xterm-screen.
 */
private fun updateOobOverlay(entry: TerminalEntry) {
    fun ensure(slot: String): HTMLElement {
        val existing = when (slot) {
            "right" -> entry.oobOverlayRight
            else -> entry.oobOverlayBottom
        }
        if (existing != null) return existing
        val el = document.createElement("div") as HTMLElement
        el.className = "oob-overlay oob-overlay-$slot"
        el.setAttribute(
            "title",
            "Unused by the current PTY size — press Reformat to reclaim this space"
        )
        entry.container.appendChild(el)
        if (slot == "right") entry.oobOverlayRight = el else entry.oobOverlayBottom = el
        return el
    }

    val screen = entry.term.asDynamic().element
        ?.asDynamic()?.querySelector(".xterm-screen") as? HTMLElement
    if (screen == null) {
        entry.oobOverlayRight?.style?.display = "none"
        entry.oobOverlayBottom?.style?.display = "none"
        return
    }

    // Work in container-local coordinates. The container has
    // position: relative so absolute-positioned children inside it land
    // relative to its top-left.
    val containerRect = entry.container.asDynamic().getBoundingClientRect()
    val screenRect = screen.asDynamic().getBoundingClientRect()
    val containerLeft = (containerRect.left as? Number)?.toDouble() ?: 0.0
    val containerTop = (containerRect.top as? Number)?.toDouble() ?: 0.0
    val containerWidth = (containerRect.width as? Number)?.toDouble() ?: 0.0
    val containerHeight = (containerRect.height as? Number)?.toDouble() ?: 0.0
    val screenLeft = ((screenRect.left as? Number)?.toDouble() ?: 0.0) - containerLeft
    val screenTop = ((screenRect.top as? Number)?.toDouble() ?: 0.0) - containerTop
    val screenWidth = (screenRect.width as? Number)?.toDouble() ?: 0.0
    val screenHeight = (screenRect.height as? Number)?.toDouble() ?: 0.0

    val gapRight = containerWidth - (screenLeft + screenWidth)
    val gapBottom = containerHeight - (screenTop + screenHeight)

    // Threshold to avoid a 1-px hairline tint from subpixel rounding on
    // HiDPI displays.
    val minGap = 4.0

    if (gapRight > minGap && screenHeight > 0) {
        val right = ensure("right")
        right.style.display = "block"
        right.style.left = "${screenLeft + screenWidth}px"
        right.style.top = "${screenTop}px"
        right.style.width = "${gapRight}px"
        right.style.height = "${screenHeight}px"
    } else {
        entry.oobOverlayRight?.style?.display = "none"
    }

    if (gapBottom > minGap && containerWidth > 0) {
        val bottom = ensure("bottom")
        bottom.style.display = "block"
        bottom.style.left = "${screenLeft}px"
        bottom.style.top = "${screenTop + screenHeight}px"
        bottom.style.width = "${maxOf(screenWidth, 0.0) + maxOf(gapRight, 0.0)}px"
        bottom.style.height = "${gapBottom}px"
    } else {
        entry.oobOverlayBottom?.style?.display = "none"
    }
}

/**
 * Apply a server-pushed PTY size: resize xterm's grid to match so cursor
 * addressing from the shell lands in the right cells, then refresh the
 * out-of-bounds tint over the container space that's no longer covered
 * by the live grid. The [TerminalEntry.applyingServerSize] flag is set
 * across the term.resize so [sendResize] can skip the bounce-back.
 */
private fun applyServerSize(entry: TerminalEntry, cols: Int, rows: Int) {
    entry.ptyCols = cols
    entry.ptyRows = rows
    if (cols != entry.term.cols || rows != entry.term.rows) {
        entry.applyingServerSize = true
        try {
            runCatching { entry.term.asDynamic().resize(cols, rows) }
        } finally {
            entry.applyingServerSize = false
        }
    }
    updateOobOverlay(entry)
}

/**
 * Reformat: tell the server to evict every other client's size entry and
 * use this client's cols/rows. The next time the other clients resize
 * themselves (rotation on android, browser drag on a second web tab) they
 * re-enter the min() aggregation — so this is a momentary override, not
 * a permanent coupling change.
 */
private fun forceReassert(entry: TerminalEntry) {
    val socket = entry.socket ?: return
    if (socket.readyState.toInt() != WebSocket.OPEN.toInt()) return
    try { safeFit(entry.term, entry.fit) } catch (_: Throwable) {}
    runCatching {
        socket.send(
            windowJson.encodeToString<PtyControl>(
                PtyControl.ForceResize(cols = entry.term.cols, rows = entry.term.rows)
            )
        )
    }
}

// Detect ESC[?25h (DECTCEM show cursor) in a raw byte chunk. Used to
// schedule a one-shot xterm refresh when an Ink-based TUI un-hides the
// cursor — see the call site in connectPane for context.
private fun containsShowCursor(bytes: Uint8Array): Boolean {
    val n = bytes.length
    if (n < 6) return false
    val d = bytes.asDynamic()
    var i = 0
    while (i <= n - 6) {
        if (d[i] == 0x1b &&
            d[i + 1] == 0x5b &&
            d[i + 2] == 0x3f &&
            d[i + 3] == 0x32 &&
            d[i + 4] == 0x35 &&
            d[i + 5] == 0x68
        ) {
            return true
        }
        i++
    }
    return false
}

private fun safeFit(term: Terminal, fit: FitAddon) {
    // Ask the FitAddon what it WOULD apply, apply the subpixel-overflow
    // correction up front, and then call term.resize() once. The previous
    // implementation called fit.fit() (resize #1) and then resize(rows-1)
    // (resize #2) when overflow was detected — two resizes paint in
    // consecutive xterm rAF frames, producing a visible one-line
    // up-then-back jump on every fit.
    val proposed = fit.asDynamic().proposeDimensions() ?: return
    var targetCols = (proposed.cols as? Number)?.toInt() ?: return
    var targetRows = (proposed.rows as? Number)?.toInt() ?: return
    if (targetCols < 1 || targetRows < 1) return

    val el = term.asDynamic().element as? HTMLElement
    if (el != null && targetRows > 1) {
        val viewport = el.querySelector(".xterm-viewport") as? HTMLElement
        val core = term.asDynamic()._core
        val cellHeight = (core?._renderService?.dimensions?.css?.cell?.height as? Number)?.toDouble()
        val viewportHeight = (viewport?.getBoundingClientRect()?.height as? Number)?.toDouble()
        if (cellHeight != null && cellHeight > 0.0 && viewportHeight != null) {
            val fitRows = kotlin.math.floor(viewportHeight / cellHeight).toInt()
            if (targetRows > fitRows && fitRows >= 1) {
                targetRows = fitRows
            }
        }
    }

    if (targetCols == term.cols && targetRows == term.rows) return
    term.asDynamic().resize(targetCols, targetRows)
}

private val defaultTheme: TerminalTheme
    get() = recommendedThemes.first { it.name == DEFAULT_THEME_NAME }

private fun systemPrefersDark(): Boolean =
    window.matchMedia("(prefers-color-scheme: dark)").matches

private fun isLightActive(appearance: Appearance): Boolean = when (appearance) {
    Appearance.Light -> true
    Appearance.Dark -> false
    Appearance.Auto -> !systemPrefersDark()
}

private fun themeForegroundForCurrent(theme: TerminalTheme, appearance: Appearance): String =
    if (isLightActive(appearance)) theme.lightFg else theme.darkFg

private fun themeBackgroundForCurrent(theme: TerminalTheme, appearance: Appearance): String =
    if (isLightActive(appearance)) theme.lightBg else theme.darkBg

// Returns true when the active background is closer to white than to black.
// Used to pick selection/ANSI-remap colors that depend on background lightness
// rather than the user's appearance preference (a light-mode Solarized still
// has a cream background, but a dark-mode Pencil theme has a near-black bg).
private fun isBackgroundLight(bg: String): Boolean {
    if (bg.length < 7 || !bg.startsWith("#")) return false
    val r = bg.substring(1, 3).toIntOrNull(16) ?: return false
    val g = bg.substring(3, 5).toIntOrNull(16) ?: return false
    val b = bg.substring(5, 7).toIntOrNull(16) ?: return false
    // Rec. 601 luma — cheap and good enough for a light/dark decision.
    return (r * 299 + g * 587 + b * 114) / 1000 > 140
}

/**
 * Ensure this browser has a unique, cryptographically strong auth token in
 * localStorage, and mirror it into a cookie so the browser attaches it to
 * every same-origin WebSocket upgrade and REST call. Unknown tokens trigger
 * an approval dialog on the server's desktop; approved tokens become silent
 * on subsequent launches. See docs/device-auth-plan.md.
 */
private fun ensureAuthToken() {
    val store = LocalStorageAuthTokenStore()
    val hadStored = !store.load().isNullOrEmpty()
    val token = getOrCreateToken(store)
    // getOrCreateToken writes to localStorage + mirrors the cookie on save,
    // but re-apply unconditionally so a cookie cleared behind our back gets
    // restored from localStorage on every boot.
    document.cookie = "termtastic_auth=$token; Path=/; SameSite=Strict; Max-Age=31536000"

    // Diagnostic: verify the cookie actually took. If document.cookie doesn't
    // include termtastic_auth after the assignment, the browser rejected it
    // (some combination of cookie policy, Electron partition, etc.) — and
    // every WS upgrade will hit the server with no token, popping a dialog
    // forever. Log loudly so we can tell in devtools.
    val tokenPrefix = token.take(6)
    val cookieNow = document.cookie
    val cookieHasToken = cookieNow.contains("termtastic_auth=")
    console.log(
        "[termtastic auth] hadStored=$hadStored tokenPrefix=$tokenPrefix " +
            "cookieVisible=$cookieHasToken cookieString=$cookieNow"
    )
    if (!cookieHasToken) {
        console.warn(
            "[termtastic auth] Cookie write silently rejected by the browser. " +
                "The server will see every connection as an unknown device and " +
                "prompt for approval forever. Check devtools > Application > " +
                "Cookies to see if anything was stored at all."
        )
    }
}

/**
 * Full-screen overlay shown when the server rejects this device (e.g. the
 * user clicked "Deny" on the approval dialog) or when the server is headless
 * and couldn't prompt at all. Offers a "Try again" button that clears the
 * cached token and reloads, giving the user a fresh approval attempt from
 * scratch.
 */
private fun showPendingApprovalOverlay() {
    if (document.getElementById("pending-approval-overlay") != null) return
    val overlay = document.createElement("div") as HTMLElement
    overlay.id = "pending-approval-overlay"
    overlay.setAttribute(
        "style",
        "position:fixed;inset:0;z-index:99999;background:rgba(0,0,0,0.85);" +
            "display:flex;align-items:center;justify-content:center;" +
            "font-family:-apple-system,system-ui,sans-serif;color:#eee;"
    )
    overlay.innerHTML = """
        <div style="max-width:460px;padding:28px 32px;background:#1e1e1e;
                    border:1px solid #444;border-radius:10px;box-shadow:0 8px 32px rgba(0,0,0,0.6);
                    text-align:center;">
          <h2 style="margin:0 0 12px 0;font-size:18px;">Waiting for server approval</h2>
          <p style="margin:0 0 18px 0;font-size:14px;line-height:1.5;color:#c8c8c8;">
            Look for the approval dialog on the host machine.
          </p>
          <div style="margin:0 auto;width:24px;height:24px;border:3px solid #555;
                      border-top-color:#0a84ff;border-radius:50%;animation:spin 1s linear infinite;">
          </div>
          <style>@keyframes spin { to { transform: rotate(360deg); } }</style>
        </div>
    """.trimIndent()
    document.body?.appendChild(overlay)
}

private fun hidePendingApprovalOverlay() {
    document.getElementById("pending-approval-overlay")?.remove()
}

private fun showDisconnectedModal() {
    if (document.getElementById("disconnected-overlay") != null) return
    val overlay = document.createElement("div") as HTMLElement
    overlay.id = "disconnected-overlay"
    overlay.setAttribute(
        "style",
        "position:fixed;inset:0;z-index:100000;background:rgba(0,0,0,0.85);" +
            "display:flex;align-items:center;justify-content:center;" +
            "font-family:-apple-system,system-ui,sans-serif;color:#eee;"
    )
    overlay.innerHTML = """
        <div style="max-width:460px;padding:28px 32px;background:#1e1e1e;
                    border:1px solid #444;border-radius:10px;box-shadow:0 8px 32px rgba(0,0,0,0.6);
                    text-align:center;">
          <h2 style="margin:0 0 12px 0;font-size:18px;">Connection lost</h2>
          <p style="margin:0 0 18px 0;font-size:14px;line-height:1.5;color:#c8c8c8;">
            The connection to the server was lost. Check that the server is
            running and try again.
          </p>
          <button id="disconnected-retry"
                  style="padding:8px 18px;background:#0a84ff;color:#fff;border:none;
                         border-radius:6px;cursor:pointer;font-size:14px;">
            Retry
          </button>
        </div>
    """.trimIndent()
    document.body?.appendChild(overlay)
    document.getElementById("disconnected-retry")?.addEventListener("click", {
        window.location.reload()
    })
}

private fun hideDisconnectedModal() {
    document.getElementById("disconnected-overlay")?.remove()
}

private fun showDeviceRejectedOverlay(closeCode: Int, closeReason: String) {
    // Avoid stacking multiple overlays if several sockets close in quick
    // succession.
    if (document.getElementById("device-rejected-overlay") != null) return
    val overlay = document.createElement("div") as HTMLElement
    overlay.id = "device-rejected-overlay"
    overlay.setAttribute(
        "style",
        "position:fixed;inset:0;z-index:100000;background:rgba(0,0,0,0.85);" +
            "display:flex;align-items:center;justify-content:center;" +
            "font-family:-apple-system,system-ui,sans-serif;color:#eee;"
    )
    val headline = if (closeReason.contains("headless", ignoreCase = true))
        "Server can't show the approval dialog"
    else
        "This device isn't approved"
    val body = if (closeReason.contains("headless", ignoreCase = true))
        "The Termtastic server is running in headless mode, so it can't pop " +
            "up the approval prompt on the host desktop. Approve this device " +
            "out-of-band (or run the server with a display attached) and try again."
    else
        "The Termtastic server rejected this browser. Ask the user at the " +
            "host machine to approve this device, then try again."
    overlay.innerHTML = """
        <div style="max-width:460px;padding:28px 32px;background:#1e1e1e;
                    border:1px solid #444;border-radius:10px;box-shadow:0 8px 32px rgba(0,0,0,0.6);">
          <h2 style="margin:0 0 12px 0;font-size:18px;">${escapeHtmlForOverlay(headline)}</h2>
          <p style="margin:0 0 18px 0;font-size:14px;line-height:1.5;color:#c8c8c8;">
            ${escapeHtmlForOverlay(body)}
          </p>
          <div style="font-size:12px;color:#888;margin-bottom:18px;">
            WebSocket close $closeCode · ${escapeHtmlForOverlay(closeReason)}
          </div>
          <button id="device-rejected-retry"
                  style="padding:8px 18px;background:#0a84ff;color:#fff;border:none;
                         border-radius:6px;cursor:pointer;font-size:14px;">
            Try again
          </button>
        </div>
    """.trimIndent()
    document.body?.appendChild(overlay)
    val btn = document.getElementById("device-rejected-retry") as? HTMLElement
    btn?.addEventListener("click", {
        // Clear the cached token so a retry generates a brand-new one —
        // otherwise the server keeps rejecting the same (still-unapproved)
        // token and the dialog never gets a second chance.
        window.localStorage.removeItem("termtastic.authToken")
        document.cookie = "termtastic_auth=; Path=/; Max-Age=0"
        window.location.reload()
    })
}

private fun escapeHtmlForOverlay(s: String): String =
    s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

private fun authTokenForSending(): String =
    window.localStorage.getItem("termtastic.authToken") ?: ""

private fun encodeUriComponent(value: String): String =
    js("encodeURIComponent(value)") as String

private fun start() {
    // Set the auth token before any WebSocket opens so the browser has a
    // termtastic_auth cookie to attach to every request.
    ensureAuthToken()

    // Electron wraps the web client as a desktop app; we label those
    // connections "Computer" in the new-device dialog so the user can tell
    // them apart from a browser tab. The full declaration of `isElectron`
    // lives lower down inside start(), but the client-info query param has
    // to be built before the first WebSocket opens, so recompute it here.
    val clientTypeAtStart =
        if (window.navigator.userAgent.contains("Electron", ignoreCase = true)) "Computer" else "Web"

    val authQueryParam = "auth=" + encodeUriComponent(authTokenForSending()) +
        "&clientType=" + encodeUriComponent(clientTypeAtStart)

    val statusDot = document.getElementById("connection-status") as? HTMLElement
    val connectionState = HashMap<String, String>()

    fun updateAggregateStatus() {
        val states = connectionState.values
        val disconnected = states.isNotEmpty() && states.any { it == "disconnected" }
        if (disconnected) {
            showDisconnectedModal()
        } else {
            hideDisconnectedModal()
        }
    }

    val terminals = HashMap<String, TerminalEntry>()
    var currentConfig: dynamic = null
    var activeTabId: String? = null
    // Tracks which pane (if any) is maximized in each tab. Purely client-side.
    val maximizedPaneIds = mutableMapOf<String, String>() // tabId → paneId

    // Forward references — defined later in start() but invoked from context
    // menu lambdas that precede their declarations.
    var openPaneTypeModal: ((String?, SplitDirection?, String?) -> Unit)? = null
    var openConfirmDialog: ((String, String, String, () -> Unit) -> Unit)? = null
    var countSessionPanes: ((String?) -> Int)? = null

    // Per-pane cache of file-browser state. Survives DOM rebuilds (each
    // renderConfig replaces the cell, but the cached listings/html let the
    // new DOM repaint immediately while a fresh server fetch is in flight).
    class FileBrowserPaneState {
        // Directory listings keyed by dirRelPath ("" = root).
        val dirListings = HashMap<String, Array<dynamic>>()
        // Directories the user has expanded — mirrors server-side state.
        val expandedDirs = HashSet<String>()
        var html: String? = null
        var kind: String? = null           // "Markdown" | "Text" | "Binary"
        var selectedRelPath: String? = null
        // Filename filter (glob like *.md, or substring). Mirrors the server's
        // FileBrowserContent.fileFilter so the input reflects persisted state
        // immediately on rebuild without a config round-trip.
        var fileFilter: String = ""
        // "name" | "mtime" — mirrors server-side FileBrowserContent.sortBy.
        var sortBy: String = "name"
    }
    val fileBrowserPaneStates = HashMap<String, FileBrowserPaneState>()
    // Live DOM handles for the currently-mounted file-browser views, refilled
    // by every renderConfig() pass. Used to route incoming fileBrowserDir /
    // fileBrowserContent / fileBrowserError messages back to the right pane.
    class FileBrowserPaneView(
        val listBody: HTMLElement,
        val rendered: HTMLElement,
    )
    val fileBrowserPaneViews = HashMap<String, FileBrowserPaneView>()

    // ---- Git pane state ---------------------------------------------------
    class GitPaneState {
        var entries: dynamic = null
        var selectedFilePath: String? = null
        var diffHtml: String? = null  // cached rendered diff HTML
        var diffMode: String = "Inline"
        var graphicalDiff: Boolean = false
        var diffFontSize: Int = 12
        var searchQuery: String = ""
        var searchMatchIndex: Int = 0
    }
    val gitPaneStates = HashMap<String, GitPaneState>()
    val collapsedGitSections = HashSet<String>()
    class GitPaneView(
        val listBody: HTMLElement,
        val diffPane: HTMLElement,
        var searchCounter: HTMLElement? = null,
        var searchNavButtons: List<HTMLElement> = emptyList(),
    )
    val gitPaneViews = HashMap<String, GitPaneView>()

    // Latest session states pushed from the server. Kept so that
    // renderConfig() can re-apply dots after rebuilding the DOM.
    var latestSessionStates = HashMap<String, String?>()

    // Debug overrides for session states (set via the debug menu).
    // These take priority over server-pushed states.
    val debugSessionStates = HashMap<String, String?>()

    // Developer tools enabled via 5 clicks on the settings header.
    // Retained in memory across dialog opens, but not persisted to DB.
    var devToolsEnabled = false

    // Update state dots from a server-provided map of session states.
    // Called when the server pushes a "state" message and also after
    // renderConfig() rebuilds the DOM.
    fun updateStateDots(sessionStates: Map<String, String?>) {
        // Merge debug overrides on top of server states.
        val effective = HashMap(sessionStates)
        for ((k, v) in debugSessionStates) effective[k] = v
        for ((sessionId, state) in effective) {
            val dots = document.querySelectorAll(".pane-state-dot[data-session='$sessionId']")
            for (i in 0 until dots.length) {
                val el = dots.item(i) as? HTMLElement ?: continue
                val isSidebar = el.classList.contains("sidebar-state-dot")
                val base = if (isSidebar) "pane-state-dot sidebar-state-dot" else "pane-state-dot"
                el.className = if (state != null) "$base state-$state" else base
                // Propagate glow class to the ancestor .terminal-cell
                if (!isSidebar) {
                    val cell = el.closest(".terminal-cell") as? HTMLElement
                    if (cell != null) {
                        cell.classList.remove("pane-working", "pane-waiting")
                        if (state != null) cell.classList.add("pane-$state")
                    }
                }
            }
        }
        // Aggregate per-tab: "waiting" wins over "working" (needs attention).
        val cfg = currentConfig ?: return
        val tabsArr = (cfg.tabs as? Array<dynamic>) ?: return
        fun collectSessionIds(node: dynamic, into: MutableList<String>) {
            if (node.kind == "leaf") {
                into.add(node.sessionId as String)
            } else {
                collectSessionIds(node.first, into)
                collectSessionIds(node.second, into)
            }
        }
        for (tab in tabsArr) {
            val tabId = tab.id as String
            val sids = mutableListOf<String>()
            if (tab.root != null) collectSessionIds(tab.root, sids)
            val floats = (tab.floating as? Array<dynamic>) ?: emptyArray()
            for (fp in floats) sids.add(fp.leaf.sessionId as String)

            var tabState: String? = null
            for (sid in sids) {
                when (effective[sid]) {
                    "waiting" -> { tabState = "waiting"; break }
                    "working" -> if (tabState != "working") tabState = "working"
                }
            }
            val tabDots = document.querySelectorAll("[data-tab-state='$tabId']")
            for (j in 0 until tabDots.length) {
                val el = tabDots.item(j) as? HTMLElement ?: continue
                val isSidebar = el.classList.contains("sidebar-state-dot")
                val base = if (isSidebar) "pane-state-dot sidebar-state-dot" else "pane-state-dot tab-state-dot"
                el.className = if (tabState != null) "$base state-$tabState" else base
            }
            // Propagate glow class to the tab button
            val tabBtn = document.querySelector(".tab-button[data-tab='$tabId']") as? HTMLElement
            if (tabBtn != null) {
                tabBtn.classList.remove("tab-working", "tab-waiting")
                if (tabState != null) tabBtn.classList.add("tab-$tabState")
            }
        }

        // Aggregate across ALL tabs for the logo status dot: "waiting" wins
        // over "working", same priority as per-tab aggregation.
        var globalPaneState: String? = null
        for ((_,  state) in effective) {
            when (state) {
                "waiting" -> { globalPaneState = "waiting"; break }
                "working" -> if (globalPaneState != "working") globalPaneState = "working"
            }
        }
        val dot = statusDot
        if (dot != null) {
            dot.classList.remove("pane-state-working", "pane-state-waiting")
            when (globalPaneState) {
                "working" -> dot.classList.add("pane-state-working")
                "waiting" -> dot.classList.add("pane-state-waiting")
            }
        }

        // The active tab's width may have changed due to a dot appearing or
        // disappearing — re-measure so the orange ring stays in sync.
        val indicator = document.querySelector(".tab-active-indicator") as? HTMLElement
        val activeBtn = document.querySelector(".tab-button.active") as? HTMLElement
        if (indicator != null && activeBtn != null) {
            indicator.style.width = "${(activeBtn.asDynamic().offsetWidth as Number).toDouble()}px"
            indicator.style.transform = "translate3d(${(activeBtn.asDynamic().offsetLeft as Number).toDouble()}px, 0, 0)"
        }
    }

    // In-memory mirror of the server-side settings blob. Populated by the
    // initial GET /api/ui-settings fetch; updated in-place on every user change
    // and POST'd back as a partial merge.
    var settings: dynamic = js("({})")

    fun putSetting(key: String, value: dynamic) {
        settings[key] = value
        val init: dynamic = js("({})")
        init.method = "POST"
        init.headers = json(
            "Content-Type" to "application/json",
            "X-Termtastic-Auth" to authTokenForSending(),
            "X-Termtastic-Client-Type" to clientTypeAtStart,
        )
        init.body = JSON.stringify(json(key to value))
        window.fetch("/api/ui-settings", init)
    }

    var theme = defaultTheme
    var appearance = Appearance.Auto
    var paneStatusDisplay = PaneStatusDisplay.Glow
    var sidebarOpenWidth = 260  // last known open width; updated on drag & server push

    /** Sync body classes so CSS can show/hide dots and glow independently. */
    fun applyPaneStatusClasses() {
        document.body?.classList?.remove("show-pane-dots", "show-pane-glow")
        when (paneStatusDisplay) {
            PaneStatusDisplay.Dots -> document.body?.classList?.add("show-pane-dots")
            PaneStatusDisplay.Glow -> document.body?.classList?.add("show-pane-glow")
            PaneStatusDisplay.Both -> {
                document.body?.classList?.add("show-pane-dots")
                document.body?.classList?.add("show-pane-glow")
            }
            PaneStatusDisplay.None -> {} // neither class → both hidden
        }
    }

    fun buildXtermTheme(): dynamic {
        val bg = themeBackgroundForCurrent(theme, appearance)
        val fg = themeForegroundForCurrent(theme, appearance)
        // Drive selection/ANSI-remap from the actual background luminance, not
        // the appearance toggle, so themes with tinted backgrounds (Solarized
        // Light's cream, Ubuntu's eggplant) still get readable highlights.
        val bgLight = isBackgroundLight(bg)
        val base = json(
            "background" to bg,
            "foreground" to fg,
            "cursor" to fg,
            "cursorAccent" to bg,
            "selectionBackground" to if (bgLight) "rgba(0,0,0,0.18)" else "rgba(255,255,255,0.25)"
        )
        // Remap the ends of the ANSI palette that would otherwise collide with
        // the terminal background — e.g. Claude Code emits ANSI white for parts
        // of its diff view, which is invisible on a white background in light
        // mode. xterm.js's theme accepts overrides for any of the 16 standard
        // colors (black, red, …, white, brightBlack, …, brightWhite).
        if (bgLight) {
            base["white"] = "#3b3b3b"
            base["brightWhite"] = "#5a5a5a"
            base["yellow"] = "#866a00"       // dark gold — readable on white
            base["brightYellow"] = "#9d7e00" // slightly lighter gold
            base["green"] = "#116329"        // dark green — GitHub-style diff green
            base["brightGreen"] = "#1a7f37"  // medium green
            base["cyan"] = "#0b6e6e"         // dark teal
            base["brightCyan"] = "#0f8585"   // medium teal
            base["blue"] = "#0550ae"         // dark blue
            base["brightBlue"] = "#0969da"   // medium blue
            base["magenta"] = "#6e3996"      // dark purple
            base["brightMagenta"] = "#8250df"// medium purple
            base["red"] = "#b3261e"          // dark red — diff removed
            base["brightRed"] = "#cf222e"    // medium red
        } else {
            base["black"] = "#5a5a5a"
            base["brightBlack"] = "#8a8a8a"
        }
        return base
    }

    val isElectron = window.navigator.userAgent.contains("Electron", ignoreCase = true)

    // Popout-mode detection: when Electron opens a separate BrowserWindow for
    // a popped-out pane, it loads the app with ?popout=<paneId>. Presence of
    // the param switches this renderer into a stripped-down single-pane render
    // path further below (after all the helper functions are defined). The
    // pane's content kind (terminal / markdown / git) is resolved from the
    // first server config push, so the URL doesn't need to encode it.
    val urlSearch = js("new URLSearchParams(window.location.search)")
    val popoutPaneIdParam = (urlSearch.get("popout") as? String)?.takeIf { it.isNotEmpty() }
    val isPopoutMode = popoutPaneIdParam != null
    if (isPopoutMode) {
        document.body?.classList?.add("popout-mode")
    }

    // Forward-declared callback used by ensureTerminal's focusin handler.
    // The real implementation (which depends on sendWindowCommand) is wired
    // up later in start() once that function is in scope.
    var notifyFocusedPane: (String, String) -> Unit = { _, _ -> }

    // Mark a leaf cell as the focused pane: adds the `.focused` class (which
    // drives the orange ring and title color) and notifies the server so the
    // sidebar highlight and tab-switch restoration pick up the change.
    // Terminal panes route through this via a `focusin` listener on the
    // xterm container; non-terminal panes (markdown, empty) call it from a
    // mousedown handler since they have no textarea to receive DOM focus.
    fun markPaneFocused(cell: HTMLElement) {
        val all = document.querySelectorAll(".terminal-cell.focused")
        for (i in 0 until all.length) {
            val el = all.item(i) as HTMLElement
            if (el !== cell) el.classList.remove("focused")
        }
        cell.classList.add("focused")
        val tabPane = cell.asDynamic().closest(".tab-pane") as? HTMLElement
        val tabId = tabPane?.id
        val paneId = cell.getAttribute("data-pane")
        if (!tabId.isNullOrEmpty() && !paneId.isNullOrEmpty()) {
            notifyFocusedPane(tabId, paneId)
        }
        // Immediately update the sidebar highlight so it feels snappy
        // without waiting for the server config roundtrip.
        if (!paneId.isNullOrEmpty()) {
            val sidebar = document.getElementById("sidebar")
            val items = sidebar?.querySelectorAll(".sidebar-pane-item")
            if (items != null) {
                for (i in 0 until items.length) {
                    val item = items.item(i) as HTMLElement
                    val itemPane = item.getAttribute("data-pane")
                    if (itemPane == paneId) item.classList.add("active-pane")
                    else item.classList.remove("active-pane")
                }
            }
        }
    }

    // Quote a path so it survives being pasted at a shell prompt with spaces
    // or other meta characters. Single-quote wrap, with `'\''` for embedded
    // single quotes — the standard POSIX shell escape.
    fun shellQuote(p: String): String = "'" + p.replace("'", "'\\''") + "'"

    fun attachDragDrop(container: HTMLElement, term: Terminal) {
        container.addEventListener("dragenter", { event -> event.preventDefault() })
        container.addEventListener("dragover", { event ->
            event.preventDefault()
            event.asDynamic().dataTransfer.dropEffect = "copy"
        })
        container.addEventListener("drop", { event ->
            event.preventDefault()
            event.stopPropagation()
            val files = event.asDynamic().dataTransfer?.files ?: return@addEventListener
            val count = (files.length as Number).toInt()
            if (count == 0) return@addEventListener
            // Electron 32+ removed File.path; the replacement lives behind
            // electronApi.getPathForFile, exposed by electron/preload.js.
            val api = window.asDynamic().electronApi
            val parts = mutableListOf<String>()
            for (k in 0 until count) {
                val file = files[k]
                val path = (if (api?.getPathForFile != null) api.getPathForFile(file) else file.path) as? String
                if (!path.isNullOrEmpty()) parts.add(shellQuote(path))
            }
            if (parts.isEmpty()) return@addEventListener
            term.focus()
            term.paste(parts.joinToString(" "))
        })
    }

    val proto = if (window.location.protocol == "https:") "wss" else "ws"

    fun connectPane(entry: TerminalEntry) {
        val url = "$proto://${window.location.host}/pty/${entry.sessionId}?$authQueryParam"
        connectionState[entry.sessionId] = "connecting"
        updateAggregateStatus()

        val socket = WebSocket(url)
        socket.asDynamic().binaryType = "arraybuffer"
        entry.socket = socket

        fun isOpen(): Boolean = socket.readyState.toInt() == WebSocket.OPEN.toInt()

        // Coalesce bursts of resize events (e.g. while dragging the window edge)
        // so the PTY only sees the final size after activity stops. SIGWINCH-aware
        // TUI apps like claude redraw on every resize, so this matters.
        var pendingResize: Int? = null
        fun sendResize() {
            // Skip the send if this onResize was triggered by us applying
            // a server-pushed size — sending the same dims back would just
            // clobber our "natural" entry in the server's clientSizes map,
            // so when the narrow client later disconnects we'd stay stuck
            // at the narrow dims instead of widening back.
            if (entry.applyingServerSize) return
            if (!isOpen()) return
            // Capture cols/rows NOW, not when the debounced timer fires.
            // During the 50ms window a stale server Size message can arrive
            // and applyServerSize shrinks the grid — reading at fire time
            // would then send those stale dimensions back, locking the PTY
            // at the wrong size until the user explicitly Reformats.
            val cols = entry.term.cols
            val rows = entry.term.rows
            pendingResize?.let { window.clearTimeout(it) }
            pendingResize = window.setTimeout({
                pendingResize = null
                if (!isOpen()) return@setTimeout
                socket.send(
                    windowJson.encodeToString<PtyControl>(
                        PtyControl.Resize(cols = cols, rows = rows)
                    )
                )
            }, 50)
        }

        fun sendInput(data: String) {
            if (!isOpen()) return
            val encoder = js("new TextEncoder()")
            val bytes = encoder.encode(data)
            socket.send(bytes.buffer as ArrayBuffer)
        }

        entry.sendInput = ::sendInput
        entry.term.onData { data -> sendInput(data) }
        entry.term.onResize { _ ->
            sendResize()
            updateOobOverlay(entry)
        }

        socket.onopen = { _: Event ->
            entry.connected = true
            connectionState[entry.sessionId] = "connected"
            updateAggregateStatus()
            // Defer the initial resize by a tick so any Size frame the
            // server already queued (authoritative PTY dims) is dispatched
            // to onmessage and applied via applyServerSize before we read
            // entry.term.cols/rows. Otherwise we'd race the server's size
            // update and potentially re-enter the min() aggregation with
            // stale values.
            window.setTimeout({ sendResize() }, 0)
        }
        socket.onmessage = { event ->
            val data = event.asDynamic().data
            if (data is String) {
                // Server-side control frame. Carries the authoritative
                // PTY size; we resize xterm to match so shell cursor
                // addressing lands in the right cells, then tint any
                // container space left over (another client is pinning
                // the PTY narrower than our fit would otherwise choose).
                runCatching {
                    val msg = windowJson.decodeFromString<PtyServerMessage>(data)
                    when (msg) {
                        is PtyServerMessage.Size -> applyServerSize(entry, msg.cols, msg.rows)
                    }
                }
            } else {
                val buf = data as ArrayBuffer
                val bytes = Uint8Array(buf)
                entry.term.write(bytes)
                // Workaround: Claude Code (and other Ink-based TUIs) emit
                // ESC[?25l / ESC[?25h around their render passes. xterm.js
                // parses these correctly but on initial startup the show
                // doesn't always trigger a repaint of the cursor layer until
                // something else (a keystroke, a tab refit) nudges the
                // renderer. Detect the show byte sequence and force a
                // refresh on the next animation frame. Cheap, idempotent.
                if (containsShowCursor(bytes)) {
                    val term = entry.term
                    window.requestAnimationFrame {
                        try {
                            term.asDynamic().refresh(0, term.rows - 1)
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        }
        socket.onclose = { event ->
            entry.connected = false
            // If the entry has been removed from the registry the session is
            // gone for good — don't reconnect.
            if (terminals[entry.paneId] === entry) {
                connectionState[entry.sessionId] = "disconnected"
                updateAggregateStatus()
                val code = (event.asDynamic().code as? Number)?.toInt() ?: 0
                val reason = (event.asDynamic().reason as? String) ?: ""
                if (code == 1008) {
                    // Device-approval gate rejected us. Stop the reconnect
                    // loop and surface the same overlay the /window socket
                    // uses so the user gets a single consistent error.
                    showDeviceRejectedOverlay(code, reason)
                } else {
                    window.setTimeout({ connectPane(entry) }, 500)
                }
            }
        }
        socket.onerror = {
            socket.close()
        }
    }

    fun ensureTerminal(paneId: String, sessionId: String): TerminalEntry {
        terminals[paneId]?.let { return it }

        val container = document.createElement("div") as HTMLElement
        container.className = "terminal"
        container.setAttribute("data-session", sessionId)
        val inner = document.createElement("div") as HTMLElement
        inner.className = "terminal-inner"
        container.appendChild(inner)

        val term = Terminal(
            json(
                "cursorBlink" to true,
                "fontFamily" to "Menlo, Monaco, 'Courier New', monospace",
                "fontSize" to 13,
                // Safety net for truecolor (ESC[38;2;R;G;Bm) foregrounds that
                // slip past buildXtermTheme()'s 16-color palette remap — e.g.
                // Claude Code's syntax highlighter emits near-white RGB which
                // would otherwise vanish on a light background.
                "minimumContrastRatio" to 4.5,
                "theme" to buildXtermTheme()
            )
        )
        val fit = FitAddon()
        term.loadAddon(fit)
        term.open(inner)
        // xterm.js's `_handleThemeChange` is what writes the theme background
        // into the .xterm-viewport's inline `style.backgroundColor`, but the
        // initial theme passed via the constructor doesn't always trigger it.
        // Re-assign the theme via the property setter so the viewport bg ends
        // up matching var(--terminal-bg) — otherwise .terminal-inner's padding
        // strip would surround a viewport with a stale default bg, showing
        // up as a visible inner ring (the "double border" we just removed).
        term.options.theme = buildXtermTheme()
        // Best-effort initial fit. The container may have zero size at this
        // point if the cell is being constructed before being attached to a
        // visible tab — in that case the ResizeObserver below will fit it as
        // soon as it gets a real layout box.
        try { safeFit(term, fit) } catch (_: Throwable) {}
        try { term.focus() } catch (_: Throwable) {}

        attachDragDrop(container, term)

        // Highlight the focused pane with an orange ring around its terminal.
        // xterm 5.x doesn't expose onFocus on Terminal directly, but it puts
        // a hidden <textarea> inside the container that receives real DOM
        // focus — so we can listen for `focusin` on the container (focus
        // events don't bubble; focusin does). We don't clear on blur: the
        // last-focused pane keeps its indicator so the user always sees a
        // "current" pane even while interacting with chrome (rename inputs,
        // menus, etc.).
        container.addEventListener("focusin", { _ ->
            val cell = container.asDynamic().closest(".terminal-cell") as? HTMLElement
                ?: return@addEventListener
            markPaneFocused(cell)
        })

        val entry = TerminalEntry(paneId, sessionId, term, fit, container)
        terminals[paneId] = entry
        connectionState[sessionId] = "connecting"
        updateAggregateStatus()

        // Re-fit whenever the cell's box changes size for any reason: split
        // layout settling, sibling pane added/closed, window drag, font load,
        // etc. The xterm `term.onResize` listener wired in connectPane will
        // forward the new dims to the server when fit() actually changes them.
        val observer = ResizeObserver { _, _ ->
            try {
                if (entry.container.offsetParent != null) {
                    fitPreservingScroll(entry.term, entry.fit)
                    updateOobOverlay(entry)
                }
            } catch (_: Throwable) {
                // Ignore — element may be size 0 mid-transition.
            }
        }
        observer.observe(entry.container)
        entry.resizeObserver = observer

        connectPane(entry)
        return entry
    }

    fun fitVisible() {
        for (entry in terminals.values) {
            // Only fit terminals whose container is currently laid out (offsetParent != null).
            val parent = (entry.term.asDynamic().element as? HTMLElement)?.offsetParent
            if (parent != null) {
                try {
                    fitPreservingScroll(entry.term, entry.fit)
                } catch (_: Throwable) {
                    // Ignore — element may be size 0 mid-transition.
                }
            }
        }
    }

    fun applyAppearanceClass() {
        document.body?.classList?.remove("appearance-light", "appearance-dark", "dark-spiced")
        val light = isLightActive(appearance)
        document.body?.classList?.add(if (light) "appearance-light" else "appearance-dark")
        if (!light && DARK_SPICED) document.body?.classList?.add("dark-spiced")
        // Push the active theme background into the CSS custom property that
        // styles the .terminal padding box, so the strip around the xterm
        // canvas matches the in-terminal background (otherwise themes like
        // Solarized Light leave a stale white border around the cream view).
        val bg = themeBackgroundForCurrent(theme, appearance)
        document.body?.style?.setProperty("--terminal-bg", bg)
    }

    fun applyThemeToTerminals() {
        val xtermTheme = buildXtermTheme()
        for (entry in terminals.values) {
            entry.term.options.theme = xtermTheme
        }
    }

    fun applyAll() {
        applyAppearanceClass()
        applyThemeToTerminals()
    }
    applyAll()

    // React to system color scheme changes when in Auto.
    val mql = window.matchMedia("(prefers-color-scheme: dark)")
    mql.asDynamic().addEventListener("change", {
        if (appearance == Appearance.Auto) applyAll()
    })

    fun setTheme(t: TerminalTheme) {
        theme = t
        applyAll()
        putSetting("theme", t.name)
    }

    fun setAppearance(a: Appearance) {
        appearance = a
        applyAll()
        putSetting("appearance", a.name)
    }

    fun renderThemeSwatch(t: TerminalTheme): String =
        """<span class="theme-swatch">
            <span class="half light" style="color:${t.lightFg};background:${t.lightBg}">A</span>
            <span class="half dark" style="color:${t.darkFg};background:${t.darkBg}">A</span>
        </span>"""

    // Global pane font size. Applies uniformly to terminal, markdown and git
    // diff panes, and persists through /api/ui-settings so every client and
    // every tab shares one value.
    val fontSizePresets = listOf(10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24)
    var paneFontSize: Int = 14

    // Set after sendCmd is declared below; used so applyGlobalFontSize can
    // kick git diffs to re-render without a forward reference to sendCmd.
    var requestGitDiff: ((String, String) -> Unit)? = null

    fun applyGlobalFontSize(size: Int) {
        paneFontSize = size
        for ((_, entry) in terminals) {
            entry.term.options.fontSize = size
            try { safeFit(entry.term, entry.fit) } catch (_: Throwable) {}
        }
        val mdRoots = document.querySelectorAll(".md-rendered")
        for (i in 0 until mdRoots.length) {
            (mdRoots.item(i) as? HTMLElement)?.style?.fontSize = "${size}px"
        }
        for ((paneId, view) in gitPaneViews) {
            val state = gitPaneStates[paneId] ?: continue
            state.diffFontSize = size
            state.diffHtml = null
            view.diffPane.style.fontSize = "${size}px"
            view.diffPane.style.lineHeight = "${(size * 1.54).toInt()}px"
            val sel = state.selectedFilePath
            if (sel != null) requestGitDiff?.invoke(paneId, sel)
        }
    }

    // ── Settings sidebar ────────────────────────────────────────────────
    // A right-hand sidebar inside .app-body, the same way the left sidebar
    // works. Slides in with a CSS transition, reducing the content area so
    // theme / appearance changes are immediately visible.

    var settingsPanel: HTMLElement? = null
    var settingsEscHandler: ((Event) -> Unit)? = null

    fun closeSettingsPanel() {
        val panel = settingsPanel ?: return
        panel.classList.remove("open")
        // After the slide-out transition, remove from DOM.
        panel.addEventListener("transitionend", {
            if (!panel.classList.contains("open")) panel.remove()
        })
        settingsEscHandler?.let { document.removeEventListener("keydown", it) }
        settingsEscHandler = null
        settingsPanel = null
    }

    /** Build (or toggle) the settings sidebar. */
    fun openSettingsPanel() {
        if (settingsPanel != null) { closeSettingsPanel(); return }

        val appBody = document.querySelector(".app-body") as? HTMLElement ?: return
        val panel = document.createElement("aside") as HTMLElement
        panel.className = "settings-sidebar"

        // Close button
        val closeBtn = document.createElement("button") as HTMLElement
        closeBtn.className = "pane-modal-close"
        closeBtn.innerHTML = "&times;"
        closeBtn.addEventListener("click", { closeSettingsPanel() })
        panel.appendChild(closeBtn)

        var devToolsSection: HTMLElement? = null

        val title = document.createElement("h2") as HTMLElement
        title.className = "pane-modal-title"
        title.textContent = "Settings"

        // 5 clicks in 2 seconds on the title enables developer tools.
        var devClickTimes = mutableListOf<Double>()
        title.style.cursor = "default"
        title.addEventListener("click", {
            val now = kotlin.js.Date.now()
            devClickTimes.add(now)
            devClickTimes = devClickTimes.filter { now - it < 2000 }.toMutableList()
            if (devClickTimes.size >= 5 && !devToolsEnabled) {
                devToolsEnabled = true
                devToolsSection?.style?.display = ""
            }
        })
        panel.appendChild(title)

        val body = document.createElement("div") as HTMLElement
        body.className = "settings-sidebar-body"

        // ─── Theme ──────────────────────────────────────────────────
        val themeSection = document.createElement("div") as HTMLElement
        themeSection.className = "settings-section"
        val themeTitle = document.createElement("div") as HTMLElement
        themeTitle.className = "settings-label"
        themeTitle.textContent = "Theme"
        themeSection.appendChild(themeTitle)

        val themeGrid = document.createElement("div") as HTMLElement
        themeGrid.className = "settings-theme-grid"

        fun renderThemeGrid() {
            themeGrid.innerHTML = ""
            for (t in recommendedThemes) {
                val card = document.createElement("div") as HTMLElement
                card.className = "settings-theme-card" + if (t.name == theme.name) " selected" else ""
                card.innerHTML = """${renderThemeSwatch(t)}<span class="settings-theme-name">${t.name}</span>"""
                card.addEventListener("click", {
                    setTheme(t)
                    renderThemeGrid()
                })
                themeGrid.appendChild(card)
            }
        }
        renderThemeGrid()
        themeSection.appendChild(themeGrid)
        body.appendChild(themeSection)

        // ─── Appearance ─────────────────────────────────────────────
        val appSection = document.createElement("div") as HTMLElement
        appSection.className = "settings-section"
        val appLabel = document.createElement("div") as HTMLElement
        appLabel.className = "settings-label"
        appLabel.textContent = "Appearance"
        appSection.appendChild(appLabel)

        val appRow = document.createElement("div") as HTMLElement
        appRow.className = "settings-button-row"
        fun renderAppearanceRow() {
            appRow.innerHTML = ""
            for (a in Appearance.values()) {
                val btn = document.createElement("button") as HTMLElement
                btn.className = "settings-choice-btn" + if (a == appearance) " selected" else ""
                btn.textContent = a.name
                btn.addEventListener("click", {
                    setAppearance(a)
                    renderAppearanceRow()
                })
                appRow.appendChild(btn)
            }
        }
        renderAppearanceRow()
        appSection.appendChild(appRow)
        body.appendChild(appSection)

        // ─── Text size ──────────────────────────────────────────────
        val sizeSection = document.createElement("div") as HTMLElement
        sizeSection.className = "settings-section"
        val sizeLabel = document.createElement("div") as HTMLElement
        sizeLabel.className = "settings-label"
        sizeLabel.textContent = "Text size"
        sizeSection.appendChild(sizeLabel)

        val sizeRow = document.createElement("div") as HTMLElement
        sizeRow.className = "settings-button-row settings-size-row"
        fun renderSizeRow() {
            sizeRow.innerHTML = ""
            for (s in fontSizePresets) {
                val btn = document.createElement("button") as HTMLElement
                btn.className = "settings-choice-btn" + if (s == paneFontSize) " selected" else ""
                btn.textContent = "${s}px"
                btn.addEventListener("click", {
                    if (s != paneFontSize) {
                        applyGlobalFontSize(s)
                        putSetting("paneFontSize", s)
                    }
                    renderSizeRow()
                })
                sizeRow.appendChild(btn)
            }
        }
        renderSizeRow()
        sizeSection.appendChild(sizeRow)
        body.appendChild(sizeSection)

        // ─── Pane status display ────────────────────────────────────
        val statusSection = document.createElement("div") as HTMLElement
        statusSection.className = "settings-section"
        val statusLabel = document.createElement("div") as HTMLElement
        statusLabel.className = "settings-label"
        statusLabel.textContent = "Pane status indicator"
        statusSection.appendChild(statusLabel)

        val statusRow = document.createElement("div") as HTMLElement
        statusRow.className = "settings-button-row"
        fun renderStatusRow() {
            statusRow.innerHTML = ""
            for (mode in PaneStatusDisplay.values()) {
                val label = when (mode) {
                    PaneStatusDisplay.Dots -> "Colored dots"
                    PaneStatusDisplay.Glow -> "Colored glow"
                    PaneStatusDisplay.Both -> "Both"
                    PaneStatusDisplay.None -> "None"
                }
                val btn = document.createElement("button") as HTMLElement
                btn.className = "settings-choice-btn" + if (mode == paneStatusDisplay) " selected" else ""
                btn.textContent = label
                btn.addEventListener("click", {
                    paneStatusDisplay = mode
                    applyPaneStatusClasses()
                    putSetting("paneStatusDisplay", mode.name)
                    updateStateDots(latestSessionStates)
                    renderStatusRow()
                })
                statusRow.appendChild(btn)
            }
        }
        renderStatusRow()
        statusSection.appendChild(statusRow)
        body.appendChild(statusSection)

        // ─── Developer Tools (hidden until activated) ───────────────
        val devSection = document.createElement("div") as HTMLElement
        devSection.className = "settings-section"
        if (!devToolsEnabled) devSection.style.display = "none"
        devToolsSection = devSection

        val devLabel = document.createElement("div") as HTMLElement
        devLabel.className = "settings-label"
        devLabel.textContent = "Developer Tools"
        devSection.appendChild(devLabel)

        val devRow = document.createElement("div") as HTMLElement
        devRow.className = "settings-button-row"
        for ((label, state) in listOf("Working" to "working", "Waiting" to "waiting", "Clear" to null)) {
            val btn = document.createElement("button") as HTMLElement
            btn.className = "settings-choice-btn"
            btn.textContent = label
            btn.addEventListener("click", {
                val focusedCell = document.querySelector(".terminal-cell.focused") as? HTMLElement
                val paneId = focusedCell?.getAttribute("data-pane")
                if (paneId != null) {
                    val entry = terminals[paneId]
                    if (entry != null) {
                        if (state != null) {
                            debugSessionStates[entry.sessionId] = state
                        } else {
                            debugSessionStates.remove(entry.sessionId)
                        }
                        updateStateDots(latestSessionStates)
                    }
                }
            })
            devRow.appendChild(btn)
        }
        devSection.appendChild(devRow)
        body.appendChild(devSection)

        panel.appendChild(body)

        // ─── Server settings button (sends OpenSettings to server) ──
        val srvBtn = document.createElement("button") as HTMLElement
        srvBtn.className = "settings-server-btn"
        srvBtn.textContent = "Server settings\u2026"
        // Click handler wired after sendCmd is declared; stored on element.
        panel.appendChild(srvBtn)

        appBody.appendChild(panel)
        settingsPanel = panel

        // Trigger the slide-in on the next frame so the transition fires.
        window.requestAnimationFrame {
            panel.classList.add("open")
        }

        // Escape key closes
        val escHandler: (Event) -> Unit = { ev ->
            val kev = ev as? KeyboardEvent
            if (kev?.key == "Escape") closeSettingsPanel()
        }
        document.addEventListener("keydown", escHandler)
        settingsEscHandler = escHandler
    }

    // Apply initial pane status classes.
    applyPaneStatusClasses()

    // Close dropdowns and any open pane menus on outside click.
    document.addEventListener("click", {
        (document.getElementById("debug-dropdown") as? HTMLElement)?.classList?.remove("open")
        val openMenus = document.querySelectorAll(".pane-menu.open, .tab-menu.open, .tab-menu-list.open, .pane-split-wrap.open, .pane-flyout-wrap.open")
        for (i in 0 until openMenus.length) {
            (openMenus.item(i) as HTMLElement).classList.remove("open")
        }
    })

    // Apply a server-sourced ui-settings blob. Used by both the initial
    // REST hydration and the `/window` WS `uiSettings` push so e.g. flipping
    // the appearance in the main window propagates to popped-out pane windows.
    fun applyServerUiSettings(data: dynamic) {
        if (data == null) return
        settings = data
        var changed = false
        val serverTheme = (data.theme as? String)
            ?.let { name -> recommendedThemes.firstOrNull { it.name == name } }
        if (serverTheme != null && serverTheme.name != theme.name) {
            theme = serverTheme; changed = true
        }
        val serverAppearance = (data.appearance as? String)
            ?.let { runCatching { Appearance.valueOf(it) }.getOrNull() }
        if (serverAppearance != null && serverAppearance != appearance) {
            appearance = serverAppearance; changed = true
        }
        val serverWidth = data.sidebarWidth as? Int
        val sb = document.getElementById("sidebar") as? HTMLElement
        if (serverWidth != null && sb != null) {
            sb.style.width = "${serverWidth}px"
            if (serverWidth > 0) sidebarOpenWidth = serverWidth
        }
        val serverFontSize = (data.paneFontSize as? Number)?.toInt()
        if (serverFontSize != null && serverFontSize != paneFontSize) {
            applyGlobalFontSize(serverFontSize)
        }
        val serverPaneStatus = parsePaneStatusDisplay(data.paneStatusDisplay as? String)
        if (serverPaneStatus != paneStatusDisplay) {
            paneStatusDisplay = serverPaneStatus
            applyPaneStatusClasses()
        }
        if (changed) {
            applyAll()
        }
    }

    // Hydrate UI prefs from the server (sole source of truth). We paint with
    // defaults immediately for a fast first frame, then apply saved values
    // once the fetch completes (localhost, so near-instant).
    val uiSettingsInit: dynamic = js("({})")
    uiSettingsInit.headers = json(
        "X-Termtastic-Auth" to authTokenForSending(),
        "X-Termtastic-Client-Type" to clientTypeAtStart,
    )
    window.fetch("/api/ui-settings", uiSettingsInit).then { resp ->
        val ok = resp.asDynamic().ok as Boolean
        if (!ok) return@then null
        resp.json()
    }.then { data ->
        applyServerUiSettings(data)
    }

    // Always swallow drops on the rest of the page so a stray drop outside a
    // terminal pane never navigates Chrome to file://… and blows away the app.
    document.addEventListener("dragover", { event -> event.preventDefault() })
    document.addEventListener("drop", { event -> event.preventDefault() })
    updateAggregateStatus()

    // ---------------------------------------------------------------------
    // Window-config WebSocket: server is the source of truth for the layout.
    // ---------------------------------------------------------------------

    var windowSocket: WebSocket? = null

    fun sendWindowCommand(payload: String) {
        val ws = windowSocket ?: return
        if (ws.readyState.toInt() == WebSocket.OPEN.toInt()) ws.send(payload)
    }

    // Typed command sender. Every outgoing mutation goes through here so the
    // wire format comes from the shared `WindowCommand` sealed class in
    // :clientServer — the hand-built JSON strings this replaced were easy to
    // get wrong (typos, missing escapes) and couldn't catch drift at compile
    // time when the server added a new command.
    fun sendCmd(cmd: WindowCommand) {
        sendWindowCommand(windowJson.encodeToString<WindowCommand>(cmd))
    }

    requestGitDiff = { paneId, filePath ->
        sendCmd(WindowCommand.GitDiff(paneId = paneId, filePath = filePath))
    }

    val settingsButton = document.getElementById("settings-button") as? HTMLElement
    if (settingsButton != null) {
        settingsButton.addEventListener("click", { event ->
            event.stopPropagation()
            openSettingsPanel()
            // Wire the "Server settings" button inside the panel now that
            // sendCmd is available.
            val srvBtn = settingsPanel
                ?.querySelector(".settings-server-btn") as? HTMLElement
            srvBtn?.addEventListener("click", {
                sendCmd(WindowCommand.OpenSettings)
            })
        })
    }

    // Debug menu — only visible when SHOW_DEBUG_MENU is on and on localhost.
    val isDev = SHOW_DEBUG_MENU && window.location.hostname.let { it == "localhost" || it == "127.0.0.1" }
    val debugDropdown = document.getElementById("debug-dropdown") as? HTMLElement
    val debugButton = document.getElementById("debug-button") as? HTMLElement
    val debugMenu = document.getElementById("debug-menu") as? HTMLElement
    if (isDev && debugDropdown != null && debugButton != null && debugMenu != null) {
        debugDropdown.style.display = ""

        fun buildDebugMenu() {
            debugMenu.innerHTML = ""

            // "Set pane state" submenu
            val submenu = document.createElement("div") as HTMLElement
            submenu.className = "pane-submenu pane-menu-item open-left"
            submenu.textContent = "Set pane state"

            val submenuList = document.createElement("div") as HTMLElement
            submenuList.className = "pane-submenu-list"

            for ((label, state) in listOf("Working" to "working", "Waiting" to "waiting", "Clear" to null)) {
                val item = document.createElement("div") as HTMLElement
                item.className = "pane-menu-item"
                item.textContent = label
                item.addEventListener("click", { ev ->
                    ev.stopPropagation()
                    debugDropdown.classList.remove("open")
                    // Find focused pane's session ID
                    val focusedCell = document.querySelector(".terminal-cell.focused") as? HTMLElement
                    val paneId = focusedCell?.getAttribute("data-pane")
                    if (paneId != null) {
                        val entry = terminals[paneId]
                        if (entry != null) {
                            if (state != null) {
                                debugSessionStates[entry.sessionId] = state
                            } else {
                                debugSessionStates.remove(entry.sessionId)
                            }
                            updateStateDots(latestSessionStates)
                        }
                    }
                })
                submenuList.appendChild(item)
            }
            submenu.appendChild(submenuList)
            debugMenu.appendChild(submenu)
        }

        buildDebugMenu()

        debugButton.addEventListener("click", { ev ->
            ev.stopPropagation()
            debugDropdown.classList.toggle("open")
        })
    }

    // Claude Code usage bar (single line at bottom of window)
    val usageBar = document.getElementById("claude-usage-bar") as? HTMLElement

    fun pctClass(pct: Int): String = when {
        pct >= 90 -> "usage-critical"
        pct >= 70 -> "usage-warn"
        else -> "usage-ok"
    }

    fun formatFetchedAt(isoString: String): String {
        if (isoString.isBlank()) return ""
        return try {
            val d = kotlin.js.Date(isoString)
            val year = d.getFullYear()
            val month = (d.getMonth() + 1).toString().padStart(2, '0')
            val day = d.getDate().toString().padStart(2, '0')
            val hour = d.getHours().toString().padStart(2, '0')
            val min = d.getMinutes().toString().padStart(2, '0')
            "(updated $year-$month-$day $hour:$min)"
        } catch (_: Exception) { "" }
    }

    val MONTH_MAP = mapOf(
        "jan" to 0, "feb" to 1, "mar" to 2, "apr" to 3,
        "may" to 4, "jun" to 5, "jul" to 6, "aug" to 7,
        "sep" to 8, "oct" to 9, "nov" to 10, "dec" to 11,
    )

    fun formatResetTime(raw: String): String {
        if (raw.isBlank()) return ""
        // Parse the am/pm time component (always present).
        val timeMatch = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)""", RegexOption.IGNORE_CASE).find(raw)
            ?: return ""
        var h = timeMatch.groupValues[1].toInt()
        val m = timeMatch.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        val isPm = timeMatch.groupValues[3].lowercase() == "pm"
        if (isPm && h < 12) h += 12
        if (!isPm && h == 12) h = 0
        val hh = h.toString().padStart(2, '0')
        val mm = m.toString().padStart(2, '0')

        // Parse optional date component ("Apr 24", "May 1", etc.).
        // If absent the reset is today.
        val dateMatch = Regex("""([A-Za-z]{3})\s+(\d{1,2})""").find(raw)
        val now = kotlin.js.Date()
        val year: Int
        val month: Int
        val day: Int
        if (dateMatch != null) {
            month = MONTH_MAP[dateMatch.groupValues[1].lowercase()] ?: now.getMonth()
            day = dateMatch.groupValues[2].toInt()
            // If the month is earlier than now, the reset is next year.
            year = if (month < now.getMonth() ||
                (month == now.getMonth() && day < now.getDate()))
                now.getFullYear() + 1 else now.getFullYear()
        } else {
            year = now.getFullYear()
            month = now.getMonth()
            day = now.getDate()
        }
        val yyyy = year.toString()
        val mo = (month + 1).toString().padStart(2, '0')
        val dd = day.toString().padStart(2, '0')
        return "(resets $yyyy-$mo-$dd $hh:$mm)"
    }

    fun usageItem(label: String, pct: Int, resetTime: String = ""): String {
        val reset = formatResetTime(resetTime)
        val resetHtml = if (reset.isNotBlank()) """ <span class="usage-reset">$reset</span>""" else ""
        return """<span class="usage-item">$label <span class="usage-pct ${pctClass(pct)}">${pct}%</span>$resetHtml</span>"""
    }

    fun updateClaudeUsageBadge(usage: dynamic) {
        val bar = usageBar ?: return
        if (usage == null || usage == undefined) {
            bar.style.display = "none"
            return
        }
        val sessionPct = (usage.sessionPercent as? Int) ?: 0
        val sessionReset = (usage.sessionResetTime as? String) ?: ""
        val weeklyAllPct = (usage.weeklyAllPercent as? Int) ?: 0
        val weeklyAllReset = (usage.weeklyAllResetTime as? String) ?: ""
        val weeklySonnetPct = (usage.weeklySonnetPercent as? Int) ?: 0
        val extraEnabled = (usage.extraUsageEnabled as? Boolean) ?: false
        val extraInfo = usage.extraUsageInfo as? String
        val fetchedAt = (usage.fetchedAt as? String) ?: ""

        val ts = formatFetchedAt(fetchedAt)

        var html = """<span class="usage-item" style="font-weight:600;">Claude Code usage:</span>"""
        html += usageItem("Session", sessionPct, sessionReset)
        html += usageItem("Week", weeklyAllPct, weeklyAllReset)
        html += usageItem("Sonnet", weeklySonnetPct)
        if (ts.isNotBlank()) {
            html += """<span class="usage-item">$ts</span>"""
        }
        html += """<button class="usage-refresh-btn" title="Refresh usage">&#x21bb;</button>"""

        bar.innerHTML = html
        bar.querySelector(".usage-refresh-btn")?.addEventListener("click", { sendCmd(WindowCommand.RefreshUsage) })
        bar.className = "claude-usage-bar ${pctClass(sessionPct)}"
        bar.style.display = ""
    }

    fun jsonString(s: String): String = js("JSON.stringify(s)") as String

    // Now that sendWindowCommand & jsonString exist, install the real
    // notifyFocusedPane implementation. ensureTerminal's focusin handler
    // (set up earlier) will start using this on the next focus event.
    notifyFocusedPane = { tabId, paneId ->
        sendCmd(WindowCommand.SetFocusedPane(tabId = tabId, paneId = paneId))
    }

    fun startRename(titleEl: HTMLElement, paneId: String) {
        val current = titleEl.textContent ?: ""
        val parent = titleEl.parentElement ?: return
        val input = document.createElement("input") as HTMLInputElement
        input.type = "text"
        input.className = "terminal-title-input"
        input.value = current
        parent.replaceChild(input, titleEl)
        input.focus()
        input.select()

        var settled = false
        fun cancel() {
            if (settled) return
            settled = true
            if (input.parentElement === parent) parent.replaceChild(titleEl, input)
        }
        fun commit() {
            if (settled) return
            settled = true
            val newTitle = input.value.trim()
            if (newTitle == current) {
                if (input.parentElement === parent) parent.replaceChild(titleEl, input)
                return
            }
            // Empty submission clears the custom name on the server, which
            // falls back to the cwd-derived title.
            sendCmd(WindowCommand.Rename(paneId = paneId, title = newTitle))
            if (newTitle.isEmpty()) {
                // Server may not push a config update if there was no custom
                // name to clear, so restore the title element ourselves to
                // avoid stranding the input in place.
                if (input.parentElement === parent) parent.replaceChild(titleEl, input)
                return
            }
            // The next config push will rebuild the header with the new
            // title — until then, leave the input in place to avoid a
            // jarring flash back to the old name.
        }

        input.addEventListener("blur", { commit() })
        input.addEventListener("keydown", { ev ->
            val key = (ev as KeyboardEvent).key
            when (key) {
                "Enter" -> { ev.preventDefault(); commit() }
                "Escape" -> { ev.preventDefault(); cancel() }
            }
        })
        // Prevent clicks inside the input from bubbling up to the
        // document-level outside-click handler that closes pane menus.
        input.addEventListener("click", { ev -> ev.stopPropagation() })
    }

    fun startTabRename(labelEl: HTMLElement, tabId: String) {
        val current = labelEl.textContent ?: ""
        val parent = labelEl.parentElement ?: return
        // Mark the tab as being renamed so the ⋯ menu hides itself for the
        // duration; also force-close the menu in case it was open when the
        // user picked "Rename tab".
        parent.classList.add("renaming")
        val openMenu = parent.querySelector(".tab-menu.open") as? HTMLElement
        openMenu?.classList?.remove("open")
        val input = document.createElement("input") as HTMLInputElement
        input.type = "text"
        input.className = "tab-label-input"
        input.value = current
        parent.replaceChild(input, labelEl)
        input.focus()
        input.select()

        var settled = false
        fun cancel() {
            if (settled) return
            settled = true
            if (input.parentElement === parent) parent.replaceChild(labelEl, input)
            parent.classList.remove("renaming")
        }
        fun commit() {
            if (settled) return
            settled = true
            val newTitle = input.value.trim()
            if (newTitle.isEmpty() || newTitle == current) {
                if (input.parentElement === parent) parent.replaceChild(labelEl, input)
                parent.classList.remove("renaming")
                return
            }
            parent.classList.remove("renaming")
            sendCmd(WindowCommand.RenameTab(tabId = tabId, title = newTitle))
            // Next config push will rebuild the tab bar with the new name.
        }

        input.addEventListener("blur", { commit() })
        input.addEventListener("keydown", { ev ->
            val key = (ev as KeyboardEvent).key
            when (key) {
                "Enter" -> { ev.preventDefault(); commit() }
                "Escape" -> { ev.preventDefault(); cancel() }
            }
        })
        input.addEventListener("click", { ev -> ev.stopPropagation() })
        input.addEventListener("dblclick", { ev -> ev.stopPropagation() })
    }

    /**
     * True iff the pane [paneId] is currently in the floating layer of any
     * tab. Used by the pane menu to label "Toggle floating" as either
     * "Float pane" or "Dock pane" depending on its current state.
     */
    fun isPaneFloating(paneId: String): Boolean {
        val cfg = currentConfig ?: return false
        val tabsArr = cfg.tabs as Array<dynamic>
        for (tab in tabsArr) {
            val floats = (tab.floating as? Array<dynamic>) ?: continue
            for (fp in floats) {
                if ((fp.leaf.id as String) == paneId) return true
            }
        }
        return false
    }

    /** Find the .tab-pane ancestor for a given element. */
    fun findTabPane(el: HTMLElement): HTMLElement? {
        var cur: HTMLElement? = el
        while (cur != null) {
            if (cur.classList.contains("tab-pane")) return cur
            cur = cur.parentElement as? HTMLElement
        }
        return null
    }

    // SVG icons for maximize / restore buttons.
    val iconMaximize = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="1.5"/></svg>"""
    val iconRestore = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="6" y="3" width="15" height="15" rx="1.5"/><path d="M6 7H4.5A1.5 1.5 0 0 0 3 8.5V21a1.5 1.5 0 0 0 1.5 1.5H17A1.5 1.5 0 0 0 18.5 21v-1.5"/></svg>"""

    /**
     * Restore a maximized pane back to its normal split position, animated.
     */
    fun restorePane(tabId: String, animate: Boolean = true) {
        val paneId = maximizedPaneIds.remove(tabId) ?: return
        val cell = document.querySelector(".terminal-cell[data-pane=\"$paneId\"]") as? HTMLElement ?: return
        val tabPane = findTabPane(cell) ?: return
        val backdrop = tabPane.querySelector(".maximized-backdrop") as? HTMLElement

        // Update button icon immediately
        val btn = cell.querySelector(".pane-maximize-btn") as? HTMLElement
        btn?.innerHTML = iconMaximize
        btn?.setAttribute("title", "Maximize pane")

        if (animate) {
            val splitChild = cell.parentElement as? HTMLElement
            val tabRect = tabPane.getBoundingClientRect()

            // Get the target rect from the split-child (which maintained its
            // flex-grow size even while the cell was absolutely positioned).
            val targetRect = splitChild?.getBoundingClientRect()
            val targetTop = (targetRect?.top ?: 0.0) - tabRect.top
            val targetLeft = (targetRect?.left ?: 0.0) - tabRect.left
            val targetWidth = targetRect?.width ?: tabRect.width
            val targetHeight = targetRect?.height ?: tabRect.height

            // Start from the full-size position
            cell.classList.remove("maximized")
            cell.style.position = "absolute"
            cell.style.zIndex = "20"
            cell.style.top = "0px"
            cell.style.left = "0px"
            cell.style.width = "${tabRect.width}px"
            cell.style.height = "${tabRect.height}px"
            cell.classList.add("restoring")

            // Force reflow, then animate to target
            cell.offsetHeight
            backdrop?.classList?.remove("visible")
            cell.style.top = "${targetTop}px"
            cell.style.left = "${targetLeft}px"
            cell.style.width = "${targetWidth}px"
            cell.style.height = "${targetHeight}px"

            var restored = false
            cell.addEventListener("transitionend", { ev ->
                if (restored) return@addEventListener
                if ((ev.target as? HTMLElement) !== cell) return@addEventListener
                restored = true
                cell.classList.remove("restoring")
                cell.style.removeProperty("position")
                cell.style.removeProperty("z-index")
                cell.style.removeProperty("top")
                cell.style.removeProperty("left")
                cell.style.removeProperty("width")
                cell.style.removeProperty("height")
                backdrop?.remove()
                fitVisible()
            })
        } else {
            cell.classList.remove("maximized")
            cell.style.removeProperty("position")
            cell.style.removeProperty("z-index")
            cell.style.removeProperty("top")
            cell.style.removeProperty("left")
            cell.style.removeProperty("width")
            cell.style.removeProperty("height")
            backdrop?.remove()
            fitVisible()
        }
    }

    /**
     * Maximize a pane so it visually fills the entire tab area, animated
     * from its current position. Purely client-side overlay — no server
     * involvement.
     */
    fun maximizePane(paneId: String, animate: Boolean = true) {
        val cell = document.querySelector(".terminal-cell[data-pane=\"$paneId\"]") as? HTMLElement ?: return
        val tabPane = findTabPane(cell) ?: return
        val tabId = tabPane.id
        // If another pane is already maximized in this tab, restore it first.
        val prev = maximizedPaneIds[tabId]
        if (prev != null && prev != paneId) {
            restorePane(tabId, animate = false)
        }
        maximizedPaneIds[tabId] = paneId

        // Backdrop
        var backdrop = tabPane.querySelector(".maximized-backdrop") as? HTMLElement
        if (backdrop == null) {
            backdrop = document.createElement("div") as HTMLElement
            backdrop.className = "maximized-backdrop"
            backdrop.addEventListener("click", { _ -> restorePane(tabId) })
            tabPane.appendChild(backdrop)
        }

        if (animate) {
            // Capture current rect relative to tabPane
            val tabRect = tabPane.getBoundingClientRect()
            val cellRect = cell.getBoundingClientRect()
            val startTop = cellRect.top - tabRect.top
            val startLeft = cellRect.left - tabRect.left
            val startWidth = cellRect.width
            val startHeight = cellRect.height

            // Set starting position
            cell.style.position = "absolute"
            cell.style.zIndex = "20"
            cell.style.top = "${startTop}px"
            cell.style.left = "${startLeft}px"
            cell.style.width = "${startWidth}px"
            cell.style.height = "${startHeight}px"
            cell.classList.add("maximizing")

            // Force layout, then animate to full size
            cell.offsetHeight // reflow
            backdrop.classList.add("visible")
            cell.style.top = "0px"
            cell.style.left = "0px"
            cell.style.width = "100%"
            cell.style.height = "100%"

            // Add focused class locally during the animation (without
            // notifying the server, which would trigger a DOM rebuild
            // mid-transition). Full markPaneFocused runs after the
            // transition completes.
            cell.classList.add("focused")

            var maximized = false
            cell.addEventListener("transitionend", { ev ->
                if (maximized) return@addEventListener
                if ((ev.target as? HTMLElement) !== cell) return@addEventListener
                maximized = true
                cell.classList.remove("maximizing")
                cell.classList.add("maximized")
                cell.style.removeProperty("top")
                cell.style.removeProperty("left")
                cell.style.removeProperty("width")
                cell.style.removeProperty("height")
                cell.style.removeProperty("position")
                cell.style.removeProperty("z-index")
                markPaneFocused(cell)
                val entry = terminals[paneId]
                entry?.term?.focus()
                fitVisible()
            })
        } else {
            // Snap to maximized (no animation) — used during DOM rebuilds.
            cell.classList.add("maximized")
            backdrop.classList.add("visible")
            markPaneFocused(cell)
            val entry = terminals[paneId]
            entry?.term?.focus()
            fitVisible()
        }

        // Update the button icon
        val btn = cell.querySelector(".pane-maximize-btn") as? HTMLElement
        btn?.innerHTML = iconRestore
        btn?.setAttribute("title", "Restore pane")
    }

    fun buildPaneHeader(
        paneId: String,
        title: String,
        sessionId: String? = null,
        popoutMode: Boolean = false,
        isLink: Boolean = false,
        extraControls: List<HTMLElement> = emptyList(),
    ): HTMLElement {
        val header = document.createElement("div") as HTMLElement
        header.className = "terminal-header"

        val titleEl = document.createElement("div") as HTMLElement
        titleEl.className = "terminal-title"
        titleEl.textContent = title
        titleEl.setAttribute("title", "Hover, then click to rename")
        // Renaming is a two-step gesture so it doesn't conflict with dragging
        // the pane (the title doubles as a grab handle, especially for
        // floating panes):
        //   1. Hover the title for ~1s — the title visually "arms" itself
        //      (background highlight) to signal that it's now clickable.
        //   2. Click while armed → enter rename input.
        // A plain click on a cold (un-armed) title does nothing, leaving
        // mousedown free to start a drag. Double-click is a fast path that
        // skips the arming delay entirely.
        var armTimer: Int = -1
        fun disarm() {
            if (armTimer != -1) {
                window.clearTimeout(armTimer)
                armTimer = -1
            }
            titleEl.classList.remove("armed")
        }
        titleEl.addEventListener("mouseenter", { _ ->
            disarm()
            armTimer = window.setTimeout({
                armTimer = -1
                titleEl.classList.add("armed")
            }, 1000)
        })
        titleEl.addEventListener("mouseleave", { _ -> disarm() })
        // Mousedown that turns into a drag will fire mouseleave and disarm
        // naturally; a mousedown-then-mouseup-without-moving fires click,
        // which we gate on the armed state below.
        titleEl.addEventListener("click", { ev ->
            if (!titleEl.classList.contains("armed")) return@addEventListener
            ev.stopPropagation()
            disarm()
            startRename(titleEl, paneId)
        })
        titleEl.addEventListener("dblclick", { ev ->
            ev.stopPropagation()
            ev.preventDefault()
            disarm()
            startRename(titleEl, paneId)
        })
        header.appendChild(titleEl)

        // Claude Code state indicator dot. Always created so updateStateDots
        // can propagate glow classes to the .terminal-cell; CSS body classes
        // (show-pane-dots / show-pane-glow) control visibility.
        if (sessionId != null) {
            val dot = document.createElement("span") as HTMLElement
            dot.className = "pane-state-dot"
            dot.setAttribute("data-session", sessionId)
            header.appendChild(dot)
        }

        val spacer = document.createElement("div") as HTMLElement
        spacer.className = "terminal-header-spacer"
        header.appendChild(spacer)

        for (ctrl in extraControls) header.appendChild(ctrl)

        fun makeIconBtn(titleText: String, svg: String, onClick: (dynamic) -> Unit, extraClass: String = ""): HTMLElement {
            val btn = document.createElement("button") as HTMLElement
            btn.className = "pane-action-btn" + (if (extraClass.isNotEmpty()) " $extraClass" else "")
            btn.setAttribute("type", "button")
            btn.setAttribute("title", titleText)
            btn.innerHTML = svg
            btn.addEventListener("click", { ev ->
                ev.stopPropagation()
                onClick(ev)
            })
            return btn
        }

        val iconSplit = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="16" rx="1.5"/><line x1="12" y1="4" x2="12" y2="20"/></svg>"""
        val iconFloat = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="6" width="16" height="13" rx="1.5"/><line x1="4" y1="9" x2="20" y2="9"/></svg>"""
        val iconDock = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="1.5"/><line x1="3" y1="9" x2="21" y2="9"/></svg>"""
        val iconPopOut = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 4h6v6"/><line x1="10" y1="14" x2="20" y2="4"/><path d="M20 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1h5"/></svg>"""
        val iconCopy = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="11" height="11" rx="1.5"/><path d="M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1"/></svg>"""
        val iconReformat = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="5" width="18" height="14" rx="1.5"/><polyline points="7 10 4 12 7 14"/><polyline points="17 10 20 12 17 14"/></svg>"""
        val iconClose = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="6" y1="6" x2="18" y2="18"/><line x1="18" y1="6" x2="6" y2="18"/></svg>"""

        val actions = document.createElement("div") as HTMLElement
        actions.className = "pane-actions"
        // Prevent mousedown from reaching the cell's focus handler —
        // otherwise clicking a button on an unfocused pane triggers a
        // server config push + full DOM rebuild, destroying the click target
        // before the click event fires.
        actions.addEventListener("mousedown", { ev -> ev.stopPropagation() })

        fun performClose() {
            // Closing a linked view only removes that single pane.
            // Closing the original pane cascades to all linked views.
            val linkedCount = if (!isLink) (countSessionPanes?.invoke(sessionId) ?: 0) else 0
            val hasLinks = linkedCount > 1
            val msg = if (hasLinks) {
                "This terminal session has <strong>${linkedCount - 1} linked pane${if (linkedCount > 2) "s" else ""}</strong> " +
                    "that will also be closed."
            } else {
                "Are you sure you want to close <strong>${title}</strong>?"
            }
            openConfirmDialog?.invoke(
                "Close pane",
                msg,
                if (hasLinks) "Close all" else "Close",
            ) {
                val cell = (header.parentElement as? HTMLElement)
                val splitChild = cell?.parentElement as? HTMLElement
                val animTarget: HTMLElement? = when {
                    splitChild?.classList?.contains("split-child") == true -> splitChild
                    cell?.parentElement?.classList?.contains("floating-pane") == true ->
                        cell.parentElement as? HTMLElement
                    else -> cell
                }
                animTarget?.classList?.add("leaving")
                window.setTimeout({
                    if (hasLinks && sessionId != null) {
                        sendCmd(WindowCommand.CloseSession(sessionId = sessionId))
                    } else {
                        sendCmd(WindowCommand.Close(paneId = paneId))
                    }
                }, 200)
            }
        }

        if (sessionId != null) {
            actions.appendChild(makeIconBtn("Reformat", iconReformat, { _ ->
                val entry = terminals[paneId] ?: return@makeIconBtn
                forceReassert(entry)
            }, "reformat"))
        }

        if (popoutMode) {
            actions.appendChild(makeIconBtn("Dock", iconDock, { _ ->
                sendCmd(WindowCommand.DockPoppedOut(paneId = paneId))
                val api = window.asDynamic().electronApi
                if (api?.closePopout != null) {
                    api.closePopout(paneId)
                } else {
                    window.close()
                }
            }))
        } else {
            val floating = isPaneFloating(paneId)

            // Single entry point for split / float / pop-out actions.
            val splitWrap = document.createElement("div") as HTMLElement
            splitWrap.className = "pane-split-wrap"

            val splitBtn = makeIconBtn("Pane layout", iconSplit, { _ ->
                val open = document.querySelectorAll(".pane-split-wrap.open")
                for (i in 0 until open.length) {
                    val other = open.item(i) as HTMLElement
                    if (other !== splitWrap) other.classList.remove("open")
                }
                splitWrap.classList.toggle("open")
            })
            splitWrap.appendChild(splitBtn)

            val flyout = document.createElement("div") as HTMLElement
            flyout.className = "pane-split-flyout"

            if (!floating) {
                val compass = document.createElement("div") as HTMLElement
                compass.className = "pane-split-compass"
                fun addDir(cssCls: String, titleTxt: String, arrowSvg: String, dir: SplitDirection) {
                    val b = makeIconBtn(titleTxt, arrowSvg, { _ ->
                        splitWrap.classList.remove("open")
                        openPaneTypeModal?.invoke(paneId, dir, null)
                    }, cssCls)
                    compass.appendChild(b)
                }
                val arrowUp = """<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 14 12 8 18 14"/></svg>"""
                val arrowDown = """<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 10 12 16 18 10"/></svg>"""
                val arrowLeft = """<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="14 6 8 12 14 18"/></svg>"""
                val arrowRight = """<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="10 6 16 12 10 18"/></svg>"""
                addDir("dir-up", "Split up", arrowUp, SplitDirection.Up)
                addDir("dir-left", "Split left", arrowLeft, SplitDirection.Left)
                addDir("dir-right", "Split right", arrowRight, SplitDirection.Right)
                addDir("dir-down", "Split down", arrowDown, SplitDirection.Down)
                flyout.appendChild(compass)

                val sep = document.createElement("div") as HTMLElement
                sep.className = "pane-split-sep"
                flyout.appendChild(sep)
            }

            fun addMenuItem(label: String, svg: String, onClick: () -> Unit) {
                val item = document.createElement("button") as HTMLElement
                item.className = "pane-split-item"
                item.setAttribute("type", "button")
                item.innerHTML = """<span class="pane-split-item-icon">$svg</span><span class="pane-split-item-label">$label</span>"""
                item.addEventListener("click", { ev ->
                    ev.stopPropagation()
                    splitWrap.classList.remove("open")
                    onClick()
                })
                flyout.appendChild(item)
            }

            addMenuItem(
                if (floating) "Dock pane" else "Float pane",
                if (floating) iconDock else iconFloat,
            ) { sendCmd(WindowCommand.ToggleFloating(paneId = paneId)) }

            val electronApi = window.asDynamic().electronApi
            if (electronApi?.popOutPane != null) {
                addMenuItem("Pop out to window", iconPopOut) {
                    sendCmd(WindowCommand.PopOut(paneId = paneId))
                    electronApi.popOutPane(paneId, title)
                }
            }

            splitWrap.appendChild(flyout)
            actions.appendChild(splitWrap)
        }

        if (fileBrowserPaneStates.containsKey(paneId)) {
            actions.appendChild(makeIconBtn("Copy path", iconCopy, { _ ->
                val rel = fileBrowserPaneStates[paneId]?.selectedRelPath ?: return@makeIconBtn
                window.asDynamic().navigator.clipboard.writeText(rel)
            }))
        }

        // Maximize / restore toggle. Skip for popped-out and floating panes.
        if (!popoutMode && !isPaneFloating(paneId)) {
            val isMaximized = maximizedPaneIds.values.contains(paneId)
            val maxBtn = makeIconBtn(
                if (isMaximized) "Restore pane" else "Maximize pane",
                if (isMaximized) iconRestore else iconMaximize,
                { _ ->
                    val tabPane = findTabPane(header)
                    val tabId = tabPane?.id ?: return@makeIconBtn
                    if (maximizedPaneIds[tabId] == paneId) {
                        restorePane(tabId)
                    } else {
                        maximizePane(paneId)
                    }
                },
                "pane-maximize-btn",
            )
            actions.appendChild(maxBtn)
        }

        actions.appendChild(makeIconBtn("Close pane", iconClose, { _ -> performClose() }, "close"))

        header.appendChild(actions)

        return header
    }

    fun attachDividerDrag(
        divider: HTMLElement,
        container: HTMLElement,
        firstWrap: HTMLElement,
        secondWrap: HTMLElement,
        splitId: String,
        isHorizontal: Boolean,
    ) {
        divider.addEventListener("mousedown", { ev ->
            val mouse = ev as MouseEvent
            if (mouse.button.toInt() != 0) return@addEventListener
            mouse.preventDefault()

            val rect = container.getBoundingClientRect()
            val total = if (isHorizontal) rect.width else rect.height
            if (total <= 0.0) return@addEventListener

            divider.classList.add("dragging")
            // Suppress the .split-child flex-grow transition while dragging
            // so the resize tracks the cursor without lag.
            container.classList.add("resizing")
            val previousBodyCursor = document.body?.style?.cursor ?: ""
            document.body?.style?.cursor = if (isHorizontal) "col-resize" else "row-resize"

            var latestRatio = -1.0

            val moveListener: (Event) -> Unit = { evMove ->
                val m = evMove as MouseEvent
                val offset = if (isHorizontal) m.clientX - rect.left else m.clientY - rect.top
                var r = offset / total
                if (r < 0.05) r = 0.05
                if (r > 0.95) r = 0.95
                latestRatio = r
                val secondR = 1.0 - r
                firstWrap.style.flex = "$r $r 0%"
                secondWrap.style.flex = "$secondR $secondR 0%"
            }
            lateinit var upListener: (Event) -> Unit
            upListener = { _ ->
                document.removeEventListener("mousemove", moveListener)
                document.removeEventListener("mouseup", upListener)
                divider.classList.remove("dragging")
                container.classList.remove("resizing")
                document.body?.style?.cursor = previousBodyCursor
                if (latestRatio > 0.0) {
                    sendCmd(WindowCommand.SetRatio(splitId = splitId, ratio = latestRatio))
                }
            }
            document.addEventListener("mousemove", moveListener)
            document.addEventListener("mouseup", upListener)
        })
    }

    /**
     * Build the visible cell (header + xterm container) for a single leaf
     * pane. Shared between the in-tree leaf path of [buildNode] and the
     * floating-pane renderer so the two render strictly identical chrome.
     */
    /**
     * Build the centered picker shown inside an empty (just-split) pane until
     * the user chooses what kind of content it should host. Two big cards:
     * Terminal (always available) and Markdown overview (enabled in step 6).
     * Pane type modal: full-screen overlay with cards for Terminal, Terminal
     * Link, and Markdown. Captures the anchor pane and split direction so the
     * appropriate atomic SplitTerminal/SplitMarkdown/SplitLink command is sent
     * once the user picks a type (and, for links, a target session).
     */

    // ── Confirmation dialog ──
    fun showConfirmDialog(title: String, message: String, confirmLabel: String = "Close", onConfirm: () -> Unit) {
        if (document.getElementById("confirm-dialog") != null) return
        val overlay = document.createElement("div") as HTMLElement
        overlay.id = "confirm-dialog"
        overlay.className = "pane-modal-overlay"

        val card = document.createElement("div") as HTMLElement
        card.className = "pane-modal confirm-modal"
        card.addEventListener("click", { ev: Event -> ev.stopPropagation() })

        val titleEl = document.createElement("h2") as HTMLElement
        titleEl.className = "pane-modal-title"
        titleEl.textContent = title
        card.appendChild(titleEl)

        val msg = document.createElement("p") as HTMLElement
        msg.className = "confirm-message"
        msg.innerHTML = message
        card.appendChild(msg)

        val btnRow = document.createElement("div") as HTMLElement
        btnRow.className = "confirm-buttons"

        val cancelBtn = document.createElement("button") as HTMLElement
        cancelBtn.className = "confirm-btn confirm-cancel"
        (cancelBtn.asDynamic()).type = "button"
        cancelBtn.textContent = "Cancel"
        cancelBtn.addEventListener("click", { _: Event -> overlay.remove() })
        btnRow.appendChild(cancelBtn)

        val confirmBtn = document.createElement("button") as HTMLElement
        confirmBtn.className = "confirm-btn confirm-ok"
        (confirmBtn.asDynamic()).type = "button"
        confirmBtn.textContent = confirmLabel
        confirmBtn.addEventListener("click", { _: Event ->
            overlay.remove()
            onConfirm()
        })
        btnRow.appendChild(confirmBtn)

        card.appendChild(btnRow)
        overlay.appendChild(card)
        overlay.addEventListener("click", { ev: Event ->
            if (ev.target === overlay) overlay.remove()
        })
        var escHandler: ((Event) -> Unit)? = null
        escHandler = { ev: Event ->
            if ((ev as KeyboardEvent).key == "Escape") {
                overlay.remove()
                escHandler?.let { document.removeEventListener("keydown", it) }
            }
        }
        document.addEventListener("keydown", escHandler)
        document.body?.appendChild(overlay)
        confirmBtn.focus()
    }

    /**
     * Count how many panes share [sessionId] across the entire config.
     * Returns 0 if no config or sessionId is empty.
     */
    fun countPanesForSession(sessionId: String?): Int {
        if (sessionId.isNullOrEmpty()) return 0
        val cfg = currentConfig ?: return 0
        val tabsArr = (cfg.tabs as? Array<dynamic>) ?: return 0
        var count = 0
        fun walk(node: dynamic) {
            if (node == null) return
            if (node.kind == "leaf") {
                if ((node.sessionId as? String) == sessionId) count++
            } else {
                walk(node.first)
                walk(node.second)
            }
        }
        for (tab in tabsArr) {
            walk(tab.root)
            val floats = tab.floating as? Array<dynamic> ?: emptyArray()
            for (fp in floats) {
                if ((fp.leaf.sessionId as? String) == sessionId) count++
            }
            val popped = tab.poppedOut as? Array<dynamic> ?: emptyArray()
            for (po in popped) {
                if ((po.leaf.sessionId as? String) == sessionId) count++
            }
        }
        return count
    }
    openConfirmDialog = ::showConfirmDialog
    countSessionPanes = ::countPanesForSession

    // Track preview xterm instances so we can dispose them when modal closes.
    // Each entry is a js object with { term, fit, socket } fields.
    val previewEntries = mutableListOf<dynamic>()

    fun disposeAllPreviews() {
        for (entry in previewEntries) {
            try { (entry.socket as WebSocket).close() } catch (_: Throwable) {}
            try { entry.term.asDynamic().dispose() } catch (_: Throwable) {}
        }
        previewEntries.clear()
    }

    // Stash the escape-key handler so closePaneTypeModal can remove it.
    var modalEscHandler: ((Event) -> Unit)? = null

    fun closePaneTypeModal() {
        disposeAllPreviews()
        modalEscHandler?.let { document.removeEventListener("keydown", it) }
        modalEscHandler = null
        document.getElementById("pane-type-modal")?.remove()
    }

    val modalTerminalSvg =
        """<svg viewBox="0 0 32 32" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" width="40" height="40"><rect x="3" y="5" width="26" height="22" rx="2"/><polyline points="9,13 13,16 9,19"/><line x1="15" y1="20" x2="22" y2="20"/></svg>"""
    val modalFileBrowserSvg =
        """<svg viewBox="0 0 32 32" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" width="40" height="40"><path d="M4 9 a1 1 0 0 1 1-1 h7 l2 3 h13 a1 1 0 0 1 1 1 v13 a1 1 0 0 1 -1 1 H5 a1 1 0 0 1 -1 -1 Z"/><line x1="9" y1="17" x2="23" y2="17"/><line x1="9" y1="21" x2="19" y2="21"/></svg>"""
    val modalGitSvg =
        """<svg viewBox="0 0 32 32" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" width="40" height="40"><circle cx="10" cy="8" r="2.5"/><circle cx="10" cy="24" r="2.5"/><circle cx="22" cy="16" r="2.5"/><line x1="10" y1="10.5" x2="10" y2="21.5"/><path d="M10 10.5 C10 16 16 16 19.5 16"/></svg>"""
    val modalLinkSvg =
        """<svg viewBox="0 0 32 32" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" width="40" height="40"><path d="M13 19a5 5 0 0 0 7.07 0l4-4a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M19 13a5 5 0 0 0-7.07 0l-4 4a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>"""

    fun showPaneTypeModal(
        anchorPaneId: String? = null,
        direction: SplitDirection? = null,
        emptyTabId: String? = null,
    ) {
        // Don't stack modals.
        if (document.getElementById("pane-type-modal") != null) return

        val overlay = document.createElement("div") as HTMLElement
        overlay.id = "pane-type-modal"
        overlay.className = "pane-modal-overlay"

        val card = document.createElement("div") as HTMLElement
        card.className = "pane-modal"

        // Close button
        val closeBtn = document.createElement("button") as HTMLElement
        closeBtn.className = "pane-modal-close"
        closeBtn.innerHTML = "&times;"
        (closeBtn.asDynamic()).type = "button"
        closeBtn.addEventListener("click", { ev: Event ->
            ev.stopPropagation()
            closePaneTypeModal()
        })
        card.appendChild(closeBtn)

        val titleEl = document.createElement("h2") as HTMLElement
        titleEl.className = "pane-modal-title"
        titleEl.textContent = "New pane"
        card.appendChild(titleEl)

        val body = document.createElement("div") as HTMLElement
        body.className = "pane-modal-body"
        card.appendChild(body)

        fun makeTypeCard(label: String, svg: String, onClick: () -> Unit): HTMLElement {
            val btn = document.createElement("button") as HTMLElement
            btn.className = "pane-type-card"
            (btn.asDynamic()).type = "button"
            val iconWrap = document.createElement("span") as HTMLElement
            iconWrap.className = "pane-type-icon"
            iconWrap.innerHTML = svg
            val labelSpan = document.createElement("span") as HTMLElement
            labelSpan.className = "pane-type-label"
            labelSpan.textContent = label
            btn.appendChild(iconWrap)
            btn.appendChild(labelSpan)
            btn.addEventListener("click", { ev: Event ->
                ev.stopPropagation()
                onClick()
            })
            return btn
        }

        // Prevent clicks inside the card from reaching the backdrop handler.
        card.addEventListener("click", { ev: Event -> ev.stopPropagation() })

        // Collect terminal leaves from the current config using dynamic JS
        // (the typed LeafInfo/collectLeaves helpers are defined later in the
        // file and can't be forward-referenced).
        data class TermLeaf(val paneId: String, val leafTitle: String, val sessionId: String)
        data class TabGroup(val tabTitle: String, val leaves: List<TermLeaf>)

        fun collectTerminalLeaves(): List<TabGroup> {
            val cfg = currentConfig ?: return emptyList()
            val tabsArr = (cfg.tabs as? Array<dynamic>) ?: return emptyList()
            val groups = mutableListOf<TabGroup>()
            for (tab in tabsArr) {
                val tabTitle = tab.title as String
                val leaves = mutableListOf<TermLeaf>()
                fun walkNode(node: dynamic) {
                    if (node == null) return
                    if (node.kind == "leaf") {
                        val kind = (node.content?.kind as? String) ?: "terminal"
                        val sid = node.sessionId as String
                        if (kind == "terminal" && sid.isNotEmpty()) {
                            leaves.add(TermLeaf(node.id as String, node.title as String, sid))
                        }
                    } else {
                        walkNode(node.first)
                        walkNode(node.second)
                    }
                }
                walkNode(tab.root)
                val floats = tab.floating as? Array<dynamic> ?: emptyArray()
                for (fp in floats) {
                    val kind = (fp.leaf.content?.kind as? String) ?: "terminal"
                    val sid = fp.leaf.sessionId as String
                    if (kind == "terminal" && sid.isNotEmpty()) {
                        leaves.add(TermLeaf(fp.leaf.id as String, fp.leaf.title as String, sid))
                    }
                }
                if (leaves.isNotEmpty()) groups.add(TabGroup(tabTitle, leaves))
            }
            return groups
        }

        // showLinkPicker must be declared before showTypeCards (which calls it).
        fun showLinkPicker() {
            disposeAllPreviews()
            body.innerHTML = ""

            val backBtn = document.createElement("button") as HTMLElement
            backBtn.className = "pane-modal-back"
            (backBtn.asDynamic()).type = "button"
            backBtn.textContent = "\u2190 Back"
            // Can't call showTypeCards() here directly (not yet declared), so
            // we close and re-open the modal — same visual result.
            backBtn.addEventListener("click", { _: Event ->
                closePaneTypeModal()
                showPaneTypeModal(anchorPaneId, direction, emptyTabId)
            })
            body.appendChild(backBtn)

            titleEl.textContent = "Link to terminal"

            val groups = collectTerminalLeaves()
            if (groups.isEmpty()) {
                val emptyMsg = document.createElement("p") as HTMLElement
                emptyMsg.className = "pane-modal-empty"
                emptyMsg.textContent = "No terminal sessions to link to"
                body.appendChild(emptyMsg)
                return
            }

            val scrollContainer = document.createElement("div") as HTMLElement
            scrollContainer.className = "link-picker-scroll"

            for (group in groups) {
                val section = document.createElement("div") as HTMLElement
                section.className = "link-picker-section"

                val header = document.createElement("div") as HTMLElement
                header.className = "link-picker-tab-header"
                header.innerHTML = """<span class="link-picker-tab-arrow">&#9660;</span> ${group.tabTitle}"""
                val contentDiv = document.createElement("div") as HTMLElement
                contentDiv.className = "link-picker-tab-content"

                header.addEventListener("click", { _: Event ->
                    val collapsed = contentDiv.style.display == "none"
                    contentDiv.style.display = if (collapsed) "" else "none"
                    header.querySelector(".link-picker-tab-arrow")?.let { arrow ->
                        (arrow as HTMLElement).innerHTML = if (collapsed) "&#9660;" else "&#9654;"
                    }
                })

                val grid = document.createElement("div") as HTMLElement
                grid.className = "link-picker-grid"

                for (leaf in group.leaves) {
                    val leafCard = document.createElement("div") as HTMLElement
                    leafCard.className = "link-picker-card"

                    val cardTitle = document.createElement("div") as HTMLElement
                    cardTitle.className = "link-picker-card-title"
                    cardTitle.textContent = leaf.leafTitle
                    leafCard.appendChild(cardTitle)

                    // Miniature terminal preview
                    val previewContainer = document.createElement("div") as HTMLElement
                    previewContainer.className = "link-preview-terminal"
                    val previewInner = document.createElement("div") as HTMLElement
                    previewInner.className = "link-preview-inner"
                    previewContainer.appendChild(previewInner)

                    val previewTerm = Terminal(
                        json(
                            "cursorBlink" to false,
                            "disableStdin" to true,
                            "fontFamily" to "Menlo, Monaco, 'Courier New', monospace",
                            "fontSize" to 7,
                            "scrollback" to 100,
                            "theme" to buildXtermTheme()
                        )
                    )
                    val previewFit = FitAddon()
                    previewTerm.loadAddon(previewFit)
                    previewTerm.open(previewInner)
                    previewTerm.options.theme = buildXtermTheme()

                    leafCard.appendChild(previewContainer)

                    // Connect preview to PTY for ring buffer replay
                    val previewUrl = "$proto://${window.location.host}/pty/${leaf.sessionId}?$authQueryParam"
                    val previewSocket = WebSocket(previewUrl)
                    previewSocket.asDynamic().binaryType = "arraybuffer"
                    previewSocket.onmessage = { event ->
                        val data = event.asDynamic().data
                        if (data !is String) {
                            val buf = data as ArrayBuffer
                            previewTerm.write(Uint8Array(buf))
                        }
                    }
                    previewSocket.onopen = { _: Event ->
                        try { safeFit(previewTerm, previewFit) } catch (_: Throwable) {}
                    }

                    previewEntries.add(json("term" to previewTerm, "fit" to previewFit, "socket" to previewSocket))

                    // Fit once visible
                    window.setTimeout({
                        try { safeFit(previewTerm, previewFit) } catch (_: Throwable) {}
                    }, 100)

                    // Click: send SplitLink or AddLinkToTab and close
                    leafCard.addEventListener("click", { _: Event ->
                        closePaneTypeModal()
                        if (emptyTabId != null) {
                            sendCmd(WindowCommand.AddLinkToTab(
                                tabId = emptyTabId,
                                targetSessionId = leaf.sessionId
                            ))
                        } else {
                            sendCmd(WindowCommand.SplitLink(
                                paneId = anchorPaneId!!,
                                direction = direction!!,
                                targetSessionId = leaf.sessionId
                            ))
                        }
                    })

                    grid.appendChild(leafCard)
                }

                contentDiv.appendChild(grid)
                section.appendChild(header)
                section.appendChild(contentDiv)
                scrollContainer.appendChild(section)
            }

            body.appendChild(scrollContainer)
        }

        fun showTypeCards() {
            body.innerHTML = ""
            val grid = document.createElement("div") as HTMLElement
            grid.className = "pane-type-grid"

            grid.appendChild(makeTypeCard("Terminal", modalTerminalSvg) {
                closePaneTypeModal()
                if (emptyTabId != null) {
                    sendCmd(WindowCommand.AddPaneToTab(tabId = emptyTabId))
                } else {
                    sendCmd(WindowCommand.SplitTerminal(paneId = anchorPaneId!!, direction = direction!!))
                }
            })
            grid.appendChild(makeTypeCard("Terminal link", modalLinkSvg) {
                showLinkPicker()
            })
            grid.appendChild(makeTypeCard("File Browser", modalFileBrowserSvg) {
                closePaneTypeModal()
                if (emptyTabId != null) {
                    sendCmd(WindowCommand.AddFileBrowserToTab(tabId = emptyTabId))
                } else {
                    sendCmd(WindowCommand.SplitFileBrowser(paneId = anchorPaneId!!, direction = direction!!))
                }
            })
            grid.appendChild(makeTypeCard("Git", modalGitSvg) {
                closePaneTypeModal()
                if (emptyTabId != null) {
                    sendCmd(WindowCommand.AddGitToTab(tabId = emptyTabId))
                } else {
                    sendCmd(WindowCommand.SplitGit(paneId = anchorPaneId!!, direction = direction!!))
                }
            })
            body.appendChild(grid)
        }

        showTypeCards()
        overlay.appendChild(card)

        // Close on backdrop click
        overlay.addEventListener("click", { ev: Event ->
            if (ev.target === overlay) closePaneTypeModal()
        })

        // Close on Escape
        val escHandler: (Event) -> Unit = { ev ->
            if ((ev as KeyboardEvent).key == "Escape") closePaneTypeModal()
        }
        modalEscHandler = escHandler
        document.addEventListener("keydown", escHandler)

        document.body?.appendChild(overlay)
    }
    openPaneTypeModal = ::showPaneTypeModal

    /**
     * Format a millisecond epoch timestamp as a short relative string ("3m",
     * "2h", "5d") for the file list. Falls back to a yyyy-mm-dd
     * date for anything older than ~30 days. Pure helper, no DOM.
     */
    fun formatMtime(epochMs: Long): String {
        val now = js("Date.now()") as Double
        val diffSec = ((now - epochMs.toDouble()) / 1000.0).toLong().coerceAtLeast(0)
        return when {
            diffSec < 60 -> "now"
            diffSec < 3600 -> "${diffSec / 60}m"
            diffSec < 86_400 -> "${diffSec / 3600}h"
            diffSec < 86_400L * 30 -> "${diffSec / 86_400}d"
            else -> {
                val d = js("new Date(epochMs)")
                val y = d.getFullYear() as Int
                val m = ((d.getMonth() as Int) + 1).toString().padStart(2, '0')
                val day = (d.getDate() as Int).toString().padStart(2, '0')
                "$y-$m-$day"
            }
        }
    }

    /**
     * Wire up fragment-link navigation inside the rendered markdown preview.
     * Clicks on `<a href="#some-id">` scroll to the matching heading within
     * the container instead of navigating the whole page.
     */
    fun wireMarkdownAnchorLinks(container: HTMLElement) {
        container.addEventListener("click", { ev ->
            // Walk up from the click target to find the enclosing <a>, if any.
            var node = ev.target as? HTMLElement
            while (node != null && node != container && node !is HTMLAnchorElement) {
                node = node.parentElement as? HTMLElement
            }
            if (node is HTMLAnchorElement) {
                val href = node.getAttribute("href") ?: return@addEventListener
                if (href.startsWith("#")) {
                    ev.preventDefault()
                    val id = href.removePrefix("#")
                    val heading = container.querySelector("[id='${id.replace("'", "\\'")}']")
                    heading?.scrollIntoView(js("({behavior:'smooth',block:'start'})"))
                }
            }
        })
    }

    /**
     * Paint the file-browser tree into [view.listBody]. Works directly off the
     * [state.dirListings] map: each directory that the user has expanded has
     * its entries under the corresponding key. The root listing lives under
     * the empty-string key. Directories that are in [state.expandedDirs] but
     * missing from the listings map haven't finished loading yet — those
     * render as disabled loading rows until the dir arrives.
     */
    fun renderFileBrowserTree(paneId: String, view: FileBrowserPaneView, state: FileBrowserPaneState) {
        // Preserve the user's scroll position across the rebuild — opening a
        // file or expanding a sibling shouldn't fling the tree to the top.
        val savedScroll = view.listBody.scrollTop
        view.listBody.innerHTML = ""
        val rootEntries = state.dirListings[""]
        if (rootEntries == null) {
            val loading = document.createElement("div") as HTMLElement
            loading.className = "md-list-empty"
            loading.textContent = "Loading…"
            view.listBody.appendChild(loading)
            return
        }
        if (rootEntries.isEmpty()) {
            val empty = document.createElement("div") as HTMLElement
            empty.className = "md-list-empty"
            empty.textContent = "No files"
            view.listBody.appendChild(empty)
            return
        }

        fun toggleDir(relPath: String) {
            val nowExpanded = relPath !in state.expandedDirs
            if (nowExpanded) {
                state.expandedDirs.add(relPath)
                if (state.dirListings[relPath] == null) {
                    sendCmd(WindowCommand.FileBrowserListDir(paneId = paneId, dirRelPath = relPath))
                }
            } else {
                state.expandedDirs.remove(relPath)
            }
            sendCmd(WindowCommand.FileBrowserSetExpanded(
                paneId = paneId, dirRelPath = relPath, expanded = nowExpanded,
            ))
            renderFileBrowserTree(paneId, view, state)
        }

        fun renderDir(container: HTMLElement, entries: Array<dynamic>, depth: Int) {
            for (entry in entries) {
                val relPath = entry.relPath as String
                val name = entry.name as String
                val isDir = entry.isDir as Boolean
                val row = document.createElement("div") as HTMLElement
                row.className = "md-list-item"
                if (!isDir && relPath == state.selectedRelPath) row.classList.add("active")
                row.style.asDynamic().paddingLeft = "${4 + depth * 14}px"

                // Far-left chevron column: dirs get an interactive ▸/▾, files
                // get a hidden placeholder so labels stay aligned.
                val chevron = document.createElement("span") as HTMLElement
                chevron.className = if (isDir) "md-list-chevron" else "md-list-chevron empty"
                if (isDir) {
                    val expanded = relPath in state.expandedDirs
                    chevron.textContent = if (expanded) "\u25BE" else "\u25B8"
                    chevron.addEventListener("mousedown", { ev -> ev.stopPropagation() })
                    chevron.addEventListener("click", { ev ->
                        ev.stopPropagation()
                        toggleDir(relPath)
                    })
                }
                row.appendChild(chevron)

                val icon = document.createElement("span") as HTMLElement
                icon.className = "md-list-item-icon"
                if (isDir) {
                    icon.innerHTML =
                        """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><path d="M1.75 4.25a0.5 0.5 0 0 1 0.5 -0.5h3.5L7.25 5.25H13.75a0.5 0.5 0 0 1 0.5 0.5v7.5a0.5 0.5 0 0 1 -0.5 0.5H2.25a0.5 0.5 0 0 1 -0.5 -0.5z"/></svg>"""
                } else {
                    icon.innerHTML =
                        """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><path d="M3 1.75h6.5L13 5.25V14.25a0.5 0.5 0 0 1 -0.5 0.5H3a0.5 0.5 0 0 1 -0.5 -0.5V2.25a0.5 0.5 0 0 1 0.5 -0.5Z"/><path d="M9.25 1.75V5.25H13"/></svg>"""
                }
                val label = document.createElement("span") as HTMLElement
                label.className = "md-list-item-name"
                label.textContent = name
                label.title = relPath
                row.appendChild(icon)
                row.appendChild(label)
                row.addEventListener("mousedown", { ev -> ev.stopPropagation() })
                row.addEventListener("click", { _ ->
                    if (isDir) toggleDir(relPath)
                    else sendCmd(WindowCommand.FileBrowserOpenFile(paneId = paneId, relPath = relPath))
                })
                container.appendChild(row)
                if (isDir && relPath in state.expandedDirs) {
                    val children = state.dirListings[relPath]
                    if (children == null) {
                        val loading = document.createElement("div") as HTMLElement
                        loading.className = "md-list-empty"
                        loading.style.asDynamic().paddingLeft = "${4 + (depth + 1) * 14}px"
                        loading.textContent = "Loading…"
                        container.appendChild(loading)
                    } else {
                        renderDir(container, children, depth + 1)
                    }
                }
            }
        }
        renderDir(view.listBody, rootEntries, 0)
        view.listBody.scrollTop = savedScroll
    }

    /**
     * Render the right-side content panel for the currently selected file.
     * [kind] is one of "Markdown", "Text", "Binary"; [html] holds the
     * pre-rendered markup (empty string for binary).
     */
    fun renderFileBrowserContent(view: FileBrowserPaneView, kind: String?, html: String?) {
        view.rendered.innerHTML = ""
        // Drop the prose padding for non-markdown content so the source/binary
        // view fills the pane edge-to-edge instead of leaving a fat margin.
        if (kind == "Markdown") view.rendered.classList.remove("is-source")
        else view.rendered.classList.add("is-source")
        when (kind) {
            "Markdown" -> {
                view.rendered.innerHTML = html ?: ""
            }
            "Text" -> {
                val pre = document.createElement("pre") as HTMLElement
                pre.className = "file-source"
                val code = document.createElement("code") as HTMLElement
                code.innerHTML = html ?: ""
                pre.appendChild(code)
                view.rendered.appendChild(pre)
            }
            "Binary" -> {
                val placeholder = document.createElement("div") as HTMLElement
                placeholder.className = "md-rendered-empty"
                placeholder.innerHTML = """
                    <div class="md-rendered-empty-icon">&#128452;</div>
                    <div class="md-rendered-empty-title">Binary file</div>
                    <div class="md-rendered-empty-subtitle">Preview unavailable</div>
                """.trimIndent()
                view.rendered.appendChild(placeholder)
            }
            else -> {
                val emptyState = document.createElement("div") as HTMLElement
                emptyState.className = "md-rendered-empty"
                emptyState.innerHTML = """
                    <div class="md-rendered-empty-icon">&#128196;</div>
                    <div class="md-rendered-empty-title">No document selected</div>
                    <div class="md-rendered-empty-subtitle">Choose a file from the tree</div>
                """.trimIndent()
                view.rendered.appendChild(emptyState)
            }
        }
    }

    /**
     * Build the file-browser cell: a left-side tree (lazy-expanded) and a
     * right-side preview, with a draggable splitter between them. All data
     * comes from the server — the cell requests by paneId and renders what
     * it gets back.
     */
    fun buildFileBrowserView(paneId: String, leaf: dynamic, headerEl: HTMLElement? = null): HTMLElement {
        val content = leaf.content
        val initialWidth = (content?.leftColumnWidthPx as? Number)?.toInt() ?: 240
        val initialSelected = content?.selectedRelPath as? String
        val persistedExpanded = content?.expandedDirs as? Array<String>
        val persistedFilter = content?.fileFilter as? String
        val persistedSort = content?.sortBy as? String

        val state = fileBrowserPaneStates.getOrPut(paneId) { FileBrowserPaneState() }
        if (initialSelected != null) state.selectedRelPath = initialSelected
        if (persistedExpanded != null) {
            for (p in persistedExpanded) state.expandedDirs.add(p)
        }
        if (persistedFilter != null) state.fileFilter = persistedFilter
        if (persistedSort != null) state.sortBy = persistedSort

        val outer = document.createElement("div") as HTMLElement
        outer.className = "md-view"

        val left = document.createElement("div") as HTMLElement
        left.className = "md-list"
        left.style.width = "${initialWidth.coerceIn(0, 640)}px"
        left.style.asDynamic().flexShrink = "0"

        val header = document.createElement("div") as HTMLElement
        header.className = "md-list-header"
        val titleEl = document.createElement("span") as HTMLElement
        titleEl.className = "md-list-title"
        titleEl.textContent = "Files"
        header.appendChild(titleEl)

        left.appendChild(header)

        // -- Header flyout controls (injected into the pane header bar) --------

        val iconFilter = """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polygon points="2,3 14,3 10,8.5 10,13 6,11 6,8.5"/></svg>"""
        val iconSort = """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><line x1="3" y1="4" x2="11" y2="4"/><line x1="3" y1="8" x2="9" y2="8"/><line x1="3" y1="12" x2="7" y2="12"/><polyline points="11,10 13,12 15,10"/><line x1="13" y1="5" x2="13" y2="12"/></svg>"""
        val iconExpand = """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="3,6 8,11 13,6"/></svg>"""
        val iconCollapse = """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="3,10 8,5 13,10"/></svg>"""

        // -- Filter flyout --
        val filterWrap = document.createElement("div") as HTMLElement
        filterWrap.className = "pane-flyout-wrap"
        filterWrap.addEventListener("mousedown", { ev -> ev.stopPropagation() })
        val filterTrigger = document.createElement("button") as HTMLElement
        filterTrigger.className = "pane-action-btn"
        filterTrigger.setAttribute("type", "button")
        filterTrigger.setAttribute("title", "Filter")
        filterTrigger.innerHTML = iconFilter
        filterTrigger.addEventListener("click", { ev ->
            ev.stopPropagation()
            val open = document.querySelectorAll(".pane-flyout-wrap.open")
            for (i in 0 until open.length) {
                val other = open.item(i) as HTMLElement
                if (other !== filterWrap) other.classList.remove("open")
            }
            filterWrap.classList.toggle("open")
        })
        filterWrap.appendChild(filterTrigger)
        val filterFlyout = document.createElement("div") as HTMLElement
        filterFlyout.className = "pane-flyout"
        filterFlyout.addEventListener("click", { ev -> ev.stopPropagation() })

        val filterInput = document.createElement("input") as HTMLInputElement
        filterInput.className = "git-search-input"
        filterInput.type = "text"
        filterInput.placeholder = "Filter (e.g. *.md)"
        filterInput.value = state.fileFilter
        filterInput.title = "Filter files by name (glob or substring)"
        filterInput.style.width = "100%"
        filterInput.style.marginBottom = "4px"
        filterFlyout.appendChild(filterInput)

        var filterDebounce: Int? = null
        fun dispatchFilter(value: String) {
            state.fileFilter = value
            filterDebounce?.let { window.clearTimeout(it) }
            filterDebounce = window.setTimeout({
                sendCmd(WindowCommand.SetFileBrowserFilter(paneId = paneId, filter = value))
            }, 250)
        }
        filterInput.addEventListener("input", { _ ->
            dispatchFilter(filterInput.value)
        })

        val presets = listOf(
            "" to "All files",
            "*.md" to "Markdown",
            "*.{kt,kts}" to "Kotlin",
            "*.{js,ts,jsx,tsx}" to "JS / TS",
            "*.{py}" to "Python",
            "*.{java,scala,groovy}" to "JVM",
            "*.{c,h,cpp,hpp,cc}" to "C / C++",
            "*.{rs}" to "Rust",
            "*.{go}" to "Go",
            "*.{json,yaml,yml,toml,xml}" to "Config",
            "*.{png,jpg,jpeg,gif,svg,webp}" to "Images",
        )
        for ((value, label) in presets) {
            val item = document.createElement("div") as HTMLElement
            item.className = "pane-split-item"
            item.textContent = label
            item.addEventListener("click", { ev ->
                ev.stopPropagation()
                filterInput.value = value
                dispatchFilter(value)
                filterWrap.classList.remove("open")
            })
            filterFlyout.appendChild(item)
        }
        filterWrap.appendChild(filterFlyout)

        // -- Sort flyout --
        val sortWrap = document.createElement("div") as HTMLElement
        sortWrap.className = "pane-flyout-wrap"
        sortWrap.addEventListener("mousedown", { ev -> ev.stopPropagation() })
        val sortTrigger = document.createElement("button") as HTMLElement
        sortTrigger.className = "pane-action-btn"
        sortTrigger.setAttribute("type", "button")
        sortTrigger.setAttribute("title", "Sort order")
        sortTrigger.innerHTML = iconSort
        sortTrigger.addEventListener("click", { ev ->
            ev.stopPropagation()
            val open = document.querySelectorAll(".pane-flyout-wrap.open")
            for (i in 0 until open.length) {
                val other = open.item(i) as HTMLElement
                if (other !== sortWrap) other.classList.remove("open")
            }
            sortWrap.classList.toggle("open")
        })
        sortWrap.appendChild(sortTrigger)
        val sortFlyout = document.createElement("div") as HTMLElement
        sortFlyout.className = "pane-flyout"
        sortFlyout.addEventListener("click", { ev -> ev.stopPropagation() })

        val sortOptions = listOf(
            "name" to "By name",
            "mtime" to "By last change",
        )
        val sortItems = HashMap<String, HTMLElement>()
        for ((value, label) in sortOptions) {
            val item = document.createElement("div") as HTMLElement
            item.className = "pane-split-item"
            if (value == state.sortBy) item.classList.add("active")
            item.innerHTML = """<span class="pane-split-item-label">$label</span><span class="check">${if (value == state.sortBy) "\u2713" else ""}</span>"""
            item.addEventListener("click", { ev ->
                ev.stopPropagation()
                state.sortBy = value
                for ((k, el) in sortItems) {
                    val check = el.querySelector(".check") as? HTMLElement
                    if (k == value) { el.classList.add("active"); check?.textContent = "\u2713" }
                    else { el.classList.remove("active"); check?.textContent = "" }
                }
                val sortEnum = if (value == "mtime") FileBrowserSort.MTIME else FileBrowserSort.NAME
                sendCmd(WindowCommand.SetFileBrowserSort(paneId = paneId, sort = sortEnum))
                sortWrap.classList.remove("open")
            })
            sortFlyout.appendChild(item)
            sortItems[value] = item
        }
        sortWrap.appendChild(sortFlyout)

        // -- Expand / Collapse all buttons (direct icon buttons) --
        val expandBtn = document.createElement("button") as HTMLElement
        expandBtn.className = "pane-action-btn"
        expandBtn.setAttribute("type", "button")
        expandBtn.setAttribute("title", "Expand all")
        expandBtn.innerHTML = iconExpand
        expandBtn.addEventListener("mousedown", { ev -> ev.stopPropagation() })
        expandBtn.addEventListener("click", { ev ->
            ev.stopPropagation()
            sendCmd(WindowCommand.FileBrowserExpandAll(paneId = paneId))
        })

        val collapseBtn = document.createElement("button") as HTMLElement
        collapseBtn.className = "pane-action-btn"
        collapseBtn.setAttribute("type", "button")
        collapseBtn.setAttribute("title", "Collapse all")
        collapseBtn.innerHTML = iconCollapse
        collapseBtn.addEventListener("mousedown", { ev -> ev.stopPropagation() })
        collapseBtn.addEventListener("click", { ev ->
            ev.stopPropagation()
            state.expandedDirs.clear()
            sendCmd(WindowCommand.FileBrowserCollapseAll(paneId = paneId))
            val v = fileBrowserPaneViews[paneId]
            if (v != null) renderFileBrowserTree(paneId, v, state)
        })

        // Close any open flyout on outside click.
        document.addEventListener("click", { _ ->
            filterWrap.classList.remove("open")
            sortWrap.classList.remove("open")
        })

        // -- Refresh button (injected into pane header) --
        val refreshBtn = document.createElement("button") as HTMLElement
        refreshBtn.className = "pane-action-btn"
        refreshBtn.setAttribute("type", "button")
        refreshBtn.setAttribute("title", "Refresh")
        refreshBtn.innerHTML =
            """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M2 8a6 6 0 0 1 10.5-3.9"/><polyline points="13,2 13,5 10,5"/><path d="M14 8a6 6 0 0 1 -10.5 3.9"/><polyline points="3,14 3,11 6,11"/></svg>"""
        refreshBtn.addEventListener("mousedown", { ev -> ev.stopPropagation() })
        refreshBtn.addEventListener("click", { ev ->
            ev.stopPropagation()
            sendCmd(WindowCommand.FileBrowserListDir(paneId = paneId, dirRelPath = ""))
            for (dir in state.expandedDirs) {
                sendCmd(WindowCommand.FileBrowserListDir(paneId = paneId, dirRelPath = dir))
            }
        })

        // Inject controls into the pane header bar.
        if (headerEl != null) {
            val actions = headerEl.querySelector(".pane-actions") as? HTMLElement
            if (actions != null) {
                actions.parentNode?.insertBefore(filterWrap, actions)
                actions.parentNode?.insertBefore(sortWrap, actions)
                actions.parentNode?.insertBefore(expandBtn, actions)
                actions.parentNode?.insertBefore(collapseBtn, actions)
                actions.parentNode?.insertBefore(refreshBtn, actions)
                val spacer = document.createElement("div") as HTMLElement
                spacer.className = "pane-header-spacer"
                actions.parentNode?.insertBefore(spacer, actions)
            }
        }

        val listBody = document.createElement("div") as HTMLElement
        listBody.className = "md-list-body"
        left.appendChild(listBody)

        val divider = document.createElement("div") as HTMLElement
        divider.className = "md-divider"

        val right = document.createElement("div") as HTMLElement
        right.className = "md-rendered"

        outer.appendChild(left)
        outer.appendChild(divider)
        outer.appendChild(right)

        wireMarkdownAnchorLinks(right)

        val view = FileBrowserPaneView(listBody, right)
        fileBrowserPaneViews[paneId] = view

        renderFileBrowserTree(paneId, view, state)
        renderFileBrowserContent(view, state.kind, state.html)

        // Always refresh the root on mount so the tree reflects current FS.
        sendCmd(WindowCommand.FileBrowserListDir(paneId = paneId, dirRelPath = ""))
        // Replay persisted expansions: request listings we don't have cached.
        for (dir in state.expandedDirs) {
            if (state.dirListings[dir] == null) {
                sendCmd(WindowCommand.FileBrowserListDir(paneId = paneId, dirRelPath = dir))
            }
        }
        if (state.selectedRelPath != null && state.html == null) {
            sendCmd(WindowCommand.FileBrowserOpenFile(paneId = paneId, relPath = state.selectedRelPath!!))
        }

        // Drag the divider to resize the left column.
        divider.addEventListener("mousedown", { ev ->
            val mev = ev as MouseEvent
            mev.preventDefault()
            divider.classList.add("dragging")
            val startX = mev.clientX
            val startWidth = left.offsetWidth
            val previousBodyCursor = document.body?.style?.cursor ?: ""
            document.body?.style?.cursor = "col-resize"
            var latestPx = startWidth
            val moveListener: (Event) -> Unit = { evMove ->
                val m = evMove as MouseEvent
                val w = (startWidth + (m.clientX - startX)).coerceIn(0, 640)
                latestPx = w
                left.style.width = "${w}px"
            }
            lateinit var upListener: (Event) -> Unit
            upListener = { _ ->
                document.removeEventListener("mousemove", moveListener)
                document.removeEventListener("mouseup", upListener)
                divider.classList.remove("dragging")
                document.body?.style?.cursor = previousBodyCursor
                sendCmd(WindowCommand.SetFileBrowserLeftWidth(paneId = paneId, px = latestPx))
            }
            document.addEventListener("mousemove", moveListener)
            document.addEventListener("mouseup", upListener)
        })

        return outer
    }

    // ---- Git pane rendering -------------------------------------------------

    /**
     * Update only the active-file highlight in the list without rebuilding.
     */
    fun updateGitListActiveFile(view: GitPaneView, filePath: String?) {
        val items = view.listBody.querySelectorAll(".git-list-item")
        for (i in 0 until items.length) {
            val el = items.item(i) as HTMLElement
            val path = el.querySelector(".git-list-item-name")?.getAttribute("title")
            if (path == filePath) el.classList.add("active")
            else el.classList.remove("active")
        }
    }

    fun renderGitList(paneId: String, view: GitPaneView, state: GitPaneState) {
        val savedScroll = view.listBody.scrollTop
        // Suppress maxHeight transitions during rebuild so the full height
        // is available immediately and scrollTop can be restored.
        view.listBody.style.setProperty("--git-section-transition", "none")
        view.listBody.innerHTML = ""
        val entries = state.entries
        val arr = entries as? Array<dynamic>
        if (arr == null || arr.isEmpty()) {
            if (arr != null) {
                val empty = document.createElement("div") as HTMLElement
                empty.className = "git-list-empty"
                empty.textContent = "No uncommitted changes"
                view.listBody.appendChild(empty)
            }
            view.listBody.scrollTop = savedScroll
            val el2 = view.listBody
            kotlinx.browser.window.requestAnimationFrame {
                el2.style.removeProperty("--git-section-transition")
            }
            return
        }

        val groups = LinkedHashMap<String, MutableList<dynamic>>()
        for (entry in arr) {
            val dir = entry.directory as String
            groups.getOrPut(dir) { mutableListOf() }.add(entry)
        }

        for ((parent, items) in groups) {
            val sectionKey = "$paneId|$parent"
            val isCollapsed = sectionKey in collapsedGitSections

            val section = document.createElement("div") as HTMLElement
            section.className = "git-list-section"

            val headline = document.createElement("div") as HTMLElement
            headline.className = "git-list-section-header"
            if (isCollapsed) headline.classList.add("collapsed-tree")

            val chevron = document.createElement("span") as HTMLElement
            chevron.className = "git-list-section-chevron"
            chevron.textContent = "\u25BE"
            headline.appendChild(chevron)

            val titleSpan = document.createElement("span") as HTMLElement
            titleSpan.className = "git-list-section-title"
            titleSpan.textContent = if (parent.isEmpty()) "(root)" else parent
            titleSpan.title = titleSpan.textContent ?: ""
            headline.appendChild(titleSpan)

            section.appendChild(headline)

            val childrenEl = document.createElement("div") as HTMLElement
            childrenEl.className = "git-list-section-children"
            if (isCollapsed) childrenEl.classList.add("collapsed-tree")

            headline.addEventListener("mousedown", { ev -> ev.stopPropagation() })
            headline.addEventListener("click", { _ ->
                if (sectionKey in collapsedGitSections) {
                    collapsedGitSections.remove(sectionKey)
                    headline.classList.remove("collapsed-tree")
                    childrenEl.classList.remove("collapsed-tree")
                    childrenEl.style.maxHeight = "${childrenEl.scrollHeight}px"
                } else {
                    collapsedGitSections.add(sectionKey)
                    headline.classList.add("collapsed-tree")
                    childrenEl.classList.add("collapsed-tree")
                }
            })

            for (entry in items) {
                val filePath = entry.filePath as String
                val status = entry.status as String
                val basename = filePath.substringAfterLast('/')
                val statusChar = when (status) {
                    "Added" -> "A"; "Modified" -> "M"; "Deleted" -> "D"
                    "Renamed" -> "R"; "Untracked" -> "U"; else -> "?"
                }
                val item = document.createElement("div") as HTMLElement
                item.className = "git-list-item"
                if (filePath == state.selectedFilePath) item.classList.add("active")

                val badge = document.createElement("span") as HTMLElement
                badge.className = "git-status-badge git-status-$statusChar"
                badge.textContent = statusChar

                val name = document.createElement("span") as HTMLElement
                name.className = "git-list-item-name"
                name.textContent = basename
                name.title = filePath

                item.appendChild(badge)
                item.appendChild(name)

                item.addEventListener("mousedown", { ev -> ev.stopPropagation() })
                item.addEventListener("click", { _ ->
                    sendCmd(WindowCommand.GitDiff(paneId = paneId, filePath = filePath))
                })
                childrenEl.appendChild(item)
            }
            section.appendChild(childrenEl)
            if (!isCollapsed) childrenEl.style.maxHeight = "9999px"
            view.listBody.appendChild(section)
        }
        view.listBody.scrollTop = savedScroll
        // Re-enable the transition on the next frame so user-driven
        // collapse/expand still animates.
        val el = view.listBody
        kotlinx.browser.window.requestAnimationFrame {
            el.style.removeProperty("--git-section-transition")
        }
    }

    fun clearDiffSearch(diffContent: HTMLElement) {
        val marks = diffContent.querySelectorAll("mark.git-search-hit")
        for (i in 0 until marks.length) {
            val mark = marks.item(i) as HTMLElement
            val parent = mark.parentNode ?: continue
            val text = document.createTextNode(mark.textContent ?: "")
            parent.replaceChild(text, mark)
            (parent as HTMLElement).normalize()
        }
    }

    fun performDiffSearch(
        diffContent: HTMLElement,
        query: String,
        state: GitPaneState,
        counterLabel: HTMLElement,
        navButtons: List<HTMLElement>,
    ) {
        clearDiffSearch(diffContent)
        if (query.isEmpty()) {
            state.searchMatchIndex = 0
            counterLabel.textContent = ""
            navButtons.forEach { it.asDynamic().disabled = true }
            return
        }
        val lowerQuery = query.lowercase()
        val matches = mutableListOf<HTMLElement>()
        val walker = document.asDynamic().createTreeWalker(diffContent, 4 /* NodeFilter.SHOW_TEXT */, null)
        val textNodes = mutableListOf<org.w3c.dom.Text>()
        while (true) {
            val node = walker.nextNode() ?: break
            textNodes.add(node as org.w3c.dom.Text)
        }
        for (textNode in textNodes) {
            val text = textNode.textContent ?: continue
            val lowerText = text.lowercase()
            val offsets = mutableListOf<Int>()
            var searchFrom = 0
            while (searchFrom < text.length) {
                val idx = lowerText.indexOf(lowerQuery, searchFrom)
                if (idx < 0) break
                offsets.add(idx)
                searchFrom = idx + 1
            }
            if (offsets.isEmpty()) continue
            val parent = textNode.parentNode ?: continue
            val frag = document.createDocumentFragment()
            var pos = 0
            for (off in offsets) {
                if (off > pos) {
                    frag.appendChild(document.createTextNode(text.substring(pos, off)))
                }
                val mark = document.createElement("mark") as HTMLElement
                mark.className = "git-search-hit"
                mark.textContent = text.substring(off, off + query.length)
                frag.appendChild(mark)
                matches.add(mark)
                pos = off + query.length
            }
            if (pos < text.length) {
                frag.appendChild(document.createTextNode(text.substring(pos)))
            }
            parent.replaceChild(frag, textNode)
        }
        val total = matches.size
        if (total == 0) {
            state.searchMatchIndex = 0
            counterLabel.textContent = "0/0"
            navButtons.forEach { it.asDynamic().disabled = true }
            return
        }
        state.searchMatchIndex = state.searchMatchIndex.coerceIn(0, total - 1)
        navButtons.forEach { it.asDynamic().disabled = false }
        matches.getOrNull(state.searchMatchIndex)?.classList?.add("git-search-current")
        matches.getOrNull(state.searchMatchIndex)?.scrollIntoView(js("{block:'nearest',behavior:'smooth'}"))
        counterLabel.textContent = "${state.searchMatchIndex + 1}/$total"
    }

    fun navigateDiffSearch(
        diffContent: HTMLElement,
        state: GitPaneState,
        counterLabel: HTMLElement,
        delta: Int,
    ) {
        val marks = diffContent.querySelectorAll("mark.git-search-hit")
        val total = marks.length
        if (total == 0) return
        (marks.item(state.searchMatchIndex) as? HTMLElement)?.classList?.remove("git-search-current")
        state.searchMatchIndex = when (delta) {
            Int.MIN_VALUE -> 0                   // first
            Int.MAX_VALUE -> total - 1            // last
            else -> {
                var n = state.searchMatchIndex + delta
                if (n < 0) n = total - 1
                if (n >= total) n = 0
                n
            }
        }
        val current = marks.item(state.searchMatchIndex) as? HTMLElement
        current?.classList?.add("git-search-current")
        current?.scrollIntoView(js("{block:'nearest',behavior:'smooth'}"))
        counterLabel.textContent = "${state.searchMatchIndex + 1}/$total"
    }

    fun renderGitDiffInline(diffPane: HTMLElement, parsed: dynamic) {
        diffPane.innerHTML = ""
        val hunks = parsed.hunks as Array<dynamic>
        if (hunks.isEmpty()) {
            val empty = document.createElement("div") as HTMLElement
            empty.className = "git-diff-empty"
            empty.textContent = "No changes"
            diffPane.appendChild(empty)
            return
        }
        val container = document.createElement("div") as HTMLElement
        container.className = "git-diff-inline"
        for (hunk in hunks) {
            val hunkDiv = document.createElement("div") as HTMLElement
            hunkDiv.className = "git-hunk"

            val lines = hunk.lines as Array<dynamic>
            for (line in lines) {
                val lineDiv = document.createElement("div") as HTMLElement
                lineDiv.className = "git-diff-line"
                val type = line.type as String
                when (type) {
                    "Addition" -> lineDiv.classList.add("git-line-add")
                    "Deletion" -> lineDiv.classList.add("git-line-del")
                }
                val oldNo = document.createElement("span") as HTMLElement
                oldNo.className = "git-line-no"
                oldNo.textContent = (line.oldLineNo as? Number)?.toString() ?: ""
                val newNo = document.createElement("span") as HTMLElement
                newNo.className = "git-line-no"
                newNo.textContent = (line.newLineNo as? Number)?.toString() ?: ""
                val content = document.createElement("span") as HTMLElement
                content.className = "git-line-content"
                // Content is pre-highlighted HTML from the server.
                content.innerHTML = line.content as String

                lineDiv.appendChild(oldNo)
                lineDiv.appendChild(newNo)
                lineDiv.appendChild(content)
                hunkDiv.appendChild(lineDiv)
            }
            container.appendChild(hunkDiv)
        }
        diffPane.appendChild(container)
    }

    fun renderGitDiffSplit(diffPane: HTMLElement, parsed: dynamic) {
        diffPane.innerHTML = ""
        val hunks = parsed.hunks as Array<dynamic>
        if (hunks.isEmpty()) {
            val empty = document.createElement("div") as HTMLElement
            empty.className = "git-diff-empty"
            empty.textContent = "No changes"
            diffPane.appendChild(empty)
            return
        }
        // Single scrollable container — no separate left/right scroll areas.
        val container = document.createElement("div") as HTMLElement
        container.className = "git-diff-split"

        // Build paired rows from hunks.
        data class SplitRow(
            val oldNo: String, val oldContent: String, val oldClass: String,
            val newNo: String, val newContent: String, val newClass: String,
        )
        val rows = mutableListOf<SplitRow>()

        for (hunk in hunks) {
            val lines = hunk.lines as Array<dynamic>
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                val type = line.type as String
                if (type == "Context") {
                    val no = (line.oldLineNo as? Number)?.toString() ?: ""
                    val nno = (line.newLineNo as? Number)?.toString() ?: ""
                    rows.add(SplitRow(no, line.content as String, "", nno, line.content as String, ""))
                    i++
                } else {
                    val dels = mutableListOf<dynamic>()
                    val adds = mutableListOf<dynamic>()
                    while (i < lines.size && (lines[i].type as String) == "Deletion") {
                        dels.add(lines[i]); i++
                    }
                    while (i < lines.size && (lines[i].type as String) == "Addition") {
                        adds.add(lines[i]); i++
                    }
                    val maxLen = maxOf(dels.size, adds.size)
                    for (j in 0 until maxLen) {
                        val del = dels.getOrNull(j)
                        val add = adds.getOrNull(j)
                        val isModified = del != null && add != null
                        rows.add(SplitRow(
                            oldNo = if (del != null) (del.oldLineNo as? Number)?.toString() ?: "" else "",
                            oldContent = if (del != null) del.content as String else "",
                            oldClass = when {
                                del == null -> "git-split-placeholder"
                                isModified -> "git-split-mod"
                                else -> "git-line-del"
                            },
                            newNo = if (add != null) (add.newLineNo as? Number)?.toString() ?: "" else "",
                            newContent = if (add != null) add.content as String else "",
                            newClass = when {
                                add == null -> "git-split-placeholder"
                                isModified -> "git-split-mod"
                                else -> "git-line-add"
                            },
                        ))
                    }
                }
            }
        }

        // Render each paired row as a single flex row spanning both halves.
        for (row in rows) {
            val rowDiv = document.createElement("div") as HTMLElement
            rowDiv.className = "git-split-row"

            val leftHalf = document.createElement("div") as HTMLElement
            leftHalf.className = "git-split-half ${ row.oldClass }"
            val leftNo = document.createElement("span") as HTMLElement
            leftNo.className = "git-line-no"
            leftNo.textContent = row.oldNo
            val leftContent = document.createElement("span") as HTMLElement
            leftContent.className = "git-line-content"
            leftContent.innerHTML = row.oldContent
            leftHalf.appendChild(leftNo)
            leftHalf.appendChild(leftContent)

            val gutter = document.createElement("div") as HTMLElement
            gutter.className = "git-split-gutter"

            val rightHalf = document.createElement("div") as HTMLElement
            rightHalf.className = "git-split-half ${ row.newClass }"
            val rightNo = document.createElement("span") as HTMLElement
            rightNo.className = "git-line-no"
            rightNo.textContent = row.newNo
            val rightContent = document.createElement("span") as HTMLElement
            rightContent.className = "git-line-content"
            rightContent.innerHTML = row.newContent
            rightHalf.appendChild(rightNo)
            rightHalf.appendChild(rightContent)

            rowDiv.appendChild(leftHalf)
            rowDiv.appendChild(gutter)
            rowDiv.appendChild(rightHalf)
            container.appendChild(rowDiv)
        }

        diffPane.appendChild(container)
    }

    /**
     * P4Merge-style graphical diff: both panels show the unmodified full
     * file content. The right panel has the scrollbar; the left panel's
     * scroll is managed by anchoring on the nearest visible change — the
     * change closest to the viewport center is kept at the same screen-Y
     * on both sides, with a smooth offset transition between changes.
     * This keeps connectors nearly horizontal for focused changes.
     */
    fun renderGitDiffGraphical(diffPane: HTMLElement, parsed: dynamic, state: GitPaneState) {
        diffPane.innerHTML = ""
        val oldContent = parsed.oldContent as? String
        val newContent = parsed.newContent as? String
        val hunks = parsed.hunks as Array<dynamic>
        if (oldContent == null && newContent == null) {
            val empty = document.createElement("div") as HTMLElement
            empty.className = "git-diff-empty"
            empty.textContent = "No content available for graphical diff"
            diffPane.appendChild(empty)
            return
        }
        val oldLines = (oldContent ?: "").split("\n")
        val newLines = (newContent ?: "").split("\n")
        val fontSize = state.diffFontSize
        val lineHeight = (fontSize * 1.54).toInt()
        val lh = lineHeight.toDouble()

        // --- Build change regions from hunks ---
        data class ChangeRegion(
            val oldStart: Int, val oldEnd: Int,
            val newStart: Int, val newEnd: Int,
            val type: String,
        )
        val oldModified = mutableSetOf<Int>()
        val newModified = mutableSetOf<Int>()
        val oldChanged = mutableSetOf<Int>()
        val newChanged = mutableSetOf<Int>()
        val regions = mutableListOf<ChangeRegion>()

        for (hunk in hunks) {
            val lines = hunk.lines as Array<dynamic>
            // Track the last known old/new line number as we walk lines,
            // so pure additions/deletions get the correct position on the
            // missing side (the hunk-level offset is wrong after earlier
            // change blocks within the same hunk shift the numbering).
            var lastOldLineNo = (hunk.oldStart as Number).toInt() - 1
            var lastNewLineNo = (hunk.newStart as Number).toInt() - 1
            var i = 0
            while (i < lines.size) {
                val type = lines[i].type as String
                if (type == "Context") {
                    lastOldLineNo = (lines[i].oldLineNo as? Number)?.toInt() ?: lastOldLineNo
                    lastNewLineNo = (lines[i].newLineNo as? Number)?.toInt() ?: lastNewLineNo
                    i++; continue
                }
                val dels = mutableListOf<dynamic>()
                val adds = mutableListOf<dynamic>()
                while (i < lines.size && (lines[i].type as String) == "Deletion") { dels.add(lines[i]); i++ }
                while (i < lines.size && (lines[i].type as String) == "Addition") { adds.add(lines[i]); i++ }
                val paired = minOf(dels.size, adds.size)
                for (j in 0 until paired) {
                    val oln = (dels[j].oldLineNo as Number).toInt(); val nln = (adds[j].newLineNo as Number).toInt()
                    oldModified.add(oln); oldChanged.add(oln); newModified.add(nln); newChanged.add(nln)
                }
                for (j in paired until dels.size) oldChanged.add((dels[j].oldLineNo as Number).toInt())
                for (j in paired until adds.size) newChanged.add((adds[j].newLineNo as Number).toInt())
                val regionType = when { dels.isNotEmpty() && adds.isNotEmpty() -> "mod"; dels.isNotEmpty() -> "del"; else -> "add" }
                // Use tracked positions for the missing side.
                val oldStart = if (dels.isNotEmpty()) (dels.first().oldLineNo as Number).toInt() else lastOldLineNo + 1
                val oldEnd = if (dels.isNotEmpty()) (dels.last().oldLineNo as Number).toInt() else oldStart - 1
                val newStart = if (adds.isNotEmpty()) (adds.first().newLineNo as Number).toInt() else lastNewLineNo + 1
                val newEnd = if (adds.isNotEmpty()) (adds.last().newLineNo as Number).toInt() else newStart - 1
                regions.add(ChangeRegion(oldStart, oldEnd, newStart, newEnd, regionType))
                // Update tracked positions.
                if (dels.isNotEmpty()) lastOldLineNo = (dels.last().oldLineNo as Number).toInt()
                if (adds.isNotEmpty()) lastNewLineNo = (adds.last().newLineNo as Number).toInt()
            }
        }

        // --- Build panels (full file content, bottom padding for scroll range) ---
        val container = document.createElement("div") as HTMLElement
        container.className = "git-diff-graphical"

        val leftPane = document.createElement("div") as HTMLElement
        leftPane.className = "git-graphical-left"
        val leftInner = document.createElement("div") as HTMLElement
        leftInner.className = "git-graphical-inner"
        for ((idx, line) in oldLines.withIndex()) {
            val lineNo = idx + 1
            val div = document.createElement("div") as HTMLElement
            div.className = "git-graphical-line" + when {
                oldModified.contains(lineNo) -> " highlight-mod"
                oldChanged.contains(lineNo) -> " highlight-del"
                else -> ""
            }
            div.style.height = "${lineHeight}px"; div.style.lineHeight = "${lineHeight}px"
            val noSpan = document.createElement("span") as HTMLElement
            noSpan.className = "git-line-no"; noSpan.textContent = "$lineNo"
            val cSpan = document.createElement("span") as HTMLElement
            cSpan.className = "git-line-content"; cSpan.textContent = line.ifEmpty { " " }
            div.appendChild(noSpan); div.appendChild(cSpan)
            leftInner.appendChild(div)
        }
        leftPane.appendChild(leftInner)

        val svgNs = "http://www.w3.org/2000/svg"
        val svgContainer = document.createElement("div") as HTMLElement
        svgContainer.className = "git-graphical-connector"
        val svg = document.createElementNS(svgNs, "svg")
        svg.setAttribute("class", "git-graphical-svg")
        svgContainer.appendChild(svg)

        val rightPane = document.createElement("div") as HTMLElement
        rightPane.className = "git-graphical-right"
        val rightInner = document.createElement("div") as HTMLElement
        rightInner.className = "git-graphical-inner"
        for ((idx, line) in newLines.withIndex()) {
            val lineNo = idx + 1
            val div = document.createElement("div") as HTMLElement
            div.className = "git-graphical-line" + when {
                newModified.contains(lineNo) -> " highlight-mod"
                newChanged.contains(lineNo) -> " highlight-add"
                else -> ""
            }
            div.style.height = "${lineHeight}px"; div.style.lineHeight = "${lineHeight}px"
            val noSpan = document.createElement("span") as HTMLElement
            noSpan.className = "git-line-no"; noSpan.textContent = "$lineNo"
            val cSpan = document.createElement("span") as HTMLElement
            cSpan.className = "git-line-content"; cSpan.textContent = line.ifEmpty { " " }
            div.appendChild(noSpan); div.appendChild(cSpan)
            rightInner.appendChild(div)
        }
        rightPane.appendChild(rightInner)

        // Pad the shorter side at the bottom so both panes have equal
        // scroll range — otherwise the master (right) runs out before
        // the mapping can reach the bottom of the longer pane.
        val diff = kotlin.math.abs(oldLines.size - newLines.size) * lineHeight
        if (diff > 0) {
            val pad = document.createElement("div") as HTMLElement
            pad.style.height = "${diff}px"
            if (oldLines.size > newLines.size) rightInner.appendChild(pad)
            else leftInner.appendChild(pad)
        }

        container.appendChild(leftPane)
        container.appendChild(svgContainer)
        container.appendChild(rightPane)
        diffPane.appendChild(container)

        // --- Piecewise linear scroll mapping from change regions ---
        // Context between changes: both sides scroll 1:1.
        // Within a change: left scrolls at (oldCount/newCount) rate so
        // the other side catches up by the end of the change block.
        //
        // Pure deletions (nc=0) are skipped — they'd create a discontinuity
        // (two anchors at the same rightY). Instead, the surrounding context
        // absorbs the offset, making the left panel speed up smoothly
        // through the deletion rather than jumping.
        data class Anchor(val rightY: Double, val leftY: Double)
        val anchors = mutableListOf<Anchor>()
        anchors.add(Anchor(0.0, 0.0))
        for (r in regions) {
            val oc = if (r.oldEnd >= r.oldStart) r.oldEnd - r.oldStart + 1 else 0
            val nc = if (r.newEnd >= r.newStart) r.newEnd - r.newStart + 1 else 0
            if (nc == 0) continue // skip pure deletions to avoid discontinuity
            anchors.add(Anchor((r.newStart - 1) * lh, (r.oldStart - 1) * lh))
            anchors.add(Anchor((r.newStart - 1 + nc) * lh, (r.oldStart - 1 + oc) * lh))
        }
        anchors.add(Anchor(newLines.size * lh, oldLines.size * lh))

        fun mapRightToLeft(rY: Double): Double {
            for (i in 1 until anchors.size) {
                if (rY <= anchors[i].rightY) {
                    val prev = anchors[i - 1]
                    val next = anchors[i]
                    val span = next.rightY - prev.rightY
                    if (span <= 0.0) return next.leftY
                    val t = ((rY - prev.rightY) / span).coerceIn(0.0, 1.0)
                    return prev.leftY + t * (next.leftY - prev.leftY)
                }
            }
            return anchors.last().leftY
        }

        // --- SVG connectors (limited to nearest changes) ---
        var rafPending = false
        fun updateConnectors() {
            val connW = svgContainer.clientWidth.toDouble()
            val viewH = svgContainer.clientHeight.toDouble()
            if (connW <= 0 || viewH <= 0) return
            svg.setAttribute("width", "$connW")
            svg.setAttribute("height", "$viewH")
            svg.setAttribute("viewBox", "0 0 $connW $viewH")
            while (svg.firstChild != null) svg.removeChild(svg.firstChild!!)

            val leftScroll = leftPane.scrollTop.toDouble()
            val rightScroll = rightPane.scrollTop.toDouble()
            val centerY = viewH / 2.0

            // Compute screen midpoint for each region, pick the N closest
            // to viewport center. This keeps visuals clean like P4Merge.
            data class RegionDraw(
                val leftTop: Double, val leftBot: Double,
                val rightTop: Double, val rightBot: Double,
                val type: String, val distFromCenter: Double,
            )
            val candidates = mutableListOf<RegionDraw>()
            for (region in regions) {
                val lt = (region.oldStart - 1) * lh - leftScroll
                val lb = if (region.oldEnd >= region.oldStart) region.oldEnd * lh - leftScroll else lt + lh * 0.5
                val rt = (region.newStart - 1) * lh - rightScroll
                val rb = if (region.newEnd >= region.newStart) region.newEnd * lh - rightScroll else rt + lh * 0.5

                // Skip if completely off-screen on either side.
                if (lb < 0 && rb < 0) continue
                if (lt > viewH && rt > viewH) continue

                val midL = (lt + lb) / 2.0
                val midR = (rt + rb) / 2.0
                val dist = minOf(
                    kotlin.math.abs(midL - centerY),
                    kotlin.math.abs(midR - centerY),
                )
                candidates.add(RegionDraw(lt, lb, rt, rb, region.type, dist))
            }

            // Draw only the 5 closest to center, fading by distance.
            candidates.sortBy { it.distFromCenter }
            val maxDraw = 5
            for ((idx, rd) in candidates.take(maxDraw).withIndex()) {
                val fade = if (candidates.size <= 1) 1.0
                    else (1.0 - idx.toDouble() / maxDraw).coerceIn(0.3, 1.0)

                // When true, match the line-highlight backgrounds exactly and
                // drop the stroke so the connector ribbon reads as a single
                // continuous strip flowing through both panes.
                val meltWithLines = false
                val fill = if (meltWithLines) when (rd.type) {
                    "del" -> "rgba(200, 60, 55, ${0.20 * fade})"
                    "add" -> "rgba(55, 150, 65, ${0.18 * fade})"
                    else  -> "rgba(180, 130, 40, ${0.18 * fade})"
                } else when (rd.type) {
                    "del" -> "rgba(180, 60, 55, ${0.35 * fade})"
                    "add" -> "rgba(55, 140, 65, ${0.30 * fade})"
                    else  -> "rgba(170, 130, 50, ${0.30 * fade})"
                }
                val stroke = when (rd.type) {
                    "del" -> "rgba(180, 60, 55, ${0.55 * fade})"
                    "add" -> "rgba(55, 140, 65, ${0.50 * fade})"
                    else  -> "rgba(170, 130, 50, ${0.50 * fade})"
                }

                val cx1 = connW * 0.35; val cx2 = connW * 0.65
                val path = document.createElementNS(svgNs, "path")
                val d = buildString {
                    append("M 0 ${rd.leftTop} ")
                    append("C $cx1 ${rd.leftTop} $cx2 ${rd.rightTop} $connW ${rd.rightTop} ")
                    append("L $connW ${rd.rightBot} ")
                    append("C $cx2 ${rd.rightBot} $cx1 ${rd.leftBot} 0 ${rd.leftBot} ")
                    append("Z")
                }
                path.setAttribute("d", d)
                path.setAttribute("fill", fill)
                if (!meltWithLines) {
                    path.setAttribute("stroke", stroke)
                    path.setAttribute("stroke-width", "1")
                }
                svg.appendChild(path)
            }
        }

        window.requestAnimationFrame { updateConnectors() }
        fun scheduleUpdate() {
            if (!rafPending) {
                rafPending = true
                window.requestAnimationFrame { rafPending = false; updateConnectors() }
            }
        }

        // --- Scroll management: piecewise linear, center-mapped ---
        // Map the viewport CENTER through the function so the change the
        // user is looking at (screen center) has the flattest connector.
        var syncing = false
        fun syncLeftFromRight() {
            if (syncing) return
            syncing = true
            val rightTop = rightPane.scrollTop.toDouble()
            val halfView = rightPane.clientHeight / 2.0
            val rightCenter = rightTop + halfView
            val leftCenter = mapRightToLeft(rightCenter)
            val leftTarget = leftCenter - halfView
            val leftMax = (leftPane.scrollHeight - leftPane.clientHeight).toDouble()
            leftPane.scrollTop = leftTarget.coerceIn(0.0, if (leftMax > 0) leftMax else 0.0)
            syncing = false
        }
        rightPane.addEventListener("scroll", { _ ->
            syncLeftFromRight()
            scheduleUpdate()
        })
        leftPane.addEventListener("scroll", { _ -> scheduleUpdate() })

        leftPane.asDynamic().addEventListener("wheel", { ev: dynamic ->
            ev.preventDefault()
            rightPane.scrollTop = rightPane.scrollTop + (ev.deltaY as Number).toDouble()
            leftPane.scrollLeft = leftPane.scrollLeft + (ev.deltaX as Number).toDouble()
        }, json("passive" to false))
        svgContainer.asDynamic().addEventListener("wheel", { ev: dynamic ->
            ev.preventDefault()
            rightPane.scrollTop = rightPane.scrollTop + (ev.deltaY as Number).toDouble()
        }, json("passive" to false))

        window.requestAnimationFrame { syncLeftFromRight() }

        // Re-sync scroll positions and redraw SVG connectors on window resize.
        window.addEventListener("resize", { _ ->
            syncLeftFromRight()
            scheduleUpdate()
        })
    }

    fun buildGitView(paneId: String, leaf: dynamic, headerEl: HTMLElement? = null): HTMLElement {
        val content = leaf.content
        val initialWidth = (content?.leftColumnWidthPx as? Number)?.toInt() ?: 280
        val initialSelected = content?.selectedFilePath as? String
        val initialAutoRefresh = content?.autoRefresh as? Boolean ?: false
        val initialDiffMode = (content?.diffMode as? String) ?: "Inline"
        val initialGraphical = content?.graphicalDiff as? Boolean ?: false
        val state = gitPaneStates.getOrPut(paneId) { GitPaneState() }
        if (initialSelected != null) state.selectedFilePath = initialSelected
        state.diffMode = initialDiffMode
        state.graphicalDiff = initialGraphical
        state.diffFontSize = paneFontSize

        val outer = document.createElement("div") as HTMLElement
        outer.className = "git-view"

        val left = document.createElement("div") as HTMLElement
        left.className = "git-list"
        left.style.width = "${initialWidth.coerceIn(0, 640)}px"
        left.style.asDynamic().flexShrink = "0"

        val header = document.createElement("div") as HTMLElement
        header.className = "git-list-header"
        val titleEl = document.createElement("span") as HTMLElement
        titleEl.className = "git-list-title"
        titleEl.textContent = "Git Changes"
        header.appendChild(titleEl)
        left.appendChild(header)

        val listBody = document.createElement("div") as HTMLElement
        listBody.className = "git-list-body"
        left.appendChild(listBody)

        val divider = document.createElement("div") as HTMLElement
        divider.className = "git-divider"

        val right = document.createElement("div") as HTMLElement
        right.className = "git-diff-pane"

        val diffContent = document.createElement("div") as HTMLElement
        diffContent.className = "git-diff-content"

        // -- Header flyout controls (injected into the pane header bar) --------

        val iconDiffMode = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09a1.65 1.65 0 0 0-1.08-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09a1.65 1.65 0 0 0 1.51-1.08 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1.08 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1.08z"/></svg>"""
        val iconSearch = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7"/><line x1="16" y1="16" x2="21" y2="21"/></svg>"""
        val iconAutoRefresh = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><polyline points="12,7 12,12 15,14"/></svg>"""

        fun makeFlyoutBtn(titleText: String, svg: String, extraClass: String = ""): Pair<HTMLElement, HTMLElement> {
            val wrap = document.createElement("div") as HTMLElement
            wrap.className = "pane-flyout-wrap"
            wrap.addEventListener("mousedown", { ev -> ev.stopPropagation() })
            val btn = document.createElement("button") as HTMLElement
            btn.className = "pane-action-btn" + (if (extraClass.isNotEmpty()) " $extraClass" else "")
            btn.setAttribute("type", "button")
            btn.setAttribute("title", titleText)
            btn.innerHTML = svg
            btn.addEventListener("click", { ev ->
                ev.stopPropagation()
                val open = document.querySelectorAll(".pane-flyout-wrap.open")
                for (i in 0 until open.length) {
                    val other = open.item(i) as HTMLElement
                    if (other !== wrap) other.classList.remove("open")
                }
                wrap.classList.toggle("open")
            })
            wrap.appendChild(btn)
            val flyout = document.createElement("div") as HTMLElement
            flyout.className = "pane-flyout"
            flyout.addEventListener("click", { ev -> ev.stopPropagation() })
            wrap.appendChild(flyout)
            return Pair(wrap, flyout)
        }

        // Callback set once the graphical checkbox is created.
        var updateGraphicalVisibility: () -> Unit = {}

        // -- Diff mode flyout --
        val (diffModeWrap, diffModeFlyout) = makeFlyoutBtn("Diff mode", iconDiffMode)

        val modeRow = document.createElement("div") as HTMLElement
        modeRow.className = "pane-flyout-row"
        fun updateModeButtons() {
            val btns = modeRow.querySelectorAll(".git-mode-btn")
            for (i in 0 until btns.length) {
                val btn = btns.item(i) as HTMLElement
                val mode = btn.getAttribute("data-mode") ?: ""
                if (mode == state.diffMode) btn.classList.add("active")
                else btn.classList.remove("active")
            }
        }
        for (mode in listOf("Inline", "Split")) {
            val btn = document.createElement("button") as HTMLElement
            btn.className = "git-mode-btn"
            (btn.asDynamic()).type = "button"
            btn.textContent = mode
            btn.setAttribute("data-mode", mode)
            btn.addEventListener("click", { _ ->
                if (state.diffMode != mode) {
                    state.diffMode = mode
                    state.diffHtml = null
                    sendCmd(WindowCommand.SetGitDiffMode(paneId = paneId, mode = if (mode == "Split") GitDiffMode.Split else GitDiffMode.Inline))
                    updateModeButtons()
                    updateGraphicalVisibility()
                    val sel = state.selectedFilePath
                    if (sel != null) {
                        sendCmd(WindowCommand.GitDiff(paneId = paneId, filePath = sel))
                    }
                }
            })
            modeRow.appendChild(btn)
        }
        diffModeFlyout.appendChild(modeRow)

        val graphicalLabel = document.createElement("label") as HTMLElement
        graphicalLabel.className = "git-graphical-toggle"
        graphicalLabel.style.display = if (state.diffMode == "Split") "inline-flex" else "none"
        val graphicalCb = document.createElement("input") as HTMLInputElement
        graphicalCb.type = "checkbox"
        graphicalCb.checked = state.graphicalDiff
        graphicalCb.addEventListener("change", { _ ->
            state.graphicalDiff = graphicalCb.checked
            state.diffHtml = null
            sendCmd(WindowCommand.SetGitGraphicalDiff(paneId = paneId, enabled = graphicalCb.checked))
            val sel = state.selectedFilePath
            if (sel != null) {
                sendCmd(WindowCommand.GitDiff(paneId = paneId, filePath = sel))
            }
        })
        graphicalLabel.appendChild(graphicalCb)
        val graphicalText = document.createElement("span") as HTMLElement
        graphicalText.textContent = "Graphical"
        graphicalLabel.appendChild(graphicalText)
        diffModeFlyout.appendChild(graphicalLabel)
        updateModeButtons()
        updateGraphicalVisibility = {
            graphicalLabel.style.display = if (state.diffMode == "Split") "inline-flex" else "none"
        }

        // -- Search flyout --
        val (searchWrap, searchFlyout) = makeFlyoutBtn("Search diff", iconSearch)

        val searchInput = document.createElement("input") as HTMLInputElement
        searchInput.type = "text"
        searchInput.placeholder = "Search\u2026"
        searchInput.className = "git-search-input"
        searchInput.value = state.searchQuery
        searchFlyout.appendChild(searchInput)

        val searchNavRow = document.createElement("div") as HTMLElement
        searchNavRow.className = "pane-flyout-row"

        val searchCounter = document.createElement("span") as HTMLElement
        searchCounter.className = "git-search-counter"
        searchNavRow.appendChild(searchCounter)

        val firstBtn = document.createElement("button") as HTMLElement
        (firstBtn.asDynamic()).type = "button"
        firstBtn.className = "git-font-btn git-search-nav"
        firstBtn.textContent = "\u23EE"
        firstBtn.title = "First match"
        firstBtn.asDynamic().disabled = true

        val prevBtn = document.createElement("button") as HTMLElement
        (prevBtn.asDynamic()).type = "button"
        prevBtn.className = "git-font-btn git-search-nav"
        prevBtn.textContent = "\u25C0"
        prevBtn.title = "Previous match"
        prevBtn.asDynamic().disabled = true

        val nextBtn = document.createElement("button") as HTMLElement
        (nextBtn.asDynamic()).type = "button"
        nextBtn.className = "git-font-btn git-search-nav"
        nextBtn.textContent = "\u25B6"
        nextBtn.title = "Next match"
        nextBtn.asDynamic().disabled = true

        val lastBtn = document.createElement("button") as HTMLElement
        (lastBtn.asDynamic()).type = "button"
        lastBtn.className = "git-font-btn git-search-nav"
        lastBtn.textContent = "\u23ED"
        lastBtn.title = "Last match"
        lastBtn.asDynamic().disabled = true

        val searchNavButtons = listOf(firstBtn, prevBtn, nextBtn, lastBtn)

        var searchTimer: Int? = null
        fun runSearch() {
            state.searchQuery = searchInput.value
            state.searchMatchIndex = 0
            performDiffSearch(diffContent, state.searchQuery, state, searchCounter, searchNavButtons)
        }
        searchInput.addEventListener("input", { _ ->
            searchTimer?.let { kotlinx.browser.window.clearTimeout(it) }
            searchTimer = kotlinx.browser.window.setTimeout({ runSearch() }, 200)
        })

        firstBtn.addEventListener("click", { _ -> navigateDiffSearch(diffContent, state, searchCounter, Int.MIN_VALUE) })
        prevBtn.addEventListener("click", { _ -> navigateDiffSearch(diffContent, state, searchCounter, -1) })
        nextBtn.addEventListener("click", { _ -> navigateDiffSearch(diffContent, state, searchCounter, 1) })
        lastBtn.addEventListener("click", { _ -> navigateDiffSearch(diffContent, state, searchCounter, Int.MAX_VALUE) })

        for (b in searchNavButtons) searchNavRow.appendChild(b)
        searchFlyout.appendChild(searchNavRow)

        // -- Auto-refresh toggle (injected into pane header) --
        val autoRefreshBtn = document.createElement("button") as HTMLElement
        autoRefreshBtn.className = "pane-action-btn" + if (initialAutoRefresh) " active" else ""
        autoRefreshBtn.setAttribute("type", "button")
        autoRefreshBtn.setAttribute("title", "Auto-refresh")
        autoRefreshBtn.innerHTML = iconAutoRefresh
        autoRefreshBtn.addEventListener("mousedown", { ev -> ev.stopPropagation() })
        autoRefreshBtn.addEventListener("click", { ev ->
            ev.stopPropagation()
            val nowOn = !autoRefreshBtn.classList.contains("active")
            autoRefreshBtn.classList.toggle("active", nowOn)
            sendCmd(WindowCommand.SetGitAutoRefresh(paneId = paneId, enabled = nowOn))
        })

        // -- Refresh button (injected into pane header) --
        val refreshBtn = document.createElement("button") as HTMLElement
        refreshBtn.className = "pane-action-btn"
        refreshBtn.setAttribute("type", "button")
        refreshBtn.setAttribute("title", "Refresh")
        refreshBtn.innerHTML =
            """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M2 8a6 6 0 0 1 10.5-3.9"/><polyline points="13,2 13,5 10,5"/><path d="M14 8a6 6 0 0 1 -10.5 3.9"/><polyline points="3,14 3,11 6,11"/></svg>"""
        refreshBtn.addEventListener("mousedown", { ev -> ev.stopPropagation() })
        refreshBtn.addEventListener("click", { ev ->
            ev.stopPropagation()
            sendCmd(WindowCommand.GitList(paneId = paneId))
            val sel = state.selectedFilePath
            if (sel != null) {
                state.diffHtml = null
                sendCmd(WindowCommand.GitDiff(paneId = paneId, filePath = sel))
            }
        })

        // Inject controls into the pane header bar.
        if (headerEl != null) {
            val actions = headerEl.querySelector(".pane-actions") as? HTMLElement
            if (actions != null) {
                actions.parentNode?.insertBefore(diffModeWrap, actions)
                actions.parentNode?.insertBefore(searchWrap, actions)
                actions.parentNode?.insertBefore(autoRefreshBtn, actions)
                actions.parentNode?.insertBefore(refreshBtn, actions)
                val spacer = document.createElement("div") as HTMLElement
                spacer.className = "pane-header-spacer"
                actions.parentNode?.insertBefore(spacer, actions)
            }
        }

        fun applyFontSize() {
            diffContent.style.fontSize = "${state.diffFontSize}px"
            diffContent.style.lineHeight = "${(state.diffFontSize * 1.54).toInt()}px"
        }

        val emptyState = document.createElement("div") as HTMLElement
        emptyState.className = "git-diff-empty"
        emptyState.innerHTML = """
            <div class="git-diff-empty-icon">&#128221;</div>
            <div class="git-diff-empty-title">No file selected</div>
            <div class="git-diff-empty-subtitle">Choose a file from the list to view its diff</div>
        """.trimIndent()
        diffContent.appendChild(emptyState)
        right.appendChild(diffContent)
        applyFontSize()

        outer.appendChild(left)
        outer.appendChild(divider)
        outer.appendChild(right)

        val view = GitPaneView(listBody, diffContent, searchCounter, searchNavButtons)
        gitPaneViews[paneId] = view

        renderGitList(paneId, view, state)
        if (state.diffHtml != null) {
            diffContent.innerHTML = state.diffHtml!!
        }
        sendCmd(WindowCommand.GitList(paneId = paneId))
        if (state.selectedFilePath != null && state.diffHtml == null) {
            sendCmd(WindowCommand.GitDiff(paneId = paneId, filePath = state.selectedFilePath!!))
        }
        if (initialAutoRefresh) {
            sendCmd(WindowCommand.SetGitAutoRefresh(paneId = paneId, enabled = true))
        }

        // Divider drag handler.
        divider.addEventListener("mousedown", { ev ->
            val mev = ev as MouseEvent
            mev.preventDefault()
            divider.classList.add("dragging")
            val startX = mev.clientX
            val startWidth = left.offsetWidth
            val previousBodyCursor = document.body?.style?.cursor ?: ""
            document.body?.style?.cursor = "col-resize"
            var latestPx = startWidth
            val moveListener: (Event) -> Unit = { evMove ->
                val m = evMove as MouseEvent
                val w = (startWidth + (m.clientX - startX)).coerceIn(0, 640)
                latestPx = w
                left.style.width = "${w}px"
            }
            lateinit var upListener: (Event) -> Unit
            upListener = { _ ->
                document.removeEventListener("mousemove", moveListener)
                document.removeEventListener("mouseup", upListener)
                divider.classList.remove("dragging")
                document.body?.style?.cursor = previousBodyCursor
                sendCmd(WindowCommand.SetGitLeftWidth(paneId = paneId, px = latestPx))
            }
            document.addEventListener("mousemove", moveListener)
            document.addEventListener("mouseup", upListener)
        })

        return outer
    }

    fun buildLeafCell(leaf: dynamic, popoutMode: Boolean = false): HTMLElement {
        val paneId = leaf.id as String
        val title = leaf.title as String
        // Pane kind dispatch. Legacy configs (and freshly persisted blobs from
        // before LeafContent existed) have no `content` field — fall back to
        // terminal so the old behavior is preserved.
        val contentKind: String = (leaf.content?.kind as? String) ?: "terminal"

        val cell = document.createElement("div") as HTMLElement
        cell.className = "terminal-cell"
        cell.setAttribute("data-pane", paneId)

        when (contentKind) {
            "fileBrowser" -> {
                val header = buildPaneHeader(paneId, title, null, popoutMode = popoutMode)
                val fbIcon = document.createElement("span") as HTMLElement
                fbIcon.className = "pane-header-icon"
                fbIcon.innerHTML = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><path d="M2 4.5 a0.5 0.5 0 0 1 0.5 -0.5 h3.5 l1.25 1.75 h6.25 a0.5 0.5 0 0 1 0.5 0.5 v7.25 a0.5 0.5 0 0 1 -0.5 0.5 H2.5 a0.5 0.5 0 0 1 -0.5 -0.5 Z"/></svg>"""
                val fbTitleEl = header.querySelector(".terminal-title") as? HTMLElement
                if (fbTitleEl != null) header.insertBefore(fbIcon, fbTitleEl)
                cell.appendChild(header)
                val fbView = buildFileBrowserView(paneId, leaf, header)
                cell.appendChild(fbView)
                val fbRenderedEl = fbView.querySelector(".md-rendered") as? HTMLElement
                fbRenderedEl?.style?.fontSize = "${paneFontSize}px"
                cell.addEventListener("mousedown", { _ -> markPaneFocused(cell) })
            }
            "git" -> {
                val header = buildPaneHeader(paneId, title, null, popoutMode = popoutMode)
                val gitIcon = document.createElement("span") as HTMLElement
                gitIcon.className = "pane-header-icon"
                gitIcon.innerHTML = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><circle cx="5" cy="4" r="1.5"/><circle cx="5" cy="12" r="1.5"/><circle cx="11" cy="8" r="1.5"/><line x1="5" y1="5.5" x2="5" y2="10.5"/><path d="M5 5.5 C5 8 8 8 9.5 8"/></svg>"""
                val gitTitleEl = header.querySelector(".terminal-title") as? HTMLElement
                if (gitTitleEl != null) header.insertBefore(gitIcon, gitTitleEl)
                cell.appendChild(header)
                cell.appendChild(buildGitView(paneId, leaf, header))
                cell.addEventListener("mousedown", { _ -> markPaneFocused(cell) })
            }
            else -> {
                // Terminal path. Prefer the sessionId nested in the content
                // discriminated union; fall back to the top-level field for
                // legacy configs. Both should agree for terminal leaves.
                val sessionId = (leaf.content?.sessionId as? String)
                    ?: (leaf.sessionId as String)
                val isLink = leaf.isLink as? Boolean ?: false
                val header = buildPaneHeader(paneId, title, sessionId, popoutMode = popoutMode, isLink = isLink)

                // Prepend an icon before the title: link icon for linked
                // panes, terminal icon for regular terminals.
                val headerIcon = document.createElement("span") as HTMLElement
                headerIcon.className = "pane-header-icon"
                if (isLink) {
                    headerIcon.innerHTML = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><path d="M6.5 9.5a3 3 0 0 0 4.24 0l2.5-2.5a3 3 0 0 0-4.24-4.24L7.5 3.76"/><path d="M9.5 6.5a3 3 0 0 0-4.24 0l-2.5 2.5a3 3 0 0 0 4.24 4.24l1-1"/></svg>"""
                } else {
                    headerIcon.innerHTML = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><rect x="1" y="2" width="14" height="12" rx="1.5"/><polyline points="4,7 6,5 4,3"/><line x1="7" y1="7" x2="11" y2="7" stroke-linecap="round"/></svg>"""
                }
                val titleEl = header.querySelector(".terminal-title") as? HTMLElement
                if (titleEl != null) header.insertBefore(headerIcon, titleEl)

                cell.appendChild(header)
                // Clicking the titlebar should make this pane active even
                // though the xterm textarea (which drives focusin) isn't
                // involved.
                header.addEventListener("mousedown", { _ -> markPaneFocused(cell) })
                val entry = ensureTerminal(paneId, sessionId)
                entry.term.options.fontSize = paneFontSize
                try { safeFit(entry.term, entry.fit) } catch (_: Throwable) {}
                cell.appendChild(entry.container)
            }
        }
        return cell
    }

    /**
     * Make a docked pane's header into an HTML5 drag source so the user can
     * drag it onto a tab button to move the pane between tabs. Floating
     * panes intentionally skip this — they get a custom mouse-driven drag
     * for in-tab repositioning instead, and would conflict with HTML5 drag
     * if both were enabled on the same header.
     *
     * The drag carries a custom `application/x-termtastic-pane` mime type so
     * tab-button drop handlers can distinguish a pane drop from the
     * pre-existing tab-reorder drag (which uses `text/plain`).
     */
    fun attachPaneTabDrag(cell: HTMLElement, paneId: String) {
        val header = cell.querySelector(".terminal-header") as? HTMLElement ?: return
        header.setAttribute("draggable", "true")
        header.addEventListener("dragstart", { ev ->
            // Don't initiate a drag from the ⋯ menu button. The title is
            // a fine grab handle now that rename is on hover-delay/dblclick
            // rather than click.
            val target = ev.target
            if (target != null) {
                val targetEl = target as HTMLElement
                if (targetEl.closest(".pane-actions") != null) {
                    ev.preventDefault()
                    return@addEventListener
                }
            }
            val dt = ev.asDynamic().dataTransfer ?: return@addEventListener
            dt.effectAllowed = "move"
            dt.setData("application/x-termtastic-pane", paneId)
            // Some browsers require a text/plain payload too for the drag
            // to fire dragover/drop on other elements at all. Use a sentinel
            // string so a tab-bar drop handler that only sees text/plain
            // can still tell this isn't a tab-id payload.
            dt.setData("text/plain", "pane:$paneId")
            cell.classList.add("pane-dragging")
        })
        header.addEventListener("dragend", { _ ->
            cell.classList.remove("pane-dragging")
            // Clear any leftover drop highlight on tab buttons.
            val highlighted = document.querySelectorAll(".tab-button.drop-pane")
            for (i in 0 until highlighted.length) {
                (highlighted.item(i) as HTMLElement).classList.remove("drop-pane")
            }
        })
    }

    fun buildNode(node: dynamic): HTMLElement {
        if (node.kind == "leaf") {
            val cell = buildLeafCell(node)
            attachPaneTabDrag(cell, node.id as String)
            return cell
        } else {
            val splitId = node.id as String
            val orientation = node.orientation as String
            val isHorizontal = orientation == "Horizontal"
            val ratio = (node.ratio as Number).toDouble()
            val container = document.createElement("div") as HTMLElement
            val orientationClass = if (isHorizontal) "split-horizontal" else "split-vertical"
            container.className = "split $orientationClass"
            container.setAttribute("data-split", splitId)

            val firstWrap = document.createElement("div") as HTMLElement
            firstWrap.className = "split-child"
            firstWrap.style.flex = "$ratio $ratio 0%"
            firstWrap.appendChild(buildNode(node.first))

            val secondWrap = document.createElement("div") as HTMLElement
            secondWrap.className = "split-child"
            val secondRatio = 1.0 - ratio
            secondWrap.style.flex = "$secondRatio $secondRatio 0%"
            secondWrap.appendChild(buildNode(node.second))

            val divider = document.createElement("div") as HTMLElement
            val dividerOrientationClass =
                if (isHorizontal) "split-divider-horizontal" else "split-divider-vertical"
            divider.className = "split-divider $dividerOrientationClass"
            divider.setAttribute("data-split-divider", splitId)
            attachDividerDrag(divider, container, firstWrap, secondWrap, splitId, isHorizontal)

            container.appendChild(firstWrap)
            container.appendChild(divider)
            container.appendChild(secondWrap)
            return container
        }
    }

    fun collectSessionIds(node: dynamic, into: HashSet<String>) {
        if (node.kind == "leaf") {
            into.add(node.sessionId as String)
        } else {
            collectSessionIds(node.first, into)
            collectSessionIds(node.second, into)
        }
    }

    fun collectPaneIds(node: dynamic, into: HashSet<String>) {
        if (node.kind == "leaf") {
            into.add(node.id as String)
        } else {
            collectPaneIds(node.first, into)
            collectPaneIds(node.second, into)
        }
    }

    /**
     * Centered placeholder shown when a tab has no panes (no docked tree, no
     * floating panes). Clicking the button asks the server to spawn a fresh
     * shell as the tab's first pane.
     */
    fun buildEmptyTabPlaceholder(tabId: String): HTMLElement {
        val wrap = document.createElement("div") as HTMLElement
        wrap.className = "empty-tab"
        val message = document.createElement("div") as HTMLElement
        message.className = "empty-tab-message"
        message.textContent = "This tab has no panes."
        val btn = document.createElement("button") as HTMLElement
        btn.className = "empty-tab-button"
        btn.setAttribute("type", "button")
        btn.textContent = "New pane"
        btn.addEventListener("click", { ev: Event ->
            ev.stopPropagation()
            showPaneTypeModal(emptyTabId = tabId)
        })
        wrap.appendChild(message)
        wrap.appendChild(btn)
        return wrap
    }

    /**
     * Build one floating-pane DOM element. The returned `.floating-pane` is
     * absolutely positioned within its `.floating-layer` parent using
     * percentage-based left/top/width/height — that way the float keeps its
     * relative geometry across browser-window resizes for free.
     *
     * Header drag moves the float; the bottom-right corner handle resizes
     * it. Both fire a `setFloatingGeom` command on mouseup so the server
     * (and therefore persistence) catches up.
     */
    fun buildFloatingPane(floater: dynamic, tabSection: HTMLElement): HTMLElement {
        val leaf = floater.leaf
        val paneId = leaf.id as String
        val x = (floater.x as Number).toDouble()
        val y = (floater.y as Number).toDouble()
        val w = (floater.width as Number).toDouble()
        val h = (floater.height as Number).toDouble()

        val pane = document.createElement("div") as HTMLElement
        pane.className = "floating-pane"
        pane.setAttribute("data-pane", paneId)
        pane.style.left = "${x * 100}%"
        pane.style.top = "${y * 100}%"
        pane.style.width = "${w * 100}%"
        pane.style.height = "${h * 100}%"

        val cell = buildLeafCell(leaf)
        pane.appendChild(cell)

        // Bring to front on mousedown anywhere in the floater (capture phase
        // so it fires even before the header-drag handler). Don't fire if it
        // is already on top — the server is the source of truth and will
        // squelch a no-op raise, but skipping the websocket round-trip keeps
        // the UI snappy when you're just clicking inside the pane.
        pane.addEventListener("mousedown", { _ ->
            sendCmd(WindowCommand.RaiseFloating(paneId = paneId))
        })

        val header = cell.querySelector(".terminal-header") as? HTMLElement
        if (header != null) {
            header.classList.add("floating-header")
            header.addEventListener("mousedown", drag@ { ev ->
                val mouse = ev as MouseEvent
                if (mouse.button.toInt() != 0) return@drag
                // Don't start a drag from the ⋯ menu button. The title is
                // a valid grab handle (rename now triggers on hover-delay
                // / double-click, not on plain click).
                val target = ev.target
                if (target != null) {
                    val targetEl = target as HTMLElement
                    if (targetEl.closest(".pane-actions") != null) return@drag
                }
                mouse.preventDefault()

                val sectionRect = tabSection.asDynamic().getBoundingClientRect()
                val sectionWidth = (sectionRect.width as Double)
                val sectionHeight = (sectionRect.height as Double)
                if (sectionWidth <= 0.0 || sectionHeight <= 0.0) return@drag

                // Anchor at the cursor's offset within the pane so the float
                // doesn't jump to align its top-left with the cursor.
                val paneRect = pane.asDynamic().getBoundingClientRect()
                val grabDx = mouse.clientX.toDouble() - (paneRect.left as Double)
                val grabDy = mouse.clientY.toDouble() - (paneRect.top as Double)
                val paneWidth = (paneRect.width as Double)
                val paneHeight = (paneRect.height as Double)

                pane.classList.add("dragging")
                val previousBodyCursor = document.body?.style?.cursor ?: ""
                document.body?.style?.cursor = "grabbing"

                var latestX = x
                var latestY = y

                val moveListener: (Event) -> Unit = { evMove ->
                    val m = evMove as MouseEvent
                    val absLeft = m.clientX.toDouble() - grabDx - (sectionRect.left as Double)
                    val absTop = m.clientY.toDouble() - grabDy - (sectionRect.top as Double)
                    var fx = absLeft / sectionWidth
                    var fy = absTop / sectionHeight
                    val maxX = 1.0 - (paneWidth / sectionWidth)
                    val maxY = 1.0 - (paneHeight / sectionHeight)
                    if (fx < 0.0) fx = 0.0
                    if (fy < 0.0) fy = 0.0
                    if (fx > maxX) fx = maxX
                    if (fy > maxY) fy = maxY
                    latestX = fx
                    latestY = fy
                    pane.style.left = "${fx * 100}%"
                    pane.style.top = "${fy * 100}%"
                }
                lateinit var upListener: (Event) -> Unit
                upListener = { _ ->
                    document.removeEventListener("mousemove", moveListener)
                    document.removeEventListener("mouseup", upListener)
                    pane.classList.remove("dragging")
                    document.body?.style?.cursor = previousBodyCursor
                    val curW = paneWidth / sectionWidth
                    val curH = paneHeight / sectionHeight
                    sendWindowCommand(
                        windowJson.encodeToString<WindowCommand>(
                            WindowCommand.SetFloatingGeom(
                                paneId = paneId,
                                x = latestX,
                                y = latestY,
                                width = curW,
                                height = curH,
                            )
                        )
                    )
                }
                document.addEventListener("mousemove", moveListener)
                document.addEventListener("mouseup", upListener)
            })
        }

        // Bottom-right resize grip.
        val grip = document.createElement("div") as HTMLElement
        grip.className = "floating-resize-handle"
        grip.addEventListener("mousedown", resize@ { ev ->
            val mouse = ev as MouseEvent
            if (mouse.button.toInt() != 0) return@resize
            mouse.preventDefault()
            mouse.stopPropagation()

            val sectionRect = tabSection.asDynamic().getBoundingClientRect()
            val sectionWidth = (sectionRect.width as Double)
            val sectionHeight = (sectionRect.height as Double)
            if (sectionWidth <= 0.0 || sectionHeight <= 0.0) return@resize

            val paneRect = pane.asDynamic().getBoundingClientRect()
            val originX = (paneRect.left as Double) - (sectionRect.left as Double)
            val originY = (paneRect.top as Double) - (sectionRect.top as Double)
            val fxOrigin = originX / sectionWidth
            val fyOrigin = originY / sectionHeight

            pane.classList.add("dragging")
            val previousBodyCursor = document.body?.style?.cursor ?: ""
            document.body?.style?.cursor = "nwse-resize"

            var latestW = w
            var latestH = h

            val moveListener: (Event) -> Unit = { evMove ->
                val m = evMove as MouseEvent
                val rightAbs = m.clientX.toDouble() - (sectionRect.left as Double)
                val bottomAbs = m.clientY.toDouble() - (sectionRect.top as Double)
                var fw = (rightAbs / sectionWidth) - fxOrigin
                var fh = (bottomAbs / sectionHeight) - fyOrigin
                val maxW = 1.0 - fxOrigin
                val maxH = 1.0 - fyOrigin
                if (fw < 0.05) fw = 0.05
                if (fh < 0.05) fh = 0.05
                if (fw > maxW) fw = maxW
                if (fh > maxH) fh = maxH
                latestW = fw
                latestH = fh
                pane.style.width = "${fw * 100}%"
                pane.style.height = "${fh * 100}%"
            }
            lateinit var upListener: (Event) -> Unit
            upListener = { _ ->
                document.removeEventListener("mousemove", moveListener)
                document.removeEventListener("mouseup", upListener)
                pane.classList.remove("dragging")
                document.body?.style?.cursor = previousBodyCursor
                sendWindowCommand(
                    windowJson.encodeToString<WindowCommand>(
                        WindowCommand.SetFloatingGeom(
                            paneId = paneId,
                            x = fxOrigin,
                            y = fyOrigin,
                            width = latestW,
                            height = latestH,
                        )
                    )
                )
            }
            document.addEventListener("mousemove", moveListener)
            document.addEventListener("mouseup", upListener)
        })
        pane.appendChild(grip)

        return pane
    }

    // Dispatch a markdown/git content message into the matching pane view.
    // Shared between the main window's `/window` handler and the popout
    // window's handler so markdown/git panes render the same way in both.
    // Returns true if the message was recognised, false otherwise.
    fun handlePaneContentMessage(type: String?, parsed: dynamic): Boolean {
        when (type) {
            "fileBrowserDir" -> {
                val paneId = parsed.paneId as String
                val dirRelPath = parsed.dirRelPath as String
                val state = fileBrowserPaneStates.getOrPut(paneId) { FileBrowserPaneState() }
                state.dirListings[dirRelPath] = parsed.entries as Array<dynamic>
                val view = fileBrowserPaneViews[paneId]
                if (view != null) renderFileBrowserTree(paneId, view, state)
            }
            "fileBrowserContent" -> {
                val paneId = parsed.paneId as String
                val relPath = parsed.relPath as String
                val html = parsed.html as String
                val kind = parsed.kind as String
                val state = fileBrowserPaneStates.getOrPut(paneId) { FileBrowserPaneState() }
                state.selectedRelPath = relPath
                state.html = html
                state.kind = kind
                val view = fileBrowserPaneViews[paneId]
                if (view != null) {
                    renderFileBrowserContent(view, kind, html)
                    renderFileBrowserTree(paneId, view, state)
                }
            }
            "fileBrowserError" -> {
                val paneId = parsed.paneId as String
                val message = parsed.message as String
                val state = fileBrowserPaneStates[paneId]
                if (state != null) {
                    state.selectedRelPath = null
                    state.html = null
                    state.kind = null
                }
                val view = fileBrowserPaneViews[paneId]
                if (view != null) {
                    val err = document.createElement("div") as HTMLElement
                    err.className = "md-error"
                    err.textContent = message
                    view.rendered.innerHTML = ""
                    view.rendered.appendChild(err)
                    renderFileBrowserTree(paneId, view, state ?: FileBrowserPaneState())
                }
            }
            "gitList" -> {
                val paneId = parsed.paneId as String
                val state = gitPaneStates.getOrPut(paneId) { GitPaneState() }
                state.entries = parsed.entries
                val view = gitPaneViews[paneId]
                if (view != null) renderGitList(paneId, view, state)
            }
            "gitDiff" -> {
                val paneId = parsed.paneId as String
                val filePath = parsed.filePath as String
                val state = gitPaneStates.getOrPut(paneId) { GitPaneState() }
                state.selectedFilePath = filePath
                val view = gitPaneViews[paneId]
                if (view != null) {
                    val hasOld = parsed.oldContent as? String != null
                    val hasNew = parsed.newContent as? String != null
                    val oneSided = !hasOld || !hasNew
                    if (!oneSided && state.diffMode == "Split" && state.graphicalDiff) {
                        renderGitDiffGraphical(view.diffPane, parsed, state)
                    } else if (!oneSided && state.diffMode == "Split") {
                        renderGitDiffSplit(view.diffPane, parsed)
                    } else {
                        renderGitDiffInline(view.diffPane, parsed)
                    }
                    if (state.searchQuery.isNotEmpty() && view.searchCounter != null) {
                        state.searchMatchIndex = 0
                        performDiffSearch(view.diffPane, state.searchQuery, state, view.searchCounter!!, view.searchNavButtons)
                    }
                    state.diffHtml = if (state.diffMode == "Split" && state.graphicalDiff) null
                        else view.diffPane.innerHTML
                    updateGitListActiveFile(view, filePath)
                }
            }
            "gitError" -> {
                val paneId = parsed.paneId as String
                val message = parsed.message as String
                val state = gitPaneStates[paneId]
                if (state != null) {
                    state.selectedFilePath = null
                    state.diffHtml = null
                }
                val view = gitPaneViews[paneId]
                if (view != null) {
                    val err = document.createElement("div") as HTMLElement
                    err.className = "git-error"
                    err.textContent = message
                    view.diffPane.innerHTML = ""
                    view.diffPane.appendChild(err)
                    renderGitList(paneId, view, state ?: GitPaneState())
                }
            }
            else -> return false
        }
        return true
    }

    // ---------------------------------------------------------------------
    // Popout mode: this renderer instance was opened via Electron's
    // popOutPane IPC and only shows a single pane (title + menu + terminal)
    // with no tabs, sidebar, or app chrome. We still talk to the same
    // `/window` WebSocket so we can track title/state changes and auto-close
    // this window when the pane is removed server-side.
    // ---------------------------------------------------------------------
    if (isPopoutMode) {
        val paneId = popoutPaneIdParam

        // The `popout-mode` body class (added earlier) hides the app header,
        // tab bar, sidebar, and sidebar divider via CSS. We just need to
        // wipe the terminal wrap; the actual cell is built from the first
        // config push (so we can dispatch on the leaf's content kind).
        val wrap = document.getElementById("terminal-wrap") as? HTMLElement
        wrap?.innerHTML = ""

        // Placeholder shown until the first config push arrives. Then we
        // swap it out for a real cell built via `buildLeafCell`, which
        // already knows how to render terminal / markdown / git panes.
        val placeholder = document.createElement("div") as HTMLElement
        placeholder.className = "popout-placeholder"
        placeholder.textContent = "Loading…"
        (wrap ?: document.body as HTMLElement).appendChild(placeholder)

        var cell: HTMLElement? = null
        var sessionId: String? = null

        // Track whether the pane has been seen as popped-out at least once.
        // We need this because the very first config push may arrive before
        // the server has processed our popOut command — the pane would still
        // be in the tree or floating layer. We tolerate that for the initial
        // render, but once we've seen the pane in `poppedOut`, any future
        // config that doesn't have it there means the pane was docked back
        // (via main window Dock, close, or rehydrate) and this popout
        // window should close.
        var seenAsPopped = false

        // Look for the pane in the server config. Returns a triple of
        // (leaf, isPopped) or null if not found anywhere. Preference order:
        // poppedOut > floating > tree (so we correctly recognize the final
        // state even if the tree briefly holds a stale copy).
        fun findPaneInConfig(cfg: dynamic): Pair<dynamic, Boolean>? {
            val tabsArr = cfg.tabs as? Array<dynamic> ?: return null
            // First pass: poppedOut list (authoritative once server has
            // processed the popOut command).
            for (tab in tabsArr) {
                val popped = tab.poppedOut as? Array<dynamic> ?: emptyArray()
                for (po in popped) {
                    if ((po.leaf.id as String) == paneId) return Pair(po.leaf, true)
                }
            }
            // Second pass: tree / floating. Only relevant before popOut has
            // been processed by the server on first open.
            fun walkTree(node: dynamic): dynamic? {
                if (node == null) return null
                if (node.kind == "leaf") {
                    return if ((node.id as String) == paneId) node else null
                }
                return walkTree(node.first) ?: walkTree(node.second)
            }
            for (tab in tabsArr) {
                val treeHit = walkTree(tab.root)
                if (treeHit != null) return Pair(treeHit, false)
                val floats = tab.floating as? Array<dynamic> ?: emptyArray()
                for (fp in floats) {
                    if ((fp.leaf.id as String) == paneId) return Pair(fp.leaf, false)
                }
            }
            return null
        }

        fun connectPopoutWindow() {
            console.log("[popout] connecting WS for paneId=$paneId")
            val ws = WebSocket("$proto://${window.location.host}/window?$authQueryParam")
            windowSocket = ws
            ws.onopen = { _ -> console.log("[popout] WS open") }
            ws.onmessage = { event ->
                val data = event.asDynamic().data
                if (data is String) {
                    val parsed = js("JSON.parse(data)")
                    if (parsed != null) {
                        val msgType = parsed.type as? String
                        console.log("[popout] WS message type=", msgType)
                        when (msgType) {
                            "pendingApproval" -> {
                                console.log("[popout] pendingApproval — showing overlay")
                                showPendingApprovalOverlay()
                            }
                            "config" -> {
                                hidePendingApprovalOverlay()
                                val hit = findPaneInConfig(parsed.config)
                                val leaf = hit?.first
                                val isPopped = hit?.second == true
                                console.log("[popout] config: hit=", hit != null, " isPopped=", isPopped, " paneId=$paneId")
                                if (leaf != null) {
                                    // Lazily build the pane on first sighting.
                                    // We dispatch by content kind via the
                                    // shared `buildLeafCell` so terminal,
                                    // markdown, and git panes all just work.
                                    if (cell == null) {
                                        val kind = (leaf.content?.kind as? String) ?: "terminal"
                                        console.log("[popout] building cell, kind=$kind")
                                        val built = try {
                                            buildLeafCell(leaf, popoutMode = true)
                                        } catch (t: Throwable) {
                                            console.error("[popout] buildLeafCell threw", t)
                                            throw t
                                        }
                                        built.classList.add("popout-cell")
                                        cell = built
                                        sessionId = (leaf.content?.sessionId as? String)
                                            ?: (leaf.sessionId as? String)
                                        placeholder.remove()
                                        (wrap ?: document.body as HTMLElement).appendChild(built)
                                        console.log("[popout] cell appended")
                                    }
                                    if (isPopped) {
                                        seenAsPopped = true
                                        val newTitle = leaf.title as? String
                                        if (newTitle != null) {
                                            val titleEl = cell?.querySelector(".terminal-title") as? HTMLElement
                                            if (titleEl != null && titleEl.textContent != newTitle) {
                                                titleEl.textContent = newTitle
                                            }
                                            document.title = newTitle
                                        }
                                    } else if (seenAsPopped) {
                                        // Pane was docked back into the main
                                        // window (or rehydrated on server
                                        // restart). Close this popout.
                                        window.close()
                                    }
                                    // else: first config push before popOut
                                    // was processed. Tolerate briefly.
                                } else if (seenAsPopped) {
                                    // Pane was closed outright. Close popout.
                                    window.close()
                                }
                            }
                            "uiSettings" -> {
                                applyServerUiSettings(parsed.settings)
                            }
                            "state" -> {
                                // Update the state dot for our single session
                                // (terminal panes only — markdown/git panes
                                // have no session and no dot).
                                val sid = sessionId
                                val c = cell
                                if (sid != null && c != null) {
                                    val statesObj = parsed.states
                                    val state = statesObj[sid]
                                    val dot = c.querySelector(
                                        ".pane-state-dot[data-session='$sid']"
                                    ) as? HTMLElement
                                    if (dot != null) {
                                        val base = "pane-state-dot"
                                        dot.className = if (state != null && state != undefined)
                                            "$base state-$state"
                                        else base
                                    }
                                }
                            }
                            else -> {
                                // Markdown and git panes need their list /
                                // content / error messages forwarded into the
                                // pane view just like in the main window.
                                handlePaneContentMessage(parsed.type as? String, parsed)
                            }
                        }
                    }
                }
            }
            ws.onclose = { event ->
                windowSocket = null
                val code = (event.asDynamic().code as? Number)?.toInt() ?: 0
                val reason = (event.asDynamic().reason as? String) ?: ""
                console.log("[popout] WS closed code=$code reason=$reason")
                if (code == 1008) {
                    showDeviceRejectedOverlay(code, reason)
                } else {
                    window.setTimeout({ connectPopoutWindow() }, 500)
                }
            }
            ws.onerror = { e ->
                console.error("[popout] WS error", e)
                ws.close()
            }
        }
        connectPopoutWindow()

        // Resize the embedded terminal on window resize.
        window.addEventListener("resize", { fitVisible() })
        return
    }

    val tabBarEl = document.getElementById("tab-bar") as HTMLElement
    val terminalWrapEl = document.getElementById("terminal-wrap") as HTMLElement
    val sidebarEl = document.getElementById("sidebar") as HTMLElement
    val sidebarDividerEl = document.getElementById("sidebar-divider") as HTMLElement

    val collapsedTabs = HashSet<String>()
    // Forward-declared — real impl is wired once setActiveTab exists.
    var switchToTab: (String) -> Unit = { _ -> }

    // Sidebar resize via divider drag. The user hides the sidebar by
    // dragging it to zero width; no separate collapse toggle.
    run {
        var dragging = false
        var startX = 0.0
        var startWidth = 0.0
        sidebarDividerEl.addEventListener("mousedown", { ev ->
            ev.preventDefault()
            dragging = true
            startX = (ev as MouseEvent).clientX.toDouble()
            startWidth = sidebarEl.getBoundingClientRect().width
            sidebarDividerEl.classList.add("dragging")
            sidebarEl.style.transition = "none"
            document.body?.style?.cursor = "col-resize"
            // Disable text selection & pointer events on iframes during drag
            document.body?.style?.setProperty("user-select", "none")
        })
        document.addEventListener("mousemove", { ev ->
            if (!dragging) return@addEventListener
            val dx = (ev as MouseEvent).clientX.toDouble() - startX
            val newWidth = (startWidth + dx).coerceIn(0.0, 400.0)
            sidebarEl.style.width = "${newWidth}px"
        })
        document.addEventListener("mouseup", { _ ->
            if (!dragging) return@addEventListener
            dragging = false
            sidebarDividerEl.classList.remove("dragging")
            sidebarEl.style.transition = ""
            document.body?.style?.cursor = ""
            document.body?.style?.removeProperty("user-select")
            // Persist the final width and update toggle restore target.
            val w = sidebarEl.getBoundingClientRect().width
            if (w > 0) sidebarOpenWidth = w.toInt()
            putSetting("sidebarWidth", w.toInt())
            // Terminals refit via ResizeObserver, but give a manual nudge.
            fitVisible()
        })
    }

    // Sidebar toggle button — animates the left sidebar open/closed.
    run {
        val toggleBtn = document.getElementById("sidebar-toggle") as? HTMLElement
        toggleBtn?.addEventListener("click", { ev ->
            ev.stopPropagation()
            val currentWidth = sidebarEl.getBoundingClientRect().width.toInt()
            if (currentWidth > 10) {
                // Remember the width before collapsing so we can restore it.
                sidebarOpenWidth = currentWidth
                sidebarEl.style.width = "0px"
                putSetting("sidebarWidth", 0)
            } else {
                sidebarEl.style.width = "${sidebarOpenWidth}px"
                putSetting("sidebarWidth", sidebarOpenWidth)
            }
            // After the CSS transition finishes, nudge terminal sizing.
            sidebarEl.addEventListener("transitionend", {
                fitVisible()
            }, js("({once:true})"))
        })
    }

    /** Collect all leaf pane ids and titles from a PaneNode tree. */
    data class LeafInfo(val paneId: String, val title: String, val sessionId: String, val contentKind: String, val isLink: Boolean)

    fun collectLeaves(node: dynamic, into: MutableList<LeafInfo>) {
        if (node.kind == "leaf") {
            val kind = (node.content?.kind as? String) ?: "terminal"
            val link = node.isLink as? Boolean ?: false
            into.add(LeafInfo(node.id as String, node.title as String, node.sessionId as String, kind, link))
        } else {
            collectLeaves(node.first, into)
            collectLeaves(node.second, into)
        }
    }

    fun renderSidebar(config: dynamic) {
        sidebarEl.innerHTML = ""
        val tree = document.createElement("div") as HTMLElement
        tree.className = "sidebar-tree"

        val tabsArr = config.tabs as? Array<dynamic> ?: return

        for (tab in tabsArr) {
            val tabId = tab.id as String
            val tabTitle = tab.title as String
            val isActiveTab = tabId == activeTabId

            // Tab header row
            val header = document.createElement("div") as HTMLElement
            header.className = "sidebar-tab-header"
            if (isActiveTab) header.classList.add("active-tab")
            if (tabId in collapsedTabs) header.classList.add("collapsed-tree")

            val chevron = document.createElement("span") as HTMLElement
            chevron.className = "sidebar-tab-chevron"
            chevron.textContent = "\u25BE" // ▾
            header.appendChild(chevron)

            val titleSpan = document.createElement("span") as HTMLElement
            titleSpan.textContent = tabTitle
            titleSpan.style.asDynamic().overflow = "hidden"
            titleSpan.style.asDynamic().textOverflow = "ellipsis"
            titleSpan.style.asDynamic().whiteSpace = "nowrap"
            titleSpan.style.asDynamic().minWidth = "0"
            titleSpan.style.asDynamic().direction = "rtl"
            titleSpan.style.asDynamic().textAlign = "left"
            header.appendChild(titleSpan)

            val sidebarTabDot = document.createElement("span") as HTMLElement
            sidebarTabDot.className = "pane-state-dot sidebar-state-dot"
            sidebarTabDot.setAttribute("data-tab-state", tabId)
            header.appendChild(sidebarTabDot)

            header.addEventListener("click", { _ ->
                if (tabId in collapsedTabs) {
                    collapsedTabs.remove(tabId)
                    header.classList.remove("collapsed-tree")
                    val children = header.nextElementSibling as? HTMLElement
                    children?.classList?.remove("collapsed-tree")
                    children?.let { it.style.maxHeight = "${it.scrollHeight}px" }
                } else {
                    collapsedTabs.add(tabId)
                    header.classList.add("collapsed-tree")
                    val children = header.nextElementSibling as? HTMLElement
                    children?.classList?.add("collapsed-tree")
                }
            })
            tree.appendChild(header)

            // Collect all panes (docked + floating)
            val leaves = mutableListOf<LeafInfo>()
            val floatingIds = HashSet<String>()
            if (tab.root != null) collectLeaves(tab.root, leaves)
            val floats = (tab.floating as? Array<dynamic>) ?: emptyArray()
            for (fp in floats) {
                val leaf = fp.leaf
                val kind = (leaf.content?.kind as? String) ?: "terminal"
                val link = leaf.isLink as? Boolean ?: false
                leaves.add(LeafInfo(leaf.id as String, leaf.title as String, leaf.sessionId as String, kind, link))
                floatingIds.add(leaf.id as String)
            }

            // Children container
            val children = document.createElement("div") as HTMLElement
            children.className = "sidebar-tab-children"
            if (tabId in collapsedTabs) {
                children.classList.add("collapsed-tree")
            }

            for ((paneId, paneTitle, sId, contentKind, isLinked) in leaves) {
                val item = document.createElement("div") as HTMLElement
                item.className = "sidebar-pane-item"
                item.setAttribute("data-pane", paneId)
                if (paneId in floatingIds) item.classList.add("floating")

                // Highlight the focused pane in the active tab
                if (isActiveTab) {
                    val focusedId = tab.focusedPaneId as? String
                    if (focusedId == paneId) item.classList.add("active-pane")
                }

                // Pane icon (small SVG) — varies by content kind
                val icon = document.createElement("span") as HTMLElement
                icon.className = "sidebar-pane-icon"
                icon.innerHTML = if (contentKind == "fileBrowser") {
                    """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><path d="M2 4.5 a0.5 0.5 0 0 1 0.5 -0.5 h3.5 l1.25 1.75 h6.25 a0.5 0.5 0 0 1 0.5 0.5 v7.25 a0.5 0.5 0 0 1 -0.5 0.5 H2.5 a0.5 0.5 0 0 1 -0.5 -0.5 Z"/></svg>"""
                } else if (contentKind == "git") {
                    """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><circle cx="5" cy="4" r="1.5"/><circle cx="5" cy="12" r="1.5"/><circle cx="11" cy="8" r="1.5"/><line x1="5" y1="5.5" x2="5" y2="10.5"/><path d="M5 5.5 C5 8 8 8 9.5 8"/></svg>"""
                } else if (isLinked) {
                    // Linked terminal: chain/link icon
                    """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><path d="M6.5 9.5a3 3 0 0 0 4.24 0l2.5-2.5a3 3 0 0 0-4.24-4.24L7.5 3.76"/><path d="M9.5 6.5a3 3 0 0 0-4.24 0l-2.5 2.5a3 3 0 0 0 4.24 4.24l1-1"/></svg>"""
                } else if (paneId in floatingIds) {
                    """<svg viewBox="0 0 16 16" fill="currentColor" width="14" height="14"><rect x="2" y="1" width="12" height="10" rx="1.5" fill="none" stroke="currentColor" stroke-width="1.3"/><line x1="4" y1="8" x2="10" y2="8" stroke="currentColor" stroke-width="1.2"/><rect x="4" y="13" width="8" height="1.2" rx="0.6" fill="currentColor" opacity="0.5"/></svg>"""
                } else {
                    """<svg viewBox="0 0 16 16" fill="currentColor" width="14" height="14"><rect x="1" y="2" width="14" height="12" rx="1.5" fill="none" stroke="currentColor" stroke-width="1.3"/><polyline points="4,7 6,5 4,3" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/><line x1="7" y1="7" x2="11" y2="7" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/></svg>"""
                }
                item.appendChild(icon)

                val sidebarDot = document.createElement("span") as HTMLElement
                sidebarDot.className = "pane-state-dot sidebar-state-dot"
                sidebarDot.setAttribute("data-session", sId)
                item.appendChild(sidebarDot)

                val label = document.createElement("span") as HTMLElement
                label.textContent = paneTitle
                label.style.asDynamic().overflow = "hidden"
                label.style.asDynamic().textOverflow = "ellipsis"
                label.style.asDynamic().whiteSpace = "nowrap"
                label.style.asDynamic().minWidth = "0"
                label.style.asDynamic().direction = "rtl"
                label.style.asDynamic().textAlign = "left"
                item.appendChild(label)

                item.addEventListener("click", { ev ->
                    ev.stopPropagation()
                    // If a different pane is maximized in this tab, restore it
                    // so the clicked pane becomes visible.
                    val curMax = maximizedPaneIds[tabId]
                    if (curMax != null && curMax != paneId) {
                        restorePane(tabId, animate = false)
                    }
                    val needsTabSwitch = activeTabId != tabId
                    if (needsTabSwitch) switchToTab(tabId)
                    // Focus the pane — delay slightly if we switched tabs so
                    // the cross-fade has started and the pane is visible.
                    fun doFocus() {
                        val cell = terminalWrapEl.querySelector("[data-pane=\"$paneId\"]") as? HTMLElement
                        val entry = terminals[paneId]
                        if (entry != null) {
                            // Terminal pane: xterm focus fires `focusin`,
                            // which routes through markPaneFocused.
                            entry.term.focus()
                        } else if (cell != null) {
                            // Non-terminal pane (markdown) has no
                            // textarea, so mark it directly.
                            markPaneFocused(cell)
                        }
                    }
                    if (needsTabSwitch) window.setTimeout({ doFocus() }, 50) else doFocus()
                })
                children.appendChild(item)
            }

            // Set initial max-height for non-collapsed tabs so the CSS
            // transition works. Use a large sentinel value since we can't
            // measure scrollHeight before appending to the DOM.
            if (tabId !in collapsedTabs) {
                children.style.maxHeight = "9999px"
            }

            tree.appendChild(children)
        }
        sidebarEl.appendChild(tree)
    }

    // Boot fade: start the wrap and header in their hidden state, then drop
    // the .booting class once the first config has been rendered (see end of
    // renderConfig). The CSS handles the fade itself.
    terminalWrapEl.classList.add("booting")
    (document.querySelector(".app-header") as? HTMLElement)?.classList?.add("booting")

    // Translate vertical mouse-wheel scrolls over the tab bar into horizontal
    // scroll, so a normal mouse can scroll through tabs without holding shift.
    // Trackpads already emit a deltaX and are unaffected.
    tabBarEl.addEventListener("wheel", { ev ->
        val we = ev.asDynamic()
        val dy = (we.deltaY as? Number)?.toDouble() ?: 0.0
        val dx = (we.deltaX as? Number)?.toDouble() ?: 0.0
        if (dy != 0.0 && dx == 0.0) {
            tabBarEl.scrollLeft += dy
            ev.preventDefault()
        }
    })

    /** Scroll the active tab button into the visible portion of the bar. */
    fun scrollActiveTabIntoView() {
        val active = tabBarEl.querySelector(".tab-button.active") as? HTMLElement ?: return
        val barRect = tabBarEl.asDynamic().getBoundingClientRect()
        val btnRect = active.asDynamic().getBoundingClientRect()
        val barLeft = (barRect.left as Double)
        val barRight = (barRect.right as Double)
        val btnLeft = (btnRect.left as Double)
        val btnRight = (btnRect.right as Double)
        if (btnLeft < barLeft) {
            tabBarEl.scrollLeft -= (barLeft - btnLeft) + 8
        } else if (btnRight > barRight) {
            tabBarEl.scrollLeft += (btnRight - barRight) + 8
        }
    }

    /**
     * Update the inline transform/size of the sliding orange ring so it
     * overlays the currently active tab button. The ring is always present
     * inside .tab-bar (added in index.html); we only mutate its position.
     * Called from setActiveTab, renderConfig, drag-reorder FLIP, and the
     * window resize handler.
     */
    fun positionActiveIndicator() {
        val indicator = tabBarEl.querySelector(".tab-active-indicator") as? HTMLElement ?: return
        val active = tabBarEl.querySelector(".tab-button.active") as? HTMLElement
        if (active == null) {
            indicator.classList.remove("ready")
            return
        }
        // offsetLeft/offsetTop are relative to the nearest positioned ancestor
        // (the tab bar, since we set position:relative on it). They live in
        // the same coordinate system as the tab buttons themselves, so the
        // ring scrolls with the bar's overflow naturally.
        val left = (active.asDynamic().offsetLeft as Number).toDouble()
        val top = (active.asDynamic().offsetTop as Number).toDouble()
        val width = (active.asDynamic().offsetWidth as Number).toDouble()
        val height = (active.asDynamic().offsetHeight as Number).toDouble()
        indicator.style.transform = "translate3d(${left}px, 0, 0)"
        indicator.style.top = "${top}px"
        indicator.style.width = "${width}px"
        indicator.style.height = "${height}px"
        indicator.classList.add("ready")
    }

    /**
     * Like positionActiveIndicator, but suppresses the slide transition for
     * a single frame so the ring snaps into place. Used after a full
     * tab-bar rebuild where the previous ring position is meaningless.
     */
    fun snapActiveIndicator() {
        val indicator = tabBarEl.querySelector(".tab-active-indicator") as? HTMLElement ?: return
        indicator.classList.add("no-transition")
        positionActiveIndicator()
        // Force a layout flush so the no-transition style applies before we
        // remove it on the next frame.
        @Suppress("UNUSED_VARIABLE")
        val _force = indicator.asDynamic().offsetWidth
        window.requestAnimationFrame {
            indicator.classList.remove("no-transition")
        }
    }

    /**
     * Look up the persisted `focusedPaneId` for [tabId] in the current
     * config (server is the source of truth). Returns null if the config
     * isn't loaded yet, the tab is gone, or no pane has been focused there.
     */
    fun savedFocusedPaneId(tabId: String): String? {
        val cfg = currentConfig ?: return null
        val tabsArr = cfg.tabs as? Array<dynamic> ?: return null
        for (tab in tabsArr) {
            if ((tab.id as? String) == tabId) {
                return tab.focusedPaneId as? String
            }
        }
        return null
    }

    /**
     * Focus the "right" terminal in the currently-active tab. If the server
     * remembers which pane was last focused there, restore that one;
     * otherwise fall back to the first terminal in DOM order (top-left for
     * a horizontal split, top for a vertical split). Used on startup, after
     * every tab switch, and after a fresh render. Returns true if any
     * terminal received focus.
     */
    fun focusFirstPaneInActiveTab(): Boolean {
        val activePane = terminalWrapEl.querySelector(".tab-pane.active") as? HTMLElement
            ?: return false
        // Try the persisted focus first.
        val activeId = activeTabId
        val rememberedPaneId = activeId?.let { savedFocusedPaneId(it) }
        if (rememberedPaneId != null) {
            val entry = terminals[rememberedPaneId]
            if (entry != null) {
                entry.term.focus()
                return true
            }
            // Non-terminal pane (markdown): mark it focused directly
            // since there's no xterm textarea to receive DOM focus.
            val cell = activePane.querySelector("[data-pane=\"$rememberedPaneId\"]") as? HTMLElement
            if (cell != null) {
                markPaneFocused(cell)
                return true
            }
        }
        // Fallback: first terminal pane in DOM order.
        val paneCells = activePane.querySelectorAll("[data-pane]")
        for (i in 0 until paneCells.length) {
            val cell = paneCells.item(i) as HTMLElement
            val pid = cell.getAttribute("data-pane") ?: continue
            val entry = terminals[pid] ?: continue
            entry.term.focus()
            return true
        }
        return false
    }

    fun setActiveTab(tabId: String) {
        if (activeTabId == tabId) return
        activeTabId = tabId
        // Persist on the server so the selection survives reloads/restarts.
        // Fire-and-forget — the server's debounced save will pick it up.
        sendCmd(WindowCommand.SetActiveTab(tabId = tabId))
        // Toggle tab-button active classes in place so the DOM nodes (and
        // their attached event listeners for dblclick rename) survive.
        val buttons = tabBarEl.querySelectorAll(".tab-button")
        for (i in 0 until buttons.length) {
            val btn = buttons.item(i) as HTMLElement
            val id = btn.getAttribute("data-tab")
            if (id == tabId) btn.classList.add("active") else btn.classList.remove("active")
        }
        val panes = terminalWrapEl.querySelectorAll(".tab-pane")
        for (i in 0 until panes.length) {
            val pane = panes.item(i) as HTMLElement
            if (pane.id == tabId) pane.classList.add("active") else pane.classList.remove("active")
        }
        scrollActiveTabIntoView()
        positionActiveIndicator()
        // Refit xterm AFTER the cross-fade has settled — the new tab pane is
        // position:absolute and only gains real layout dimensions once the
        // visibility transition kicks in. We listen for transitionend on the
        // pane, with a timeout fallback in case the event is missed (e.g.
        // user immediately switches to yet another tab).
        val newPane = terminalWrapEl.querySelector("#$tabId.tab-pane.active") as? HTMLElement
        var refit = false
        fun doRefit() {
            if (refit) return
            refit = true
            fitVisible()
            // Auto-focus the first pane in the newly-active tab so the user
            // can type immediately. Falls back to no-op if the tab has no
            // panes (empty placeholder).
            focusFirstPaneInActiveTab()
        }
        if (newPane != null) {
            lateinit var listener: (Event) -> Unit
            listener = { ev ->
                // Only react to the pane's own opacity transition, not bubbled
                // events from descendants (e.g. xterm canvas restyles).
                if (ev.target === newPane && (ev.asDynamic().propertyName as? String) == "opacity") {
                    newPane.removeEventListener("transitionend", listener)
                    doRefit()
                }
            }
            newPane.addEventListener("transitionend", listener)
        }
        window.setTimeout({ doRefit() }, 320)
    }

    // Now that setActiveTab is defined, wire up the sidebar's forward reference.
    switchToTab = { tabId -> setActiveTab(tabId) }

    // Tracks the set of tab IDs and pane IDs from the most recent render so
    // we can detect newcomers and apply the .entering animation only to
    // genuinely new buttons/cells (rather than every render).
    val previousTabIds = HashSet<String>()
    val previousPaneIds = HashSet<String>()
    // Snapshot of tab-button bounding rects captured immediately before a
    // tab-reorder mutation. Consumed by the next renderConfig pass to run a
    // FLIP animation that glides surviving tabs to their new positions.
    var pendingTabFlip: Map<String, Double>? = null
    var firstRender = true

    /**
     * Apply a FLIP-style transform to each tab button whose pre-mutation
     * x position differs from its current one, then on the next animation
     * frame add the .flip-animating class so the transform eases back to
     * identity. The combined effect is a smooth shuffle when the tab order
     * changes (currently triggered by drag-to-reorder).
     */
    fun runTabFlip(snapshot: Map<String, Double>) {
        val buttons = tabBarEl.querySelectorAll(".tab-button")
        val moved = mutableListOf<HTMLElement>()
        for (i in 0 until buttons.length) {
            val btn = buttons.item(i) as HTMLElement
            val id = btn.getAttribute("data-tab") ?: continue
            val oldLeft = snapshot[id] ?: continue
            val newLeft = (btn.asDynamic().getBoundingClientRect().left as Number).toDouble()
            val dx = oldLeft - newLeft
            if (kotlin.math.abs(dx) < 0.5) continue
            // Pre-paint: shove the button back to where it used to be with no
            // transition. .flip-animating provides the easing once we clear
            // the inline transform.
            btn.style.transform = "translateX(${dx}px)"
            moved.add(btn)
        }
        if (moved.isEmpty()) return
        // Force layout flush so the pre-paint transform sticks before we
        // hand the easing back to the browser.
        @Suppress("UNUSED_VARIABLE")
        val _force = tabBarEl.asDynamic().offsetWidth
        window.requestAnimationFrame {
            for (btn in moved) {
                btn.classList.add("flip-animating")
                btn.style.transform = ""
            }
            // Clean up the helper class after the transition completes so
            // the next render starts from a clean slate.
            window.setTimeout({
                for (btn in moved) btn.classList.remove("flip-animating")
            }, 320)
        }
    }

    fun renderConfig(config: dynamic) {
        currentConfig = config
        // Live DOM refs from the previous pass are about to be detached when
        // we wipe terminalWrapEl below. Clear the routing map so message
        // handlers don't write into orphaned nodes; buildFileBrowserView
        // will refill it as the new tree mounts. The persistent state cache
        // (fileBrowserPaneStates) is intentionally NOT cleared so the new
        // view can immediately repaint from cached listings/html.
        // Save file-browser scroll positions before the DOM is wiped, then
        // wipe the routing map (the DOM nodes are about to be detached).
        val savedFileBrowserScrolls = HashMap<String, Double>()
        for ((pid, view) in fileBrowserPaneViews) {
            val s = view.listBody.scrollTop
            if (s > 0) savedFileBrowserScrolls[pid] = s
        }
        fileBrowserPaneViews.clear()

        // Save git file-list scroll positions before the DOM is wiped.
        val savedGitScrolls = HashMap<String, Double>()
        for ((pid, view) in gitPaneViews) {
            val s = view.listBody.scrollTop
            if (s > 0) savedGitScrolls[pid] = s
        }
        gitPaneViews.clear()

        val tabsArr = config.tabs as Array<dynamic>
        if (tabsArr.isEmpty()) {
            tabBarEl.innerHTML = ""
            // The active-tab indicator was wiped along with the tab buttons —
            // re-add an empty one so positionActiveIndicator stays a no-op.
            val emptyIndicator = document.createElement("div") as HTMLElement
            emptyIndicator.className = "tab-active-indicator"
            tabBarEl.appendChild(emptyIndicator)
            terminalWrapEl.innerHTML = ""
            sidebarEl.innerHTML = ""
            previousTabIds.clear()
            previousPaneIds.clear()
            return
        }

        // Snapshot the current set of tab/pane IDs so we can detect newcomers
        // for the .entering animation. We do this BEFORE wiping the DOM so
        // the new IDs computed below can be diffed against it.
        val previousTabIdsSnapshot = previousTabIds.toSet()
        val previousPaneIdsSnapshot = previousPaneIds.toSet()

        // 1. Find the live set of session ids and reap any entries no longer
        //    referenced. Their PTYs have already been destroyed server-side.
        val livePanes = HashSet<String>()
        for (tab in tabsArr) {
            if (tab.root != null) collectPaneIds(tab.root, livePanes)
            val floats = tab.floating as? Array<dynamic> ?: emptyArray()
            for (fp in floats) livePanes.add(fp.leaf.id as String)
            // Popped-out panes intentionally omitted: they live in a separate
            // BrowserWindow (separate renderer) with its own xterm + PTY
            // WebSocket. The main window drops its TerminalEntry for them so
            // we don't keep a redundant connection to the same PTY. When the
            // pane is docked back, a fresh TerminalEntry is created and the
            // PTY's snapshot replays recent output.
        }
        val toRemove = terminals.keys.filter { it !in livePanes }
        for (pid in toRemove) {
            val entry = terminals.remove(pid) ?: continue
            try { entry.socket?.close() } catch (_: Throwable) {}
            try { (entry.resizeObserver as? ResizeObserver)?.disconnect() } catch (_: Throwable) {}
            connectionState.remove(entry.sessionId)
            // The entry's container will be detached when we wipe the wrap below.
        }
        updateAggregateStatus()

        // 2. Pick the active tab. Priority: the local selection if still
        //    valid > the server's persisted activeTabId > first tab. The
        //    server-side fallback matters on the very first render after a
        //    page reload, when activeTabId is still null locally.
        val tabIds = tabsArr.map { it.id as String }
        val serverActive = config.activeTabId as? String
        val active = activeTabId.takeIf { it != null && tabIds.contains(it) }
            ?: serverActive?.takeIf { tabIds.contains(it) }
            ?: tabIds.first()
        activeTabId = active

        // 3. Rebuild the tab bar. Detach the persistent .tab-active-indicator
        //    before wiping innerHTML so we can re-attach the same node after
        //    rebuilding (otherwise the indicator would be recreated on every
        //    render and the snap-no-transition logic would have nothing to
        //    work with).
        val savedIndicator = tabBarEl.querySelector(".tab-active-indicator") as? HTMLElement
        savedIndicator?.let { it.parentElement?.removeChild(it) }
        // Tab overflow menus are portaled to <body>, so they wouldn't be
        // cleaned up by wiping tabBarEl. Drop any stragglers from the previous
        // render so they don't accumulate or linger after a tab is removed.
        val staleMenus = document.querySelectorAll(".tab-menu-list")
        for (i in 0 until staleMenus.length) {
            val el = staleMenus.item(i) as HTMLElement
            el.parentElement?.removeChild(el)
        }
        tabBarEl.innerHTML = ""
        val canCloseTab = tabsArr.size > 1
        // Track which tab IDs we render this pass so we can update the
        // previousTabIds set at the end without recomputing.
        val renderedTabIds = HashSet<String>()
        for (tab in tabsArr) {
            val tabId = tab.id as String
            val title = tab.title as String

            val tabWrap = document.createElement("div") as HTMLElement
            tabWrap.className = if (tabId == active) "tab-button active" else "tab-button"
            tabWrap.setAttribute("data-tab", tabId)
            tabWrap.setAttribute("draggable", "true")
            renderedTabIds.add(tabId)
            // First-time appearance gets the grow-in animation. Skipped on
            // the very first render so the initial set doesn't all swoosh in
            // at once (the boot fade covers that).
            if (!firstRender && tabId !in previousTabIdsSnapshot) {
                tabWrap.classList.add("entering")
                tabWrap.addEventListener("animationend", { _ ->
                    tabWrap.classList.remove("entering")
                })
            }

            val label = document.createElement("span") as HTMLElement
            label.className = "tab-label"
            label.textContent = title
            tabWrap.appendChild(label)

            // Aggregate state dot for the tab — shows the highest-priority
            // state across all panes in this tab. Visibility is driven by
            // the body class `show-pane-dots`.
            val tabDot = document.createElement("span") as HTMLElement
            tabDot.className = "pane-state-dot tab-state-dot"
            tabDot.setAttribute("data-tab-state", tabId)
            tabWrap.appendChild(tabDot)

            tabWrap.addEventListener("click", { ev ->
                ev.stopPropagation()
                if (activeTabId != tabId) setActiveTab(tabId)
            })

            // Drag-to-reorder. We compute insert-before vs insert-after from
            // the cursor position relative to the target tab's horizontal
            // midpoint and reflect that with a marker class so the user can
            // see exactly where the drop will land.
            tabWrap.addEventListener("dragstart", { ev ->
                val dt = ev.asDynamic().dataTransfer ?: return@addEventListener
                dt.effectAllowed = "move"
                // Some browsers require setData for the drag to actually fire
                // subsequent dragover/drop events on other elements.
                dt.setData("text/plain", tabId)
                tabWrap.classList.add("dragging")
            })
            tabWrap.addEventListener("dragend", {
                tabWrap.classList.remove("dragging")
                // Clear any leftover indicators across all tabs.
                val all = tabBarEl.querySelectorAll(".tab-button")
                for (i in 0 until all.length) {
                    val el = all.item(i) as HTMLElement
                    el.classList.remove("drop-before", "drop-after")
                }
            })
            // Sniff dataTransfer.types for our pane mime so dragover knows
            // it's a pane drag (the actual data is unreadable until drop).
            fun isPaneDrag(dynamicEv: dynamic): Boolean {
                val types = dynamicEv.dataTransfer?.types ?: return false
                val len = (types.length as Number).toInt()
                for (i in 0 until len) {
                    if (types[i] == "application/x-termtastic-pane") return true
                }
                return false
            }
            tabWrap.addEventListener("dragover", { ev ->
                ev.preventDefault()
                ev.asDynamic().dataTransfer.dropEffect = "move"
                if (isPaneDrag(ev.asDynamic())) {
                    // Pane-onto-tab drop highlights the whole tab — there
                    // is no before/after semantic since the destination is
                    // the tab as a whole.
                    tabWrap.classList.add("drop-pane")
                    tabWrap.classList.remove("drop-before", "drop-after")
                    return@addEventListener
                }
                val rect = tabWrap.asDynamic().getBoundingClientRect()
                val midpoint = (rect.left as Double) + (rect.width as Double) / 2.0
                val before = (ev.asDynamic().clientX as Double) < midpoint
                if (before) {
                    tabWrap.classList.add("drop-before")
                    tabWrap.classList.remove("drop-after")
                } else {
                    tabWrap.classList.add("drop-after")
                    tabWrap.classList.remove("drop-before")
                }
            })
            tabWrap.addEventListener("dragleave", {
                tabWrap.classList.remove("drop-before", "drop-after", "drop-pane")
            })
            tabWrap.addEventListener("drop", { ev ->
                ev.preventDefault()
                ev.stopPropagation()
                tabWrap.classList.remove("drop-before", "drop-after", "drop-pane")
                val dt = ev.asDynamic().dataTransfer ?: return@addEventListener
                val droppedPaneId = dt.getData("application/x-termtastic-pane") as? String
                if (!droppedPaneId.isNullOrEmpty()) {
                    // Pane-onto-tab. Server is a no-op if the pane already
                    // belongs to this tab.
                    sendWindowCommand(
                        windowJson.encodeToString<WindowCommand>(
                            WindowCommand.MovePaneToTab(paneId = droppedPaneId, targetTabId = tabId)
                        )
                    )
                    return@addEventListener
                }
                val sourceId = dt.getData("text/plain") as? String
                if (sourceId.isNullOrEmpty() || sourceId == tabId) return@addEventListener
                // The pane drag also stamps "pane:<id>" into text/plain as
                // a fallback for browsers that need it to fire dragover at
                // all — ignore it here so we don't try to reorder a tab
                // using a pane id.
                if (sourceId.startsWith("pane:")) return@addEventListener
                val rect = tabWrap.asDynamic().getBoundingClientRect()
                val midpoint = (rect.left as Double) + (rect.width as Double) / 2.0
                val before = (ev.asDynamic().clientX as Double) < midpoint
                // Capture each surviving tab button's pre-mutation x position.
                // The next renderConfig will consume this snapshot to run a
                // FLIP animation that glides tabs to their new spots instead
                // of jumping.
                val snapshot = HashMap<String, Double>()
                val all = tabBarEl.querySelectorAll(".tab-button")
                for (i in 0 until all.length) {
                    val el = all.item(i) as HTMLElement
                    val tid = el.getAttribute("data-tab") ?: continue
                    snapshot[tid] = (el.asDynamic().getBoundingClientRect().left as Number).toDouble()
                }
                pendingTabFlip = snapshot
                sendWindowCommand(
                    windowJson.encodeToString<WindowCommand>(
                        WindowCommand.MoveTab(tabId = sourceId, targetTabId = tabId, before = before)
                    )
                )
            })

            // Tab rename is available via the ⋯ menu; no double-click shortcut.

            // Always create the menu element, even on non-active tabs, so
            // every tab reserves the same horizontal space for the ⋯
            // button. CSS hides it unless the tab is active and hovered;
            // setActiveTab only toggles classes (no re-render), so if we
            // only added the menu to the render-time active tab, the
            // previously-active tab would stay wider after a switch.
            run {
                val menuWrap = document.createElement("div") as HTMLElement
                menuWrap.className = "tab-menu"
                val menuBtn = document.createElement("button") as HTMLElement
                menuBtn.className = "tab-menu-button"
                menuBtn.setAttribute("type", "button")
                menuBtn.setAttribute("title", "Tab menu")
                menuBtn.textContent = "⋯"
                val menuList = document.createElement("div") as HTMLElement
                menuList.className = "tab-menu-list"

                val renameItem = document.createElement("div") as HTMLElement
                renameItem.className = "tab-menu-item"
                renameItem.textContent = "Rename tab"
                renameItem.addEventListener("click", { ev ->
                    ev.stopPropagation()
                    menuWrap.classList.remove("open")
                    menuList.classList.remove("open")
                    startTabRename(label, tabId)
                })
                menuList.appendChild(renameItem)

                if (canCloseTab) {
                    val closeItem = document.createElement("div") as HTMLElement
                    closeItem.className = "tab-menu-item"
                    closeItem.textContent = "Close tab"
                    closeItem.addEventListener("click", { ev ->
                        ev.stopPropagation()
                        menuWrap.classList.remove("open")
                        menuList.classList.remove("open")
                        openConfirmDialog?.invoke(
                            "Close tab",
                            "Are you sure you want to close tab <strong>${title}</strong> and all its panes?",
                            "Close",
                        ) {
                            tabWrap.classList.add("leaving")
                            window.setTimeout({
                                sendCmd(WindowCommand.CloseTab(tabId = tabId))
                            }, 220)
                        }
                    })
                    menuList.appendChild(closeItem)
                }

                menuBtn.addEventListener("click", { ev ->
                    ev.stopPropagation()
                    // Close any other open tab/pane menus first.
                    val openWraps = document.querySelectorAll(".tab-menu.open, .pane-menu.open")
                    for (i in 0 until openWraps.length) {
                        val other = openWraps.item(i) as HTMLElement
                        if (other !== menuWrap) other.classList.remove("open")
                    }
                    val openLists = document.querySelectorAll(".tab-menu-list.open")
                    for (i in 0 until openLists.length) {
                        val other = openLists.item(i) as HTMLElement
                        if (other !== menuList) other.classList.remove("open")
                    }
                    val opening = !menuWrap.classList.contains("open")
                    menuWrap.classList.toggle("open")
                    menuList.classList.toggle("open")
                    if (opening) {
                        // Place the fixed-position list at the button's
                        // bottom-right corner so it sits just under the ⋯
                        // and doesn't get clipped by the tab bar's overflow.
                        // The list is portaled into <body> (see appendChild
                        // below) so no transformed ancestor — e.g. the
                        // .tab-button:active scale — can shift its containing
                        // block out from under the cursor.
                        val listWidth = menuList.asDynamic().offsetWidth as Number
                        val rect = menuBtn.asDynamic().getBoundingClientRect()
                        val right = rect.right as Double
                        val bottom = rect.bottom as Double
                        val left = (right - listWidth.toDouble()).coerceAtLeast(4.0)
                        menuList.style.left = "${left}px"
                        menuList.style.top = "${bottom + 4}px"
                    }
                })
                // Don't let the menu button start a tab drag.
                menuBtn.setAttribute("draggable", "false")
                menuBtn.addEventListener("dragstart", { ev -> ev.preventDefault(); ev.stopPropagation() })

                menuWrap.appendChild(menuBtn)
                tabWrap.appendChild(menuWrap)
                // Portal the dropdown to <body> so a transform/filter on any
                // ancestor (e.g. .tab-button:active scale) can't redefine its
                // fixed-positioning containing block. renderConfig wipes any
                // stragglers before rebuilding the tab bar.
                document.body?.appendChild(menuList)
            }

            tabBarEl.appendChild(tabWrap)
        }

        // Re-attach the persistent active-tab indicator (or create a new one
        // if it was missing — e.g. on the first render before index.html's
        // copy is in place).
        val indicatorEl = savedIndicator ?: (document.createElement("div") as HTMLElement).also {
            it.className = "tab-active-indicator"
            it.setAttribute("aria-hidden", "true")
        }
        tabBarEl.appendChild(indicatorEl)

        val addBtn = document.createElement("button") as HTMLElement
        addBtn.className = "tab-add"
        addBtn.setAttribute("type", "button")
        addBtn.setAttribute("title", "New tab")
        addBtn.textContent = "+"
        addBtn.addEventListener("click", { ev ->
            ev.stopPropagation()
            sendCmd(WindowCommand.AddTab)
        })
        tabBarEl.appendChild(addBtn)

        // After re-rendering the bar, make sure the active tab is visible
        // even if it was scrolled offscreen by the previous configuration.
        scrollActiveTabIntoView()

        // 4. Rebuild the tab panes. We render every tab into the DOM (with
        //    only the active one set to display) so that background tabs'
        //    xterms keep streaming output without a remount.
        terminalWrapEl.innerHTML = ""
        for (tab in tabsArr) {
            val tabId = tab.id as String
            val isActive = tabId == active
            val tabPane = document.createElement("section") as HTMLElement
            tabPane.id = tabId
            tabPane.className = if (isActive) "tab-pane active" else "tab-pane"
            val rootNode = tab.root
            val floats = (tab.floating as? Array<dynamic>) ?: emptyArray()
            if (rootNode != null) {
                tabPane.appendChild(buildNode(rootNode))
            } else if (floats.isEmpty()) {
                // No docked tree and no floats — show the "New pane" placeholder.
                tabPane.appendChild(buildEmptyTabPlaceholder(tabId))
            }
            // Floating layer sits on top of the tree (or placeholder). It's
            // always created when there are floats so that pointer events on
            // the underlying tree still work where there's no float.
            if (floats.isNotEmpty()) {
                val layer = document.createElement("div") as HTMLElement
                layer.className = "floating-layer"
                // Sort by z ascending so DOM order matches stacking order.
                val sorted = floats.toList()
                    .sortedBy { (it.z as Number).toDouble() }
                for (fp in sorted) {
                    layer.appendChild(buildFloatingPane(fp, tabPane))
                }
                tabPane.appendChild(layer)
            }
            terminalWrapEl.appendChild(tabPane)
        }

        // 4b. Restore the .focused class on the pane that was focused before
        //     the DOM wipe so the orange ring doesn't flicker off for a frame.
        val focusedPaneId = activeTabId?.let { savedFocusedPaneId(it) }
        if (focusedPaneId != null) {
            val cell = terminalWrapEl.querySelector("[data-pane=\"$focusedPaneId\"]") as? HTMLElement
            cell?.classList?.add("focused")
        }

        // 5. Apply the current theme to any newly created terminals and refit
        //    everything that's now visible.
        applyThemeToTerminals()

        // 5b. Update the sidebar tree to reflect the current config.
        renderSidebar(config)
        updateStateDots(latestSessionStates)

        // 5b2. Re-apply maximized state after the DOM rebuild. Snap (no
        //      animation) since the user already saw the pane maximized.
        for ((tabId, paneId) in maximizedPaneIds.toMap()) {
            // Verify the pane still exists in the new config.
            val cell = terminalWrapEl.querySelector("[data-pane=\"$paneId\"]") as? HTMLElement
            if (cell != null) {
                maximizedPaneIds.remove(tabId) // maximizePane re-adds it
                maximizePane(paneId, animate = false)
            } else {
                maximizedPaneIds.remove(tabId)
            }
        }

        // 5c. Restore file-list scroll positions saved before the wipe.
        //     Deferred to rAF because the elements were rendered while
        //     detached — scrollTop doesn't stick until after layout.
        if (savedGitScrolls.isNotEmpty() || savedFileBrowserScrolls.isNotEmpty()) {
            window.requestAnimationFrame {
                for ((pid, scroll) in savedGitScrolls) {
                    val view = gitPaneViews[pid] ?: continue
                    view.listBody.scrollTop = scroll
                }
                for ((pid, scroll) in savedFileBrowserScrolls) {
                    val view = fileBrowserPaneViews[pid] ?: continue
                    view.listBody.scrollTop = scroll
                }
            }
        }

        // 6. Diff: tag genuinely-new split-children and floating panes with
        //    .entering so the CSS keyframes play. We do this AFTER the DOM
        //    is in place because buildNode is recursive and ID lookup is
        //    cheaper post-hoc than threading the diff state through.
        val freshPaneIds = HashSet<String>()
        val cells = terminalWrapEl.querySelectorAll("[data-pane]")
        for (i in 0 until cells.length) {
            val cell = cells.item(i) as HTMLElement
            val pid = cell.getAttribute("data-pane") ?: continue
            freshPaneIds.add(pid)
            if (firstRender || pid in previousPaneIdsSnapshot) continue
            // Wrap is the .split-child for docked panes, .floating-pane for
            // floats. Add .entering to whichever one applies; the CSS targets
            // both.
            val splitChild = cell.parentElement as? HTMLElement
            if (splitChild?.classList?.contains("split-child") == true) {
                splitChild.classList.add("entering")
                splitChild.addEventListener("animationend", { _ ->
                    splitChild.classList.remove("entering")
                })
            } else {
                val floating = cell.parentElement as? HTMLElement
                if (floating?.classList?.contains("floating-pane") == true) {
                    floating.classList.add("entering")
                    floating.addEventListener("animationend", { _ ->
                        floating.classList.remove("entering")
                    })
                }
            }
        }

        // 7. Update the snapshots for the next render.
        previousTabIds.clear()
        previousTabIds.addAll(renderedTabIds)
        previousPaneIds.clear()
        previousPaneIds.addAll(freshPaneIds)

        // 8. Position the sliding active-tab indicator. The first render
        //    snaps it into place; subsequent renders animate it (so reorders
        //    and inserts smoothly carry the ring along). FLIP for the tab
        //    bar runs here too if a drag-reorder set up a snapshot.
        window.requestAnimationFrame {
            if (firstRender) {
                snapActiveIndicator()
            } else {
                positionActiveIndicator()
            }
            pendingTabFlip?.let { snapshot ->
                runTabFlip(snapshot)
                pendingTabFlip = null
            }
        }

        window.setTimeout({
            fitVisible()
            // Auto-focus the first leaf in the now-active tab so the user
            // can immediately start typing on first paint.
            focusFirstPaneInActiveTab()
            // Drop the boot-fade classes after the first paint so the app
            // smoothly fades in. We do this on a timeout so the .booting
            // styles get a chance to render first.
            if (firstRender) {
                document.querySelector(".terminal-wrap.booting")
                    ?.let { (it as HTMLElement).classList.remove("booting") }
                document.querySelector(".app-header.booting")
                    ?.let { (it as HTMLElement).classList.remove("booting") }
            }
            firstRender = false
        }, 0)

        // Second refit after layout has fully settled — catches pane
        // close / split collapse where the immediate refit fires before
        // the browser has reflowed the new flex structure.
        window.setTimeout({ fitVisible() }, 80)
    }

    fun connectWindow() {
        val ws = WebSocket("$proto://${window.location.host}/window?$authQueryParam")
        windowSocket = ws
        ws.onmessage = { event ->
            val data = event.asDynamic().data
            if (data is String) {
                val parsed = js("JSON.parse(data)")
                if (parsed != null) {
                    when (parsed.type as? String) {
                        "pendingApproval" -> showPendingApprovalOverlay()
                        "config" -> {
                            hidePendingApprovalOverlay()
                            renderConfig(parsed.config)
                        }
                        "state" -> {
                            val statesObj = parsed.states
                            val map = HashMap<String, String?>()
                            val keys: Array<String> = js("Object.keys(statesObj)") as Array<String>
                            for (key in keys) {
                                val v = statesObj[key]
                                map[key] = if (v == null || v == undefined) null else v as String
                            }
                            latestSessionStates = map
                            updateStateDots(map)
                        }
                        "claudeUsage" -> {
                            updateClaudeUsageBadge(parsed.usage)
                        }
                        "uiSettings" -> {
                            applyServerUiSettings(parsed.settings)
                        }
                        else -> {
                            handlePaneContentMessage(parsed.type as? String, parsed)
                        }
                    }
                }
            }
        }
        ws.onclose = { event ->
            windowSocket = null
            val code = (event.asDynamic().code as? Number)?.toInt() ?: 0
            val reason = (event.asDynamic().reason as? String) ?: ""
            if (code == 1008) {
                showDeviceRejectedOverlay(code, reason)
            } else {
                window.setTimeout({ connectWindow() }, 500)
            }
        }
        ws.onerror = {
            ws.close()
        }
    }
    connectWindow()

    // Electron-only: when a popped-out pane's OS window is closed via the
    // red X, the main process notifies us so we can dock the pane back into
    // its tab (otherwise the PTY would be orphaned in `poppedOut` until the
    // next server rehydrate). This is idempotent — sending dockPoppedOut for
    // a pane that's already been docked (e.g. via the popout's "Dock" menu
    // before closing) is a no-op server-side.
    val electronApiForPopout = window.asDynamic().electronApi
    if (electronApiForPopout?.onPopoutClosed != null) {
        electronApiForPopout.onPopoutClosed { paneId: String ->
            sendCmd(WindowCommand.DockPoppedOut(paneId = paneId))
        }
    }

    window.addEventListener("resize", {
        fitVisible()
        // Tab button widths can change with the available bar width (e.g.
        // when the header layout reflows on a window resize), so the ring
        // needs to follow.
        positionActiveIndicator()
    })
}
