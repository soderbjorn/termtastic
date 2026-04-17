package se.soderbjorn.termtastic

import kotlinx.browser.document
import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLElement
import org.w3c.dom.WebSocket

@JsName("ResizeObserver")
external class ResizeObserver(callback: (dynamic, dynamic) -> Unit) {
    fun observe(target: HTMLElement)
    fun disconnect()
}

class TerminalEntry(
    val paneId: String,
    val sessionId: String,
    val term: Terminal,
    val fit: FitAddon,
    val container: HTMLElement,
    var socket: WebSocket? = null,
    var connected: Boolean = false,
    var resizeObserver: dynamic = null,
    var ptyCols: Int? = null,
    var ptyRows: Int? = null,
    var applyingServerSize: Boolean = false,
    var oobOverlayRight: HTMLElement? = null,
    var oobOverlayBottom: HTMLElement? = null,
    var sendInput: ((String) -> Unit)? = null,
)

fun fitPreservingScroll(term: Terminal, fit: FitAddon) {
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

fun safeFit(term: Terminal, fit: FitAddon) {
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

fun updateOobOverlay(entry: TerminalEntry) {
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
            "Unused by the current PTY size \u2014 press Reformat to reclaim this space"
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

fun applyServerSize(entry: TerminalEntry, cols: Int, rows: Int) {
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

fun forceReassert(entry: TerminalEntry) {
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

fun containsShowCursor(bytes: Uint8Array): Boolean {
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
