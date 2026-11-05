package hr.gd.androidfilewifi.desktop

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import hr.gd.androidfilewifi.shared.Protocol
import java.awt.image.BufferedImage
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/** LAN address discovery. */
object Net {
    private val virtualHints = listOf(
        "virtualbox", "vmware", "vethernet", "hyper-v", "wsl", "loopback", "tailscale",
        "zerotier", "bluetooth", "docker", "vpn", "tap-", "npcap", "host-only",
    )

    /** Private IPv4 addresses of physical-looking interfaces, most likely LAN address first. */
    fun lanAddresses(): List<Inet4Address> {
        val found = ArrayList<Pair<Int, Inet4Address>>()
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        for (nif in ifaces) {
            val up = runCatching { nif.isUp && !nif.isLoopback && !nif.isVirtual }.getOrDefault(false)
            if (!up) continue
            val label = (nif.displayName + " " + nif.name).lowercase()
            val penalty = if (virtualHints.any { it in label }) 10 else 0
            for (addr in nif.inetAddresses) {
                if (addr is Inet4Address && addr.isSiteLocalAddress) {
                    // 192.168.x.x is by far the most common home network
                    val bonus = if (addr.hostAddress.startsWith("192.168.")) 0 else 1
                    found += (penalty + bonus) to addr
                }
            }
        }
        return found.sortedBy { it.first }.map { it.second }.distinct()
    }
}

/** QR rendering. */
object Qr {
    fun render(text: String, size: Int = 360): BufferedImage {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until size) for (y in 0 until size) {
            img.setRGB(x, y, if (matrix.get(x, y)) 0x000000 else 0xFFFFFF)
        }
        return img
    }
}

/** Advertises the receiver on the LAN via mDNS so phones can find it without typing an IP. */
class Discovery {
    private var jmdns: JmDNS? = null

    fun start(address: InetAddress, pcName: String, port: Int, id: String, fingerprint: String) {
        stop()
        runCatching {
            val j = JmDNS.create(address, pcName)
            val info = ServiceInfo.create(
                "${Protocol.SERVICE_TYPE}.local.",
                pcName,
                port,
                0, 0,
                mapOf("name" to pcName, "id" to id, "fp" to fingerprint, "v" to Protocol.VERSION.toString()),
            )
            j.registerService(info)
            jmdns = j
        }
    }

    fun stop() {
        runCatching { jmdns?.unregisterAllServices(); jmdns?.close() }
        jmdns = null
    }
}

/** Windows Firewall helpers. */
object Firewall {
    private const val RULE = "AndroidFileWifi"

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    /** True if our inbound rule exists (or we are not on Windows). */
    fun ruleExists(): Boolean {
        if (!isWindows) return true
        return runCatching {
            val p = ProcessBuilder("netsh", "advfirewall", "firewall", "show", "rule", "name=$RULE")
                .redirectErrorStream(true).start()
            p.inputStream.readBytes()
            p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(true)
    }

    /** Asks Windows (UAC prompt) to add inbound rules for the receiver. Returns immediately. */
    fun requestRules(portFirst: Int = Protocol.DEFAULT_PORT) {
        if (!isWindows) return
        val last = portFirst + Protocol.PORT_RANGE - 1
        val bat = File.createTempFile("afw-firewall", ".bat")
        bat.writeText(
            """
            @echo off
            netsh advfirewall firewall delete rule name="$RULE" >nul 2>&1
            netsh advfirewall firewall add rule name="$RULE" dir=in action=allow protocol=TCP localport=$portFirst-$last profile=private,domain
            netsh advfirewall firewall add rule name="$RULE" dir=in action=allow protocol=UDP localport=5353 profile=private,domain
            """.trimIndent().replace("\n", "\r\n")
        )
        bat.deleteOnExit()
        ProcessBuilder(
            "powershell", "-NoProfile", "-Command",
            "Start-Process -FilePath '${bat.absolutePath}' -Verb RunAs -WindowStyle Hidden",
        ).start()
    }
}

/** "Start with Windows" via the per-user Run registry key (only when running as an installed app). */
object Autostart {
    private const val KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val NAME = "AndroidFileWifi"

    /** jpackage sets this property for installed apps; it is absent when started from Gradle/IDE. */
    private val exePath: String? get() = System.getProperty("jpackage.app-path")

    val supported: Boolean
        get() = System.getProperty("os.name").orEmpty().startsWith("Windows") && exePath != null

    fun set(enabled: Boolean) {
        val exe = exePath ?: return
        runCatching {
            val cmd = if (enabled) listOf("reg", "add", KEY, "/v", NAME, "/t", "REG_SZ", "/d", "\"$exe\" --minimized", "/f")
            else listOf("reg", "delete", KEY, "/v", NAME, "/f")
            ProcessBuilder(cmd).redirectErrorStream(true).start().also { it.inputStream.readBytes(); it.waitFor() }
        }
    }
}
