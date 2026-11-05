package hr.gd.androidfilewifi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import hr.gd.androidfilewifi.Services
import hr.gd.androidfilewifi.data.PairedPc
import hr.gd.androidfilewifi.net.DiscoveredPc
import hr.gd.androidfilewifi.net.Pairing
import hr.gd.androidfilewifi.shared.client.PcClient
import hr.gd.androidfilewifi.shared.PairingLink
import hr.gd.androidfilewifi.shared.Protocol
import kotlinx.coroutines.launch

/** What the user still has to confirm before pairing can continue. */
private sealed interface PairStep {
    /** Ask for the 6-digit code for a PC whose fingerprint is known. */
    data class EnterCode(val name: String, val host: String, val port: Int, val fingerprint: String) : PairStep
    /** Manual pairing: ask the user to compare the Security ID shown on the PC. */
    data class ConfirmId(val host: String, val port: Int, val fingerprint: String) : PairStep
    data object ManualAddress : PairStep
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PcScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = Services.pcStore
    val pairing = remember { Pairing(store) }
    val pcs by store.pcs.collectAsState()

    var discovered by remember { mutableStateOf<List<DiscoveredPc>>(emptyList()) }
    LaunchedEffect(Unit) { Services.discovery.discover().collect { discovered = it } }

    var step by remember { mutableStateOf<PairStep?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }

    fun finish(result: Result<PairedPc>) {
        busy = false
        step = null
        result.onSuccess { message = "Paired with ${it.name}"; messageIsError = false }
            .onFailure { message = it.message ?: "Pairing failed"; messageIsError = true }
    }

    fun pairNow(host: String, port: Int, fp: String, code: String) {
        busy = true
        scope.launch { finish(pairing.pair(host, port, fp, code)) }
    }

    fun scan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode ->
                val link = PairingLink.parse(barcode.rawValue.orEmpty())
                when {
                    link == null -> { message = "That QR code isn't from AndroidFileWifi"; messageIsError = true }
                    link.code.isNotBlank() -> pairNow(link.host, link.port, link.fingerprint, link.code)
                    else -> step = PairStep.EnterCode(link.pcName.ifBlank { link.host }, link.host, link.port, link.fingerprint)
                }
            }
            .addOnFailureListener {
                message = "Couldn't open the scanner. You can pair from the list below or by address."
                messageIsError = true
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Your PCs") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            message?.let {
                item {
                    Card(colors = CardDefaults.cardColors(
                        containerColor = if (messageIsError) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.primaryContainer)) {
                        Text(it, Modifier.padding(16.dp).fillMaxWidth())
                    }
                }
            }
            if (busy) item { CircularProgressIndicator(Modifier.padding(8.dp)) }

            item { Text("Paired", style = MaterialTheme.typography.titleMedium) }
            if (pcs.isEmpty()) item {
                Text("No PC paired yet. Open AndroidFileWifi on your PC and scan its QR code.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(pcs, key = { it.id }) { pc -> PairedPcRow(pc, onRemove = { store.remove(pc.id) }) }

            item { Text("Add a PC", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            item {
                Button(onClick = ::scan, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.QrCodeScanner, null)
                    Text("Scan QR code", Modifier.padding(start = 8.dp))
                }
            }
            item {
                Text("Found on this Wi-Fi", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val unpaired = discovered.filter { d -> pcs.none { it.id == d.fingerprint } }
            if (unpaired.isEmpty()) item {
                Text("Nothing found yet. Make sure the PC app is running on the same network.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(unpaired, key = { it.serviceName }) { d ->
                Card(Modifier.fillMaxWidth().clickable {
                    step = PairStep.EnterCode(d.name, d.host, d.port, d.fingerprint)
                }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.Computer, null)
                        Column {
                            Text(d.name, style = MaterialTheme.typography.titleSmall)
                            Text(d.host, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { step = PairStep.ManualAddress }, Modifier.fillMaxWidth()) {
                    Text("Enter address manually")
                }
            }
        }
    }

    when (val s = step) {
        is PairStep.EnterCode -> CodeDialog(
            title = "Pair with ${s.name}",
            onDismiss = { step = null },
            onConfirm = { code -> pairNow(s.host, s.port, s.fingerprint, code) },
        )
        PairStep.ManualAddress -> AddressDialog(
            onDismiss = { step = null },
            onConfirm = { host, port ->
                step = null
                busy = true
                scope.launch {
                    pairing.probe(host, port)
                        .onSuccess { fp -> busy = false; step = PairStep.ConfirmId(host, port, fp) }
                        .onFailure { busy = false; message = it.message; messageIsError = true }
                }
            },
        )
        is PairStep.ConfirmId -> {
            // The code is asked in the same dialog once the user has compared the Security ID.
            CodeDialog(
                title = "Check the Security ID",
                intro = "The PC window must show this Security ID:\n\n${PairingLink.securityId(s.fingerprint)}\n\n" +
                    "If it differs, cancel: you may be connected to a different device.",
                onDismiss = { step = null },
                onConfirm = { code -> pairNow(s.host, s.port, s.fingerprint, code) },
            )
        }
        null -> Unit
    }
}

@Composable
private fun PairedPcRow(pc: PairedPc, onRemove: () -> Unit) {
    // null = checking, true = answers, false = not reachable
    var online by remember(pc.id) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(pc.id, pc.host, pc.port) {
        online = null
        online = runCatching { PcClient(pc.host, pc.port, pc.id).ping() }.isSuccess ||
            Services.discovery.find(pc.id, 3000) != null
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Computer, null)
            Column(Modifier.weight(1f)) {
                Text(pc.name, style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when (online) {
                                    true -> Color(0xFF2E9E4F)
                                    false -> Color(0xFFB3B3B3)
                                    null -> Color(0xFFE0B000)
                                }
                            )
                    )
                    Text(
                        when (online) { true -> "Online"; false -> "Not reachable right now"; null -> "Checking…" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onRemove) { Text("Remove") }
        }
    }
}

@Composable
private fun CodeDialog(title: String, intro: String? = null, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(intro ?: "Enter the 6-digit code shown in the PC window.")
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    singleLine = true,
                    label = { Text("Code") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(code) }, enabled = code.length == 6) { Text("Pair") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddressDialog(onDismiss: () -> Unit, onConfirm: (String, Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val parsed = remember(text) {
        val t = text.trim()
        val host = t.substringBefore(':')
        val port = if (t.contains(':')) t.substringAfter(':').toIntOrNull() else Protocol.DEFAULT_PORT
        if (host.isNotBlank() && port != null && port in 1..65535) host to port else null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PC address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("The IP address shown in the PC window, for example 192.168.1.20")
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true, label = { Text("IP address") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        },
        confirmButton = { TextButton(onClick = { parsed?.let { onConfirm(it.first, it.second) } }, enabled = parsed != null) { Text("Next") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
