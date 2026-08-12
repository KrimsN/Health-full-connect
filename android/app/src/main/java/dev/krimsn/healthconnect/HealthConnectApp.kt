package dev.krimsn.healthconnect

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import dev.krimsn.healthconnect.sync.SyncWorker

/**
 * Implements Configuration.Provider so WorkManager initializes on-demand instead
 * of through its default ContentProvider (disabled in AndroidManifest.xml via
 * tools:node="remove") -- this is what lets [schedulePeriodic] run here with a
 * WorkManager instance that's guaranteed already configured.
 */
class HealthConnectApp : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        SyncWorker.schedulePeriodic(this)
    }
}
