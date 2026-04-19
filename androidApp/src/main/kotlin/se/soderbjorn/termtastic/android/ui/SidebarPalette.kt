/**
 * Sidebar colour palette for the Termtastic Android app.
 *
 * Derives adaptive colours from the semantic [ResolvedPalette] system.
 * The default palette is resolved from the Tron theme (the app default);
 * screens with access to the [AppBackingViewModel] should resolve from
 * the user's actual selected theme instead.
 *
 * @see se.soderbjorn.termtastic.ResolvedPalette
 * @see se.soderbjorn.termtastic.resolve
 */
package se.soderbjorn.termtastic.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import se.soderbjorn.termtastic.DEFAULT_THEME_NAME
import se.soderbjorn.termtastic.ResolvedPalette
import se.soderbjorn.termtastic.recommendedThemes
import se.soderbjorn.termtastic.resolve

/** The default theme used when no ViewModel is available. */
private val defaultTheme = recommendedThemes.first { it.name == DEFAULT_THEME_NAME }

/**
 * Resolves the default sidebar palette for the current system appearance.
 *
 * @return the resolved palette for the default theme
 */
@Composable @ReadOnlyComposable
private fun defaultPalette(): ResolvedPalette =
    defaultTheme.resolve(isDark = isSystemInDarkTheme())

/** Adaptive sidebar background colour, derived from the semantic palette. */
internal val SidebarBackground: Color
    @Composable @ReadOnlyComposable
    get() = Color(defaultPalette().sidebar.bg)

/** Adaptive sidebar surface/card colour for elevated elements like top bars. */
internal val SidebarSurface: Color
    @Composable @ReadOnlyComposable
    get() = Color(defaultPalette().surface.raised)

/** Adaptive primary text colour for sidebar headings and labels. */
internal val SidebarTextPrimary: Color
    @Composable @ReadOnlyComposable
    get() = Color(defaultPalette().sidebar.text)

/** Adaptive secondary text colour for sidebar body text and subdued labels. */
internal val SidebarTextSecondary: Color
    @Composable @ReadOnlyComposable
    get() = Color(defaultPalette().sidebar.textDim)

/** Theme accent colour derived from the terminal foreground. */
internal val SidebarAccent: Color
    @Composable @ReadOnlyComposable
    get() = Color(defaultPalette().accent.primary)
