/**
 * Pure colour arithmetic utilities operating on ARGB [Long] values.
 *
 * Every colour is encoded as `0xAARRGGBB` where each channel occupies one
 * byte.  The helpers here convert between hex-string and ARGB representations,
 * perform channel-wise linear interpolation ([mixColors]), apply alpha
 * ([withAlpha]), and compute relative luminance ([luminance]).
 *
 * These are used exclusively by the theme resolver ([resolve]) to derive
 * the full [ResolvedPalette] from a [TerminalTheme]'s seed fg/bg values.
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
