/**
 * Pure colour arithmetic utilities operating on ARGB [Long] values.
 *
 * Every colour is encoded as `0xAARRGGBB` where each channel occupies one
 * byte.  The helpers here convert between hex-string and ARGB representations,
 * perform channel-wise linear interpolation ([mixColors]), apply alpha
 * ([withAlpha]), compute relative luminance ([luminance]) and WCAG contrast
 * ratio ([contrastRatio]), and perform readability-preserving mixing
 * ([mixWithContrastFloor]).
 *
 * These are used exclusively by the theme resolver ([resolve]) to derive
 * the full [ResolvedPalette] from a [ColorScheme]'s seed fg/bg values.
 *
 * @see ResolvedPalette
 * @see resolve
 */
package se.soderbjorn.termtastic

/**
 * Parses a CSS hex colour string into an opaque ARGB [Long].
 *
 * Accepts `#RRGGBB` (6-digit) and `#RGB` (3-digit shorthand). The returned
 * value always has full alpha (`0xFF`).
 *
 * @param hex the hex colour string, e.g. `"#00ff9f"` or `"#0f9"`
 * @return the colour as `0xFFRRGGBB`
 */
fun hexToArgb(hex: String): Long {
    val h = hex.removePrefix("#")
    val expanded = when (h.length) {
        3 -> h.map { "$it$it" }.joinToString("")
        6 -> h
        else -> "000000"
    }
    return 0xFF000000L or expanded.lowercase().toLong(16)
}

/**
 * Formats an ARGB [Long] as a 7-character CSS hex string (`#rrggbb`).
 *
 * Alpha is silently dropped.  Use [argbToCss] when alpha matters.
 *
 * @param argb the colour value (e.g. `0xFF00FF9F`)
 * @return the hex string, e.g. `"#00ff9f"`
 */
fun argbToHex(argb: Long): String {
    val r = ((argb shr 16) and 0xFF).toString(16).padStart(2, '0')
    val g = ((argb shr 8) and 0xFF).toString(16).padStart(2, '0')
    val b = (argb and 0xFF).toString(16).padStart(2, '0')
    return "#$r$g$b"
}

/**
 * Formats an ARGB [Long] as a CSS colour string, choosing the shortest
 * representation that preserves the colour.
 *
 * If the alpha channel is fully opaque (`0xFF`), the result is a 7-character
 * hex string (`#rrggbb`).  Otherwise it is an `rgba(r,g,b,a)` string with
 * the alpha expressed as a decimal fraction.
 *
 * @param argb the colour value
 * @return a CSS-compatible colour string
 */
fun argbToCss(argb: Long): String {
    val a = ((argb shr 24) and 0xFF).toInt()
    if (a == 0xFF) return argbToHex(argb)
    val r = ((argb shr 16) and 0xFF).toInt()
    val g = ((argb shr 8) and 0xFF).toInt()
    val b = (argb and 0xFF).toInt()
    // Two decimal places for alpha is sufficient for CSS
    val af = (a / 255.0 * 100).toInt() / 100.0
    return "rgba($r,$g,$b,$af)"
}

/**
 * Linearly interpolates between two ARGB colours channel-by-channel.
 *
 * When [t] is `0.0` the result is [a]; when `1.0` it is [b].  The alpha
 * channel of [a] is preserved (not interpolated).
 *
 * @param a the start colour
 * @param b the end colour
 * @param t the interpolation factor, typically in `[0.0, 1.0]`
 * @return the blended colour
 */
