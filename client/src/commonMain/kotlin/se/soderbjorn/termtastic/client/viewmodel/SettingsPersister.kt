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
interface SettingsPersister {
    /**
     * Send a single key/value setting update to the server.
     *
     * @param key   the setting key (e.g. `"theme"`, `"appearance"`).
     * @param value the setting value as a string.
     */
    suspend fun putSetting(key: String, value: String)

    /**
     * Send multiple key/value setting updates to the server in a single
     * request. The default implementation falls back to individual
     * [putSetting] calls; platforms should override this to send a single
     * batch POST so the server broadcasts one consolidated update.
     *
     * @param settings map of setting keys to values.
     */
    suspend fun putSettings(settings: Map<String, String>) {
        for ((key, value) in settings) putSetting(key, value)
    }

    /**
     * Non-suspend variant of [putSettings] for callers that need to
     * persist without entering a coroutine context. The default
     * implementation is a no-op; platforms that support synchronous
     * fire-and-forget (e.g. web via `window.fetch`) should override.
     *
     * @param settings map of setting keys to values.
     */
    fun fireAndForgetPutSettings(settings: Map<String, String>) {}
}
