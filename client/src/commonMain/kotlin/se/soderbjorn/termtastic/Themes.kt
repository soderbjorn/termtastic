/**
 * Terminal colour theme definitions for the Termtastic terminal emulator.
 *
 * Each [TerminalTheme] carries a dark-mode and light-mode foreground/background
 * colour pair. The [recommendedThemes] list is the canonical palette surfaced in
 * every platform's theme picker; it is kept in sync with the HTML colour-picker
 * tool at `tools/neon-green-picker.html`.
 *
 * @see se.soderbjorn.termtastic.client.UiSettings
 * @see se.soderbjorn.termtastic.client.effectiveColors
 */
package se.soderbjorn.termtastic

/**
 * A terminal colour theme with separate foreground and background colours for
 * dark and light appearance modes.
 *
 * @property name   Human-readable theme name shown in the picker UI.
 * @property darkFg Hex foreground colour used in dark mode (e.g. `"#33ff66"`).
 * @property lightFg Hex foreground colour used in light mode.
 * @property darkBg Hex background colour used in dark mode. Defaults to pure
 *   black; designer themes like Solarized override this with a tinted colour.
 * @property lightBg Hex background colour used in light mode. Defaults to pure
 *   white; designer themes override this with their canonical light background.
 */
data class TerminalTheme(
    val name: String,
    val darkFg: String,
    val lightFg: String,
    val darkBg: String = "#000000",
    val lightBg: String = "#ffffff",
    /**
     * Optional hand-tuned colour overrides for semantic tokens that should
     * not be derived from the fg/bg seed.
     *
     * Keys follow the pattern `"group.token.mode"`, e.g.
     * `"syntax.keyword.dark"`, `"sidebar.bg.light"`.  Values are ARGB
     * [Long] values.  The [resolve] function checks this map before falling
     * back to the deterministic derivation.
     *
     * Most themes leave this `null`; only designer palettes (Solarized,
     * Tokyo Night, etc.) specify overrides for their signature syntax colours
     * or other tokens where the derivation would look flat.
     */
    val overrides: Map<String, Long>? = null,
)

/**
 * Curated list of terminal themes available in the theme picker.
 *
 * The first group uses the default pure-black/white backgrounds; the second
 * group ("tinted-background themes") specifies custom background colours for
 * both modes, giving the picker visual variety (cream Solarized Light, deep
 * navy Tokyo Night, etc.).
 *
 * Kept in sync with the "Recommended" tab in `tools/neon-green-picker.html`.
 */
// ── Syntax override maps for designer themes ──────────────────────────

/**
 * Syntax colour overrides for the Tron theme. The green-accent base would
 * produce a monochromatic syntax palette without these hand-tuned values
 * from the design spec.
 */
private val tronOverrides: Map<String, Long> = mapOf(
    "syntax.keyword.dark"  to 0xFF7EE2C1L, "syntax.keyword.light"  to 0xFF00795CL,
    "syntax.string.dark"   to 0xFFE6C380L, "syntax.string.light"   to 0xFFA8771AL,
    "syntax.number.dark"   to 0xFF84D2C7L, "syntax.number.light"   to 0xFF1A7A75L,
    "syntax.comment.dark"  to 0xFF5E7570L, "syntax.comment.light"  to 0xFF8AA29AL,
    "syntax.function.dark" to 0xFF9EE8C4L, "syntax.function.light" to 0xFF1A7A4AL,
    "syntax.type.dark"     to 0xFFE8A5C1L, "syntax.type.light"     to 0xFFB64A78L,
    "syntax.operator.dark" to 0xFFC9D2CDL, "syntax.operator.light" to 0xFF3D4945L,
    "syntax.constant.dark" to 0xFFFF9F6EL, "syntax.constant.light" to 0xFFB85C1CL,
)

/**
 * Syntax colour overrides for the Solarized theme, using the canonical
 * Solarized accent palette (same values in both modes for most tokens).
 */
