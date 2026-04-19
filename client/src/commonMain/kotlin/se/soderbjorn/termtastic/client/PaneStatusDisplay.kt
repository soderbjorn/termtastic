/**
 * Parser for the "waiting pulse" UI setting.
 *
 * When a PTY session is waiting for user input, the sidebar/tab/header can
 * pulsate to draw attention. This setting controls whether that pulsating
 * effect is enabled. Spinners for "working" state are always shown.
 *
 * The setting is stored server-side as part of the UI settings JSON blob.
 *
 * @see se.soderbjorn.termtastic.client.viewmodel.AppBackingViewModel.State.showWaitingPulse
 */
package se.soderbjorn.termtastic.client

/**
 * Parse a string value from the server's UI settings JSON into a boolean
 * for the waiting-pulse setting. Legacy values (`"None"`) disable the
 * pulse; everything else (including `null`, `"On"`, `"Dots"`, `"Glow"`,
 * `"Both"`, `"true"`) enables it.
 *
 * @param s the raw string value, e.g. `"true"`, `"On"`, `"None"`, or `null`.
 * @return `true` if the waiting pulse should be shown, `false` otherwise.
 */
fun parseShowWaitingPulse(s: String?): Boolean =
    s != "None" && s != "false"
