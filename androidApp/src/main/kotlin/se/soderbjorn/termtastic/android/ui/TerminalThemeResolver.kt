/**
 * Theme/appearance resolution for the Android terminal screen.
 *
 * [rememberTerminalPalette] reads the optional centrally-loaded UI
 * settings from [LocalUiSettings] (or fetches them per-screen as a
 * fallback), resolves the active terminal section's [ResolvedPalette]
 * for the current system dark/light mode, and returns it.
 *
 * [applyTerminalColors] mutates a [TerminalEmulator]'s default colour
 * indices (what SGR 0 / "default" resolves to) so existing rows repaint
 * on the next `onScreenUpdated()`, and sets the [TerminalView]'s own
 * background so the letterbox around the text grid matches.
 *
 * @see TerminalScreen
 */
package se.soderbjorn.termtastic.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import se.soderbjorn.darkness.core.Appearance
import se.soderbjorn.darkness.core.DEFAULT_THEME_NAME
import se.soderbjorn.darkness.core.ResolvedPalette
import se.soderbjorn.darkness.core.UiSettings
import se.soderbjorn.darkness.core.recommendedColorSchemes
import se.soderbjorn.darkness.core.resolve
import se.soderbjorn.termtastic.client.TermtasticClient
import se.soderbjorn.termtastic.client.fetchUiSettings

/**
 * Resolve the terminal section's [ResolvedPalette] for the supplied
 * client + session. Falls back to the recommended default theme until
 * the central settings (or the per-screen fetch) arrive.
 *
 * @param client     the connected client used to fetch UI settings on first run.
 * @param sessionId  used as a remember key so a session change re-fetches.
 */
@Composable
internal fun rememberTerminalPalette(
    client: TermtasticClient,
    sessionId: String,
): ResolvedPalette {
    val centralSettings = LocalUiSettings.current
    var localSettings by remember(sessionId) { mutableStateOf<UiSettings?>(null) }
    LaunchedEffect(client, sessionId) {
        if (centralSettings == null) {
            localSettings = client.fetchUiSettings()
        }
    }
    val uiSettings = centralSettings ?: localSettings
    val systemIsDark = isSystemInDarkTheme()
    val defaultTheme = remember { recommendedColorSchemes.first { it.name == DEFAULT_THEME_NAME } }
    return remember(uiSettings, systemIsDark) {
        val theme = uiSettings?.sectionTheme("terminal") ?: defaultTheme
        val appearance = uiSettings?.appearance ?: Appearance.Auto
        theme.resolve(appearance, systemIsDark)
    }
}

/**
 * Paint the terminal with the resolved theme palette. Mutates the
 * emulator's default foreground/background and cursor colour indices
 * so existing rows repaint on the next onScreenUpdated(), and sets the
 * view's own background so the letterbox around the text grid matches.
 */
internal fun applyTerminalColors(
    view: TerminalView,
    emulator: TerminalEmulator,
    palette: ResolvedPalette,
) {
    val fg = palette.terminal.fg.toInt()
    val bg = palette.terminal.bg.toInt()
    val cursor = palette.terminal.cursor.toInt()
    emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND] = fg
    emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = bg
    emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] = cursor
    view.setBackgroundColor(bg)
}
