package hr.gd.androidfilewifi.net

import hr.gd.androidfilewifi.data.PairedPc
import hr.gd.androidfilewifi.data.PcStore
import hr.gd.androidfilewifi.shared.client.PcClient
import hr.gd.androidfilewifi.shared.client.PcHttpException
import java.io.IOException
import javax.net.ssl.SSLException

/** Turns pairing steps into results the UI can show. */
class Pairing(private val store: PcStore) {

    /** Pairs with a PC whose certificate fingerprint [fingerprint] is already known (QR code or discovery). */
    suspend fun pair(host: String, port: Int, fingerprint: String, code: String): Result<PairedPc> = runCatching {
        val fp = fingerprint.lowercase()
        val response = PcClient(host, port, fp).pair(store.deviceId, store.deviceName, code.filter { it.isDigit() })
        PairedPc(fp, response.pcName, host, port, response.token).also(store::save)
    }.recoverCatching { throw friendly(it) }

    /**
     * Manual pairing by IP: connects once without a pin only to read the certificate fingerprint. The user
     * must confirm that the Security ID shown on the PC matches before [pair] sends the code.
     */
    suspend fun probe(host: String, port: Int): Result<String> = runCatching {
        val client = PcClient(host, port, null)
        client.ping()
        client.seenFingerprint ?: throw IOException("No certificate received")
    }.recoverCatching { throw friendly(it) }

    private fun friendly(e: Throwable): Throwable = when {
        e is PcHttpException && e.code == 403 -> IOException("Wrong code. Check the code shown on the PC.")
        e is PcHttpException -> IOException(e.message ?: "The PC refused the request")
        e is SSLException -> IOException("The PC's certificate doesn't match. Scan its QR code again.")
        e is IOException -> IOException("Can't reach the PC. Is it on the same Wi-Fi with the app running?")
        else -> e
    }
}
