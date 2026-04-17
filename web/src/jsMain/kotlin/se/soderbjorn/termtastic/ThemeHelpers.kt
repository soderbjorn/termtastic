package se.soderbjorn.termtastic

import kotlinx.browser.window
import se.soderbjorn.termtastic.client.PaneStatusDisplay
import se.soderbjorn.termtastic.client.parsePaneStatusDisplay

const val DARK_SPICED = false
const val SHOW_DEBUG_MENU = false

val defaultTheme: TerminalTheme
    get() = recommendedThemes.first { it.name == DEFAULT_THEME_NAME }

fun systemPrefersDark(): Boolean =
    window.matchMedia("(prefers-color-scheme: dark)").matches

fun isLightActive(appearance: Appearance): Boolean = when (appearance) {
    Appearance.Light -> true
    Appearance.Dark -> false
    Appearance.Auto -> !systemPrefersDark()
}

fun themeForegroundForCurrent(theme: TerminalTheme, appearance: Appearance): String =
    if (isLightActive(appearance)) theme.lightFg else theme.darkFg

fun themeBackgroundForCurrent(theme: TerminalTheme, appearance: Appearance): String =
    if (isLightActive(appearance)) theme.lightBg else theme.darkBg

fun isBackgroundLight(bg: String): Boolean {
    if (bg.length < 7 || !bg.startsWith("#")) return false
    val r = bg.substring(1, 3).toIntOrNull(16) ?: return false
    val g = bg.substring(3, 5).toIntOrNull(16) ?: return false
    val b = bg.substring(5, 7).toIntOrNull(16) ?: return false
    return (r * 299 + g * 587 + b * 114) / 1000 > 140
}
