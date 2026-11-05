package hr.gd.androidfilewifi.desktop

import hr.gd.androidfilewifi.shared.PairingLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.File
import java.util.UUID

sealed interface ServerStatus {
    data object Starting : ServerStatus
    data class Running(val port: Int) : ServerStatus
    data class Failed(val message: String) : ServerStatus
}

/** Wires config, TLS identity, pairing, transfers, the HTTPS server and mDNS together for the UI. */
class AppController(
    private val scope: CoroutineScope,
    private val dir: File = ConfigStore.defaultDir(),
    private val notify: (title: String, message: String) -> Unit = { _, _ -> },
) {
    val config = ConfigStore(dir)
    val identity = Identity.loadOrCreate(dir)
    val pairing = PairingManager()
    val transfers = TransferManager(config, onFinished = { v ->
        val what = when (v.state) {
            SessionState.Completed -> "Received ${v.doneFiles} file(s) from ${v.deviceName}"
            SessionState.Incomplete -> "Transfer from ${v.deviceName} did not finish"
            else -> null
        }
        what?.let { notify("AndroidFileWifi", it) }
    })
    private val serverId = UUID.nameUUIDFromBytes(identity.fingerprint.toByteArray()).toString()

    private val context = ReceiverContext(config, identity, pairing, transfers, onPaired = {
        notify("Phone paired", "${it.name} can now send files to this PC")
    })
    private val server = ReceiverServer(context)
    private val discovery = Discovery()

    private val _status = MutableStateFlow<ServerStatus>(ServerStatus.Starting)
    val status: StateFlow<ServerStatus> = _status

    private val _addresses = MutableStateFlow<List<String>>(emptyList())
    val addresses: StateFlow<List<String>> = _addresses

    /** Which of [addresses] the QR code points at. */
    val selectedAddress = MutableStateFlow<String?>(null)

    /** null = not checked yet. */
    val firewallOk = MutableStateFlow<Boolean?>(null)

    fun start() {
        scope.launch(Dispatchers.IO) {
            try {
                val port = server.start()
                val lan = Net.lanAddresses()
                _addresses.value = lan.map { it.hostAddress }
                selectedAddress.value = lan.firstOrNull()?.hostAddress
                lan.firstOrNull()?.let {
                    discovery.start(it, config.value.pcName, port, serverId, identity.fingerprint)
                }
                _status.value = ServerStatus.Running(port)
            } catch (e: Exception) {
                _status.value = ServerStatus.Failed(e.message ?: e.javaClass.simpleName)
            }
            firewallOk.value = Firewall.ruleExists()
        }
        scope.launch {
            while (isActive) {
                transfers.publish()
                delay(400)
            }
        }
    }

    fun stop() {
        discovery.stop()
        server.stop()
    }

    fun pairingLink(): PairingLink? {
        val running = _status.value as? ServerStatus.Running ?: return null
        val host = selectedAddress.value ?: return null
        return PairingLink(host, running.port, identity.fingerprint, pairing.code.value, config.value.pcName)
    }

    fun revoke(deviceId: String) = config.update { c -> c.copy(devices = c.devices.filter { it.id != deviceId }) }

    fun setReceiveDir(path: String) = config.update { it.copy(receiveDir = path) }

    fun setCloseToTray(v: Boolean) = config.update { it.copy(closeToTray = v) }

    fun setAutostart(v: Boolean) {
        Autostart.set(v)
        config.update { it.copy(autostart = v) }
    }

    fun openFolder(folder: File) {
        runCatching {
            folder.mkdirs()
            Desktop.getDesktop().open(folder)
        }
    }

    fun allowFirewall() {
        Firewall.requestRules(config.value.preferredPort)
        scope.launch(Dispatchers.IO) {
            // Re-check for a while: the user needs a moment to accept the UAC prompt.
            repeat(30) {
                delay(2000)
                if (Firewall.ruleExists().also { ok -> firewallOk.value = ok }) return@launch
            }
        }
    }
}
