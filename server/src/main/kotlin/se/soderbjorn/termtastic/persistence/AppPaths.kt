package se.soderbjorn.termtastic.persistence

import java.io.File
import java.util.Locale

/**
 * Resolves the on-disk location of the SQLite database that backs persisted
 * server state. Honours the same override convention as `termtastic.port`:
 * a `-Dtermtastic.dbPath=...` system property or `TERMTASTIC_DB_PATH` env var
 * wins, otherwise we fall back to a per-OS application-data directory.
 */
object AppPaths {

    private const val APP_DIR_NAME = "Termtastic"
    private const val DB_FILE_NAME = "termtastic.db"

    fun databaseFile(): File {
        val override = System.getProperty("termtastic.dbPath")
            ?: System.getenv("TERMTASTIC_DB_PATH")
        if (!override.isNullOrBlank()) {
            return File(override)
        }
        return File(defaultDataDir(), DB_FILE_NAME)
    }

    /**
     * Directory for auxiliary on-disk state (shell init bootstrap files, etc.).
     * Tracks the database file's parent so a `-Dtermtastic.dbPath` override
     * keeps everything together for tests and packaged installs.
     */
    fun dataDir(): File {
        val dbFile = databaseFile()
        return dbFile.parentFile ?: defaultDataDir()
    }

    private fun defaultDataDir(): File {
        val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val home = System.getProperty("user.home")
        return when {
            os.contains("mac") || os.contains("darwin") ->
                File(home, "Library/Application Support/$APP_DIR_NAME")

            os.contains("win") -> {
                val appData = System.getenv("APPDATA")
                if (!appData.isNullOrBlank()) File(appData, APP_DIR_NAME)
                else File(home, "AppData/Roaming/$APP_DIR_NAME")
            }

            else -> {
                val xdg = System.getenv("XDG_DATA_HOME")
                val base = if (!xdg.isNullOrBlank()) File(xdg) else File(home, ".local/share")
                File(base, "termtastic")
            }
        }
    }
}
