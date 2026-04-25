/**
 * Pure pane-layout algorithm helpers used by [WindowState.applyLayout]. Given
 * a layout key and a pane count, [computePaneLayout] returns a list of
 * fractional [PaneBox]es in rank order; the caller assigns them to panes
 * sorted by descending area so the focused/biggest pane wins the largest
 * slot.
 *
 * Every layout is designed to produce at most three distinct size classes
 * (primary, secondary, rest-equal) and avoids cascade slivers — see
 * [equalStrip] for the equal-cell helper.
 */
package se.soderbjorn.termtastic

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Compute the list of pane boxes for [layout] with [n] total panes. Index
 * 0 is always the slot the caller should assign to the focused (biggest)
 * pane; subsequent indices are the remaining, uniformly-sized sibling
 * slots so the caller can assign area-ranked panes in order.
 *
 * Unknown [layout] keys fall back to `grid` for robustness.
 *
 * @param layout the layout key (must match one in `LayoutMenu.kt`)
 * @param n the number of panes to place; must be ≥ 0
 * @return a list of [PaneBox] of size [n] in rank order
 */
internal fun computePaneLayout(layout: String, n: Int): List<PaneBox> {
    if (n <= 0) return emptyList()
    if (n == 1) return listOf(PaneBox(0.0, 0.0, 1.0, 1.0))
    return when (layout) {
        "hero-left" -> listOf(PaneBox(0.0, 0.0, 0.65, 1.0)) +
            equalStrip(n - 1, ox = 0.65, oy = 0.0, sx = 0.35, sy = 1.0, axis = "vertical")
        "hero-right" -> listOf(PaneBox(0.35, 0.0, 0.65, 1.0)) +
            equalStrip(n - 1, ox = 0.0, oy = 0.0, sx = 0.35, sy = 1.0, axis = "vertical")
        "hero-top" -> listOf(PaneBox(0.0, 0.0, 1.0, 0.65)) +
            equalStrip(n - 1, ox = 0.0, oy = 0.65, sx = 1.0, sy = 0.35, axis = "horizontal")
        "hero-bottom" -> listOf(PaneBox(0.0, 0.35, 1.0, 0.65)) +
            equalStrip(n - 1, ox = 0.0, oy = 0.0, sx = 1.0, sy = 0.35, axis = "horizontal")

        "split-h" -> listOf(PaneBox(0.0, 0.0, 0.50, 1.0)) +
            equalStrip(n - 1, ox = 0.50, oy = 0.0, sx = 0.50, sy = 1.0, axis = "vertical")
        "split-v" -> listOf(PaneBox(0.0, 0.0, 1.0, 0.50)) +
            equalStrip(n - 1, ox = 0.0, oy = 0.50, sx = 1.0, sy = 0.50, axis = "horizontal")

        "sidebar-left" -> listOf(PaneBox(0.25, 0.0, 0.75, 1.0)) +
            equalStrip(n - 1, ox = 0.0, oy = 0.0, sx = 0.25, sy = 1.0, axis = "vertical")
        "sidebar-right" -> listOf(PaneBox(0.0, 0.0, 0.75, 1.0)) +
            equalStrip(n - 1, ox = 0.75, oy = 0.0, sx = 0.25, sy = 1.0, axis = "vertical")
        "sidebar-top" -> listOf(PaneBox(0.0, 0.25, 1.0, 0.75)) +
            equalStrip(n - 1, ox = 0.0, oy = 0.0, sx = 1.0, sy = 0.25, axis = "horizontal")
        "sidebar-bottom" -> listOf(PaneBox(0.0, 0.0, 1.0, 0.75)) +
            equalStrip(n - 1, ox = 0.0, oy = 0.75, sx = 1.0, sy = 0.25, axis = "horizontal")

        "t-shape" -> when (n) {
            // n=2 has no bottom row to fill, so degrade to the hero-left
            // shape at 70/30 so the layout still feels like itself.
            2 -> listOf(
                PaneBox(0.0, 0.0, 0.70, 1.0),
                PaneBox(0.70, 0.0, 0.30, 1.0),
            )
            else -> buildList {
                add(PaneBox(0.0, 0.0, 0.70, 0.70))
                add(PaneBox(0.70, 0.0, 0.30, 0.70))
                addAll(equalStrip(n - 2, ox = 0.0, oy = 0.70, sx = 1.0, sy = 0.30, axis = "horizontal"))
            }
        }
        "t-shape-inv" -> when (n) {
            2 -> listOf(
                PaneBox(0.0, 0.0, 0.70, 1.0),
                PaneBox(0.70, 0.0, 0.30, 1.0),
            )
            else -> buildList {
                add(PaneBox(0.0, 0.30, 0.70, 0.70))
                add(PaneBox(0.70, 0.30, 0.30, 0.70))
                addAll(equalStrip(n - 2, ox = 0.0, oy = 0.0, sx = 1.0, sy = 0.30, axis = "horizontal"))
            }
        }

        "l-shape" -> when (n) {
            2 -> listOf(
                PaneBox(0.0, 0.0, 0.65, 1.0),
                PaneBox(0.65, 0.0, 0.35, 1.0),
            )
            else -> buildList {
                add(PaneBox(0.0, 0.0, 0.65, 0.65))
                add(PaneBox(0.65, 0.0, 0.35, 1.0))
                addAll(equalStrip(n - 2, ox = 0.0, oy = 0.65, sx = 0.65, sy = 0.35, axis = "horizontal"))
            }
        }
        "l-shape-tr" -> when (n) {
            2 -> listOf(
                PaneBox(0.35, 0.0, 0.65, 1.0),
                PaneBox(0.0, 0.0, 0.35, 1.0),
            )
            else -> buildList {
                add(PaneBox(0.35, 0.0, 0.65, 0.65))
                add(PaneBox(0.0, 0.0, 0.35, 1.0))
                addAll(equalStrip(n - 2, ox = 0.35, oy = 0.65, sx = 0.65, sy = 0.35, axis = "horizontal"))
            }
        }
        "l-shape-bl" -> when (n) {
            2 -> listOf(
                PaneBox(0.0, 0.0, 0.65, 1.0),
                PaneBox(0.65, 0.0, 0.35, 1.0),
            )
            else -> buildList {
                add(PaneBox(0.0, 0.35, 0.65, 0.65))
                add(PaneBox(0.65, 0.0, 0.35, 1.0))
                addAll(equalStrip(n - 2, ox = 0.0, oy = 0.0, sx = 0.65, sy = 0.35, axis = "horizontal"))
            }
        }
        "l-shape-br" -> when (n) {
            2 -> listOf(
                PaneBox(0.35, 0.0, 0.65, 1.0),
                PaneBox(0.0, 0.0, 0.35, 1.0),
            )
            else -> buildList {
                add(PaneBox(0.35, 0.35, 0.65, 0.65))
                add(PaneBox(0.0, 0.0, 0.35, 1.0))
                addAll(equalStrip(n - 2, ox = 0.35, oy = 0.0, sx = 0.65, sy = 0.35, axis = "horizontal"))
            }
        }

        "big-2-stack" -> when (n) {
            2 -> listOf(
                PaneBox(0.0, 0.0, 0.60, 1.0),
                PaneBox(0.60, 0.0, 0.40, 1.0),
            )
            else -> buildList {
                add(PaneBox(0.0, 0.0, 0.60, 1.0))
                add(PaneBox(0.60, 0.0, 0.40, 0.60))
                addAll(equalStrip(n - 2, ox = 0.60, oy = 0.60, sx = 0.40, sy = 0.40, axis = "vertical"))
            }
        }
        "big-2-stack-right" -> when (n) {
            2 -> listOf(
                PaneBox(0.40, 0.0, 0.60, 1.0),
                PaneBox(0.0, 0.0, 0.40, 1.0),
            )
            else -> buildList {
                add(PaneBox(0.40, 0.0, 0.60, 1.0))
                add(PaneBox(0.0, 0.0, 0.40, 0.60))
                addAll(equalStrip(n - 2, ox = 0.0, oy = 0.60, sx = 0.40, sy = 0.40, axis = "vertical"))
            }
        }
        "big-2-stack-bottom" -> when (n) {
            2 -> listOf(
                PaneBox(0.0, 0.40, 1.0, 0.60),
                PaneBox(0.0, 0.0, 1.0, 0.40),
            )
            else -> buildList {
                add(PaneBox(0.0, 0.40, 1.0, 0.60))
                add(PaneBox(0.0, 0.0, 0.60, 0.40))
                addAll(equalStrip(n - 2, ox = 0.60, oy = 0.0, sx = 0.40, sy = 0.40, axis = "horizontal"))
            }
        }

        "columns" -> equalColumns(n)
        "rows" -> equalRows(n)
        else -> {
            // "grid" (default): even tiling with primary at top-left.
            val cols = ceil(sqrt(n.toDouble())).toInt().coerceAtLeast(1)
            val rows = ceil(n.toDouble() / cols).toInt().coerceAtLeast(1)
            val w = 1.0 / cols
            val h = 1.0 / rows
            (0 until n).map { i ->
                val r = i / cols
                val c = i % cols
                PaneBox(c * w, r * h, w, h)
            }
        }
    }
}

