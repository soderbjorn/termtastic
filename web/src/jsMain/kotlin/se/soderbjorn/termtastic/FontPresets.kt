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
 *   generic `system` stack, or any preset with `bundled = true`).
 * @property bundled `true` for presets whose font files are shipped with
 *   the app under `/fonts/` via `@font-face` rules in `styles.css`. Bundled
 *   presets are always available regardless of what's installed locally,
 *   so [detectInstalledFonts] short-circuits them.
 */
internal data class FontPreset(
    val key: String,
    val displayName: String,
    val cssStack: String,
    val detectFamily: String?,
    val bundled: Boolean = false,
)

/**
 * Ordered list of font presets shown in the Settings panel. The first entry
 * (`system`) is the default when no preset is persisted. Bundled presets
 * (shipped as `.woff2` under `web/src/jsMain/resources/fonts/`) are grouped
 * after the system-detected macOS built-ins — see `styles.css` for the
 * `@font-face` declarations and `NOTICE` for licensing.
 */
internal val fontPresets: List<FontPreset> = listOf(
    FontPreset("system", "System Default",
        "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace", null),
    // System-detected families — only shown when actually installed locally.
    FontPreset("menlo", "Menlo", "Menlo, monospace", "Menlo"),
    FontPreset("monaco", "Monaco", "Monaco, monospace", "Monaco"),
    FontPreset("sfMono", "SF Mono",
        "'SF Mono', ui-monospace, monospace", "SF Mono"),
    FontPreset("courier", "Courier New",
        "'Courier New', Courier, monospace", "Courier New"),
    // Bundled families — always available, shipped under /fonts/.
    FontPreset("jetbrainsMono", "JetBrains Mono",
        "'JetBrains Mono', ui-monospace, monospace", null, bundled = true),
    FontPreset("firaCode", "Fira Code",
        "'Fira Code', ui-monospace, monospace", null, bundled = true),
    FontPreset("cascadiaCode", "Cascadia Code",
        "'Cascadia Code', ui-monospace, monospace", null, bundled = true),
    FontPreset("ibmPlexMono", "IBM Plex Mono",
        "'IBM Plex Mono', ui-monospace, monospace", null, bundled = true),
    FontPreset("geistMono", "Geist Mono",
        "'Geist Mono', ui-monospace, monospace", null, bundled = true),
    FontPreset("sourceCodePro", "Source Code Pro",
        "'Source Code Pro', ui-monospace, monospace", null, bundled = true),
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
 *   Presets with `detectFamily == null` (the `system` stack plus all
 *   [FontPreset.bundled] families shipped under `/fonts/`) are always in the
 *   returned set.
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
 * For bundled presets, waits for the font file to finish loading via the
 * `document.fonts.load(...)` FontFaceSet API before refitting each terminal.
 * xterm.js renders to canvas and caches character metrics on the first paint,
 * so a late font load would not trigger a re-measure on its own — the user
 * would see wrong cell dimensions until a manual resize. System-detected
 * presets skip the wait because the font is either already present or the
 * user gets graceful monospace fallback.
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
    val preset = fontPresets.firstOrNull { it.key == key }
    if (preset?.bundled == true) {
        // Primary family is the first comma-separated entry in the stack
        // (e.g. "'JetBrains Mono'" from "'JetBrains Mono', ui-monospace, …").
        val primary = stack.substringBefore(',').trim()
        val fonts = document.asDynamic().fonts
        if (fonts != null) {
            // Kick off loads for both weights xterm might use so bold output
            // doesn't synthesise-bold until the real 700 weight lands.
            fonts.load("400 16px $primary")
            fonts.load("700 16px $primary").then({ _: dynamic ->
                applyStackToTerminals(stack)
            }, { _: dynamic ->
                // Load failure is cosmetic — fall back to applying the stack
                // anyway so the browser can do its best with whatever loaded.
                applyStackToTerminals(stack)
            })
            return
        }
    }
    applyStackToTerminals(stack)
}

/**
 * Pushes a resolved CSS font stack to every live xterm.js terminal and to
 * the `--t-font-mono` custom property. Factored out of [applyGlobalFontFamily]
 * so the bundled-font code path can defer this call until the font has
 * finished loading.
 *
 * @param stack the fully resolved CSS `font-family` stack to apply.
 */
private fun applyStackToTerminals(stack: String) {
    for ((_, entry) in terminals) {
        entry.term.options.fontFamily = stack
        try { safeFit(entry.term, entry.fit) } catch (_: Throwable) {}
    }
    (document.documentElement as? HTMLElement)
        ?.style?.setProperty("--t-font-mono", stack)
}
