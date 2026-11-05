package hr.gd.androidfilewifi.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import hr.gd.androidfilewifi.shared.Protocol
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

data class DiscoveredPc(
    val serviceName: String,
    val name: String,
    val host: String,
    val port: Int,
    /** Certificate fingerprint advertised by the PC (identifies it and is pinned when pairing). */
    val fingerprint: String,
)

/** Finds receivers on the Wi-Fi via mDNS/DNS-SD (NsdManager). */
class PcDiscovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    /** Emits the current list of PCs on the network, updating as they appear and disappear. */
    fun discover(): Flow<List<DiscoveredPc>> = callbackFlow {
        val found = LinkedHashMap<String, DiscoveredPc>()
        val queue = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        fun publish() { trySend(found.values.toList()) }

        // The legacy resolveService API allows only one resolve at a time, so serialise them.
        fun resolveNext() {
            if (resolving) return
            val next = queue.removeFirstOrNull() ?: return
            resolving = true
            @Suppress("DEPRECATION")
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    resolving = false; resolveNext()
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    resolving = false
                    toDiscovered(info)?.let { found[info.serviceName] = it; publish() }
                    resolveNext()
                }
            })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                queue.addLast(info); resolveNext()
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                if (found.remove(info.serviceName) != null) publish()
            }
        }
        nsd.discoverServices("${Protocol.SERVICE_TYPE}.", NsdManager.PROTOCOL_DNS_SD, listener)
        publish()
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    /** Waits up to [timeoutMs] for the PC with this fingerprint to show up. */
    suspend fun find(fingerprint: String, timeoutMs: Long = 4000): DiscoveredPc? =
        withTimeoutOrNull(timeoutMs) {
            discover().first { list -> list.any { it.fingerprint.equals(fingerprint, true) } }
                .first { it.fingerprint.equals(fingerprint, true) }
        }

    private fun toDiscovered(info: NsdServiceInfo): DiscoveredPc? {
        fun attr(key: String) = info.attributes[key]?.toString(Charsets.UTF_8)
        val fp = attr("fp") ?: return null
        val addresses: List<java.net.InetAddress> = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else {
            @Suppress("DEPRECATION") listOfNotNull(info.host)
        }
        // Prefer IPv4: the PC listens on 0.0.0.0, and link-local IPv6 addresses are awkward to use.
        val host = (addresses.firstOrNull { it is java.net.Inet4Address } ?: addresses.firstOrNull())
            ?.hostAddress ?: return null
        return DiscoveredPc(
            serviceName = info.serviceName,
            name = attr("name") ?: info.serviceName,
            host = host.substringBefore('%'),
            port = info.port,
            fingerprint = fp.lowercase(),
        )
    }
}
