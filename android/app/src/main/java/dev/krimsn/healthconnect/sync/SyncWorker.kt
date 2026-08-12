package dev.krimsn.healthconnect.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.krimsn.healthconnect.data.SyncHistoryStore
import dev.krimsn.healthconnect.data.SyncPrefs
import dev.krimsn.healthconnect.health.HealthConnectRepository
import dev.krimsn.healthconnect.remote.SupabaseUploader
import java.util.concurrent.TimeUnit

private const val KEY_MODE = "mode"
private const val KEY_BACKFILL_MONTHS = "backfill_months"

/**
 * Single worker for every sync path -- periodic, manual, full recompute, and
 * backfill all run the same [SyncEngine], distinguished only by [KEY_MODE] in
 * the input data. Periodic and manual work live under two separate unique-work
 * names ([PERIODIC_WORK_NAME], [MANUAL_WORK_NAME]) rather than one shared name:
 * WorkManager's own docs guarantee uniqueness *within* enqueueUniqueWork or
 * *within* enqueueUniquePeriodicWork, but don't document what happens when the
 * two APIs share a name, so this keeps periodic scheduling and manual
 * dedup (see [triggerManualIncremental]) on two well-documented, independent
 * lanes instead of relying on unspecified behavior.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val engine = SyncEngine(
            healthConnect = HealthConnectRepository(applicationContext),
            uploader = SupabaseUploader(),
            prefs = SyncPrefs(applicationContext),
            history = SyncHistoryStore(applicationContext),
        )

        val run = when (inputData.getString(KEY_MODE)) {
            "manual" -> engine.syncIncremental(SyncTrigger.MANUAL)
            "full_recompute" -> engine.syncFullRecompute()
            "backfill" -> engine.syncBackfill(inputData.getLong(KEY_BACKFILL_MONTHS, 1))
            else -> engine.syncIncremental(SyncTrigger.PERIODIC)
        }

        return if (run.outcome == SyncOutcome.ERROR.name.lowercase()) Result.failure() else Result.success()
    }

    companion object {
        const val PERIODIC_WORK_NAME = "health_sync_periodic"
        const val MANUAL_WORK_NAME = "health_sync_manual"

        private fun networkConstraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Idempotent -- safe to call on every app start; KEEP means an already
         *  scheduled periodic chain is left alone rather than reset. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(networkConstraints())
                .setInputData(Data.Builder().putString(KEY_MODE, "periodic").build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun triggerManualIncremental(context: Context) = enqueueManual(context, mode = "manual")

        fun triggerFullRecompute(context: Context) = enqueueManual(context, mode = "full_recompute")

        fun triggerBackfill(context: Context, months: Long) =
            enqueueManual(context, mode = "backfill", backfillMonths = months)

        /** KEEP: a second tap while one manual run is already queued or running
         *  is dropped rather than queued behind it -- mashing "Sync now" starts
         *  at most one run, not a pile of sequential ones. */
        private fun enqueueManual(context: Context, mode: String, backfillMonths: Long? = null) {
            val data = Data.Builder().putString(KEY_MODE, mode)
            backfillMonths?.let { data.putLong(KEY_BACKFILL_MONTHS, it) }
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(networkConstraints())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(data.build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(MANUAL_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
