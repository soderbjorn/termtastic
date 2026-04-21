/**
 * Theme-config helpers retained after the manager rewrite.
 *
 * The original file hosted the save/load/delete dialogs plus REST helpers
 * for named theme configurations. That flow is now owned by
 * [ThemeManager.kt]'s clone/save path and persisted directly through
 * [AppBackingViewModel.saveCustomTheme] / [AppBackingViewModel.deleteCustomTheme].
 *
 * What remains here is the single JS-interop extension that several
 * renderers still need: converting a typed [ThemeConfigPreset] into the
 * dynamic key/value bag expected by [renderConfigSilhouette].
 */
package se.soderbjorn.termtastic

/**
 * Convert a [ThemeConfigPreset] to the dynamic JS object format consumed
 * by [renderConfigSilhouette]. Produces an object with `"theme"` and
 * `"theme.<section>"` string keys — matching the wire format the
 * silhouette renderer evolved to expect.
 *
 * @return a plain JS object with `"theme"` and `"theme.<section>"` keys.
 */
fun ThemeConfigPreset.toDynamic(): dynamic {
    val obj: dynamic = js("({})")
    obj["theme"] = theme
    obj["theme.sidebar"] = sidebar
    obj["theme.terminal"] = terminal
    obj["theme.diff"] = diff
    obj["theme.fileBrowser"] = fileBrowser
    obj["theme.tabs"] = tabs
    obj["theme.chrome"] = chrome
    obj["theme.windows"] = windows
    obj["theme.active"] = active
    return obj
}
