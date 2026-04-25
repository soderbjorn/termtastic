/**
 * Web-side registry of live xterm.js terminal instances and their
 * connection-state map, plus the helper that re-fits every visible
 * terminal to its container.
 *
 * Lives next to [WebState] in the same package so all rendering code can
 * keep reading the unqualified `terminals`, `connectionState`, and
 * `windowSocketConnected` symbols without any call-site changes.
 *
 * @see WebState
 * @see fitPreservingScroll
 */
package se.soderbjorn.termtastic

import org.w3c.dom.HTMLElement

/** Map of paneId → live xterm.js terminal entry, refilled by `renderConfig`. */
internal val terminals = HashMap<String, TerminalEntry>()

/** Map of paneId → string PTY connection state ("connected", "disconnected", …). */
internal val connectionState = HashMap<String, String>()

/** Whether the `/window` WebSocket is currently connected. */
internal var windowSocketConnected = false

/**
 * Refits all visible terminal instances to their current container sizes.
 *
 * Iterates all registered terminals and calls [fitPreservingScroll] on
 * those whose DOM element has a non-null offsetParent (i.e., is visible).
 */
internal fun fitVisible() {
    for (entry in terminals.values) {
        val parent = (entry.term.asDynamic().element as? HTMLElement)?.offsetParent
        if (parent != null) {
            try { fitPreservingScroll(entry.term, entry.fit) } catch (_: Throwable) {}
        }
    }
}
