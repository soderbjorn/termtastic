/**
 * Layout picker dropdown shown in the app-header toolbar.
 *
 * Opens a panel with one tile per predefined layout (hero-left, split-h,
 * t-shape, grid, …). Each tile renders a miniature preview with the same
 * number of boxes as the active tab has panes, highlighting slot 0 (the
 * focused pane's destination) so the user can see at a glance what applying
 * that layout will do. Clicking a tile dispatches [WindowCommand.ApplyLayout];
 * the server assigns the focused pane to slot 0 and the remaining panes to
 * the other slots in descending current-area order, then computes the
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
 * matching entries here and there. Index 0 of every layout is the slot the
 * focused pane lands in; remaining slots descend in size so area-ranked
 * panes fill them biggest-to-smallest.
 */
private val LAYOUTS = listOf(
    // Equal (size-neutral resets)
    "grid",
    "columns",
    "rows",
    // Hero: primary 65% + equal sibling strip 35%
    "hero-left",
    "hero-right",
    "hero-top",
    "hero-bottom",
    // Half-and-half: primary 50% + equal sibling strip 50%
    "split-h",
    "split-v",
    // Sidebar: primary 75% + thin equal sibling strip 25%
    "sidebar-left",
    "sidebar-right",
    "sidebar-top",
    "sidebar-bottom",
    // T shapes: primary + one medium on a 70/30 row, equal strip on the other
    "t-shape",
    "t-shape-inv",
    // L shapes: primary corner + full-edge sibling + equal strip
    "l-shape",
    "l-shape-tr",
    "l-shape-bl",
    "l-shape-br",
    // Big primary + medium + equal stacked remainder
    "big-2-stack",
    "big-2-stack-right",
    "big-2-stack-bottom",
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
    "grid" -> "Grid"
    "columns" -> "Equal columns"
    "rows" -> "Equal rows"
    "hero-left" -> "Hero left"
    "hero-right" -> "Hero right"
    "hero-top" -> "Hero top"
    "hero-bottom" -> "Hero bottom"
    "split-h" -> "Split horizontal"
    "split-v" -> "Split vertical"
    "sidebar-left" -> "Sidebar left"
    "sidebar-right" -> "Sidebar right"
    "sidebar-top" -> "Sidebar top"
    "sidebar-bottom" -> "Sidebar bottom"
    "t-shape" -> "T-shape"
    "t-shape-inv" -> "Inverted T"
    "l-shape" -> "L-shape (top-left)"
    "l-shape-tr" -> "L-shape (top-right)"
    "l-shape-bl" -> "L-shape (bottom-left)"
    "l-shape-br" -> "L-shape (bottom-right)"
    "big-2-stack" -> "Big left + right stack"
    "big-2-stack-right" -> "Big right + left stack"
    "big-2-stack-bottom" -> "Big bottom + top stack"
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
 * layouts, and we're rendering the same shape in two places. Slot 0 is
 * always the primary/largest; remaining slots are equally sized within a
 * given layout family (so every layout has at most three distinct size
 * classes and no pane ends up as a sliver).
 */
private fun computeLayout(layout: String, n: Int): List<PreviewBox> {
    if (n <= 0) return emptyList()
    if (n == 1) return listOf(PreviewBox(0.0, 0.0, 1.0, 1.0))
    return when (layout) {
        "hero-left" -> listOf(PreviewBox(0.0, 0.0, 0.65, 1.0)) +
            equalStrip(n - 1, 0.65, 0.0, 0.35, 1.0, horizontal = false)
        "hero-right" -> listOf(PreviewBox(0.35, 0.0, 0.65, 1.0)) +
            equalStrip(n - 1, 0.0, 0.0, 0.35, 1.0, horizontal = false)
        "hero-top" -> listOf(PreviewBox(0.0, 0.0, 1.0, 0.65)) +
            equalStrip(n - 1, 0.0, 0.65, 1.0, 0.35, horizontal = true)
        "hero-bottom" -> listOf(PreviewBox(0.0, 0.35, 1.0, 0.65)) +
            equalStrip(n - 1, 0.0, 0.0, 1.0, 0.35, horizontal = true)

        "split-h" -> listOf(PreviewBox(0.0, 0.0, 0.50, 1.0)) +
            equalStrip(n - 1, 0.50, 0.0, 0.50, 1.0, horizontal = false)
        "split-v" -> listOf(PreviewBox(0.0, 0.0, 1.0, 0.50)) +
            equalStrip(n - 1, 0.0, 0.50, 1.0, 0.50, horizontal = true)

        "sidebar-left" -> listOf(PreviewBox(0.25, 0.0, 0.75, 1.0)) +
            equalStrip(n - 1, 0.0, 0.0, 0.25, 1.0, horizontal = false)
        "sidebar-right" -> listOf(PreviewBox(0.0, 0.0, 0.75, 1.0)) +
            equalStrip(n - 1, 0.75, 0.0, 0.25, 1.0, horizontal = false)
        "sidebar-top" -> listOf(PreviewBox(0.0, 0.25, 1.0, 0.75)) +
            equalStrip(n - 1, 0.0, 0.0, 1.0, 0.25, horizontal = true)
        "sidebar-bottom" -> listOf(PreviewBox(0.0, 0.0, 1.0, 0.75)) +
            equalStrip(n - 1, 0.0, 0.75, 1.0, 0.25, horizontal = true)

        "t-shape" -> when (n) {
            2 -> listOf(
                PreviewBox(0.0, 0.0, 0.70, 1.0),
                PreviewBox(0.70, 0.0, 0.30, 1.0),
            )
            else -> buildList {
                add(PreviewBox(0.0, 0.0, 0.70, 0.70))
                add(PreviewBox(0.70, 0.0, 0.30, 0.70))
                addAll(equalStrip(n - 2, 0.0, 0.70, 1.0, 0.30, horizontal = true))
            }
        }
        "t-shape-inv" -> when (n) {
            2 -> listOf(
                PreviewBox(0.0, 0.0, 0.70, 1.0),
                PreviewBox(0.70, 0.0, 0.30, 1.0),
            )
            else -> buildList {
                add(PreviewBox(0.0, 0.30, 0.70, 0.70))
                add(PreviewBox(0.70, 0.30, 0.30, 0.70))
                addAll(equalStrip(n - 2, 0.0, 0.0, 1.0, 0.30, horizontal = true))
            }
        }

        "l-shape" -> when (n) {
            2 -> listOf(
                PreviewBox(0.0, 0.0, 0.65, 1.0),
                PreviewBox(0.65, 0.0, 0.35, 1.0),
            )
            else -> buildList {
                add(PreviewBox(0.0, 0.0, 0.65, 0.65))
                add(PreviewBox(0.65, 0.0, 0.35, 1.0))
                addAll(equalStrip(n - 2, 0.0, 0.65, 0.65, 0.35, horizontal = true))
            }
        }
        "l-shape-tr" -> when (n) {
            2 -> listOf(
                PreviewBox(0.35, 0.0, 0.65, 1.0),
                PreviewBox(0.0, 0.0, 0.35, 1.0),
            )
            else -> buildList {
                add(PreviewBox(0.35, 0.0, 0.65, 0.65))
                add(PreviewBox(0.0, 0.0, 0.35, 1.0))
                addAll(equalStrip(n - 2, 0.35, 0.65, 0.65, 0.35, horizontal = true))
            }
        }
        "l-shape-bl" -> when (n) {
            2 -> listOf(
                PreviewBox(0.0, 0.0, 0.65, 1.0),
                PreviewBox(0.65, 0.0, 0.35, 1.0),
            )
            else -> buildList {
                add(PreviewBox(0.0, 0.35, 0.65, 0.65))
                add(PreviewBox(0.65, 0.0, 0.35, 1.0))
                addAll(equalStrip(n - 2, 0.0, 0.0, 0.65, 0.35, horizontal = true))
            }
        }
        "l-shape-br" -> when (n) {
            2 -> listOf(
                PreviewBox(0.35, 0.0, 0.65, 1.0),
                PreviewBox(0.0, 0.0, 0.35, 1.0),
            )
            else -> buildList {
                add(PreviewBox(0.35, 0.35, 0.65, 0.65))
                add(PreviewBox(0.0, 0.0, 0.35, 1.0))
                addAll(equalStrip(n - 2, 0.35, 0.0, 0.65, 0.35, horizontal = true))
            }
        }

        "big-2-stack" -> when (n) {
            2 -> listOf(
                PreviewBox(0.0, 0.0, 0.60, 1.0),
                PreviewBox(0.60, 0.0, 0.40, 1.0),
            )
            else -> buildList {
                add(PreviewBox(0.0, 0.0, 0.60, 1.0))
                add(PreviewBox(0.60, 0.0, 0.40, 0.60))
                addAll(equalStrip(n - 2, 0.60, 0.60, 0.40, 0.40, horizontal = false))
            }
        }
        "big-2-stack-right" -> when (n) {
            2 -> listOf(
                PreviewBox(0.40, 0.0, 0.60, 1.0),
                PreviewBox(0.0, 0.0, 0.40, 1.0),
            )
            else -> buildList {
                add(PreviewBox(0.40, 0.0, 0.60, 1.0))
                add(PreviewBox(0.0, 0.0, 0.40, 0.60))
                addAll(equalStrip(n - 2, 0.0, 0.60, 0.40, 0.40, horizontal = false))
            }
        }
        "big-2-stack-bottom" -> when (n) {
            2 -> listOf(
                PreviewBox(0.0, 0.40, 1.0, 0.60),
                PreviewBox(0.0, 0.0, 1.0, 0.40),
            )
            else -> buildList {
                add(PreviewBox(0.0, 0.40, 1.0, 0.60))
                add(PreviewBox(0.0, 0.0, 0.60, 0.40))
                addAll(equalStrip(n - 2, 0.60, 0.0, 0.40, 0.40, horizontal = true))
            }
        }

        "columns" -> equalColumns(n)
        "rows" -> equalRows(n)
        else -> {
            // grid (default): even tiling with primary at top-left.
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

/**
 * Fill a rectangular strip with [count] equally-sized slots. Horizontal
 * strips share `sy` as height and split `sx` into equal widths; vertical
 * strips share `sx` as width and split `sy` into equal heights. Mirror of
 * the server-side helper.
 */
private fun equalStrip(
    count: Int,
    ox: Double,
    oy: Double,
    sx: Double,
    sy: Double,
    horizontal: Boolean,
): List<PreviewBox> {
    if (count <= 0) return emptyList()
    val out = ArrayList<PreviewBox>(count)
    if (horizontal) {
        val bw = sx / count
        for (i in 0 until count) out.add(PreviewBox(ox + i * bw, oy, bw, sy))
    } else {
        val bh = sy / count
        for (i in 0 until count) out.add(PreviewBox(ox, oy + i * bh, sx, bh))
    }
    return out
}

private fun equalColumns(n: Int): List<PreviewBox> = (0 until n).map { i ->
    val w = 1.0 / n
    PreviewBox(i * w, 0.0, w, 1.0)
}

private fun equalRows(n: Int): List<PreviewBox> = (0 until n).map { i ->
    val h = 1.0 / n
    PreviewBox(0.0, i * h, 1.0, h)
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
