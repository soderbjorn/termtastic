package se.soderbjorn.termtastic.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import se.soderbjorn.termtastic.client.AuthTokenStore

internal val Context.connectionDataStore: DataStore<Preferences> by preferencesDataStore(name = "termtastic-connection")

data class ConnectionConfig(
    val host: String,
    val port: Int,
)

object ConnectionKeys {
    val HOST = stringPreferencesKey("host")
    val PORT = intPreferencesKey("port")
    val AUTH_TOKEN = stringPreferencesKey("auth_token")
}

const val DEFAULT_HOST = "10.0.2.2"
const val DEFAULT_PORT = 8083

class ConnectionRepository(private val context: Context) {
    val connection: Flow<ConnectionConfig> =
        context.connectionDataStore.data.map { prefs ->
            ConnectionConfig(
                host = prefs[ConnectionKeys.HOST] ?: DEFAULT_HOST,
                port = prefs[ConnectionKeys.PORT] ?: DEFAULT_PORT,
            )
        }

    suspend fun current(): ConnectionConfig = connection.first()

    suspend fun save(host: String, port: Int) {
        context.connectionDataStore.edit { prefs ->
            prefs[ConnectionKeys.HOST] = host
            prefs[ConnectionKeys.PORT] = port
        }
    }

    fun authTokenStore(): AuthTokenStore = object : AuthTokenStore {
        override fun load(): String? = runCatching {
            kotlinx.coroutines.runBlocking {
                context.connectionDataStore.data.first()[ConnectionKeys.AUTH_TOKEN]
            }
        }.getOrNull()

        override fun save(token: String) {
            kotlinx.coroutines.runBlocking {
                context.connectionDataStore.edit { prefs ->
                    prefs[ConnectionKeys.AUTH_TOKEN] = token
                }
            }
        }
    }
}
