/**
 * Sidebar colour palette for the Termtastic Android app.
 *
 * Wraps the shared [se.soderbjorn.termtastic.client.SidebarPalette] colour
 * constants as Compose [Color] values and exposes adaptive `@Composable`
 * getters that automatically switch between dark and light variants based on
 * [isSystemInDarkTheme]. These colours are consumed by [TreeScreen],
 * [GitListScreen], [FileBrowserListScreen], [HostsScreen], and other sidebar-
 * styled screens.
 *
 * @see se.soderbjorn.termtastic.client.SidebarPalette
 */
package se.soderbjorn.termtastic.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import se.soderbjorn.termtastic.client.SidebarPalette

// -- Dark palette --
private val SidebarBackgroundDark = Color(SidebarPalette.BACKGROUND)
private val SidebarSurfaceDark = Color(SidebarPalette.SURFACE)
private val SidebarTextPrimaryDark = Color(SidebarPalette.TEXT_PRIMARY)
private val SidebarTextSecondaryDark = Color(SidebarPalette.TEXT_SECONDARY)

// Light palette
private val SidebarBackgroundLight = Color(SidebarPalette.BACKGROUND_LIGHT)
private val SidebarSurfaceLight = Color(SidebarPalette.SURFACE_LIGHT)
private val SidebarTextPrimaryLight = Color(SidebarPalette.TEXT_PRIMARY_LIGHT)
private val SidebarTextSecondaryLight = Color(SidebarPalette.TEXT_SECONDARY_LIGHT)

/** Dot colour for panes in the "working" state (blue). Same in both themes. */
internal val SidebarDotWorking = Color(SidebarPalette.DOT_WORKING)

/** Dot colour for panes in the "waiting" state (red). Same in both themes. */
internal val SidebarDotWaiting = Color(SidebarPalette.DOT_WAITING)

/** Adaptive sidebar background colour, switching between dark and light based on system theme. */
internal val SidebarBackground: Color
    @Composable @ReadOnlyComposable
    get() = if (isSystemInDarkTheme()) SidebarBackgroundDark else SidebarBackgroundLight

/** Adaptive sidebar surface/card colour for elevated elements like top bars. */
internal val SidebarSurface: Color
    @Composable @ReadOnlyComposable
    get() = if (isSystemInDarkTheme()) SidebarSurfaceDark else SidebarSurfaceLight

/** Adaptive primary text colour for sidebar headings and labels. */
internal val SidebarTextPrimary: Color
    @Composable @ReadOnlyComposable
    get() = if (isSystemInDarkTheme()) SidebarTextPrimaryDark else SidebarTextPrimaryLight

/** Adaptive secondary text colour for sidebar body text and subdued labels. */
internal val SidebarTextSecondary: Color
    @Composable @ReadOnlyComposable
    get() = if (isSystemInDarkTheme()) SidebarTextSecondaryDark else SidebarTextSecondaryLight
