/**
 * UI settings model and server-side settings fetch logic.
 *
 * [UiSettings] is a snapshot of the user's visual preferences (theme and
 * appearance mode) as stored on the server in a free-form JSON blob at
 * `ui.settings.v1`. The [fetchUiSettings] extension on [TermtasticClient]
 * pulls the blob via `GET /api/ui-settings` and maps it into a type-safe
 * [UiSettings] instance, falling back to safe defaults for unknown values.
 *
 * @see se.soderbjorn.termtastic.TerminalTheme
 * @see se.soderbjorn.termtastic.Appearance
 */
package se.soderbjorn.termtastic.client

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import se.soderbjorn.termtastic.Appearance
import se.soderbjorn.termtastic.DEFAULT_THEME_NAME
import se.soderbjorn.termtastic.TerminalTheme
import se.soderbjorn.termtastic.recommendedThemes

/**
 * Snapshot of the server-side UI preferences that matter for rendering a
 * terminal. The server stores these under `ui.settings.v1` as a free-form
 * JSON blob (see SettingsRepository.mergeUiSettings); we only pick the
 * theme + appearance keys out of it.
 */
/**
 * @property theme             the active terminal colour theme.
 * @property appearance        the user's light/dark mode preference.
 * @property sidebarTheme      optional per-section override for the sidebar.
 * @property terminalTheme     optional per-section override for terminal panes.
 * @property diffTheme         optional per-section override for diff views.
 * @property fileBrowserTheme  optional per-section override for the file browser.
 * @property tabsTheme         optional per-section override for the tab bar.
 * @property chromeTheme       optional per-section override for window chrome.
 * @property windowsTheme     optional per-section override for pane window frames.
 * @property activeTheme      optional per-section override for active indicators
 *   (tab ring, focused-pane border, sidebar active-pane highlight).
 */
data class UiSettings(
    val theme: TerminalTheme,
    val appearance: Appearance,
    val sidebarTheme: TerminalTheme? = null,
    val terminalTheme: TerminalTheme? = null,
    val diffTheme: TerminalTheme? = null,
    val fileBrowserTheme: TerminalTheme? = null,
    val tabsTheme: TerminalTheme? = null,
    val chromeTheme: TerminalTheme? = null,
    val windowsTheme: TerminalTheme? = null,
    val activeTheme: TerminalTheme? = null,
) {
    /**
     * Resolves the theme for a specific app section, falling back to the global theme.
     *
     * @param section one of `"sidebar"`, `"terminal"`, `"diff"`, `"fileBrowser"`, `"tabs"`, `"chrome"`, `"active"`
     * @return the [TerminalTheme] for that section
     */
    fun sectionTheme(section: String): TerminalTheme = when (section) {
        "sidebar" -> sidebarTheme
        "terminal" -> terminalTheme
        "diff" -> diffTheme
        "fileBrowser" -> fileBrowserTheme
        "tabs" -> tabsTheme
        "chrome" -> chromeTheme
        "windows" -> windowsTheme
        "active" -> activeTheme
        else -> null
    } ?: theme
}

/**
 * Resolve the (foreground, background) hex pair this theme should paint with,
 * given the user's appearance preference and the host's system dark-mode flag.
 * Appearance.Auto defers to [systemIsDark].
 */
@Deprecated(
    "Use TerminalTheme.resolve(appearance, systemIsDark) for the full semantic palette",
    replaceWith = ReplaceWith("resolve(appearance, systemIsDark)", "se.soderbjorn.termtastic.resolve"),
)
fun TerminalTheme.effectiveColors(
    appearance: Appearance,
    systemIsDark: Boolean,
): Pair<String, String> {
    val dark = when (appearance) {
        Appearance.Dark -> true
        Appearance.Light -> false
        Appearance.Auto -> systemIsDark
    }
    return if (dark) darkFg to darkBg else lightFg to lightBg
}

/**
 * Fetch `/api/ui-settings` and map it to a [UiSettings]. Returns `null` if the
 * server rejects auth or the request fails; callers should keep using their
 * current/default colors in that case.
 *
 * Unknown theme names and unknown appearance values fall back to
 * [DEFAULT_THEME_NAME] / [Appearance.Auto] so a typo in the server blob never
 * crashes a client.
 */
suspend fun TermtasticClient.fetchUiSettings(): UiSettings? {
    val url = serverUrl.httpUrl("/api/ui-settings")
    val response = runCatching {
        httpClient.get(url) {
            header("X-Termtastic-Auth", authToken)
            for ((name, value) in clientInfoHeaders()) header(name, value)
        }
    }.getOrElse { return null }
    if (!response.status.isSuccess()) return null
    if (response.status == HttpStatusCode.NoContent) return defaultUiSettings()
    val body = runCatching { response.bodyAsText() }.getOrElse { return null }
    if (body.isBlank()) return defaultUiSettings()
    val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }
        .getOrNull() ?: return defaultUiSettings()

    val themeName = (obj["theme"]?.jsonPrimitive?.contentOrNullSafe())
    val theme = recommendedThemes.firstOrNull { it.name == themeName }
        ?: recommendedThemes.first { it.name == DEFAULT_THEME_NAME }

    val appearanceName = obj["appearance"]?.jsonPrimitive?.contentOrNullSafe()
    val appearance = appearanceName
        ?.let { runCatching { Appearance.valueOf(it) }.getOrNull() }
        ?: Appearance.Auto

    fun parseSectionTheme(key: String): TerminalTheme? {
        val name = obj[key]?.jsonPrimitive?.contentOrNullSafe() ?: return null
        return recommendedThemes.firstOrNull { it.name == name }
    }

    return UiSettings(
        theme = theme,
        appearance = appearance,
        sidebarTheme = parseSectionTheme("theme.sidebar"),
        terminalTheme = parseSectionTheme("theme.terminal"),
        diffTheme = parseSectionTheme("theme.diff"),
        fileBrowserTheme = parseSectionTheme("theme.fileBrowser"),
        tabsTheme = parseSectionTheme("theme.tabs"),
        chromeTheme = parseSectionTheme("theme.chrome"),
        windowsTheme = parseSectionTheme("theme.windows"),
        activeTheme = parseSectionTheme("theme.active"),
    )
}

/**
 * Construct a [UiSettings] with the factory defaults (Tron theme, auto
 * appearance). Used when the server has no stored settings or the response
 * is empty/unparseable.
 */
private fun defaultUiSettings(): UiSettings =
    UiSettings(
        theme = recommendedThemes.first { it.name == DEFAULT_THEME_NAME },
        appearance = Appearance.Auto
    )

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (isString) content else null
