package se.soderbjorn.termtastic

import kotlinx.serialization.json.Json

/**
 * Shared `Json` configuration for [WindowConfig] payloads. Used by both the
 * websocket route and the persistence layer so that on-disk blobs round-trip
 * cleanly with what clients see on the wire.
 *
 * Polymorphic discriminator is `"kind"` so it doesn't collide with the
 * envelope's `"type"` field at the message level.
 */
val windowJson: Json = Json {
    classDiscriminator = "kind"
    ignoreUnknownKeys = true
    encodeDefaults = true
}
