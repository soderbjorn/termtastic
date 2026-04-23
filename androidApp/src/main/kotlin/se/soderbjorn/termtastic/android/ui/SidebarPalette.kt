/**
 * Sidebar colour palette and shared [UiSettings] provider for the Termtastic
 * Android app.
 *
 * Derives adaptive colours from the semantic [ResolvedPalette] system using
 * the user's selected theme from [UiSettings]. When no settings have been
 * loaded yet (e.g. before the first server fetch), falls back to the default
 * Tron theme.
 *
 * [LocalUiSettings] is a [CompositionLocal] provided at the app root by
 * [se.soderbjorn.termtastic.android.ui.TermtasticApp] so that all screens
 * can access the user's theme settings without fetching independently.
 *
 * @see se.soderbjorn.termtastic.ResolvedPalette
 * @see se.soderbjorn.termtastic.resolve
 * @see se.soderbjorn.termtastic.client.UiSettings
 */
package se.soderbjorn.termtastic.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import se.soderbjorn.termtastic.Appearance
import se.soderbjorn.termtastic.DEFAULT_THEME_NAME
import se.soderbjorn.termtastic.ResolvedPalette
import se.soderbjorn.termtastic.recommendedColorSchemes
import se.soderbjorn.termtastic.resolve
import se.soderbjorn.termtastic.client.UiSettings

/**
 * Composition-local providing the user's [UiSettings] fetched from the server.
 *
 * Provided by [TermtasticApp] after a successful connection; defaults to
 * `null` (which causes palette accessors to fall back to the Tron theme).
 *
 * @see TermtasticApp
 */
val LocalUiSettings = compositionLocalOf<UiSettings?> { null }

/** The default theme used when no [UiSettings] have been loaded yet. */
private val defaultTheme = recommendedColorSchemes.first { it.name == DEFAULT_THEME_NAME }

/**
 * Resolves the sidebar palette for the current theme and system appearance.
 *
 * Uses the user's selected sidebar section theme (or global theme) from
 * [LocalUiSettings], respecting the appearance preference. Falls back to
 * the default Tron theme when settings are not yet available.
 *
 * @return the resolved palette for the sidebar section
 */
@Composable @ReadOnlyComposable
private fun sidebarPalette(): ResolvedPalette {
    val settings = LocalUiSettings.current
    val systemIsDark = isSystemInDarkTheme()
    val theme = settings?.sectionTheme("sidebar") ?: defaultTheme
    val appearance = settings?.appearance ?: Appearance.Auto
    return theme.resolve(appearance, systemIsDark)
}

/** Adaptive sidebar background colour, derived from the semantic palette. */
internal val SidebarBackground: Color
    @Composable @ReadOnlyComposable
    get() = Color(sidebarPalette().sidebar.bg)

/** Adaptive sidebar surface/card colour for elevated elements like top bars. */
internal val SidebarSurface: Color
    @Composable @ReadOnlyComposable
    get() = Color(sidebarPalette().surface.raised)

/** Adaptive primary text colour for sidebar headings and labels. */
internal val SidebarTextPrimary: Color
    @Composable @ReadOnlyComposable
    get() = Color(sidebarPalette().sidebar.text)

/** Adaptive secondary text colour for sidebar body text and subdued labels. */
internal val SidebarTextSecondary: Color
    @Composable @ReadOnlyComposable
    get() = Color(sidebarPalette().sidebar.textDim)

/** Theme accent colour derived from the terminal foreground. */
internal val SidebarAccent: Color
    @Composable @ReadOnlyComposable
    get() = Color(sidebarPalette().accent.primary)
