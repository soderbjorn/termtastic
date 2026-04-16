package se.soderbjorn.termtastic.client

import kotlinx.browser.window

/**
 * Browser localStorage-backed auth token store. Mirrors the pre-refactor web
 * client's `localStorage["termtastic.authToken"]` key exactly so a token
 * previously minted by the legacy JS code survives the migration — the
 * server-side trust store looks up by SHA-256 of the token, so reusing the
 * same string means the approval dialog doesn't re-appear.
 */
class LocalStorageAuthTokenStore : AuthTokenStore {
    private val key = "termtastic.authToken"

    override fun load(): String? = window.localStorage.getItem(key)

    override fun save(token: String) {
        window.localStorage.setItem(key, token)
        // Mirror into a cookie so same-origin fetches attach it automatically.
        // SameSite=Strict + one-year Max-Age match the pre-refactor behaviour.
        window.document.cookie =
            "termtastic_auth=$token; Path=/; SameSite=Strict; Max-Age=31536000"
    }
}
