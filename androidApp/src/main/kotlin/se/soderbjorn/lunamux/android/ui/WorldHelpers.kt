/**
 * World-resolution helpers for the Lunamux Android client.
 *
 * A "world" is a named workspace one level above tabs (see [WorldConfig]).
 * World-aware (>=1.9) clients render the tabs of the *active* world rather
 * than the legacy top-level [WindowConfig.tabs] mirror. These helpers pick
 * the active world out of a server-pushed [WindowConfig] and expose its
 * theme pair, mirroring the web client's `activeWorldOrNull()` +
 * `effectiveThemeSnapshot()` (see web's `LunamuxTabSource.kt` /
 * `ThemeHelpers.kt`).
 *
 * @see se.soderbjorn.lunamux.WindowConfig
 * @see se.soderbjorn.lunamux.WorldConfig
 */
package se.soderbjorn.lunamux.android.ui

import se.soderbjorn.darkness.core.ThemeSnapshotV2
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.WorldConfig

/**
 * The active world of a server config (by [WindowConfig.activeWorldId]),
 * falling back to the first world, or `null` when the config carries no
 * worlds (a legacy pre-1.9 server / flat fixture).
 *
 * Called by [TreeScreen] (to render the active world's tabs) and by
 * [LunamuxApp] (to resolve the active world's theme pair). Mirrors the web
 * client's `WindowConfig.activeWorldOrNull()`.
 *
 * @receiver the freshly-pushed server config.
 * @return the active [WorldConfig], or `null` if the config has no worlds.
 */
fun WindowConfig.activeWorldOrNull(): WorldConfig? =
    worlds.firstOrNull { it.id == activeWorldId } ?: worlds.firstOrNull()

/**
 * Overlay the active world's theme **pair** onto a global theme snapshot.
 *
 * Called by [LunamuxApp] on every config push so the palette repaints to the
 * active world's dark/light slot names while the global appearance
 * (Auto/Dark/Light), custom themes, and favorites stay untouched. When the
 * active world carries no [WorldConfig.themeSelection] (or the config has no
 * worlds) the snapshot is returned unchanged, so the app follows the global
 * selection. Purely a paint-time override: it is never persisted, so
 * switching worlds cannot corrupt the global selection.
 *
 * @receiver the global canonical theme snapshot.
 * @param config the freshly-pushed server config, or `null` before the first
 *   push.
 * @return the snapshot to paint with — the receiver, with its slot names
 *   swapped for the active world's pair when one exists.
 */
fun ThemeSnapshotV2.withActiveWorldTheme(config: WindowConfig?): ThemeSnapshotV2 {
    val selection = config?.activeWorldOrNull()?.themeSelection ?: return this
    return copy(
        darkThemeName = selection.darkThemeName,
        lightThemeName = selection.lightThemeName,
    )
}
