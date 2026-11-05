package hr.gd.androidfilewifi.shared

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What the PC puts in its QR code:  afw://192.168.1.20:47800?fp=<sha256 hex>&code=123456&name=My%20PC
 */
data class PairingLink(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val code: String,
    val pcName: String = "",
) {
    fun toUri(): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return "afw://$host:$port?fp=${enc(fingerprint)}&code=${enc(code)}&name=${enc(pcName)}"
    }

    companion object {
        fun parse(text: String): PairingLink? {
            val t = text.trim()
            if (!t.startsWith("afw://")) return null
            val rest = t.removePrefix("afw://")
            val hostPort = rest.substringBefore('?').trimEnd('/')
            val query = rest.substringAfter('?', "")
            val host = hostPort.substringBeforeLast(':', "")
            val port = hostPort.substringAfterLast(':', "").toIntOrNull() ?: return null
            if (host.isEmpty() || port !in 1..65535) return null
            val params = query.split('&').filter { it.contains('=') }.associate {
                it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            val fp = params["fp"]?.lowercase() ?: return null
            if (fp.length != 64 || !fp.all { it in '0'..'9' || it in 'a'..'f' }) return null
            return PairingLink(host, port, fp, params["code"].orEmpty(), params["name"].orEmpty())
        }
    }
}
