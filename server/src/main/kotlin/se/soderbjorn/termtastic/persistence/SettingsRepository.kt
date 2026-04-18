/**
 * SQLite-backed persistent settings storage for Termtastic.
 *
 * This file contains [SettingsRepository], which wraps a SQLDelight-managed
 * SQLite database behind a generic key/value API. It persists the window
 * layout ([WindowConfig]), per-pane scrollback ring buffers, UI preferences
 * (theme, sidebar width, etc. as a JSON blob), device-auth trusted/denied
 * lists, and server feature flags (allow remote connections, Claude usage
 * polling).
 *
 * The repository is created once in [Application.main] and threaded through
 * to all consumers: [WindowState], [DeviceAuth], [SettingsDialog],
 * [ClaudeUsageMonitor], and the `/api/ui-settings` REST endpoints.
 *
 * UI settings are also exposed as a [StateFlow] so the `/window` WebSocket
 * can push live updates to all connected renderers when the user toggles
 * dark/light mode.
 *
 * @see AppPaths
 * @see WindowState
 * @see DeviceAuth
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
import org.slf4j.LoggerFactory
import se.soderbjorn.termtastic.WindowConfig
import se.soderbjorn.termtastic.db.TermtasticDatabase
import se.soderbjorn.termtastic.windowJson
import java.io.File

/**
 * SQLite-backed key/value store. v1 only persists the window config blob, but
 * the table is generic so additional keys (themes, recent commands, etc.) can
 * land without a schema migration.
 *
 * Blob format is versioned via the key suffix (`window.config.v1`) so most
 * future evolution doesn't touch SQL at all.
 */
class SettingsRepository(dbFile: File) {

    private val log = LoggerFactory.getLogger(SettingsRepository::class.java)
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
     * Load the persisted window layout configuration.
     *
     * Prefers the v2 key (post file-browser migration); falls back to v1
     * once, migrating `MarkdownContent` discriminators to `FileBrowserContent`,
     * and re-persists under the v2 key. Returns null if no config has been
     * saved yet. Corrupt blobs are quarantined under a timestamped key.
     *
     * @return the deserialised [WindowConfig], or null if none exists
     * @see saveWindowConfig
     */
    fun loadWindowConfig(): WindowConfig? {
        // Prefer v2 (post file-browser migration). Fall back to v1 once, rewrite
        // any legacy MarkdownContent leaves as FileBrowserContent, and persist
        // the migrated blob under the new key so the fallback only ever runs
        // one time per SQLite DB.
        val v2 = database.settingsQueries
            .selectByKey(WINDOW_CONFIG_KEY_V2)
            .executeAsOneOrNull()
        if (v2 != null) {
            return decodeOrQuarantine(v2, WINDOW_CONFIG_KEY_V2)
        }
        val v1 = database.settingsQueries
            .selectByKey(WINDOW_CONFIG_KEY_V1)
            .executeAsOneOrNull()
            ?: return null
        // The legacy MarkdownContent and the new FileBrowserContent share all
        // field names that were previously persisted (selectedRelPath,
        // leftColumnWidthPx, autoRefresh, fontSize), so the cheapest migration
        // is a literal rename of the polymorphic discriminator value.
        val migrated = v1.replace("\"kind\":\"markdown\"", "\"kind\":\"fileBrowser\"")
        val decoded = decodeOrQuarantine(migrated, WINDOW_CONFIG_KEY_V1) ?: return null
        // Persist the migrated form under v2 so next boot skips the rewrite.
        val encoded = windowJson.encodeToString(WindowConfig.serializer(), decoded)
        database.settingsQueries.upsert(WINDOW_CONFIG_KEY_V2, encoded)
        database.settingsQueries.deleteByKey(WINDOW_CONFIG_KEY_V1)
        return decoded
    }

    private fun decodeOrQuarantine(raw: String, sourceKey: String): WindowConfig? {
        return try {
            windowJson.decodeFromString(WindowConfig.serializer(), raw)
        } catch (t: Throwable) {
            val ts = System.currentTimeMillis()
            val quarantineKey = "$sourceKey.corrupt.$ts"
            log.warn(
                "Failed to decode persisted window config; quarantining as '{}'",
                quarantineKey,
                t
            )
            database.settingsQueries.upsert(quarantineKey, raw)
            database.settingsQueries.deleteByKey(sourceKey)
            null
        }
    }

    /**
     * Persist the window layout configuration to SQLite.
     *
     * Serialises [config] to JSON and upserts it under the current version key.
     * Runs on [Dispatchers.IO] to avoid blocking the caller.
     *
     * @param config the window configuration to persist
     * @see loadWindowConfig
     */
    suspend fun saveWindowConfig(config: WindowConfig) = withContext(Dispatchers.IO) {
        val json = windowJson.encodeToString(WindowConfig.serializer(), config)
        database.settingsQueries.upsert(WINDOW_CONFIG_KEY_V2, json)
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

    private val _uiSettings = MutableStateFlow(loadUiSettings())
    val uiSettings: StateFlow<JsonObject> = _uiSettings.asStateFlow()

    private fun loadUiSettings(): JsonObject {
        val raw = getString(UI_SETTINGS_KEY) ?: return JsonObject(emptyMap())
        return runCatching { Json.parseToJsonElement(raw) as JsonObject }
            .getOrElse { JsonObject(emptyMap()) }
    }

    /**
     * Return the current UI settings JSON object (in-memory snapshot).
     *
     * @return the merged UI settings as a [JsonObject]
     */
    fun getUiSettings(): JsonObject = _uiSettings.value

    /**
     * Merge [incoming] keys into the existing UI settings, persist the result,
     * and update the in-memory [uiSettings] flow.
     *
     * @param incoming JSON object with the keys to merge (new keys are added,
     *                 existing keys are overwritten)
     * @return the merged [JsonObject] after persistence
     */
    fun mergeUiSettings(incoming: JsonObject): JsonObject {
        val existing = _uiSettings.value
        val merged = JsonObject(existing + incoming)
        putString(UI_SETTINGS_KEY, merged.toString())
        _uiSettings.value = merged
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
        internal const val WINDOW_CONFIG_KEY_V1 = "window.config.v1"
        internal const val WINDOW_CONFIG_KEY_V2 = "window.config.v2"
        /** Current write key. Updated whenever the persisted schema rev bumps. */
        const val WINDOW_CONFIG_KEY = WINDOW_CONFIG_KEY_V2
        private const val UI_SETTINGS_KEY = "ui.settings.v1"
        private const val ALLOW_REMOTE_KEY = "network.allow_remote.v1"
        private const val CLAUDE_USAGE_POLL_KEY = "claude.usage_poll.v1"
    }
}
