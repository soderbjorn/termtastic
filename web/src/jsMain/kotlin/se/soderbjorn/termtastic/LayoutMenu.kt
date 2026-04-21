/**
 * Layout picker dropdown shown in the app-header toolbar.
 *
 * Opens a panel with one tile per predefined layout (grid, primary-left,
 * columns, …). Each tile renders a miniature preview with the same number
 * of boxes as the active tab has panes, highlighting the primary pane so
 * the user can see at a glance what applying that layout will do. Clicking
 * a tile dispatches [WindowCommand.ApplyLayout]; the server computes the
 * authoritative geometry and pushes an updated config, so the preview and
 * the applied result always agree.
 *
 * @see WindowCommand.ApplyLayout
 * @see WindowState.applyLayout the server-side counterpart
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * Order matches the order tiles render in the dropdown (top-to-bottom,
 * left-to-right in a 3-column grid). The keys are also what the server's
 * `applyLayout` handler dispatches on, so adding a new layout requires
 * matching entries here and there.
 */
private val LAYOUTS = listOf(
    "primary-left",
    "primary-right",
    "primary-top",
    "primary-bottom",
    "primary-wide-left",
    "primary-wide-right",
    "primary-wide-top",
    "primary-wide-bottom",
    "primary-main-2col",
    "primary-main-2row",
    "grid",
    "two-col-grid",
    "three-col-grid",
    "columns",
    "rows",
)

/**
 * Wire the layout-dropdown button and menu. Safe to call once at boot; the
 * click handlers stay attached for the lifetime of the page.
 */
fun initLayoutMenu() {
    val wrap = document.getElementById("layout-dropdown") as? HTMLElement ?: return
    val button = document.getElementById("layout-button") as? HTMLElement ?: return
    val menu = document.getElementById("layout-menu") as? HTMLElement ?: return

    button.addEventListener("click", { ev ->
        ev.stopPropagation()
        if (wrap.classList.contains("open")) {
            wrap.classList.remove("open")
        } else {
            populateLayoutMenu(menu)
            wrap.classList.add("open")
        }
    })

    // Close on any outside click / Esc.
    document.addEventListener("click", { ev ->
        val t = ev.target as? HTMLElement ?: return@addEventListener
        if (wrap.contains(t)) return@addEventListener
        wrap.classList.remove("open")
    })
    document.addEventListener("keydown", { ev ->
        val key = ev.asDynamic().key as? String ?: return@addEventListener
        if (key == "Escape") wrap.classList.remove("open")
    })
}

/**
 * Rebuild the dropdown's tile list against the currently active tab. Called
 * on open so the preview box count always matches the live pane count.
 */
private fun populateLayoutMenu(menu: HTMLElement) {
    menu.innerHTML = ""
    val cfg = currentConfig
    val active = activeTabId
    if (cfg == null || active == null) {
        menu.appendChild(emptyMessage("No tab active"))
        return
    }
    val tabsArr = cfg.tabs as? Array<dynamic>
    val tab = tabsArr?.firstOrNull { (it.id as? String) == active }
    val panes = (tab?.panes as? Array<dynamic>) ?: emptyArray()
    if (panes.isEmpty()) {
        menu.appendChild(emptyMessage("This tab has no panes"))
        return
    }

    // Primary = tab's persisted focused pane, or fall back to the first pane
    // in DOM order. The server uses the same fallback so dispatching a null
    // primaryPaneId here would also work, but sending it explicitly lets the
    // preview highlight the right tile.
    val primaryPaneId = (tab?.focusedPaneId as? String)
        ?: (panes[0].leaf.id as? String)
        ?: return
    val primaryIndex = panes.indexOfFirst { (it.leaf.id as? String) == primaryPaneId }.coerceAtLeast(0)

    for (key in LAYOUTS) {
        val boxes = computeLayout(key, panes.size)
        val tile = document.createElement("button") as HTMLElement
        tile.className = "layout-option"
        (tile.asDynamic()).type = "button"
        tile.setAttribute("data-layout", key)
        tile.setAttribute("title", labelFor(key))
        tile.innerHTML = renderPreviewSvg(boxes, primaryIndex = 0)
        tile.addEventListener("click", { ev: Event ->
            ev.stopPropagation()
            val wrap = document.getElementById("layout-dropdown") as? HTMLElement
            wrap?.classList?.remove("open")
            launchCmd(WindowCommand.ApplyLayout(
                tabId = active,
                layout = key,
                primaryPaneId = primaryPaneId,
            ))
        })
        menu.appendChild(tile)
        // Reference to suppress unused-variable warning: the primary slot
        // in every layout is index 0, so we always highlight boxes[0] in
        // the preview. `primaryIndex` is retained on the pane descriptor
        // for future per-tile positional highlighting.
        @Suppress("UNUSED_VARIABLE")
        val _unused = primaryIndex
    }
}

