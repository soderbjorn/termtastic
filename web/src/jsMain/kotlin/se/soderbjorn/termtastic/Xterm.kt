@file:JsModule("xterm")
@file:JsNonModule

package se.soderbjorn.termtastic

import org.w3c.dom.HTMLElement

external class Terminal(options: dynamic = definedExternally) {
    val cols: Int
    val rows: Int
    val options: dynamic
    fun open(parent: HTMLElement)
    fun write(data: dynamic)
    fun onData(cb: (String) -> Unit)
    fun onResize(cb: (dynamic) -> Unit)
    fun loadAddon(addon: dynamic)
    fun focus()
    fun paste(data: String)
}
