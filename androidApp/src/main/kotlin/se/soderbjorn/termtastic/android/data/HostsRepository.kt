package se.soderbjorn.termtastic.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import se.soderbjorn.termtastic.client.HostEntry
import java.util.UUID

private val HOSTS_JSON = stringPreferencesKey("hosts_json")

private val json = Json { ignoreUnknownKeys = true }

class HostsRepository(private val context: Context) {

    val hosts: Flow<List<HostEntry>> =
        context.connectionDataStore.data.map { prefs -> decode(prefs[HOSTS_JSON]) }

    suspend fun current(): List<HostEntry> = hosts.first()

    suspend fun add(label: String, host: String, port: Int): HostEntry {
        val entry = HostEntry(id = UUID.randomUUID().toString(), label = label, host = host, port = port)
        mutate { it + entry }
        return entry
    }

    suspend fun update(entry: HostEntry) {
        mutate { list -> list.map { if (it.id == entry.id) entry else it } }
    }

    suspend fun delete(id: String) {
        mutate { list -> list.filterNot { it.id == id } }
    }

    private suspend fun mutate(transform: (List<HostEntry>) -> List<HostEntry>) {
        context.connectionDataStore.edit { prefs ->
            val next = transform(decode(prefs[HOSTS_JSON]))
            prefs[HOSTS_JSON] = json.encodeToString(next)
        }
    }

    private fun decode(raw: String?): List<HostEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<HostEntry>>(raw) }.getOrDefault(emptyList())
    }
}
