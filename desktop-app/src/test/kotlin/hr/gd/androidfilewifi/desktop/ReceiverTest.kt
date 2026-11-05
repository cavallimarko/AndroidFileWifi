package hr.gd.androidfilewifi.desktop

import hr.gd.androidfilewifi.shared.CompleteResponse
import hr.gd.androidfilewifi.shared.CreateSessionRequest
import hr.gd.androidfilewifi.shared.CreateSessionResponse
import hr.gd.androidfilewifi.shared.FileSpec
import hr.gd.androidfilewifi.shared.FileStatus
import hr.gd.androidfilewifi.shared.PairRequest
import hr.gd.androidfilewifi.shared.PairResponse
import hr.gd.androidfilewifi.shared.PingResponse
import hr.gd.androidfilewifi.shared.UploadResponse
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.X509TrustManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Drives the real HTTPS server with a certificate-pinning client, exactly like the phone does. */
class ReceiverTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var tmp: File
    private lateinit var recv: File
    private lateinit var ctx: ReceiverContext
    private lateinit var server: ReceiverServer
    private lateinit var client: HttpClient
    private var base = ""

    @BeforeTest fun setUp() {
        // The phone connects by IP, so hostname verification is replaced by fingerprint pinning.
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true")
        tmp = Files.createTempDirectory("afw-test").toFile()
        recv = File(tmp, "received")
        val store = ConfigStore(File(tmp, "cfg"))
        store.update { it.copy(receiveDir = recv.path, pcName = "TestPC", preferredPort = 48800 + (System.nanoTime() % 150).toInt()) }
        val identity = Identity.loadOrCreate(File(tmp, "cfg"))
        ctx = ReceiverContext(store, identity, PairingManager(), TransferManager(store))
        server = ReceiverServer(ctx)
        val port = server.start()
        base = "https://localhost:$port/api/v1"

        val pinned = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                check(Identity.fingerprintOf(chain[0]) == identity.fingerprint) { "fingerprint mismatch" }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(pinned), null) }
        client = HttpClient.newBuilder()
            .sslContext(ssl)
            .sslParameters(SSLParameters().apply { endpointIdentificationAlgorithm = "" })
            .build()
    }

    @AfterTest fun tearDown() {
        server.stop()
        tmp.deleteRecursively()
    }

    private fun send(method: String, path: String, body: ByteArray? = null, token: String? = null, contentType: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI("$base$path"))
        token?.let { b.header("Authorization", "Bearer $it") }
        if (body != null) {
            b.header("Content-Type", contentType ?: "application/octet-stream")
            b.method(method, HttpRequest.BodyPublishers.ofByteArray(body))
        } else b.method(method, HttpRequest.BodyPublishers.noBody())
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun postJson(path: String, jsonBody: String, token: String? = null) =
        send("POST", path, jsonBody.toByteArray(), token, "application/json")

    private fun pair(code: String = ctx.pairing.code.value, id: String = "dev1", name: String = "Pixel 8"): HttpResponse<String> =
        postJson("/pair", json.encodeToString(PairRequest.serializer(), PairRequest(id, name, code)))

    private fun pairedToken(): String =
        json.decodeFromString(PairResponse.serializer(), pair().body()).token

    private fun createSession(token: String, vararg files: FileSpec): CreateSessionResponse {
        val r = postJson("/sessions", json.encodeToString(CreateSessionRequest.serializer(), CreateSessionRequest(files.toList())), token)
        assertEquals(200, r.statusCode(), r.body())
        return json.decodeFromString(CreateSessionResponse.serializer(), r.body())
    }

    private fun upload(token: String, sid: String, id: Int, data: ByteArray, offset: Long = 0): HttpResponse<String> =
        send("PUT", "/sessions/$sid/files/$id?offset=$offset", data, token)

    @Test fun pingNeedsNoAuthAndPinnedTlsWorks() {
        val r = send("GET", "/ping")
        assertEquals(200, r.statusCode())
        assertEquals("TestPC", json.decodeFromString(PingResponse.serializer(), r.body()).pcName)
    }

    @Test fun pairingRequiresCorrectSingleUseCode() {
        assertEquals(403, pair(code = "nope").statusCode())
        val code = ctx.pairing.code.value
        assertEquals(200, pair(code = code).statusCode())
        // code is single-use: the same code must now be rejected
        assertEquals(403, pair(code = code, id = "dev2").statusCode())
        assertEquals(1, ctx.config.value.devices.size)
        // token is stored hashed, never in clear
        assertEquals(64, ctx.config.value.devices[0].tokenHash.length)
    }

    @Test fun fiveWrongGuessesRotateTheCode() {
        val before = ctx.pairing.code.value
        repeat(PairingManager.MAX_FAILURES) { pair(code = "000000x") }
        assertNotEquals(before, ctx.pairing.code.value)
    }

    @Test fun sessionsRequireAuth() {
        assertEquals(401, postJson("/sessions", """{"files":[]}""").statusCode())
        assertEquals(401, postJson("/sessions", """{"files":[]}""", token = "bogus").statusCode())
    }

    @Test fun uploadsFileKeepsFolderAndDate() {
        val token = pairedToken()
        val data = ByteArray(700_000) { (it % 251).toByte() }
        val mtime = 1_600_000_000_000L
        val s = createSession(token, FileSpec(1, "Camera/IMG_1.jpg", data.size.toLong(), mtime, "image/jpeg"))
        assertEquals(FileStatus.New, s.files[0].status)

        val r = upload(token, s.sessionId, 1, data)
        assertEquals(200, r.statusCode(), r.body())
        assertTrue(json.decodeFromString(UploadResponse.serializer(), r.body()).done)

        val out = File(recv, "Pixel 8/Camera/IMG_1.jpg")
        assertContentEquals(data, out.readBytes())
        assertTrue(kotlin.math.abs(out.lastModified() - mtime) < 2000)
        assertFalse(File(out.path + ".part").exists())

        val c = postJson("/sessions/${s.sessionId}/complete", "{}", token)
        assertEquals(CompleteResponse(1, 0, 0), json.decodeFromString(CompleteResponse.serializer(), c.body()))
        assertEquals(SessionState.Completed, ctx.transfers.views.value.first().state)
    }

    @Test fun identicalFileIsSkippedNextTime() {
        val token = pairedToken()
        val data = "hello".toByteArray()
        val spec = FileSpec(1, "A/x.txt", 5, 1_600_000_000_000L)
        upload(token, createSession(token, spec).sessionId, 1, data)
        val again = createSession(token, spec)
        assertEquals(FileStatus.Skip, again.files[0].status)
    }

    @Test fun sameNameDifferentContentGetsNumberedCopy() {
        val token = pairedToken()
        upload(token, createSession(token, FileSpec(1, "A/x.txt", 5, 1_600_000_000_000L)).sessionId, 1, "hello".toByteArray())
        val s = createSession(token, FileSpec(1, "A/x.txt", 7, 1_700_000_000_000L))
        assertEquals(FileStatus.New, s.files[0].status)
        upload(token, s.sessionId, 1, "goodbye".toByteArray())
        assertEquals("hello", File(recv, "Pixel 8/A/x.txt").readText())
        assertEquals("goodbye", File(recv, "Pixel 8/A/x (1).txt").readText())
        // and re-sending the second version is recognised as already present
        assertEquals(FileStatus.Skip, createSession(token, FileSpec(1, "A/x.txt", 7, 1_700_000_000_000L)).files[0].status)
    }

    @Test fun interruptedUploadResumesFromOffset() {
        val token = pairedToken()
        val data = ByteArray(10_000) { it.toByte() }
        val spec = FileSpec(1, "V/clip.mp4", data.size.toLong(), 1_600_000_000_000L)
        val s1 = createSession(token, spec)
        // first half arrives, then the "connection drops" (we simply stop)
        val half = upload(token, s1.sessionId, 1, data.copyOfRange(0, 4000))
        assertFalse(json.decodeFromString(UploadResponse.serializer(), half.body()).done)

        val s2 = createSession(token, spec)
        assertEquals(FileStatus.Partial, s2.files[0].status)
        assertEquals(4000L, s2.files[0].offset)
        // wrong offset is refused with the expected one
        val bad = upload(token, s2.sessionId, 1, data.copyOfRange(5000, 10_000), offset = 5000)
        assertEquals(409, bad.statusCode())
        assertTrue(bad.body().contains("4000"))

        val ok = upload(token, s2.sessionId, 1, data.copyOfRange(4000, 10_000), offset = 4000)
        assertTrue(json.decodeFromString(UploadResponse.serializer(), ok.body()).done)
        assertContentEquals(data, File(recv, "Pixel 8/V/clip.mp4").readBytes())
    }

    @Test fun pathTraversalCannotEscapeTheReceiveFolder() {
        val token = pairedToken()
        val s = createSession(token, FileSpec(1, "../../../evil.txt", 3, 0), FileSpec(2, "C:\\Windows\\x.txt", 3, 0))
        upload(token, s.sessionId, 1, "abc".toByteArray())
        upload(token, s.sessionId, 2, "abc".toByteArray())
        val written = recv.walkTopDown().filter { it.isFile }.map { it.canonicalPath }.toList()
        assertEquals(2, written.size)
        assertTrue(written.all { it.startsWith(recv.canonicalPath) }, written.toString())
        assertFalse(File(tmp, "evil.txt").exists())
    }

    @Test fun refusesMoreDataThanDeclared() {
        val token = pairedToken()
        val s = createSession(token, FileSpec(1, "a.bin", 3, 0))
        assertEquals(400, upload(token, s.sessionId, 1, ByteArray(10)).statusCode())
    }

    @Test fun otherDevicesCannotUseMySession() {
        val t1 = pairedToken()
        val s = createSession(t1, FileSpec(1, "a.bin", 3, 0))
        val t2 = json.decodeFromString(PairResponse.serializer(), pair(id = "dev2", name = "Other").body()).token
        assertEquals(403, upload(t2, s.sessionId, 1, ByteArray(3)).statusCode())
    }

    @Test fun cancelRemovesPartialFiles() {
        val token = pairedToken()
        val s = createSession(token, FileSpec(1, "a.bin", 100, 0))
        upload(token, s.sessionId, 1, ByteArray(40))
        assertEquals(204, send("DELETE", "/sessions/${s.sessionId}", token = token).statusCode())
        assertTrue(recv.walkTopDown().none { it.isFile })
        assertEquals(SessionState.Cancelled, ctx.transfers.views.value.first().state)
    }

    @Test fun senderCanAskHowMuchWasReceived() {
        val token = pairedToken()
        val s = createSession(token, FileSpec(1, "a.bin", 100, 0))
        fun status() = json.decodeFromString(UploadResponse.serializer(), send("GET", "/sessions/${s.sessionId}/files/1", token = token).body())
        assertEquals(UploadResponse(0, false), status())
        upload(token, s.sessionId, 1, ByteArray(40))
        assertEquals(UploadResponse(40, false), status())
        upload(token, s.sessionId, 1, ByteArray(60), offset = 40)
        assertEquals(UploadResponse(100, true), status())
    }

    @Test fun zeroByteFileWorks() {
        val token = pairedToken()
        val s = createSession(token, FileSpec(1, "empty.txt", 0, 1_600_000_000_000L))
        assertTrue(json.decodeFromString(UploadResponse.serializer(), upload(token, s.sessionId, 1, ByteArray(0)).body()).done)
        assertEquals(0L, File(recv, "Pixel 8/empty.txt").length())
    }
}
