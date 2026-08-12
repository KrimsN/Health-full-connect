package dev.krimsn.healthconnect.sync

import android.util.Log
import dev.krimsn.healthconnect.data.SyncHistoryStore
import dev.krimsn.healthconnect.data.SyncPrefs
import dev.krimsn.healthconnect.health.HealthConnectRepository
import dev.krimsn.healthconnect.health.SyncRecord
import dev.krimsn.healthconnect.remote.SupabaseUploader
import java.time.Duration
import java.time.Instant

/** Caps a single POST body -- a multi-month backfill can return tens of
 *  thousands of rows, and Supabase's PostgREST has its own request-size limits. */
private const val UPLOAD_BATCH_SIZE = 500

/** adb logcat -s HealthSync -- see docs/SETUP.md's diagnostics section. */
private const val LOG_TAG = "HealthSync"

/**
 * Orchestrates one sync attempt: read from Health Connect, upload to Supabase,
 * persist the changes token, and record the outcome to both the local history
 * (data/SyncHistoryStore -- the source of truth for the History screen) and the
 * `sync_runs` table (best-effort, for the MCP server). Every public entry point
 * returns the [SyncRun] it recorded, success or failure, so callers (SyncWorker,
 * the dashboard's manual-sync button) never need their own try/catch.
 */
class SyncEngine(
    private val healthConnect: HealthConnectRepository,
    private val uploader: SupabaseUploader,
    private val prefs: SyncPrefs,
    private val history: SyncHistoryStore,
) {

    /** Incremental sync via the stored changes token. What both the periodic
     *  worker and the "Sync now" button run. */
    suspend fun syncIncremental(trigger: SyncTrigger): SyncRun = runTracked(trigger) {
        val storedToken = prefs.changesToken()
        val result = healthConnect.readChangesSince(storedToken)

        val records = if (result.tokenExpired) readSinceLastSync() else result.upserted

        uploadInBatches(records)
        prefs.setChangesToken(result.nextToken)
        records
    }

    /** Resets the changes token and re-reads the window since the last
     *  successful sync. For when the user suspects the incremental token has
     *  drifted from what's actually in Health Connect. */
    suspend fun syncFullRecompute(): SyncRun = runTracked(SyncTrigger.MANUAL_FULL) {
        val records = readSinceLastSync()
        uploadInBatches(records)
        prefs.setChangesToken(healthConnect.readChangesSince(null).nextToken)
        records
    }

    suspend fun syncBackfill(months: Long): SyncRun = runTracked(SyncTrigger.BACKFILL) {
        val since = Instant.now().minus(Duration.ofDays(months * 30))
        val records = healthConnect.readRange(since, Instant.now())
        uploadInBatches(records)
        records
    }

    private suspend fun readSinceLastSync(): List<SyncRecord> {
        val since = prefs.lastSyncAt() ?: Instant.now().minus(Duration.ofDays(30))
        return healthConnect.readRange(since, Instant.now())
    }

    private suspend fun uploadInBatches(records: List<SyncRecord>) {
        records.chunked(UPLOAD_BATCH_SIZE).forEach { batch -> uploader.uploadRecords(batch) }
    }

    private suspend fun runTracked(
        trigger: SyncTrigger,
        block: suspend () -> List<SyncRecord>,
    ): SyncRun {
        val startedAt = Instant.now()
        Log.i(LOG_TAG, "sync start: trigger=${trigger.name.lowercase()}")

        // This is the sync's terminal error boundary, not a swallowed failure:
        // any exception becomes a SyncRun with outcome=error and the exception's
        // message preserved verbatim, which is exactly what the History screen's
        // error detail view (see ui/HistoryScreen.kt) is built to show. Letting
        // it propagate instead would just turn into an opaque WorkManager retry
        // with no user-visible reason.
        val run = try {
            val records = block()
            val finishedAt = Instant.now()
            prefs.setLastSyncAt(finishedAt)
            prefs.mergeLastRecordTimes(latestTimePerType(records))
            SyncRun(
                startedAt = startedAt.toString(),
                finishedAt = finishedAt.toString(),
                trigger = trigger.name.lowercase(),
                outcome = SyncOutcome.OK.name.lowercase(),
                recordsRead = records.size,
                recordsSent = records.size,
                perType = records.groupingBy { it.recordType }.eachCount(),
                durationMs = Duration.between(startedAt, finishedAt).toMillis(),
            )
        } catch (e: Exception) {
            val finishedAt = Instant.now()
            Log.e(LOG_TAG, "sync failed: trigger=${trigger.name.lowercase()}", e)
            SyncRun(
                startedAt = startedAt.toString(),
                finishedAt = finishedAt.toString(),
                trigger = trigger.name.lowercase(),
                outcome = SyncOutcome.ERROR.name.lowercase(),
                errorMessage = e.message ?: e::class.simpleName ?: "Unknown error",
                durationMs = Duration.between(startedAt, finishedAt).toMillis(),
            )
        }

        if (run.outcome == SyncOutcome.OK.name.lowercase()) {
            Log.i(LOG_TAG, "sync ok: ${run.recordsSent} records, ${run.durationMs}ms")
        }

        history.upsert(run)
        // Best-effort mirror: if this specific call fails right after a
        // successful upload above, local history (the UI's source of truth)
        // still has the correct entry -- only the MCP server's view lags.
        runCatching { uploader.uploadSyncRun(run) }

        return run
    }

    private fun latestTimePerType(records: List<SyncRecord>): Map<String, Instant> =
        records.groupBy { it.recordType }.mapValues { (_, rs) -> rs.maxOf { it.startTime } }
}