private fun labelFor(key: String): String = when (key) {
    "primary-left" -> "Primary left"
    "primary-right" -> "Primary right"
    "primary-top" -> "Primary top"
    "primary-bottom" -> "Primary bottom"
    "primary-wide-left" -> "Primary dominant (left)"
    "primary-wide-right" -> "Primary dominant (right)"
    "primary-wide-top" -> "Primary dominant (top)"
    "primary-wide-bottom" -> "Primary dominant (bottom)"
    "primary-main-2col" -> "Primary + right-side grid"
    "primary-main-2row" -> "Primary + bottom grid"
    "grid" -> "Grid"
    "two-col-grid" -> "Two-column grid"
    "three-col-grid" -> "Three-column grid"
    "columns" -> "Equal columns"
    "rows" -> "Equal rows"
    else -> key
}

private fun emptyMessage(text: String): HTMLElement {
    val el = document.createElement("div") as HTMLElement
    el.className = "layout-menu-empty"
    el.textContent = text
    return el
}

/**
 * Mirror of [WindowState.computeLayout] on the client so the dropdown
 * previews match the geometry the server will actually apply. Must stay in
 * sync with the server-side copy — there's only one canonical set of
 * layouts, and we're rendering the same shape in two places.
 */
private fun computeLayout(layout: String, n: Int): List<PreviewBox> {
    if (n <= 0) return emptyList()
    if (n == 1) return listOf(PreviewBox(0.0, 0.0, 1.0, 1.0))
    val others = n - 1
    return when (layout) {
        "primary-left" -> primaryStack(n, 0.6, horizontal = true, primaryFirst = true)
        "primary-right" -> primaryStack(n, 0.6, horizontal = true, primaryFirst = false)
        "primary-top" -> primaryStack(n, 0.6, horizontal = false, primaryFirst = true)
        "primary-bottom" -> primaryStack(n, 0.6, horizontal = false, primaryFirst = false)
        "primary-wide-left" -> primaryStack(n, 0.75, horizontal = true, primaryFirst = true)
        "primary-wide-right" -> primaryStack(n, 0.75, horizontal = true, primaryFirst = false)
        "primary-wide-top" -> primaryStack(n, 0.75, horizontal = false, primaryFirst = true)
        "primary-wide-bottom" -> primaryStack(n, 0.75, horizontal = false, primaryFirst = false)
        "columns" -> (0 until n).map { i ->
            val w = 1.0 / n
            PreviewBox(i * w, 0.0, w, 1.0)
        }
        "rows" -> (0 until n).map { i ->
            val h = 1.0 / n
            PreviewBox(0.0, i * h, 1.0, h)
        }
        "two-col-grid" -> {
            val cols = 2
            val rows = kotlin.math.ceil(n.toDouble() / cols).toInt().coerceAtLeast(1)
            val w = 1.0 / cols
            val h = 1.0 / rows
            (0 until n).map { i ->
                val r = i / cols
                val c = i % cols
                PreviewBox(c * w, r * h, w, h)
            }
        }
        "three-col-grid" -> {
            val cols = 3
            val rows = kotlin.math.ceil(n.toDouble() / cols).toInt().coerceAtLeast(1)
            val w = 1.0 / cols
            val h = 1.0 / rows
            (0 until n).map { i ->
                val r = i / cols
                val c = i % cols
                PreviewBox(c * w, r * h, w, h)
            }
        }
        "primary-main-2col" -> buildList {
            add(PreviewBox(0.0, 0.0, 0.5, 1.0))
            if (others > 0) {
                val cols = 2
                val rows = kotlin.math.ceil(others.toDouble() / cols).toInt().coerceAtLeast(1)
                val w = 0.5 / cols
                val h = 1.0 / rows
                for (i in 0 until others) {
                    val r = i / cols
                    val c = i % cols
                    add(PreviewBox(0.5 + c * w, r * h, w, h))
                }
            }
        }
        "primary-main-2row" -> buildList {
            add(PreviewBox(0.0, 0.0, 1.0, 0.5))
            if (others > 0) {
                val cols = 2
                val rows = kotlin.math.ceil(others.toDouble() / cols).toInt().coerceAtLeast(1)
                val w = 1.0 / cols
                val h = 0.5 / rows
                for (i in 0 until others) {
                    val r = i / cols
                    val c = i % cols
                    add(PreviewBox(c * w, 0.5 + r * h, w, h))
                }
            }
        }
        else -> {
            // grid
            val cols = kotlin.math.ceil(kotlin.math.sqrt(n.toDouble())).toInt().coerceAtLeast(1)
            val rows = kotlin.math.ceil(n.toDouble() / cols).toInt().coerceAtLeast(1)
            val w = 1.0 / cols
            val h = 1.0 / rows
            (0 until n).map { i ->
                val r = i / cols
                val c = i % cols
                PreviewBox(c * w, r * h, w, h)
            }
        }
    }
}