private val solarizedOverrides: Map<String, Long> = mapOf(
    "syntax.keyword.dark"  to 0xFF859900L, "syntax.keyword.light"  to 0xFF859900L,
    "syntax.string.dark"   to 0xFF2AA198L, "syntax.string.light"   to 0xFF2AA198L,
    "syntax.number.dark"   to 0xFFD33682L, "syntax.number.light"   to 0xFFD33682L,
    "syntax.comment.dark"  to 0xFF586E75L, "syntax.comment.light"  to 0xFF93A1A1L,
    "syntax.function.dark" to 0xFF268BD2L, "syntax.function.light" to 0xFF268BD2L,
    "syntax.type.dark"     to 0xFFB58900L, "syntax.type.light"     to 0xFFB58900L,
    "syntax.operator.dark" to 0xFF93A1A1L, "syntax.operator.light" to 0xFF657B83L,
    "syntax.constant.dark" to 0xFFCB4B16L, "syntax.constant.light" to 0xFFCB4B16L,
)

/**
 * Syntax colour overrides for the Tokyo Night theme, using the official
 * Storm palette values.
 */
private val tokyoNightOverrides: Map<String, Long> = mapOf(
    "syntax.keyword.dark"  to 0xFFBB9AF7L, "syntax.keyword.light"  to 0xFF5A3E8EL,
    "syntax.string.dark"   to 0xFF9ECE6AL, "syntax.string.light"   to 0xFF485E30L,
    "syntax.number.dark"   to 0xFFFF9E64L, "syntax.number.light"   to 0xFFA05A20L,
    "syntax.comment.dark"  to 0xFF565F89L, "syntax.comment.light"  to 0xFF8A91A8L,
    "syntax.function.dark" to 0xFF7AA2F7L, "syntax.function.light" to 0xFF2E5CA8L,
    "syntax.type.dark"     to 0xFF7DCFFFL, "syntax.type.light"     to 0xFF1F6A94L,
    "syntax.operator.dark" to 0xFF89DDFFL, "syntax.operator.light" to 0xFF1F6A94L,
    "syntax.constant.dark" to 0xFFF7768EL, "syntax.constant.light" to 0xFF9A2A44L,
)

/**
 * Syntax colour overrides for the Rose Pine theme, using the official
 * dawn/moon palette values.
 */
private val rosePineOverrides: Map<String, Long> = mapOf(
    "syntax.keyword.dark"  to 0xFFC4A7E7L, "syntax.keyword.light"  to 0xFF6E5599L,
    "syntax.string.dark"   to 0xFFF6C177L, "syntax.string.light"   to 0xFFA87B2AL,
    "syntax.number.dark"   to 0xFFEB6F92L, "syntax.number.light"   to 0xFFA83352L,
    "syntax.comment.dark"  to 0xFF6E6A86L, "syntax.comment.light"  to 0xFF908CAAL,
    "syntax.function.dark" to 0xFF9CCFD8L, "syntax.function.light" to 0xFF2A7B90L,
    "syntax.type.dark"     to 0xFFEBBCBAL, "syntax.type.light"     to 0xFFA85A58L,
    "syntax.operator.dark" to 0xFF908CAAL, "syntax.operator.light" to 0xFF575279L,
    "syntax.constant.dark" to 0xFFEB6F92L, "syntax.constant.light" to 0xFFA83352L,
)

