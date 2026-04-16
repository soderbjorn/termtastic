package se.soderbjorn.termtastic.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import se.soderbjorn.termtastic.client.SidebarPalette

// Dark palette
private val SidebarBackgroundDark = Color(SidebarPalette.BACKGROUND)
private val SidebarSurfaceDark = Color(SidebarPalette.SURFACE)
private val SidebarTextPrimaryDark = Color(SidebarPalette.TEXT_PRIMARY)
private val SidebarTextSecondaryDark = Color(SidebarPalette.TEXT_SECONDARY)

// Light palette
private val SidebarBackgroundLight = Color(SidebarPalette.BACKGROUND_LIGHT)
private val SidebarSurfaceLight = Color(SidebarPalette.SURFACE_LIGHT)
private val SidebarTextPrimaryLight = Color(SidebarPalette.TEXT_PRIMARY_LIGHT)
private val SidebarTextSecondaryLight = Color(SidebarPalette.TEXT_SECONDARY_LIGHT)

// Dots are the same in both themes
internal val SidebarDotWorking = Color(SidebarPalette.DOT_WORKING)
internal val SidebarDotWaiting = Color(SidebarPalette.DOT_WAITING)

// Adaptive accessors that follow system dark/light setting
internal val SidebarBackground: Color
    @Composable @ReadOnlyComposable
    get() = if (isSystemInDarkTheme()) SidebarBackgroundDark else SidebarBackgroundLight

internal val SidebarSurface: Color
    @Composable @ReadOnlyComposable
    get() = if (isSystemInDarkTheme()) SidebarSurfaceDark else SidebarSurfaceLight

internal val SidebarTextPrimary: Color
    @Composable @ReadOnlyComposable
    get() = if (isSystemInDarkTheme()) SidebarTextPrimaryDark else SidebarTextPrimaryLight

internal val SidebarTextSecondary: Color
    @Composable @ReadOnlyComposable
    get() = if (isSystemInDarkTheme()) SidebarTextSecondaryDark else SidebarTextSecondaryLight
