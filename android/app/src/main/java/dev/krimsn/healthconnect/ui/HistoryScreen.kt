package dev.krimsn.healthconnect.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.krimsn.healthconnect.sync.SyncOutcome
import dev.krimsn.healthconnect.sync.SyncRun
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: HealthSyncViewModel, onBack: () -> Unit) {
    val runs by viewModel.history.collectAsState()
    var errorsOnly by remember { mutableStateOf(false) }

    val visible = if (errorsOnly) {
        runs.filter { it.outcome == SyncOutcome.ERROR.name.lowercase() }
    } else {
        runs
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync history") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                FilterChip(
                    selected = errorsOnly,
                    onClick = { errorsOnly = !errorsOnly },
                    label = { Text("Only errors") },
                )
                OutlinedButton(onClick = viewModel::clearHistory) { Text("Clear history") }
            }

            Spacer(Modifier.padding(top = 8.dp))

            if (visible.isEmpty()) {
                Text(
                    text = if (errorsOnly) "No errors recorded" else "No sync runs yet",
                    modifier = Modifier.padding(top = 24.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(visible, key = { it.runId }) { run -> HistoryRow(run) }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(run: SyncRun) {
    var expanded by remember { mutableStateOf(false) }
    val isError = run.outcome == SyncOutcome.ERROR.name.lowercase()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${run.trigger} · ${run.outcome}",
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(formatIso(run.startedAt), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = "${run.recordsSent} records" +
                    (run.durationMs?.let { " · ${it}ms" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )

            if (expanded) {
                Spacer(Modifier.padding(top = 4.dp))
                if (run.perType.isNotEmpty()) {
                    run.perType.entries.sortedByDescending { it.value }.forEach { (type, count) ->
                        Text("  $type: $count", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (isError && run.errorMessage != null) {
                    Text(
                        text = run.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun formatIso(iso: String): String = try {
    TIME_FORMAT.format(Instant.parse(iso))
} catch (e: java.time.format.DateTimeParseException) {
    iso
}
