/* LunamuxWorldSource.kt (jsMain)
 * Adapter that exposes Lunamux's server-driven world model
 * ([WindowConfig.worlds] + [WindowConfig.activeWorldId]) as a
 * darkness-toolkit [WorldSource]. The toolkit's globe world switcher
 * subscribes to the push channel and renders the world list; user gestures
 * (select / add / rename / close, plus moving a tab to another world via a
 * tab's dot-menu submenu) are forwarded as the world [WindowCommand]s through
 * the existing [WindowSocket].
 *
 * The companion of [lunamuxTabSource]: the tab source renders the active
 * world's tabs, this feeds the switcher above them. Per-world theme is
 * applied separately on config push (see [applyActiveWorldTheme]).
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import se.soderbjorn.darkness.web.shell.WorldListSnapshot
import se.soderbjorn.darkness.web.shell.WorldSnapshotEntry
import se.soderbjorn.darkness.web.shell.WorldSource
import se.soderbjorn.lunamux.client.WindowSocket
import se.soderbjorn.lunamux.client.WindowStateRepository

/**
 * Builds a [WorldSource] backed by Lunamux's server-pushed
 * [WindowStateRepository.config] flow.
 *
 * On `subscribe`, collects the config flow on [scope]; every emission with
 * a non-empty `worlds` list is mapped to a [WorldListSnapshot] and pushed
 * into the toolkit switcher. User gestures fire the world [WindowCommand]s
 * through [socket]; the server re-broadcasts an updated [WindowConfig],
 * which flows back through this collector (and the tab source) to re-render.
 *
 * @param scope coroutine scope for the collector.
 * @param windowState the live server-state repository.
 * @param socket the open [WindowSocket] for sending commands back.
 * @return a [WorldSource] for [se.soderbjorn.darkness.web.shell.AppShellSpec.worldSource].
 */
fun lunamuxWorldSource(
    scope: CoroutineScope,
    windowState: WindowStateRepository,
    socket: WindowSocket,
): WorldSource = WorldSource(
    subscribe = { push ->
        scope.launch {
            windowState.config.collect { config ->
                if (config == null) return@collect
                // Only surface the switcher once the server reports worlds
                // (a ≥1.9 server always does). An empty list pushes an empty
                // snapshot, which the toolkit renders as "no switcher".
                push(
                    WorldListSnapshot(
                        worlds = config.worlds.map { WorldSnapshotEntry(id = it.id, label = it.name) },
                        activeWorldId = config.activeWorldId,
                    ),
                )
            }
        }
    },
    onSelect = { id -> scope.launch { socket.send(WindowCommand.SetActiveWorld(id)) } },
    onAdd = { name -> scope.launch { socket.send(WindowCommand.AddWorld(name)) } },
    onRename = { id, name -> scope.launch { socket.send(WindowCommand.RenameWorld(id, name)) } },
    onClose = { id -> scope.launch { socket.send(WindowCommand.CloseWorld(id)) } },
    onMoveTab = { tabId, worldId -> scope.launch { socket.send(WindowCommand.MoveTabToWorld(tabId, worldId)) } },
)
