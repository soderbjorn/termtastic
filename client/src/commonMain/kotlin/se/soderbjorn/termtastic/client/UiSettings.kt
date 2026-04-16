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
data class UiSettings(
    val theme: TerminalTheme,
    val appearance: Appearance,
)

/**
 * Resolve the (foreground, background) hex pair this theme should paint with,
 * given the user's appearance preference and the host's system dark-mode flag.
 * Appearance.Auto defers to [systemIsDark].
 */
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

    return UiSettings(theme = theme, appearance = appearance)
}

private fun defaultUiSettings(): UiSettings =
    UiSettings(
        theme = recommendedThemes.first { it.name == DEFAULT_THEME_NAME },
        appearance = Appearance.Auto,
    )

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (isString) content else null
