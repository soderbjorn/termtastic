/**
 * Data model for a saved server/host entry in the Termtastic connection list.
 *
 * Mobile clients (Android, iOS) persist a list of [HostEntry] items so users
 * can switch between multiple Termtastic servers without re-typing addresses.
 *
 * @see se.soderbjorn.termtastic.client.ServerUrl
 */
package se.soderbjorn.termtastic.client

import kotlinx.serialization.Serializable

/**
 * A single saved Termtastic server endpoint shown in the host picker UI.
 *
 * @property id    Unique identifier for this entry (typically a UUID).
 * @property label Human-readable display name chosen by the user (e.g. "Home server").
 * @property host  Hostname or IP address of the Termtastic server.
 * @property port  TCP port the server listens on.
 */
@Serializable
data class HostEntry(
    val id: String,
    val label: String,
    val host: String,
    val port: Int,
)
