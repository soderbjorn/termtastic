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
 * Visual style for the pane activity indicator in the sidebar, tabs, and pane headers.
 *
 * - [On]   -- spinners for "working" state, opacity pulsation for "waiting" state.
 * - [None] -- no indicator at all.
 */
enum class PaneStatusDisplay { On, None }

/**
 * Parse a string value from the server's UI settings JSON into a
 * [PaneStatusDisplay]. Legacy values (`"Dots"`, `"Glow"`, `"Both"`) are
 * mapped to [PaneStatusDisplay.On] for backward compatibility. Only the
 * explicit `"None"` value disables indicators.
 *
 * @param s the raw string value, e.g. `"On"`, or `null`.
 * @return the corresponding enum member.
 */
fun parsePaneStatusDisplay(s: String?): PaneStatusDisplay =
    if (s == "None") PaneStatusDisplay.None else PaneStatusDisplay.On
