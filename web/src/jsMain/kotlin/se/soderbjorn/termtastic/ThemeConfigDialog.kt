/**
 * [Theme]-to-JS helpers retained after the manager rewrite.
 *
 * The original file hosted the save/load/delete dialogs plus REST helpers
 * for named themes. That flow is now owned by [ThemeManager.kt]'s
 * clone/save path and persisted directly through
 * [AppBackingViewModel.saveCustomTheme] / [AppBackingViewModel.deleteCustomTheme].
 *
 * What remains here is the single JS-interop extension that several
 * renderers still need: converting a typed [Theme] into the
 * dynamic key/value bag expected by [renderConfigSilhouette].
 */
package se.soderbjorn.termtastic

/**
 * Convert a [Theme] to the dynamic JS object format consumed
 * by [renderConfigSilhouette]. Produces an object with `"theme"` and
 * `"theme.<section>"` string keys — matching the internal shape the
 * silhouette renderer evolved to expect. The `"theme"` entry carries the
 * main colour-scheme name and `"theme.<section>"` entries carry the
 * section-specific overrides.
 *
 * @return a plain JS object with `"theme"` and `"theme.<section>"` keys.
 */
fun Theme.toDynamic(): dynamic {
    val obj: dynamic = js("({})")
    obj["theme"] = colorScheme
    obj["theme.sidebar"] = sidebar
    obj["theme.terminal"] = terminal
    obj["theme.diff"] = diff
    obj["theme.fileBrowser"] = fileBrowser
    obj["theme.tabs"] = tabs
    obj["theme.chrome"] = chrome
    obj["theme.windows"] = windows
    obj["theme.active"] = active
    obj["theme.bottomBar"] = bottomBar
    return obj
}
