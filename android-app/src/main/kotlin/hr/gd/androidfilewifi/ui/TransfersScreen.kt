package hr.gd.androidfilewifi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hr.gd.androidfilewifi.Services
import hr.gd.androidfilewifi.transfer.JobState
import hr.gd.androidfilewifi.transfer.JobView
import hr.gd.androidfilewifi.transfer.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(onBack: () -> Unit) {
    val repo = Services.transfers
    val jobs by repo.jobs.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transfers") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        if (jobs.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), verticalArrangement = Arrangement.Center) {
                Text("Nothing sent yet.", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Select photos or folders and tap the send button to copy them to your PC.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(jobs, key = { it.id }) { job ->
                    JobCard(
                        job,
                        onCancel = { repo.cancel(job.id) },
                        onRetry = { repo.retry(job.id) },
                        onDismiss = { repo.dismiss(job.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun JobCard(job: JobView, onCancel: () -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(job.pcName, fontWeight = FontWeight.Medium)
                    Text(
                        when (job.state) {
                            JobState.Queued -> "Waiting…"
                            JobState.Connecting -> "Connecting…"
                            JobState.Sending -> "Sending"
                            JobState.Done -> "Done"
                            JobState.Failed -> "Stopped"
                            JobState.Cancelled -> "Cancelled"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (job.state == JobState.Failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (job.state) {
                    JobState.Queued, JobState.Connecting, JobState.Sending ->
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    JobState.Failed -> {
                        TextButton(onClick = onRetry) { Text("Retry") }
                        TextButton(onClick = onDismiss) { Text("Dismiss") }
                    }
                    JobState.Done, JobState.Cancelled -> TextButton(onClick = onDismiss) { Text("Dismiss") }
                }
            }
            if (!job.isFinished || job.state == JobState.Done) {
                if (job.state == JobState.Connecting || job.state == JobState.Queued) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { job.fraction }, modifier = Modifier.fillMaxWidth())
                }
            }
            Text(detail(job), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            job.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun detail(j: JobView): String = buildList {
    add("${j.doneFiles + j.skippedFiles} / ${j.totalFiles} files")
    if (j.skippedFiles > 0) add("${j.skippedFiles} already on PC")
    if (j.totalBytes > 0) add("${formatBytes(j.sentBytes)} of ${formatBytes(j.totalBytes)}")
    if (j.state == JobState.Sending && j.speedBytesPerSec > 0) {
        add("${formatBytes(j.speedBytesPerSec)}/s")
        val eta = (j.totalBytes - j.sentBytes).coerceAtLeast(0) / j.speedBytesPerSec
        add(if (eta < 60) "${eta}s left" else "${eta / 60}m ${eta % 60}s left")
    }
}.joinToString(" · ")
