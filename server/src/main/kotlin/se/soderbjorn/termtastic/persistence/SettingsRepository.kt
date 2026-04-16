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

    suspend fun saveWindowConfig(config: WindowConfig) = withContext(Dispatchers.IO) {
        val json = windowJson.encodeToString(WindowConfig.serializer(), config)
        database.settingsQueries.upsert(WINDOW_CONFIG_KEY_V2, json)
    }

    fun loadScrollback(leafId: String): ByteArray? =
        database.settingsQueries.selectScrollback(leafId).executeAsOneOrNull()

    suspend fun saveScrollback(leafId: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        database.settingsQueries.upsertScrollback(leafId, bytes, System.currentTimeMillis())
    }

    fun deleteScrollback(leafId: String) {
        database.settingsQueries.deleteScrollback(leafId)
    }

    fun allScrollbackLeafIds(): Set<String> =
        database.settingsQueries.selectAllScrollbackLeafIds().executeAsList().toSet()

    fun getString(key: String): String? =
        database.settingsQueries.selectByKey(key).executeAsOneOrNull()

    fun putString(key: String, value: String) {
        database.settingsQueries.upsert(key, value)
    }

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

    fun getUiSettings(): JsonObject = _uiSettings.value

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

    fun setAllowRemoteConnections(value: Boolean) {
        putString(ALLOW_REMOTE_KEY, value.toString())
    }

    fun isClaudeUsagePollEnabled(): Boolean =
        getString(CLAUDE_USAGE_POLL_KEY)?.equals("true", ignoreCase = true) == true

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
