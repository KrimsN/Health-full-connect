package dev.krimsn.healthconnect.ui

import android.app.Application
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.krimsn.healthconnect.data.SyncHistoryStore
import dev.krimsn.healthconnect.data.SyncPrefs
import dev.krimsn.healthconnect.health.HealthConnectRepository
import dev.krimsn.healthconnect.health.RecordType
import dev.krimsn.healthconnect.sync.SyncRun
import dev.krimsn.healthconnect.sync.SyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

data class DashboardUiState(
    val permissionsGranted: Boolean = false,
    val healthConnectAvailable: Boolean = true,
    val lastRun: SyncRun? = null,
    val freshnessByType: Map<String, Instant> = emptyMap(),
    val syncRunning: Boolean = false,
    /** False means the OS may throttle the periodic WorkManager job (see
     *  DashboardScreen's exemption card) well past its configured interval --
     *  most visible on OEMs (Samsung, Xiaomi) that demote rarely-opened apps
     *  to the RARE app standby bucket. */
    val batteryOptimizationExempt: Boolean = true,
)

/**
 * Owns UI state for both screens. Sync itself always runs inside SyncWorker
 * (see sync/SyncWorker.kt), never called directly from here -- the ViewModel
 * only enqueues work and observes its result through two read models that
 * outlive the ViewModel: WorkManager's WorkInfo (is a sync running right now)
 * and SyncHistoryStore (what happened in past runs).
 */
class HealthSyncViewModel(application: Application) : AndroidViewModel(application) {

    private val healthConnect = HealthConnectRepository(application)
    private val prefs = SyncPrefs(application)
    private val historyStore = SyncHistoryStore(application)

    val history: StateFlow<List<SyncRun>> = historyStore.runs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val permissionsGranted = MutableStateFlow(false)
    private val freshness = MutableStateFlow<Map<String, Instant>>(emptyMap())
    private val syncRunning = MutableStateFlow(false)
    private val batteryOptimizationExempt = MutableStateFlow(true)

    val uiState: StateFlow<DashboardUiState> =
        combine(
            history, permissionsGranted, freshness, syncRunning, batteryOptimizationExempt,
        ) { runs, granted, fresh, running, batteryExempt ->
            DashboardUiState(
                permissionsGranted = granted,
                healthConnectAvailable = healthConnect.isAvailable(),
                lastRun = runs.firstOrNull(),
                freshnessByType = fresh,
                syncRunning = running,
                batteryOptimizationExempt = batteryExempt,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    init {
        refreshPermissions()
        refreshFreshness()
        refreshBatteryOptimizationStatus()
        observeWork()
    }

    fun requiredPermissions(): Set<String> = RecordType.ALL_PERMISSIONS

    fun refreshPermissions() {
        viewModelScope.launch {
            permissionsGranted.value = healthConnect.isAvailable() && healthConnect.hasAllPermissions()
        }
    }

    fun refreshBatteryOptimizationStatus() {
        val app = getApplication<Application>()
        val powerManager = app.getSystemService(PowerManager::class.java)
        batteryOptimizationExempt.value = powerManager.isIgnoringBatteryOptimizations(app.packageName)
    }

    private fun refreshFreshness() {
        viewModelScope.launch { freshness.value = prefs.lastRecordTimeByType() }
    }

    private fun observeWork() {
        val workManager = WorkManager.getInstance(getApplication())
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.MANUAL_WORK_NAME).collect { infos ->
                val wasRunning = syncRunning.value
                syncRunning.value = infos.any {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                }
                if (wasRunning && !syncRunning.value) refreshFreshness()
            }
        }
    }

    fun syncNow() = SyncWorker.triggerManualIncremental(getApplication())
    fun fullRecompute() = SyncWorker.triggerFullRecompute(getApplication())
    fun backfill(months: Long) = SyncWorker.triggerBackfill(getApplication(), months)

    fun clearHistory() {
        viewModelScope.launch { historyStore.clear() }
    }
}
