package dev.krimsn.healthconnect.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import java.time.Instant

private val Context.syncDataStore by preferencesDataStore(name = "sync_state")

private val KEY_CHANGES_TOKEN = stringPreferencesKey("changes_token")
private val KEY_LAST_SYNC_AT = stringPreferencesKey("last_sync_at")
private val KEY_LAST_RECORD_TIME_BY_TYPE = stringPreferencesKey("last_record_time_by_type")

@Serializable
private data class LastRecordTimes(val byType: Map<String, String>)

/**
 * Small persisted state the sync engine needs across app restarts:
 *  - the Health Connect changes token (see HealthConnectRepository.readChangesSince)
 *  - when the last successful run finished, used as the fallback read-window
 *    start when the token has expired
 *  - the most recent record timestamp seen per type, which drives the
 *    dashboard's per-type freshness table
 */
class SyncPrefs(private val context: Context) {

    suspend fun changesToken(): String? =
        context.syncDataStore.data.first()[KEY_CHANGES_TOKEN]

    suspend fun setChangesToken(token: String) {
        context.syncDataStore.edit { it[KEY_CHANGES_TOKEN] = token }
    }

    suspend fun lastSyncAt(): Instant? =
        context.syncDataStore.data.first()[KEY_LAST_SYNC_AT]?.let(Instant::parse)

    suspend fun setLastSyncAt(instant: Instant) {
        context.syncDataStore.edit { it[KEY_LAST_SYNC_AT] = instant.toString() }
    }

    suspend fun lastRecordTimeByType(): Map<String, Instant> {
        val raw = context.syncDataStore.data.first()[KEY_LAST_RECORD_TIME_BY_TYPE] ?: return emptyMap()
        return runCatching { Json.decodeFromString<LastRecordTimes>(raw) }
            .getOrDefault(LastRecordTimes(emptyMap()))
            .byType
            .mapValues { Instant.parse(it.value) }
    }

    /** Merges newly seen (type -> latest start_time) pairs into the stored map,
     *  keeping the max per type rather than overwriting. */
    suspend fun mergeLastRecordTimes(seen: Map<String, Instant>) {
        if (seen.isEmpty()) return
        val current = lastRecordTimeByType().toMutableMap()
        seen.forEach { (type, time) ->
            val existing = current[type]
            if (existing == null || time.isAfter(existing)) current[type] = time
        }
        val encoded = Json.encodeToString(LastRecordTimes(current.mapValues { it.value.toString() }))
        context.syncDataStore.edit { it[KEY_LAST_RECORD_TIME_BY_TYPE] = encoded }
    }
}