val recommendedThemes: List<TerminalTheme> = listOf(
    TerminalTheme("Matrix",        "#33ff66", "#0a7d2c"),
    TerminalTheme("Mint terminal", "#33ff99", "#0b8a5b"),
    TerminalTheme("Cyber teal",    "#00e5ff", "#006d80"),
    TerminalTheme("Tron",          "#00ff9f", "#00795c", overrides = tronOverrides),
    TerminalTheme("Vapor pink",    "#ff77ff", "#a3008c"),
    TerminalTheme("Amber Glow",    "#F4B869", "#A87620"),
    TerminalTheme("Amber CRT",     "#ffb000", "#8a4b00"),
    TerminalTheme("Ember",         "#ff6d3d", "#a23b00"),
    TerminalTheme("Plasma",        "#ff3df8", "#9b008f"),
    TerminalTheme("Cobalt",        "#5b9dff", "#0a3d91"),
    TerminalTheme("Aqua glow",     "#00ffe1", "#007466"),
    TerminalTheme("Sunset",        "#ffaa33", "#9a4a00"),
    TerminalTheme("Hot rose",      "#ff5fa2", "#a00050"),
    TerminalTheme("Cyber lime",    "#ccff00", "#5a7800"),
    TerminalTheme("Royal violet",  "#b388ff", "#4527a0"),
    TerminalTheme("Synthwave",     "#ff7edb", "#b33d8f"),
    TerminalTheme("Forest",        "#7fce6f", "#2d6a1f"),
    TerminalTheme("Ocean",         "#4fc3f7", "#0277bd"),
    TerminalTheme("Lava",          "#ff5722", "#b71c1c"),
    TerminalTheme("Ice",           "#5dd8ff", "#006e93"),
    TerminalTheme("Coral",         "#ff8a65", "#c1421c"),
    TerminalTheme("Lavender",      "#b39ddb", "#5e35b1"),
    TerminalTheme("Pastel Pink",   "#ffb6d9", "#c2185b"),
    TerminalTheme("Lime Burst",    "#d4ff00", "#6b7c00"),
    TerminalTheme("Magenta",       "#ff00ff", "#8b008b"),
    TerminalTheme("Sunflower",     "#ffd700", "#b8860b"),
    TerminalTheme("Cherry",        "#ff4d6d", "#9d0028"),
    TerminalTheme("Sky",           "#87ceeb", "#1e6091"),
    TerminalTheme("Mint Cream",    "#98ff98", "#2e8b57"),
    TerminalTheme("Peach",         "#ffcc99", "#cc6600"),
    TerminalTheme("Indigo",        "#6f70ff", "#1a237e"),
    TerminalTheme("Sand",          "#d2b48c", "#8b6914"),
    TerminalTheme("Crimson",       "#ff5252", "#8b0000"),
    TerminalTheme("Sea Foam",      "#71eeb8", "#00695c"),
    TerminalTheme("Apricot",       "#ffb347", "#c66900"),
    TerminalTheme("Ultraviolet",   "#9d4edd", "#4a148c"),
    TerminalTheme("Periwinkle",    "#aab8ff", "#3949ab"),
    TerminalTheme("Teal Storm",    "#20d6c7", "#00695c"),
    TerminalTheme("Olive",         "#c5d637", "#5d6e0a"),
    TerminalTheme("Cyan Pop",      "#00ffff", "#007a7a"),
    TerminalTheme("Honey",         "#f4c430", "#a47000"),
    TerminalTheme("Sage",          "#b2c8a4", "#5d7c4f"),

    // Tinted-background themes — designer palettes with their canonical
    // background colors instead of pure black/white. These give the picker
    // real variation (cream Solarized Light, deep navy Tokyo Night, etc.).
    TerminalTheme("Solarized",      darkFg = "#93a1a1", lightFg = "#657b83",
                                    darkBg = "#002b36", lightBg = "#fdf6e3",
                                    overrides = solarizedOverrides),
    TerminalTheme("Gruvbox",        darkFg = "#ebdbb2", lightFg = "#3c3836",
                                    darkBg = "#282828", lightBg = "#fbf1c7"),
    TerminalTheme("Nord",           darkFg = "#d8dee9", lightFg = "#2e3440",
                                    darkBg = "#2e3440", lightBg = "#eceff4"),
    TerminalTheme("Dracula",        darkFg = "#f8f8f2", lightFg = "#282a36",
                                    darkBg = "#282a36", lightBg = "#f8f8f2"),
    TerminalTheme("Monokai",        darkFg = "#f8f8f2", lightFg = "#272822",
                                    darkBg = "#272822", lightBg = "#fafafa"),
    TerminalTheme("Tokyo Night",    darkFg = "#a9b1d6", lightFg = "#343b58",
                                    darkBg = "#1a1b26", lightBg = "#d5d6db",
                                    overrides = tokyoNightOverrides),
    TerminalTheme("One Dark",       darkFg = "#abb2bf", lightFg = "#383a42",
                                    darkBg = "#282c34", lightBg = "#fafafa"),
    TerminalTheme("GitHub",         darkFg = "#c9d1d9", lightFg = "#24292f",
                                    darkBg = "#0d1117", lightBg = "#ffffff"),
    TerminalTheme("Catppuccin",     darkFg = "#cdd6f4", lightFg = "#4c4f69",
                                    darkBg = "#1e1e2e", lightBg = "#eff1f5"),
    TerminalTheme("Rose Pine",      darkFg = "#e0def4", lightFg = "#575279",
                                    darkBg = "#191724", lightBg = "#faf4ed",
                                    overrides = rosePineOverrides),
    TerminalTheme("Ayu",            darkFg = "#b3b1ad", lightFg = "#5c6773",
                                    darkBg = "#0a0e14", lightBg = "#fafafa"),
    TerminalTheme("Ayu Mirage",     darkFg = "#cbccc6", lightFg = "#5c6773",
                                    darkBg = "#1f2430", lightBg = "#fafafa"),
    TerminalTheme("Night Owl",      darkFg = "#d6deeb", lightFg = "#403f53",
                                    darkBg = "#011627", lightBg = "#fbfbfb"),
    TerminalTheme("Material",       darkFg = "#eeffff", lightFg = "#37474f",
                                    darkBg = "#263238", lightBg = "#fafafa"),
    TerminalTheme("Cobalt2",        darkFg = "#ffffff", lightFg = "#193549",
                                    darkBg = "#193549", lightBg = "#e8eef2"),
    TerminalTheme("Ubuntu",         darkFg = "#eeeeec", lightFg = "#300a24",
                                    darkBg = "#300a24", lightBg = "#f5e6f0"),
    TerminalTheme("Sepia",          darkFg = "#e8d9b6", lightFg = "#5b4636",
                                    darkBg = "#3a2e25", lightBg = "#f4ecd8"),
    TerminalTheme("Pencil",         darkFg = "#f1f1f1", lightFg = "#424242",
                                    darkBg = "#212121", lightBg = "#f1f1f1"),
    TerminalTheme("Hopscotch",      darkFg = "#b9b5b8", lightFg = "#322931",
                                    darkBg = "#322931", lightBg = "#ffffff"),
    TerminalTheme("Spacegray",      darkFg = "#c0c5ce",  lightFg = "#2c2e34",
                                    darkBg = "#2c2e34", lightBg = "#f5f5f5"),
    TerminalTheme("Paper White",    darkFg = "#222222", lightFg = "#222222",
                                    darkBg = "#f5f5dc", lightBg = "#fffff8"),
    TerminalTheme("Mono Black",     darkFg = "#ffffff", lightFg = "#000000",
                                    darkBg = "#000000", lightBg = "#ffffff"),
)

