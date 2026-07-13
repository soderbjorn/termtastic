/* WorldLayoutKeys.kt (jsMain)
 * Per-world pane-layout persistence key helpers.
 *
 * Pane geometry (positions / size / z-order / preset / pane-order) is
 * toolkit-owned and persisted through the server's flat key-value UI
 * settings. Post multi-world, that layout is namespaced per world:
 *
 *   • the DEFAULT (first) world keeps the flat, legacy
 *     [PersistKeys.LAYOUT_STATE] key in its existing shape — so pre-1.9
 *     ("world-unaware") clients and every existing saved layout keep
 *     working with no migration; and
 *   • every non-default world stores its layout under a per-world key
 *     ([serverLayoutKeyForWorld]) that old clients simply ignore.
 *
 * The darkness-toolkit web shell is deliberately dumb about which world is
 * "default": it always emits a suffixed per-world key
 * ([toolkitLayoutKeyForWorld], mirroring `AppShellMount.layoutKeyForWorld`)
 * and lets the host route it. This file is that routing: it maps the
 * toolkit's per-world key onto the server key ([serverKeyForToolkitKey]),
 * aliasing the default world's suffixed key back onto flat LAYOUT_STATE,
 * and reads a world's blob out of the cached server settings snapshot
 * ([worldLayoutBlob]) for the toolkit's synchronous
 * `AppShellSpec.worldLayoutProvider`.
 */
package se.soderbjorn.lunamux

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import se.soderbjorn.darkness.core.PersistKeys

/**
 * The prefix the toolkit uses for its per-world layout keys. A key of the
 * form `"<LAYOUT_STATE>.world.<worldId>"` is a per-world layout blob.
 */
internal val WORLD_LAYOUT_KEY_PREFIX: String = "${PersistKeys.LAYOUT_STATE}.world."

/**
 * The id of the **default (first)** world, or `null` before the first
 * server config has landed. The default world is the one whose layout is
 * kept on the legacy flat key for old-client / saved-data compatibility.
 *
 * Read from [latestWindowConfig], which the tab-source collector refreshes
 * before every toolkit push — so by the time the toolkit invokes any of the
 * helpers below (during a snapshot push) it is populated.
 *
 * @return the first world's id, or `null` when no worlds are known yet.
 */
internal fun defaultWorldId(): String? = latestWindowConfig?.worlds?.firstOrNull()?.id

/**
 * The **server** flat-KV key holding [worldId]'s pane layout: the default
 * world maps to the legacy [PersistKeys.LAYOUT_STATE]; every other world to
 * a per-world suffix. This is the key that actually lives in the server's
 * UI-settings store and is broadcast to clients.
 *
 * @param worldId the world whose layout key to resolve.
 * @return the server settings key for that world's layout.
 * @see worldLayoutBlob @see serverKeyForToolkitKey
 */
internal fun serverLayoutKeyForWorld(worldId: String): String =
    if (worldId == defaultWorldId()) PersistKeys.LAYOUT_STATE
    else "$WORLD_LAYOUT_KEY_PREFIX$worldId"

/**
 * The **toolkit-side** per-world key for [worldId] — always suffixed, even
 * for the default world (the toolkit doesn't know which world is default).
 * [serverKeyForToolkitKey] maps the default world's variant back onto flat
 * LAYOUT_STATE when the toolkit writes it. Mirrors
 * `AppShellMount.layoutKeyForWorld`.
 *
 * @param worldId the world whose toolkit layout key to build.
 * @return `"<LAYOUT_STATE>.world.<worldId>"`.
 */
internal fun toolkitLayoutKeyForWorld(worldId: String): String =
    "$WORLD_LAYOUT_KEY_PREFIX$worldId"

/**
 * If [key] is a toolkit per-world layout key, the world id it carries;
 * otherwise `null`.
 *
 * @param key a persister key emitted by the toolkit.
 * @return the embedded world id, or `null` when [key] is not a per-world
 *   layout key.
 */
internal fun worldIdOfToolkitLayoutKey(key: String): String? =
    if (key.startsWith(WORLD_LAYOUT_KEY_PREFIX)) key.removePrefix(WORLD_LAYOUT_KEY_PREFIX) else null

/**
 * Map a key the **toolkit** wrote to the **server** key it should land on.
 * Aliases the default world's per-world key back onto flat LAYOUT_STATE (so
 * old clients and existing data keep reading the default world under the
 * legacy key); passes every other key — non-default worlds and non-layout
 * keys alike — through unchanged.
 *
 * Called by [SettingsPersisterAdapter] on write (and read) so the toolkit
 * can stay world-uniform while the wire/storage stays old-client-compatible.
 *
 * @param key the key as emitted by the toolkit's persister call.
 * @return the server settings key to actually read/write.
 */
internal fun serverKeyForToolkitKey(key: String): String {
    val worldId = worldIdOfToolkitLayoutKey(key) ?: return key
    return serverLayoutKeyForWorld(worldId)
}

/**
 * Read [worldId]'s persisted layout blob out of the cached server
 * UI-settings snapshot ([toolkitSettingsSnapshot]), as the JSON string the
 * toolkit's `worldLayoutProvider` expects — or `null` when that world has no
 * saved layout yet (so its panes Auto-tile on first show).
 *
 * Synchronous by design: it only touches the already-materialised snapshot,
 * so it is safe to call from the toolkit's synchronous world-swap path.
 *
 * @param worldId the world whose layout blob to fetch.
 * @return the layout JSON string, or `null` if none is stored.
 * @see se.soderbjorn.darkness.web.shell.AppShellSpec.worldLayoutProvider
 */
internal fun worldLayoutBlob(worldId: String): String? {
    val element = toolkitSettingsSnapshot[serverLayoutKeyForWorld(worldId)] ?: return null
    return when (element) {
        is JsonPrimitive -> if (element.isString) element.content else element.toString()
        else -> element.toString()
    }
}
