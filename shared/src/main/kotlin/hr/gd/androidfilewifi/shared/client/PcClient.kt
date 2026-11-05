package hr.gd.androidfilewifi.shared.client

import hr.gd.androidfilewifi.shared.CompleteResponse
import hr.gd.androidfilewifi.shared.CreateSessionRequest
import hr.gd.androidfilewifi.shared.CreateSessionResponse
import hr.gd.androidfilewifi.shared.ErrorResponse
import hr.gd.androidfilewifi.shared.FileSpec
import hr.gd.androidfilewifi.shared.PairRequest
import hr.gd.androidfilewifi.shared.PairResponse
import hr.gd.androidfilewifi.shared.PingResponse
import hr.gd.androidfilewifi.shared.Protocol
import hr.gd.androidfilewifi.shared.UploadResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The PC answered with an error status. */
class PcHttpException(val code: Int, message: String, val expectedOffset: Long? = null) : IOException(message)

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * An OkHttp client that trusts exactly one certificate: the one whose SHA-256 fingerprint we got from the
 * QR code / discovery. Normal CA validation and host-name checks are replaced by this pin, because the PC
 * uses a self-signed certificate and is addressed by LAN IP.
 *
 * If [pin] is null the client records the certificate it sees instead of rejecting it
 * (trust-on-first-use, used by manual pairing where the user then compares the Security ID).
 */
class PinnedTls(private val pin: String?) {
    @Volatile var seenFingerprint: String? = null
        private set

    val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val fp = sha256Hex(chain[0].encoded)
            seenFingerprint = fp
            if (pin != null && !MessageDigest.isEqual(fp.toByteArray(), pin.lowercase().toByteArray())) {
                throw CertificateException("PC certificate does not match the paired one")
            }
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun newClient(readTimeoutSec: Long = 60): OkHttpClient {
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}

class PcClient(
    private val host: String,
    private val port: Int,
    fingerprint: String?,
    private val token: String? = null,
) {
    private val tls = PinnedTls(fingerprint)
    private val http = tls.newClient()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonType = "application/json".toMediaType()

    /** Fingerprint of the certificate the PC presented on the last connection. */
    val seenFingerprint: String? get() = tls.seenFingerprint

    private fun url(path: String): HttpUrl =
        HttpUrl.Builder().scheme("https").host(host).port(port).encodedPath(Protocol.API + path).build()

    private fun builder(path: String): Request.Builder =
        Request.Builder().url(url(path)).apply { token?.let { header("Authorization", "Bearer $it") } }

    suspend fun ping(): PingResponse =
        send(builder("/ping").get().build(), PingResponse.serializer())

    suspend fun pair(deviceId: String, deviceName: String, code: String): PairResponse =
        send(
            builder("/pair").post(json.encodeToString(PairRequest.serializer(), PairRequest(deviceId, deviceName, code)).toRequestBody(jsonType)).build(),
            PairResponse.serializer(),
        )

    suspend fun createSession(files: List<FileSpec>): CreateSessionResponse =
        send(
            builder("/sessions").post(json.encodeToString(CreateSessionRequest.serializer(), CreateSessionRequest(files)).toRequestBody(jsonType)).build(),
            CreateSessionResponse.serializer(),
        )

    suspend fun fileStatus(sessionId: String, fileId: Int): UploadResponse =
        send(builder("/sessions/$sessionId/files/$fileId").get().build(), UploadResponse.serializer())

    suspend fun upload(sessionId: String, fileId: Int, offset: Long, body: RequestBody): UploadResponse {
        val target = url("/sessions/$sessionId/files/$fileId").newBuilder()
            .addQueryParameter("offset", offset.toString()).build()
        val request = Request.Builder().url(target).put(body)
            .apply { token?.let { header("Authorization", "Bearer $it") } }.build()
        return send(request, UploadResponse.serializer())
    }

    suspend fun complete(sessionId: String): CompleteResponse =
        send(builder("/sessions/$sessionId/complete").post(ByteArray(0).toRequestBody(null)).build(), CompleteResponse.serializer())

    suspend fun cancel(sessionId: String) {
        runCatching { http.newCall(builder("/sessions/$sessionId").delete().build()).await().close() }
    }

    private suspend fun <T> send(request: Request, serializer: KSerializer<T>): T {
        http.newCall(request).await().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val err = runCatching { json.decodeFromString(ErrorResponse.serializer(), text) }.getOrNull()
                throw PcHttpException(response.code, err?.error ?: "HTTP ${response.code}", err?.expectedOffset)
            }
            return json.decodeFromString(serializer, text)
        }
    }
}

/** Suspends until the call finishes; cancelling the coroutine cancels the HTTP call (and its upload). */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
