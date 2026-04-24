/**
 * SQLite-backed persistent settings storage for Termtastic.
 *
 * This file contains [SettingsRepository], which wraps a SQLDelight-managed
 * SQLite database behind a generic key/value API. It persists per-pane
 * scrollback ring buffers, per-window UI preferences (theme, sidebar width,
 * etc. as JSON blobs keyed by windowId), device-auth trusted/denied lists,
 * and server feature flags (allow remote connections, Claude usage
 * polling).
 *
 * Per-window [WindowConfig] persistence is owned by [WindowState] itself —
 * this repository only provides the generic [getString]/[putString] surface
 * that [WindowState] uses for its per-window blobs.
 *
 * The repository is created once in [Application.main] and threaded through
 * to all consumers: [WindowState], [DeviceAuth], [SettingsDialog],
 * [ClaudeUsageMonitor], and the `/api/ui-settings` REST endpoints.
 *
 * UI settings are exposed as per-window [StateFlow]s so the `/window`
 * WebSocket for a given window only pushes the settings for that window to
 * the renderer on the other end. Each Electron BrowserWindow can have its
 * own theme, appearance, and sidebar widths.
 *
 * @see AppPaths
 * @see se.soderbjorn.termtastic.WindowState
 * @see se.soderbjorn.termtastic.auth.DeviceAuth
 */
package se.soderbjorn.termtastic.persistence

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import se.soderbjorn.termtastic.db.TermtasticDatabase
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * SQLite-backed key/value store. The underlying `settings` table is
 * generic — additional keys can land without a schema migration. Scrollback
 * blobs are in a separate `pane_scrollback` table because they are binary.
 *
 * Blob formats are versioned via the key suffix (e.g. `ui.settings.v2.`) so
 * most future evolution doesn't touch SQL at all.
 */
class SettingsRepository(dbFile: File) {

    private val driver: JdbcSqliteDriver
    private val database: TermtasticDatabase

    init {
        dbFile.parentFile?.mkdirs()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")

        val schema = TermtasticDatabase.Schema
        val current = currentUserVersion()
        if (current == 0L) {
            schema.create(driver).value
            setUserVersion(schema.version)
        } else if (current < schema.version) {
            schema.migrate(driver, current, schema.version).value
            setUserVersion(schema.version)
        }

        database = TermtasticDatabase(driver)

        // Belt-and-suspenders: the SQLDelight-generated schema version doesn't
        // bump automatically when we add tables to Settings.sq (no .sqm
        // migrations yet), so pre-existing DBs at user_version=1 would never
        // pick up the new pane_scrollback table otherwise.
        driver.execute(
            identifier = null,
            sql = """
                CREATE TABLE IF NOT EXISTS pane_scrollback (
                    leaf_id    TEXT NOT NULL PRIMARY KEY,
                    bytes      BLOB NOT NULL,
                    updated_at INTEGER NOT NULL
                )
            """.trimIndent(),
            parameters = 0,
        )
    }

    /**
     * Load the persisted scrollback ring buffer for a pane.
     *
     * @param leafId the leaf node id whose scrollback to load
     * @return the raw scrollback bytes, or null if none have been saved
     */
    fun loadScrollback(leafId: String): ByteArray? =
        database.settingsQueries.selectScrollback(leafId).executeAsOneOrNull()

    /**
     * Persist the scrollback ring buffer for a pane.
     *
     * @param leafId the leaf node id to save scrollback for
     * @param bytes the raw ring buffer bytes to persist
     */
    suspend fun saveScrollback(leafId: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        database.settingsQueries.upsertScrollback(leafId, bytes, System.currentTimeMillis())
    }

    /**
     * Delete the persisted scrollback for a pane (used during GC of stale leaves).
     *
     * @param leafId the leaf node id whose scrollback to delete
     */
    fun deleteScrollback(leafId: String) {
        database.settingsQueries.deleteScrollback(leafId)
    }

    /**
     * Return the set of all leaf ids that have persisted scrollback data.
     * Used by the GC pass in [WindowState.initialize] to identify stale entries.
     *
     * @return set of leaf id strings
     */
    fun allScrollbackLeafIds(): Set<String> =
        database.settingsQueries.selectAllScrollbackLeafIds().executeAsList().toSet()

    /**
     * Read a raw string value from the settings key/value store.
     *
     * @param key the settings key
     * @return the stored value, or null if the key does not exist
     */
    fun getString(key: String): String? =
        database.settingsQueries.selectByKey(key).executeAsOneOrNull()

    /**
     * Write a raw string value to the settings key/value store (upsert).
     *
     * @param key the settings key
     * @param value the string value to store
     */
    fun putString(key: String, value: String) {
        database.settingsQueries.upsert(key, value)
    }

    /**
     * Close the underlying SQLite JDBC driver. Called from the shutdown hook
     * in [Application.main].
     */
    fun close() {
        driver.close()
    }

