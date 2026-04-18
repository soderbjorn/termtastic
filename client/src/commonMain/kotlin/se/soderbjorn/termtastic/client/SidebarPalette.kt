/**
 * Sidebar colour palette constants shared across all Termtastic client
 * platforms.
 *
 * These ARGB hex values mirror the CSS custom properties defined in
 * `web/src/jsMain/resources/styles.css` (`:root` for dark, `body.appearance-light`
 * for light). Native clients (Android Compose, SwiftUI) convert them to their
 * respective `Color` types.
 *
 * @see se.soderbjorn.termtastic.client.PaneStatusDisplay
 */
package se.soderbjorn.termtastic.client

/**
 * Shared ARGB colour constants for the sidebar theme, matching the `:root`
 * and `body.appearance-light` blocks in `web/src/jsMain/resources/styles.css`.
 * Platform-specific UI code converts these to native color types (`Color`
 * on Android, `SwiftUI.Color` on iOS).
 */
object SidebarPalette {
    // ── Dark theme (default) ───────────────────────────────────────

    /** Dark-mode sidebar background. */
    const val BACKGROUND = 0xFF1C1C1E
    /** Dark-mode card/surface background. */
    const val SURFACE = 0xFF2C2C2E
    /** Dark-mode primary text colour. */
    const val TEXT_PRIMARY = 0xFFF5F5F5
    /** Dark-mode secondary/muted text colour. */
    const val TEXT_SECONDARY = 0xFF8E8E93
    /** Status dot colour for a "working" (active) PTY session (blue). */
    const val DOT_WORKING = 0xFF5AC8FA
    /** Status dot colour for a "waiting" (idle/blocked) PTY session (red). */
    const val DOT_WAITING = 0xFFFF6961

    // ── Light theme ────────────────────────────────────────────────

    /** Light-mode sidebar background. */
    const val BACKGROUND_LIGHT = 0xFFF5F5F7
    /** Light-mode card/surface background. */
    const val SURFACE_LIGHT = 0xFFFFFFFF
    /** Light-mode primary text colour. */
    const val TEXT_PRIMARY_LIGHT = 0xFF1C1C1E
    /** Light-mode secondary/muted text colour. */
    const val TEXT_SECONDARY_LIGHT = 0xFF6E6E73
}
