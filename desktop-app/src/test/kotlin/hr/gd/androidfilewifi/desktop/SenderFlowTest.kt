package hr.gd.androidfilewifi.desktop

import hr.gd.androidfilewifi.shared.FileSpec
import hr.gd.androidfilewifi.shared.FileStatus
import hr.gd.androidfilewifi.shared.client.PcClient
import hr.gd.androidfilewifi.shared.client.PcHttpException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The phone's own HTTP client (PcClient) against the real desktop server. */
class SenderFlowTest {
    private lateinit var tmp: File
    private lateinit var recv: File
    private lateinit var ctx: ReceiverContext
    private lateinit var server: ReceiverServer
    private var port = 0

    @BeforeTest fun setUp() {
        tmp = Files.createTempDirectory("afw-sender").toFile()
        recv = File(tmp, "received")
        val store = ConfigStore(File(tmp, "cfg"))
        store.update { it.copy(receiveDir = recv.path, pcName = "TestPC", preferredPort = 48950 + (System.nanoTime() % 40).toInt()) }
        ctx = ReceiverContext(store, Identity.loadOrCreate(File(tmp, "cfg")), PairingManager(), TransferManager(store))
        server = ReceiverServer(ctx)
        port = server.start()
    }

    @AfterTest fun tearDown() {
        server.stop()
        tmp.deleteRecursively()
    }

    private val fp get() = ctx.identity.fingerprint

    private suspend fun paired(): PcClient {
        val resp = PcClient("127.0.0.1", port, fp).pair("phone-1", "Pixel 8", ctx.pairing.code.value)
        return PcClient("127.0.0.1", port, fp, resp.token)
    }

    private fun bytesBody(data: ByteArray, from: Int = 0, to: Int = data.size): RequestBody =
        data.copyOfRange(from, to).toRequestBody("application/octet-stream".toMediaType())

    /** Streams without buffering, like the phone's StreamBody. */
    private fun generatedBody(length: Long, seed: Int): RequestBody = object : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = length
        override fun writeTo(sink: BufferedSink) {
            val rnd = Random(seed)
            var left = length
            val buf = ByteArray(64 * 1024)
            while (left > 0) {
                val n = minOf(buf.size.toLong(), left).toInt()
                rnd.nextBytes(buf)
                sink.write(buf, 0, n)
                left -= n
            }
        }
    }

    @Test fun trustOnFirstUseProbeSeesTheRealFingerprint() = runBlocking<Unit> {
        val probe = PcClient("127.0.0.1", port, null)
        assertEquals("TestPC", probe.ping().pcName)
        assertEquals(fp, probe.seenFingerprint)
    }

    @Test fun wrongPinIsRejected() {
        val wrong = PcClient("127.0.0.1", port, "00".repeat(32))
        assertFailsWith<IOException> { runBlocking { wrong.ping() } }
    }

    @Test fun wrongCodeIsRefusedWith403() {
        val e = assertFailsWith<PcHttpException> {
            runBlocking { PcClient("127.0.0.1", port, fp).pair("p", "Phone", "000000x") }
        }
        assertEquals(403, e.code)
    }

    @Test fun pairThenSendOverStreamingBodyAndComplete() = runBlocking<Unit> {
        val client = paired()
        val size = 5L * 1024 * 1024
        val session = client.createSession(listOf(FileSpec(0, "Camera/clip.mp4", size, 1_650_000_000_000L, "video/mp4")))
        assertEquals(FileStatus.New, session.files[0].status)
        val r = client.upload(session.sessionId, 0, 0, generatedBody(size, seed = 7))
        assertTrue(r.done)

        val expected = ByteArray(size.toInt()).also { dst ->
            val rnd = Random(7); var pos = 0
            val buf = ByteArray(64 * 1024)
            while (pos < dst.size) { val n = minOf(buf.size, dst.size - pos); rnd.nextBytes(buf); buf.copyInto(dst, pos, 0, n); pos += n }
        }
        assertContentEquals(expected, File(recv, "Pixel 8/Camera/clip.mp4").readBytes())
        val done = client.complete(session.sessionId)
        assertEquals(1, done.received)
        assertEquals(0, done.failed)
    }

    @Test fun threeParallelUploadsLikeTheWorker() = runBlocking<Unit> {
        val client = paired()
        val specs = (0 until 3).map { FileSpec(it, "Album/f$it.bin", 2_000_000L, 1_650_000_000_000L + it) }
        val s = client.createSession(specs)
        specs.map { spec -> async(kotlinx.coroutines.Dispatchers.IO) { client.upload(s.sessionId, spec.id, 0, generatedBody(spec.size, spec.id)) } }
            .awaitAll().forEach { assertTrue(it.done) }
        assertEquals(3, File(recv, "Pixel 8/Album").listFiles()!!.size)
        assertEquals(SessionState.Active, ctx.transfers.views.value.first().state)
        client.complete(s.sessionId)
    }

    @Test fun resumeAfterDroppedConnectionContinuesFromServerOffset() = runBlocking<Unit> {
        val client = paired()
        val data = Random(3).nextBytes(300_000)
        val spec = FileSpec(0, "V/a.bin", data.size.toLong(), 1_650_000_000_000L)
        val s1 = client.createSession(listOf(spec))

        // A body that dies half-way, like a Wi-Fi drop.
        val broken = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = data.size.toLong()
            override fun writeTo(sink: BufferedSink) {
                sink.write(data, 0, 120_000); sink.flush()
                throw IOException("simulated drop")
            }
        }
        assertFailsWith<IOException> { client.upload(s1.sessionId, 0, 0, broken) }

        // Next attempt: ask the server what it kept, then send the rest.
        val kept = client.fileStatus(s1.sessionId, 0)
        assertTrue(kept.received in 1 until data.size, "server kept ${kept.received}")
        val rest = client.upload(s1.sessionId, 0, kept.received, bytesBody(data, kept.received.toInt()))
        assertTrue(rest.done)
        assertContentEquals(data, File(recv, "Pixel 8/V/a.bin").readBytes())
    }

    @Test fun revokedDeviceCanNoLongerSend() = runBlocking<Unit> {
        val client = paired()
        ctx.config.update { c -> c.copy(devices = emptyList()) }
        val e = assertFailsWith<PcHttpException> { client.createSession(emptyList()) }
        assertEquals(401, e.code)
        assertNotNull(e.message)
    }
}
