/**
 * Settings persistence abstraction for the Termtastic client.
 *
 * Platform-specific implementations send individual setting key/value pairs to
 * the server's `PATCH /api/ui-settings` endpoint, which merges them into the
 * stored JSON blob. The web client uses a `fetch`-based implementation; Android
 * and iOS use [TermtasticClient.httpClient].
 *
 * @see AppBackingViewModel
 */
package se.soderbjorn.termtastic.client.viewmodel

/**
 * Functional interface for persisting a single UI setting to the server.
 *
 * Called by [AppBackingViewModel] whenever the user changes a visual preference
 * (theme, appearance, font size, etc.).
 */
fun interface SettingsPersister {
    /**
     * Send a single key/value setting update to the server.
     *
     * @param key   the setting key (e.g. `"theme"`, `"appearance"`).
     * @param value the setting value as a string.
     */
    suspend fun putSetting(key: String, value: String)
}
