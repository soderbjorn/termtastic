package se.soderbjorn.termtastic.client

/**
 * Shared hex colour constants for the sidebar theme, matching the `:root`
 * and `body.appearance-light` blocks in `web/src/jsMain/resources/styles.css`.
 * Platform-specific UI code converts these to native color types (`Color`
 * on Android, `SwiftUI.Color` on iOS).
 */
object SidebarPalette {
    // Dark theme (default)
    const val BACKGROUND = 0xFF1C1C1E
    const val SURFACE = 0xFF2C2C2E
    const val TEXT_PRIMARY = 0xFFF5F5F5
    const val TEXT_SECONDARY = 0xFF8E8E93
    const val DOT_WORKING = 0xFF5AC8FA
    const val DOT_WAITING = 0xFFFF6961

    // Light theme — matches web `body.appearance-light`
    const val BACKGROUND_LIGHT = 0xFFF5F5F7
    const val SURFACE_LIGHT = 0xFFFFFFFF
    const val TEXT_PRIMARY_LIGHT = 0xFF1C1C1E
    const val TEXT_SECONDARY_LIGHT = 0xFF6E6E73
}
