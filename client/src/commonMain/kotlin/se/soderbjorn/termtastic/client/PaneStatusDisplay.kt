/**
 * Pane status indicator display mode for the sidebar and tab bar.
 *
 * The user can choose how active/waiting PTY sessions are visually indicated
 * in the sidebar: coloured dots, a glow effect, both, or nothing. This
 * preference is stored server-side as part of the UI settings JSON blob.
 *
 * @see se.soderbjorn.termtastic.client.viewmodel.AppBackingViewModel.State.paneStatusDisplay
 */
package se.soderbjorn.termtastic.client

/**
 * Visual style for the pane activity indicator in the sidebar.
 *
 * - [Dots]  -- small coloured dots (blue = working, red = waiting).
 * - [Glow]  -- a subtle glow around the pane entry (default).
 * - [Both]  -- dots and glow combined.
 * - [None]  -- no indicator at all.
 */
enum class PaneStatusDisplay { Dots, Glow, Both, None }

/**
 * Parse a string value from the server's UI settings JSON into a
 * [PaneStatusDisplay]. Unknown or `null` values default to [PaneStatusDisplay.Glow].
 *
 * @param s the raw string value, e.g. `"Dots"`, or `null`.
 * @return the corresponding enum member.
 */
fun parsePaneStatusDisplay(s: String?): PaneStatusDisplay =
    when (s) {
        "Dots" -> PaneStatusDisplay.Dots
        "Both" -> PaneStatusDisplay.Both
        "None" -> PaneStatusDisplay.None
        else -> PaneStatusDisplay.Glow
    }
