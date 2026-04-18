/**
 * Theme-related utility functions for the Termtastic web frontend.
 *
 * Provides helpers for resolving the current effective theme colors based on
 * appearance mode (Light/Dark/Auto), detecting the system color scheme preference,
 * and determining whether a background color is light or dark for contrast adjustment.
 *
 * @see buildXtermTheme
 * @see applyAppearanceClass
 */
package se.soderbjorn.termtastic

import kotlinx.browser.window
import se.soderbjorn.termtastic.client.PaneStatusDisplay
import se.soderbjorn.termtastic.client.parsePaneStatusDisplay

/** Feature flag: when true, applies a "spiced" variant to dark themes. Currently disabled. */
const val DARK_SPICED = false

/** Feature flag: when true, shows a debug menu on localhost. Currently disabled. */
const val SHOW_DEBUG_MENU = false

/**
 * The default terminal theme, resolved by name from the [recommendedThemes] list.
 */
val defaultTheme: TerminalTheme
    get() = recommendedThemes.first { it.name == DEFAULT_THEME_NAME }

/**
 * Checks whether the user's system prefers dark mode via the `prefers-color-scheme` media query.
 *
 * @return true if the system prefers dark mode
 */
fun systemPrefersDark(): Boolean =
    window.matchMedia("(prefers-color-scheme: dark)").matches

/**
 * Determines whether the light variant of a theme should be active based on the
 * current appearance setting.
 *
 * @param appearance the user's selected appearance mode
 * @return true if light mode should be used
 */
fun isLightActive(appearance: Appearance): Boolean = when (appearance) {
    Appearance.Light -> true
    Appearance.Dark -> false
    Appearance.Auto -> !systemPrefersDark()
}

/**
 * Returns the foreground color for the given theme and appearance mode.
 *
 * @param theme the terminal theme
 * @param appearance the user's selected appearance mode
 * @return the hex foreground color string
 */
fun themeForegroundForCurrent(theme: TerminalTheme, appearance: Appearance): String =
    if (isLightActive(appearance)) theme.lightFg else theme.darkFg

/**
 * Returns the background color for the given theme and appearance mode.
 *
 * @param theme the terminal theme
 * @param appearance the user's selected appearance mode
 * @return the hex background color string
 */
fun themeBackgroundForCurrent(theme: TerminalTheme, appearance: Appearance): String =
    if (isLightActive(appearance)) theme.lightBg else theme.darkBg

/**
 * Determines whether a hex background color is perceptually light using the
 * YIQ luminance formula (weighted sum: R*299 + G*587 + B*114).
 *
 * Used to adjust ANSI color palettes for readability against the terminal background.
 *
 * @param bg the hex color string (e.g. "#1e1e1e")
 * @return true if the background is light (luminance > 140), false otherwise
 */
fun isBackgroundLight(bg: String): Boolean {
    if (bg.length < 7 || !bg.startsWith("#")) return false
    val r = bg.substring(1, 3).toIntOrNull(16) ?: return false
    val g = bg.substring(3, 5).toIntOrNull(16) ?: return false
    val b = bg.substring(5, 7).toIntOrNull(16) ?: return false
    return (r * 299 + g * 587 + b * 114) / 1000 > 140
}
