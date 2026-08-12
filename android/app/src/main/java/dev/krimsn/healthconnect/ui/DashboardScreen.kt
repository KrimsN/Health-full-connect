package dev.krimsn.healthconnect.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import dev.krimsn.healthconnect.sync.SyncOutcome
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: HealthSyncViewModel, onOpenHistory: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) {
        viewModel.refreshPermissions()
    }

    val batteryOptimizationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.refreshBatteryOptimizationStatus()
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Health Sync") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.healthConnectAvailable) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Health Connect isn't available on this device. Install or update " +
                            "it from the Play Store, then reopen this app.",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (state.permissionsGranted) {
                            "Health Connect permissions granted"
                        } else {
                            "Permissions needed"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (!state.permissionsGranted) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { permissionLauncher.launch(viewModel.requiredPermissions()) }) {
                            Text("Grant permissions")
                        }
                    }
                }
            }

            if (!state.batteryOptimizationExempt) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Background sync may be delayed", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "This phone's battery manager can throttle the periodic sync job well " +
                                "past its normal schedule. Exempting the app keeps it reliable.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            val intent = Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"),
                            )
                            batteryOptimizationLauncher.launch(intent)
                        }) {
                            Text("Exempt from battery optimization")
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Last run", style = MaterialTheme.typography.titleMedium)
                    val last = state.lastRun
                    if (last == null) {
                        Text("No syncs yet")
                    } else {
                        Text("${last.trigger} · ${last.outcome} · ${last.recordsSent} records")
                        Text(formatIso(last.startedAt), style = MaterialTheme.typography.bodySmall)
                        if (last.outcome == SyncOutcome.ERROR.name.lowercase() && last.errorMessage != null) {
                            Text(
                                text = last.errorMessage,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Freshness by type", style = MaterialTheme.typography.titleMedium)
                    if (state.freshnessByType.isEmpty()) {
                        Text("No data synced yet")
                    } else {
                        val sorted = state.freshnessByType.entries.sortedByDescending { it.value }
                        LazyColumn(modifier = Modifier.height(180.dp)) {
                            items(sorted) { entry ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(entry.key, style = MaterialTheme.typography.bodyMedium)
                                    Text(formatInstant(entry.value), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }

            if (state.syncRunning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Syncing…")
                }
            }

            Button(
                onClick = viewModel::syncNow,
                enabled = state.permissionsGranted && !state.syncRunning,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sync now") }

            OutlinedButton(
                onClick = viewModel::fullRecompute,
                enabled = state.permissionsGranted && !state.syncRunning,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Full recompute") }

            OutlinedButton(
                onClick = { viewModel.backfill(3) },
                enabled = state.permissionsGranted && !state.syncRunning,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Backfill 3 months") }

            OutlinedButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
                Text("View history")
            }

            Text(
                text = "If “Sync now” doesn’t produce new data, sync your band in " +
                    "Gadgetbridge first — this app only reads what Health Connect already has.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun formatIso(iso: String): String = try {
    formatInstant(Instant.parse(iso))
} catch (e: java.time.format.DateTimeParseException) {
    iso
}

private fun formatInstant(instant: Instant): String = TIME_FORMAT.format(instant)
