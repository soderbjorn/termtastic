/**
 * Multi-window registry for Termtastic.
 *
 * Tracks every open main window (Electron BrowserWindow) known to the server:
 * its client-assigned id, its most recently reported screen geometry (position,
 * size), and the monitor/display it lives on. The registry is persisted in the
 * generic key/value store via [SettingsRepository] so that on the next cold
 * start the Electron main process can recreate the same set of windows at the
 * same positions on the same monitors.
 *
 * This file contains:
 *  - [WindowRecord]     -- the persisted shape of one window entry.
 *  - [WindowRegistry]   -- the process-wide singleton that owns the in-memory
 *                          map and drives writes to SQLite.
 *
 * Callers:
 *  - [Application.module]'s `/api/windows` REST routes dispatch into this
 *    object when the Electron renderer reports lifecycle events (open, move,
 *    resize, close) for the browser window that hosts it.
 *  - Electron main process reads the list on startup (via `GET /api/windows`)
 *    to decide how many BrowserWindows to recreate and where to place them.
 *
 * @see SettingsRepository.putString
 * @see WindowState the sibling singleton owning per-window tab/pane layout
 */
package se.soderbjorn.termtastic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import se.soderbjorn.termtastic.persistence.SettingsRepository

/**
 * One entry in the window registry.
 *
 * All coordinates are in screen (device-independent) pixels as reported by
 * Electron's `BrowserWindow.getBounds()` / the renderer's `window.screenX`.
 * The monitor identifier is whatever Electron's `screen.getDisplayMatching()`
 * returns for the window's bounds; it is opaque to the server and used only
 * as a hint when the main process asks "which monitor did this window live
 * on last time?".
 *
 * @property id           client-assigned unique window id (UUID string)
 * @property x            screen x of the window's top-left corner, in px
 * @property y            screen y of the window's top-left corner, in px
 * @property width        window width in px
 * @property height       window height in px
 * @property displayId    opaque Electron display id the window was last on,
 *                        or null if the client hasn't reported one
 * @property updatedAt    epoch millis of the last report; used for ordering
 *                        and stale-entry pruning
 */
@Serializable
data class WindowRecord(
    val id: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val displayId: String? = null,
    val updatedAt: Long = 0L,
)

/**
 * Serialised wrapper for the persisted registry blob — versioned so a future
 * schema bump can land without touching SQL. See [SettingsRepository.putString].
 */
@Serializable
private data class WindowRegistrySnapshotV1(
    val windows: List<WindowRecord>,
)

/**
 * Process-wide window registry. All mutations funnel through this singleton
 * so the in-memory map and the persisted blob always agree.
 *
 * Synchronization: mutations are guarded by `synchronized(this)`. The volume
 * is negligible (a handful of open/close/move events per second at most) and
 * the cost of contention is dwarfed by the SQLite write.
 */
object WindowRegistry {
    private val log = LoggerFactory.getLogger(WindowRegistry::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /** Storage key for the persisted registry blob. */
    private const val REGISTRY_KEY = "windows.registry.v1"

    private val records: MutableMap<String, WindowRecord> = LinkedHashMap()

    @Volatile
    private var repo: SettingsRepository? = null

    /**
     * One-shot bootstrap. Called once from [main] before any HTTP route is
     * served so the first `GET /api/windows` reply reflects the persisted
     * state. Must be called before [upsert] / [remove] / [list].
     *
     * Silently ignores a corrupt persisted blob (logs the failure and starts
     * empty) so a bad shape can't wedge the server's startup.
     *
     * @param settingsRepo the key/value store to read/write the registry blob
     */
    @Synchronized
    fun initialize(settingsRepo: SettingsRepository) {
        repo = settingsRepo
        records.clear()
        val raw = settingsRepo.getString(REGISTRY_KEY) ?: return
        val parsed = runCatching {
            json.decodeFromString(WindowRegistrySnapshotV1.serializer(), raw)
        }.onFailure {
            log.warn("Failed to decode persisted window registry; starting empty", it)
        }.getOrNull() ?: return
        for (rec in parsed.windows) {
            records[rec.id] = rec
        }
    }

    /**
     * Return a snapshot of every known window, ordered by [WindowRecord.updatedAt]
     * (oldest first) so clients can pick a stable primary when they need one.
     *
     * @return an immutable list of [WindowRecord]
     */
    @Synchronized
    fun list(): List<WindowRecord> =
        records.values.sortedBy { it.updatedAt }

    /**
     * Insert or update a window entry. Triggered by the Electron renderer on
     * window creation, move, resize, and focus-change events. The passed-in
     * [WindowRecord.updatedAt] is overwritten with the current wall clock time
     * so the caller does not need to manage it.
     *
     * @param record the record to persist; its `updatedAt` is ignored
     * @return the stored [WindowRecord] (with the updated timestamp)
     */
    @Synchronized
    fun upsert(record: WindowRecord): WindowRecord {
        val stamped = record.copy(updatedAt = System.currentTimeMillis())
        records[stamped.id] = stamped
        persist()
        return stamped
    }

    /**
     * Remove the window with [id] from the registry. Triggered when the
     * Electron renderer unloads (window closed). No-op if the id is unknown,
     * which keeps handlers idempotent under reconnect/retry scenarios.
     *
     * @param id the client-assigned window id to remove
     * @return true if an entry was removed, false if the id was unknown
     */
    @Synchronized
    fun remove(id: String): Boolean {
        val existed = records.remove(id) != null
        if (existed) persist()
        return existed
    }

    /**
     * Return the full count of currently-tracked windows. Primarily used by
     * the Electron main process at startup to decide how many BrowserWindows
     * to recreate.
     */
    @Synchronized
    fun size(): Int = records.size

    /**
     * Write the current in-memory map to the persistent store. Runs on the
     * caller's thread because [SettingsRepository.putString] already wraps a
     * fast JDBC UPSERT; no need for a background dispatcher here.
     */
    private fun persist() {
        val r = repo ?: return
        val snapshot = WindowRegistrySnapshotV1(windows = records.values.toList())
        val encoded = json.encodeToString(WindowRegistrySnapshotV1.serializer(), snapshot)
        runCatching { r.putString(REGISTRY_KEY, encoded) }
            .onFailure { log.warn("Failed to persist window registry", it) }
    }
}
