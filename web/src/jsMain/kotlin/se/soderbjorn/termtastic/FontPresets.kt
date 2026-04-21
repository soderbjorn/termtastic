/**
 * Monospace font-family presets for the Termtastic web client.
 *
 * Defines the list of font-family choices shown in the Settings panel, the
 * logic for mapping a persisted preset key to a CSS font-family stack, a
 * width-measurement routine that detects which presets are actually installed
 * on the user's machine (since browsers don't expose the font list), and the
 * [applyGlobalFontFamily] routine that pushes the selected font to every
 * live xterm.js instance and the `--t-font-mono` CSS custom property (which
 * drives git diff panes and markdown code blocks via rules in `styles.css`).
 *
 * @see SettingsPanel
 * @see applyGlobalFontSize
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement

/**
 * A single monospace font preset selectable in the Settings panel.
 *
 * @property key        short stable identifier persisted in server settings
 *   (e.g. `"menlo"`). The persisted preset **key**, never the raw CSS stack.
 * @property displayName human-readable label shown on the Settings button.
 * @property cssStack   full CSS `font-family` stack applied to xterm.js and
 *   to the `--t-font-mono` custom property. Always ends in `monospace` so
 *   the browser falls through gracefully if the first family is missing.
 * @property detectFamily primary family name used by [detectInstalledFonts]
 *   to decide whether this preset is available on the current machine, or
 *   `null` for presets that are always considered available (e.g. the
 *   generic `system` stack).
 */
internal data class FontPreset(
    val key: String,
    val displayName: String,
    val cssStack: String,
    val detectFamily: String?,
)

/**
 * Ordered list of font presets shown in the Settings panel. The first entry
 * (`system`) is the default when no preset is persisted.
 */
internal val fontPresets: List<FontPreset> = listOf(
    FontPreset("system", "System Default",
        "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace", null),
    FontPreset("menlo", "Menlo", "Menlo, monospace", "Menlo"),
    FontPreset("monaco", "Monaco", "Monaco, monospace", "Monaco"),
    FontPreset("sfMono", "SF Mono",
        "'SF Mono', ui-monospace, monospace", "SF Mono"),
    FontPreset("courier", "Courier New",
        "'Courier New', Courier, monospace", "Courier New"),
    FontPreset("jetbrainsMono", "JetBrains Mono",
        "'JetBrains Mono', ui-monospace, monospace", "JetBrains Mono"),
    FontPreset("firaCode", "Fira Code",
        "'Fira Code', ui-monospace, monospace", "Fira Code"),
    FontPreset("cascadiaCode", "Cascadia Code",
        "'Cascadia Code', ui-monospace, monospace", "Cascadia Code"),
)

/** The `system` preset's CSS stack, used as the default when nothing is persisted. */
private val systemFontStack: String =
    fontPresets.first { it.key == "system" }.cssStack

/**
 * Resolves a persisted preset key to its CSS font-family stack.
 *
 * Unknown or null keys fall back to the `system` stack so rendering is
 * always sensible even if the server returns a stale or renamed preset key.
 *
 * @param key the persisted preset key, or `null` if none is set
 * @return the CSS font-family stack to hand to xterm.js / `--t-font-mono`
 */
internal fun resolveFontFamilyCss(key: String?): String {
    if (key.isNullOrEmpty()) return systemFontStack
    return fontPresets.firstOrNull { it.key == key }?.cssStack ?: systemFontStack
}

/** Cached result of [detectInstalledFonts]; null until the first call. */
private var installedFontsCache: Set<String>? = null

/**
 * Detects which [fontPresets] are actually installed on the user's machine
 * using the canvas text-width-measurement technique.
 *
 * Browsers do not expose the installed-fonts list directly (Chromium's
 * `queryLocalFonts` is gated behind a user permission prompt and unavailable
 * in Firefox/Safari), so we render a sample string in the candidate family
 * with each of three generic fallbacks (`monospace`, `serif`, `sans-serif`)
 * and also in just the fallback alone. If the candidate is installed, its
 * rendered width will differ from at least one fallback baseline; if the
 * browser is falling through to the generic, all three widths will match
 * the corresponding baseline.
 *
 * The result is cached after the first call — installed fonts don't change
 * mid-session.
 *
 * @return the set of preset keys ([FontPreset.key]) available on this client.
 *   Presets with `detectFamily == null` (currently `system`) are always in
 *   the returned set.
 */
internal fun detectInstalledFonts(): Set<String> {
    installedFontsCache?.let { return it }

    val canvas = document.createElement("canvas") as HTMLCanvasElement
    val ctx = canvas.getContext("2d").asDynamic() ?: return emptySet()
    val sample = "mwiIWMOQabcdefghijklmnopqrstuvwxyz0123456789"
    val baselines = listOf("monospace", "serif", "sans-serif")
    val fontSize = 72

    fun widthOf(family: String): Double {
        ctx.font = "${fontSize}px $family"
        val metrics = ctx.measureText(sample)
        return (metrics.width as Number).toDouble()
    }

    val baselineWidths = baselines.associateWith { widthOf(it) }

    val available = mutableSetOf<String>()
    for (preset in fontPresets) {
        val detect = preset.detectFamily
        if (detect == null) {
            available.add(preset.key)
            continue
        }
        val quoted = if (detect.contains(' ')) "'$detect'" else detect
        val installed = baselines.any { baseline ->
            val w = widthOf("$quoted, $baseline")
            val b = baselineWidths.getValue(baseline)
            kotlin.math.abs(w - b) > 0.5
        }
        if (installed) available.add(preset.key)
    }

    installedFontsCache = available
    return available
}

/**
 * Applies the selected font-family preset to every live xterm.js terminal and
 * publishes the resolved CSS stack as the `--t-font-mono` custom property on
 * `<html>`, so CSS rules in `styles.css` (git diff panes, markdown `pre`/`code`)
 * pick up the new font too.
 *
 * Called from [SettingsPanel] when the user clicks a font button, and from
 * the initial hydration path in `main.kt` once the server's `UiSettings`
 * envelope arrives.
 *
 * @param key the persisted preset key, or `null` to fall back to the system
 *   default stack.
 * @see resolveFontFamilyCss
 * @see applyGlobalFontSize
 */
internal fun applyGlobalFontFamily(key: String?) {
    val stack = resolveFontFamilyCss(key)
    for ((_, entry) in terminals) {
        entry.term.options.fontFamily = stack
        try { safeFit(entry.term, entry.fit) } catch (_: Throwable) {}
    }
    (document.documentElement as? HTMLElement)
        ?.style?.setProperty("--t-font-mono", stack)
}