/**
 * Fill a rectangular strip with [count] equally-sized slots along [axis].
 * The strip's top-left is at (`ox`, `oy`) and it spans (`sx`, `sy`) of
 * the tab area.
 */
internal fun equalStrip(
    count: Int,
    ox: Double,
    oy: Double,
    sx: Double,
    sy: Double,
    axis: String,
): List<PaneBox> {
    if (count <= 0) return emptyList()
    val out = ArrayList<PaneBox>(count)
    if (axis == "horizontal") {
        val bw = sx / count
        for (i in 0 until count) out.add(PaneBox(ox + i * bw, oy, bw, sy))
    } else {
        val bh = sy / count
        for (i in 0 until count) out.add(PaneBox(ox, oy + i * bh, sx, bh))
    }
    return out
}

/** Tile the full tab area into [n] equal-width columns, primary at left. */
internal fun equalColumns(n: Int): List<PaneBox> = (0 until n).map { i ->
    val w = 1.0 / n
    PaneBox(i * w, 0.0, w, 1.0)
}

/** Tile the full tab area into [n] equal-height rows, primary at top. */
internal fun equalRows(n: Int): List<PaneBox> = (0 until n).map { i ->
    val h = 1.0 / n
    PaneBox(0.0, i * h, 1.0, h)
}