    private fun currentUserVersion(): Long {
        return driver.executeQuery(
            identifier = null,
            sql = "PRAGMA user_version",
            mapper = { cursor: SqlCursor ->
                val v = if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L
                QueryResult.Value(v)
            },
            parameters = 0
        ).value
    }

    private fun setUserVersion(version: Long) {
        driver.execute(null, "PRAGMA user_version = $version", 0)
    }

    // Per-window UI settings — every Electron main window can have its own
    // theme, appearance, sidebar width, etc. Keyed by windowId. Each window
    // gets its own StateFlow so /window sockets only push the settings
    // relevant to the connected renderer.
    //
    // Storage key layout: `ui.settings.v2.<windowId>` (JSON blob). The
    // windowId "primary" is used as the fallback for clients that don't
    // pass one (the plain-browser client and the Android app).
    private val perWindowFlows = ConcurrentHashMap<String, MutableStateFlow<JsonObject>>()

    /**
     * Return (or lazily create) the per-window UI-settings flow for [windowId].
     * Called by the `/window` WebSocket when a renderer connects, and by
     * [mergeUiSettings] when the REST endpoint receives a settings update.
     *
     * @param windowId the client-assigned window id
     * @return a hot [StateFlow] that replays the current settings and pushes
     *         every subsequent merge
     */
    fun uiSettingsFlow(windowId: String): StateFlow<JsonObject> =
        flowFor(windowId).asStateFlow()

    private fun flowFor(windowId: String): MutableStateFlow<JsonObject> {
        return perWindowFlows.computeIfAbsent(windowId) {
            MutableStateFlow(loadUiSettings(windowId))
        }
    }

    private fun uiSettingsKey(windowId: String): String =
        "$UI_SETTINGS_KEY_PREFIX$windowId"

    private fun loadUiSettings(windowId: String): JsonObject {
        val raw = getString(uiSettingsKey(windowId)) ?: return JsonObject(emptyMap())
        return runCatching { Json.parseToJsonElement(raw) as JsonObject }
            .getOrElse { JsonObject(emptyMap()) }
    }

    /**
     * Return the current UI settings JSON object for [windowId] (in-memory
     * snapshot).
     *
     * @param windowId the client-assigned window id
     * @return the merged UI settings as a [JsonObject]
     */
    fun getUiSettings(windowId: String): JsonObject = flowFor(windowId).value

    /**
     * Merge [incoming] keys into the existing UI settings for [windowId],
     * persist the result, and update the in-memory flow.
     *
     * @param windowId the client-assigned window id to update
     * @param incoming JSON object with the keys to merge (new keys are added,
     *                 existing keys are overwritten)
     * @return the merged [JsonObject] after persistence
     */
    fun mergeUiSettings(windowId: String, incoming: JsonObject): JsonObject {
        val flow = flowFor(windowId)
        val existing = flow.value
        val merged = JsonObject(existing + incoming)
        putString(uiSettingsKey(windowId), merged.toString())
        flow.value = merged
        return merged
    }

    /**
     * Whether the server accepts connections from non-loopback addresses.
     * Default is false (localhost only). Toggled at runtime from the
     * settings dialog; the effective check runs in DeviceAuth.authorize, so
     * changes apply on the next incoming request without restarting Netty.
     */
    fun isAllowRemoteConnections(): Boolean =
        getString(ALLOW_REMOTE_KEY)?.equals("true", ignoreCase = true) == true

    /**
     * Toggle whether the server accepts connections from non-loopback addresses.
     *
     * @param value true to allow remote connections, false for localhost only
     */
    fun setAllowRemoteConnections(value: Boolean) {
        putString(ALLOW_REMOTE_KEY, value.toString())
    }

    /**
     * Whether the Claude Code usage polling monitor is enabled.
     *
     * @return true if the user has opted in to usage data collection
     */
    fun isClaudeUsagePollEnabled(): Boolean =
        getString(CLAUDE_USAGE_POLL_KEY)?.equals("true", ignoreCase = true) == true

    /**
     * Toggle the Claude Code usage polling monitor.
     *
     * @param value true to enable polling, false to disable
     */
    fun setClaudeUsagePollEnabled(value: Boolean) {
        putString(CLAUDE_USAGE_POLL_KEY, value.toString())
    }

    companion object {
        /**
         * Prefix for per-window UI settings blobs. The full key is
         * `${UI_SETTINGS_KEY_PREFIX}<windowId>`. Incrementing the version
         * suffix (currently `v2`) deliberately drops any prior
         * single-blob `ui.settings.v1` payload — issue #18's per-window
         * refactor explicitly sheds backwards compat.
         */
        private const val UI_SETTINGS_KEY_PREFIX = "ui.settings.v2."
        private const val ALLOW_REMOTE_KEY = "network.allow_remote.v1"
        private const val CLAUDE_USAGE_POLL_KEY = "claude.usage_poll.v1"
    }
}

