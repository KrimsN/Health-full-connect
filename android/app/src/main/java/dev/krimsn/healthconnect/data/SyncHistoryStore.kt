package dev.krimsn.healthconnect.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.krimsn.healthconnect.sync.SyncRun
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.historyDataStore by preferencesDataStore(name = "sync_history")
private val KEY_RUNS = stringPreferencesKey("runs_json")
private const val MAX_RUNS = 500

/**
 * Local, on-device history of sync runs -- the source of truth for the History
 * screen. Kept separate from the Supabase-mirrored `sync_runs` table because a
 * run that fails on the network never reaches Supabase by definition, and those
 * are exactly the runs a user most needs to see to diagnose a stuck sync.
 */
class SyncHistoryStore(private val context: Context) {

    val runs: Flow<List<SyncRun>> = context.historyDataStore.data.map { prefs ->
        prefs[KEY_RUNS]?.let(::decode) ?: emptyList()
    }

    suspend fun recent(): List<SyncRun> = runs.first()

    /** Prepends [run]; an existing entry with the same run_id (e.g. updating a
     *  run's outcome once it finishes) is replaced in place, not duplicated. */
    suspend fun upsert(run: SyncRun) {
        context.historyDataStore.edit { prefs ->
            val current = prefs[KEY_RUNS]?.let(::decode) ?: emptyList()
            val withoutRun = current.filterNot { it.runId == run.runId }
            val updated = (listOf(run) + withoutRun).take(MAX_RUNS)
            prefs[KEY_RUNS] = Json.encodeToString(updated)
        }
    }

    suspend fun clear() {
        context.historyDataStore.edit { it[KEY_RUNS] = Json.encodeToString(emptyList<SyncRun>()) }
    }

    private fun decode(raw: String): List<SyncRun> =
        runCatching { Json.decodeFromString<List<SyncRun>>(raw) }.getOrDefault(emptyList())
}