fun mixColors(a: Long, b: Long, t: Double): Long {
    fun ch(shift: Int): Long {
        val ca = ((a shr shift) and 0xFF).toDouble()
        val cb = ((b shr shift) and 0xFF).toDouble()
        return (ca + (cb - ca) * t).toInt().coerceIn(0, 255).toLong()
    }
    return 0xFF000000L or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/**
 * Returns a copy of [color] with its alpha channel set to [alpha].
 *
 * @param color the source ARGB colour
 * @param alpha the desired alpha, `0.0` (transparent) to `1.0` (opaque)
 * @return the colour with the new alpha channel
 */
fun withAlpha(color: Long, alpha: Double): Long {
    val a = (alpha * 255).toInt().coerceIn(0, 255).toLong()
    return (a shl 24) or (color and 0x00FFFFFFL)
}

/**
 * Computes the relative luminance of an ARGB colour per the sRGB transfer
 * function (used by WCAG contrast calculations).
 *
 * Alpha is ignored; only the RGB channels contribute.
 *
 * @param color the ARGB colour value
 * @return luminance in `[0.0, 1.0]` where 0 is black and 1 is white
 */
fun luminance(color: Long): Double {
    fun lin(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92
        else {
            val v = (s + 0.055) / 1.055
            kotlin.math.exp(2.4 * kotlin.math.ln(v))
        }
    }
    val r = lin(((color shr 16) and 0xFF).toInt())
    val g = lin(((color shr 8) and 0xFF).toInt())
    val b = lin((color and 0xFF).toInt())
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/**
 * Returns `true` if [color] is perceptually light (luminance > 0.5).
 *
 * @param color the ARGB colour value
 * @return whether the colour is light
 */
fun isColorLight(color: Long): Boolean = luminance(color) > 0.5

/**
 * Computes the WCAG 2.1 contrast ratio between two colours.
 *
 * Result ranges from `1.0` (identical luminance) to `21.0` (black on white).
 * WCAG AA requires `≥ 4.5` for normal text and `≥ 3.0` for large text.
 *
 * Used by [mixWithContrastFloor] and by any caller that needs to gate a
 * derived colour against a readability target.
 *
 * @param a first colour (ARGB)
 * @param b second colour (ARGB)
 * @return the contrast ratio between [a] and [b]
 * @see luminance
 * @see mixWithContrastFloor
 */
fun contrastRatio(a: Long, b: Long): Double {
    val la = luminance(a)
    val lb = luminance(b)
    val hi = if (la >= lb) la else lb
    val lo = if (la >= lb) lb else la
    return (hi + 0.05) / (lo + 0.05)
}

/**
 * Mixes [fg] toward [bg] by [ratio] (see [mixColors]), but guarantees the
 * result has at least [minContrast] contrast against [bg].
 *
 * Called by [resolve] to derive `text.secondary`, `text.tertiary`,
 * `text.disabled`, and `sidebar.textDim` when the theme hasn't supplied
 * an explicit override.  For neutral high-luminance fg (e.g. Nord's
 * `#d8dee9` on `#2e3440`) the naive mix already clears the floor and is
 * returned unchanged, so hierarchy spacing is preserved.  For saturated
 * low-luminance fg (e.g. Vapor pink's `#ff77ff` on `#000000`) the naive
 * mix falls short; [ratio] is reduced via bisection until the contrast
 * floor is met.
 *
 * If even `ratio = 0` (pure [fg]) can't meet [minContrast] — i.e. the
 * theme has deliberately low-contrast primary text — [fg] is returned
 * as-is. There is no brighter option available, and themes that want
 * a specific value can set an explicit override.
 *
 * The bisection assumes contrast is monotonic in [ratio]: as the mix
 * drifts from [fg] to [bg], contrast against [bg] only decreases. This
 * holds whenever [fg] and [bg] differ in luminance.
 *
 * @param fg          the foreground seed colour (ARGB)
 * @param bg          the background against which readability is measured
 * @param ratio       the desired mix amount toward [bg] in `[0.0, 1.0]`
 * @param minContrast the minimum acceptable WCAG contrast against [bg]
 * @return the contrast-preserving mix result
 * @see mixColors
 * @see contrastRatio
 */
fun mixWithContrastFloor(
    fg: Long,
    bg: Long,
    ratio: Double,
    minContrast: Double,
): Long {
    val naive = mixColors(fg, bg, ratio)
    if (contrastRatio(naive, bg) >= minContrast) return naive
    if (contrastRatio(fg, bg) < minContrast) return fg
    // Bisect on r ∈ [0, ratio]: largest r where contrast still meets the floor.
    var lo = 0.0
    var hi = ratio
    repeat(12) {
        val mid = (lo + hi) / 2.0
        if (contrastRatio(mixColors(fg, bg, mid), bg) >= minContrast) {
            lo = mid
        } else {
            hi = mid
        }
    }
    return mixColors(fg, bg, lo)
}
