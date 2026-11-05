package hr.gd.androidfilewifi.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import hr.gd.androidfilewifi.GalleryViewModel
import hr.gd.androidfilewifi.Services
import hr.gd.androidfilewifi.transfer.formatBytes

/** The "Send N items to <PC>" dialog, shown whenever the view-model has a pending send request. */
@Composable
fun SendHost(viewModel: GalleryViewModel) {
    val items by viewModel.pendingSend.collectAsState()
    val pending = items ?: return
    val pcs by Services.pcStore.pcs.collectAsState()
    val context = LocalContext.current

    var chosenId by remember(pcs.size) { mutableStateOf(pcs.firstOrNull()?.id) }
    val total = pending.sumOf { it.sizeBytes }
    val videos = pending.count { it.isVideo }
    val summary = buildString {
        append("${pending.size - videos} photo(s)")
        if (videos > 0) append(", $videos video(s)")
        append(" · ${formatBytes(total)}")
    }

    fun start() {
        val pc = pcs.firstOrNull { it.id == chosenId } ?: return
        Services.transfers.enqueue(pc, pending)
        viewModel.sendStarted()
    }

    // Android 13+: ask for notification permission so progress can be shown; send either way.
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start() }

    if (pcs.isEmpty()) {
        AlertDialog(
            onDismissRequest = viewModel::cancelSend,
            title = { Text("No PC paired yet") },
            text = { Text("Pair this phone with your PC first. It takes a few seconds: scan the QR code shown in the PC app.") },
            confirmButton = { TextButton(onClick = { viewModel.cancelSend(); viewModel.openPcs() }) { Text("Pair a PC") } },
            dismissButton = { TextButton(onClick = viewModel::cancelSend) { Text("Cancel") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = viewModel::cancelSend,
        title = { Text("Send to PC") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(summary, style = MaterialTheme.typography.bodyMedium)
                pcs.forEach { pc ->
                    Row(
                        Modifier.fillMaxWidth().clickable { chosenId = pc.id }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = chosenId == pc.id, onClick = { chosenId = pc.id })
                        Text(pc.name, Modifier.padding(start = 4.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = chosenId != null,
                onClick = {
                    val needsAsk = Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    if (needsAsk) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else start()
                },
            ) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = viewModel::cancelSend) { Text("Cancel") } },
    )
}
