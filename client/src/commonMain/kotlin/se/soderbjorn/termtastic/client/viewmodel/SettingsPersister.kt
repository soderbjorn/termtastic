package se.soderbjorn.termtastic.client.viewmodel

/**
 * Abstraction for persisting UI settings to the server. The web client
 * provides an implementation backed by `fetch("/api/ui-settings")`;
 * Android/iOS use `TermtasticClient.httpClient`.
 */
fun interface SettingsPersister {
    suspend fun putSetting(key: String, value: String)
}