private fun primaryStack(n: Int, primaryW: Double, horizontal: Boolean, primaryFirst: Boolean): List<PreviewBox> {
    val others = n - 1
    val out = ArrayList<PreviewBox>(n)
    val primary: PreviewBox
    val stackOrigin: Double
    val stackExtent = 1.0 - primaryW
    if (horizontal) {
        primary = if (primaryFirst) PreviewBox(0.0, 0.0, primaryW, 1.0)
                  else PreviewBox(1.0 - primaryW, 0.0, primaryW, 1.0)
        stackOrigin = if (primaryFirst) primaryW else 0.0
    } else {
        primary = if (primaryFirst) PreviewBox(0.0, 0.0, 1.0, primaryW)
                  else PreviewBox(0.0, 1.0 - primaryW, 1.0, primaryW)
        stackOrigin = if (primaryFirst) primaryW else 0.0
    }
    out.add(primary)
    if (others > 0) {
        val step = 1.0 / others
        for (i in 0 until others) {
            if (horizontal) out.add(PreviewBox(stackOrigin, i * step, stackExtent, step))
            else out.add(PreviewBox(i * step, stackOrigin, step, stackExtent))
        }
    }
    return out
}

private data class PreviewBox(val x: Double, val y: Double, val width: Double, val height: Double)

/**
 * Build the inline SVG preview for a layout. [primaryIndex] highlights one
 * of the boxes with the primary accent so the user knows which slot the
 * active pane will land in; remaining boxes are drawn in a muted fill.
 */
private fun renderPreviewSvg(boxes: List<PreviewBox>, primaryIndex: Int): String {
    val w = 64
    val h = 48
    val pad = 1.0
    val sb = StringBuilder()
    sb.append("""<svg viewBox="0 0 $w $h" width="$w" height="$h" class="layout-preview" aria-hidden="true">""")
    for ((i, b) in boxes.withIndex()) {
        val bx = b.x * w + pad
        val by = b.y * h + pad
        val bw = (b.width * w - pad * 2).coerceAtLeast(2.0)
        val bh = (b.height * h - pad * 2).coerceAtLeast(2.0)
        val cls = if (i == primaryIndex) "layout-preview-primary" else "layout-preview-other"
        sb.append("""<rect x="$bx" y="$by" width="$bw" height="$bh" rx="1.2" class="$cls"/>""")
    }
    sb.append("</svg>")
    return sb.toString()
}
