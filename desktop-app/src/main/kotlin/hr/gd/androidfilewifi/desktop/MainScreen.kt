package hr.gd.androidfilewifi.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

@Composable
fun MainScreen(controller: AppController) {
    val status by controller.status.collectAsState()
    val config by controller.config.state.collectAsState()
    val sessions by controller.transfers.views.collectAsState()
    val firewallOk by controller.firewallOk.collectAsState()
    val code by controller.pairing.code.collectAsState()
    val addresses by controller.addresses.collectAsState()
    val selected by controller.selectedAddress.collectAsState()

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Header(status, config.pcName)

                if (firewallOk == false) FirewallCard(controller)

                PairCard(controller, status, code, addresses, selected)
                FolderCard(controller, config.receiveDir)
                PhonesCard(controller, config.devices)
                TransfersCard(controller, sessions)
                SettingsCard(controller, config)
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Header(status: ServerStatus, pcName: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Default.Computer, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text("AndroidFileWifi", style = MaterialTheme.typography.headlineSmall)
            Text(
                when (status) {
                    ServerStatus.Starting -> "Starting…"
                    is ServerStatus.Running -> "$pcName is ready to receive on port ${status.port}"
                    is ServerStatus.Failed -> "Could not start: ${status.message}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (status is ServerStatus.Failed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FirewallCard(controller: AppController) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.Warning, null)
            Column(Modifier.weight(1f)) {
                Text("Windows Firewall may block your phone", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Allow incoming connections on private networks so your phone can reach this PC. " +
                        "Windows will ask for administrator permission once.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = controller::allowFirewall) { Text("Allow") }
        }
    }
}

@Composable
private fun PairCard(
    controller: AppController,
    status: ServerStatus,
    code: String,
    addresses: List<String>,
    selected: String?,
) {
    SectionCard("Pair your phone") {
        val link = if (status is ServerStatus.Running) controller.pairingLink() else null
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            if (link != null) {
                val bitmap = remember(link.toUri()) { Qr.render(link.toUri()).toComposeImageBitmap() }
                Image(
                    bitmap = bitmap,
                    contentDescription = "Pairing QR code",
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.size(200.dp),
                )
            } else {
                Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) { Text("No network") }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Open FileWifi on your phone", style = MaterialTheme.typography.bodyMedium)
                Text("2. Tap the PC icon, then “Scan QR code”", style = MaterialTheme.typography.bodyMedium)
                Text("Or enter this code on the phone:", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    code.chunked(3).joinToString(" "),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                if (selected != null) {
                    Text("PC address: $selected", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = controller.pairing::regenerate) { Text("New code") }
            }
        }
        if (addresses.size > 1) {
            Text("This PC has several network addresses. Pick the one on your Wi-Fi:",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                addresses.forEach { a ->
                    FilterChip(
                        selected = a == selected,
                        onClick = { controller.selectedAddress.value = a },
                        label = { Text(a) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderCard(controller: AppController, dir: String) {
    SectionCard("Receive folder") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
            Text(dir, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { chooseFolder(dir)?.let(controller::setReceiveDir) }) { Text("Change…") }
            OutlinedButton(onClick = { controller.openFolder(File(dir)) }) { Text("Open") }
        }
        Text(
            "Files are saved in <folder>\\<phone name>\\<album>\\ with their original dates.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PhonesCard(controller: AppController, devices: List<PairedDevice>) {
    SectionCard("Paired phones") {
        if (devices.isEmpty()) {
            Text("No phones paired yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        devices.forEach { d ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.PhoneAndroid, null)
                Text(d.name, Modifier.weight(1f))
                TextButton(onClick = { controller.revoke(d.id) }) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun TransfersCard(controller: AppController, sessions: List<SessionView>) {
    SectionCard("Transfers") {
        if (sessions.isEmpty()) {
            Text("Nothing received yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        sessions.forEach { s ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.deviceName, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(stateLabel(s), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { controller.openFolder(s.folder) }) { Text("Open folder") }
                }
                LinearProgressIndicator(progress = { s.fraction }, modifier = Modifier.fillMaxWidth())
                Text(detailLine(s), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SettingsCard(controller: AppController, config: Config) {
    SectionCard("Settings") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = config.autostart && Autostart.supported,
                onCheckedChange = { controller.setAutostart(it) },
                enabled = Autostart.supported,
            )
            Column {
                Text("Start with Windows")
                if (!Autostart.supported) {
                    Text("Available in the installed version", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = config.closeToTray, onCheckedChange = controller::setCloseToTray)
            Text("Keep running in the tray when the window is closed")
        }
        Spacer(Modifier.size(0.dp))
    }
}

private fun stateLabel(s: SessionView) = when (s.state) {
    SessionState.Active -> "Receiving"
    SessionState.Completed -> "Done"
    SessionState.Incomplete -> "Incomplete"
    SessionState.Cancelled -> "Cancelled"
}

private fun detailLine(s: SessionView): String {
    val parts = ArrayList<String>()
    parts += "${s.doneFiles + s.skippedFiles} / ${s.totalFiles} files"
    if (s.skippedFiles > 0) parts += "${s.skippedFiles} already there"
    parts += "${formatBytes(s.receivedBytes)} of ${formatBytes(s.totalBytes)}"
    if (s.state == SessionState.Active && s.speedBytesPerSec > 0) {
        parts += "${formatBytes(s.speedBytesPerSec)}/s"
        s.etaSeconds?.let { parts += formatEta(it) + " left" }
    }
    return parts.joinToString(" · ")
}

fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024L * 1024 -> "%.0f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}

fun formatEta(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

private fun chooseFolder(current: String): String? {
    var result: String? = null
    val task = Runnable {
        val chooser = JFileChooser(File(current).takeIf { it.exists() } ?: File(System.getProperty("user.home")))
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.dialogTitle = "Choose receive folder"
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) result = chooser.selectedFile.absolutePath
    }
    if (SwingUtilities.isEventDispatchThread()) task.run() else SwingUtilities.invokeAndWait(task)
    return result
}