/** Name of the theme applied when the server has no stored preference. */
const val DEFAULT_THEME_NAME = "Tron"

/**
 * Name of the default theme bundle used for the light slot on fresh
 * installs. Resolves to a light-optimised preset in [defaultThemeConfigs].
 */
const val DEFAULT_LIGHT_THEME_NAME = "Paper & Ink"

/**
 * Name of the default theme bundle used for the dark slot on fresh
 * installs. Resolves to a dark-optimised preset in [defaultThemeConfigs].
 */
const val DEFAULT_DARK_THEME_NAME = "Neon Circuit"

/**
 * User preference for light/dark appearance. [Auto] defers to the host
 * platform's system setting.
 */
enum class Appearance { Auto, Dark, Light }

/**
 * A user-defined custom colour scheme. Unlike [recommendedThemes] which are
 * hard-coded at compile time, these are persisted server-side as part of the
 * UI settings blob and may be freely edited by the user.
 *
 * A [CustomScheme] materialises into a [TerminalTheme] at apply time via
 * [toTerminalTheme]. The user may edit every semantic override token
 * independently for dark and light appearance; default [recommendedThemes]
 * remain read-only and must be cloned into a [CustomScheme] before editing.
 *
 * @property name      Human-readable scheme name; unique across custom schemes.
 * @property darkFg    Hex foreground colour used in dark mode.
 * @property lightFg   Hex foreground colour used in light mode.
 * @property darkBg    Hex background colour used in dark mode.
 * @property lightBg   Hex background colour used in light mode.
 * @property overrides Hand-tuned ARGB override values keyed by
 *   `"group.token.mode"` (e.g. `"syntax.keyword.dark"`). Mirrors
 *   [TerminalTheme.overrides]; empty map by default.
 */
data class CustomScheme(
    val name: String,
    val darkFg: String,
    val lightFg: String,
    val darkBg: String,
    val lightBg: String,
    val overrides: Map<String, Long> = emptyMap(),
) {
    /**
     * Materialise this custom scheme into a [TerminalTheme] suitable for
     * passing to [resolve] / the theme pipeline. Called whenever a custom
     * scheme is referenced from a theme slot (main or per-section).
     *
     * @return a [TerminalTheme] with the same name and colours.
     */
    fun toTerminalTheme(): TerminalTheme = TerminalTheme(
        name = name,
        darkFg = darkFg,
        lightFg = lightFg,
        darkBg = darkBg,
        lightBg = lightBg,
        overrides = overrides.ifEmpty { null },
    )
}
