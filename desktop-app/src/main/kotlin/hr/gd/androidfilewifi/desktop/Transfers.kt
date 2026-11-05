package hr.gd.androidfilewifi.desktop

import hr.gd.androidfilewifi.shared.CompleteResponse
import hr.gd.androidfilewifi.shared.CreateSessionResponse
import hr.gd.androidfilewifi.shared.FileDecision
import hr.gd.androidfilewifi.shared.FileSpec
import hr.gd.androidfilewifi.shared.FileStatus
import hr.gd.androidfilewifi.shared.PathSanitizer
import hr.gd.androidfilewifi.shared.UploadResponse
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ApiException(val status: HttpStatusCode, message: String, val expectedOffset: Long? = null) :
    Exception(message)

enum class SessionState { Active, Completed, Incomplete, Cancelled }

/** Immutable snapshot of a session for the UI. */
data class SessionView(
    val id: String,
    val deviceName: String,
    val folder: File,
    val startedAt: Long,
    val endedAt: Long?,
    val state: SessionState,
    val totalFiles: Int,
    val doneFiles: Int,
    val skippedFiles: Int,
    val totalBytes: Long,
    val receivedBytes: Long,
    val speedBytesPerSec: Long,
) {
    val fraction: Float
        get() = if (totalBytes > 0) (receivedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        else if (totalFiles == 0 || doneFiles + skippedFiles >= totalFiles) 1f else 0f
    val etaSeconds: Long?
        get() = if (state == SessionState.Active && speedBytesPerSec > 0)
            (totalBytes - receivedBytes).coerceAtLeast(0) / speedBytesPerSec else null
}

internal class FileState(
    val spec: FileSpec,
    val finalFile: File,
    val decision: FileStatus,
    initialOffset: Long,
) {
    val partFile = File(finalFile.path + ".part")
    val lock = Mutex()

    @Volatile var done: Boolean = decision == FileStatus.Skip
    @Volatile var failed: Boolean = false
    val received = AtomicLong(initialOffset)
}

class Session internal constructor(
    val id: String,
    val deviceId: String,
    val deviceName: String,
    val folder: File,
    internal val files: Map<Int, FileState>,
) {
    val startedAt = System.currentTimeMillis()
    @Volatile var endedAt: Long? = null
    @Volatile var state: SessionState = SessionState.Active
    internal val receivedBytes = AtomicLong(files.values.filter { !it.done }.sumOf { it.received.get() })
    internal val doneFiles = AtomicInteger(0)
    internal val totalBytes = files.values.filter { it.decision != FileStatus.Skip }.sumOf { it.spec.size }
    internal val skipped = files.values.count { it.decision == FileStatus.Skip }
    @Volatile var speed: Long = 0
    private var lastBytes = receivedBytes.get()
    private var lastTick = System.nanoTime()

    internal fun tickSpeed() {
        val now = System.nanoTime()
        val bytes = receivedBytes.get()
        val dt = (now - lastTick) / 1e9
        if (dt >= 0.5) {
            val inst = ((bytes - lastBytes) / dt).toLong()
            speed = if (speed == 0L) inst else (speed * 0.6 + inst * 0.4).toLong()
            if (state != SessionState.Active || inst == 0L && speed < 1024) speed = 0
            lastBytes = bytes
            lastTick = now
        }
    }

    fun view() = SessionView(
        id, deviceName, folder, startedAt, endedAt, state,
        totalFiles = files.size,
        doneFiles = files.values.count { it.done && it.decision != FileStatus.Skip },
        skippedFiles = skipped,
        totalBytes = totalBytes,
        receivedBytes = receivedBytes.get().coerceAtMost(totalBytes),
        speedBytesPerSec = speed,
    )
}

/** Owns all upload sessions: decides what to receive, writes files, and exposes progress to the UI. */
class TransferManager(
    private val config: ConfigStore,
    /** Called once when a session finishes (for notifications). */
    private val onFinished: (SessionView) -> Unit = {},
) {
    private val sessions = ConcurrentHashMap<String, Session>()
    private val order = java.util.concurrent.CopyOnWriteArrayList<Session>()

    private val _views = MutableStateFlow<List<SessionView>>(emptyList())
    val views: StateFlow<List<SessionView>> = _views

    /** Recomputes snapshots for the UI; call periodically (a few times per second). */
    fun publish() {
        order.forEach { it.tickSpeed() }
        _views.value = order.map { it.view() }.reversed()
    }

    private fun root(): File = File(config.value.receiveDir)

    fun session(id: String, deviceId: String): Session {
        val s = sessions[id] ?: throw ApiException(HttpStatusCode.NotFound, "Unknown session")
        if (s.deviceId != deviceId) throw ApiException(HttpStatusCode.Forbidden, "Not your session")
        return s
    }

    fun create(device: PairedDevice, specs: List<FileSpec>): Pair<Session, CreateSessionResponse> {
        if (specs.size > MAX_FILES) throw ApiException(HttpStatusCode.PayloadTooLarge, "Too many files in one session")
        if (specs.map { it.id }.toSet().size != specs.size) throw ApiException(HttpStatusCode.BadRequest, "Duplicate file ids")
        val deviceFolder = File(root(), PathSanitizer.segment(device.name, "phone"))
        val rootCanonical = root().canonicalFile
        val claimed = HashSet<String>()
        val states = LinkedHashMap<Int, FileState>()

        for (spec in specs) {
            if (spec.size < 0) throw ApiException(HttpStatusCode.BadRequest, "Negative size")
            val segments = PathSanitizer.segments(spec.relPath)
            var base = deviceFolder
            for (seg in segments.dropLast(1)) base = File(base, seg)
            val name = segments.last()
            val stem = name.substringBeforeLast('.', name)
            val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""

            var n = 0
            var chosen: File? = null
            var status = FileStatus.New
            var offset = 0L
            while (n < 1000 && chosen == null) {
                val candidate = File(base, if (n == 0) name else "$stem ($n)$ext")
                if (!candidate.canonicalFile.path.startsWith(rootCanonical.path)) {
                    throw ApiException(HttpStatusCode.BadRequest, "Invalid path")
                }
                val key = candidate.canonicalFile.path.lowercase()
                when {
                    key in claimed -> n++
                    candidate.exists() -> {
                        if (candidate.isFile && candidate.length() == spec.size &&
                            kotlin.math.abs(candidate.lastModified() - spec.modified) <= MTIME_TOLERANCE_MS
                        ) {
                            chosen = candidate; status = FileStatus.Skip
                        } else n++
                    }
                    else -> {
                        val part = File(candidate.path + ".part")
                        if (part.isFile && part.length() in 1 until spec.size) {
                            status = FileStatus.Partial; offset = part.length()
                        } else if (part.exists()) {
                            part.delete()
                        }
                        chosen = candidate
                    }
                }
            }
            val target = chosen ?: throw ApiException(HttpStatusCode.Conflict, "Too many name conflicts")
            claimed += target.canonicalFile.path.lowercase()
            states[spec.id] = FileState(spec, target, status, offset)
        }

        val session = Session(UUID.randomUUID().toString(), device.id, device.name, deviceFolder, states)
        sessions[session.id] = session
        order += session
        while (order.size > MAX_HISTORY) {
            val old = order.firstOrNull { it.state != SessionState.Active } ?: break
            order.remove(old); sessions.remove(old.id)
        }
        publish()
        val decisions = states.values.map { FileDecision(it.spec.id, it.decision, it.received.get().takeIf { _ -> it.decision == FileStatus.Partial } ?: 0) }
        return session to CreateSessionResponse(session.id, decisions)
    }

    /** Appends the request body to the file's .part, starting at [offset]. */
    suspend fun receive(session: Session, fileId: Int, offset: Long, body: ByteReadChannel): UploadResponse {
        val f = session.files[fileId] ?: throw ApiException(HttpStatusCode.NotFound, "Unknown file")
        if (session.state != SessionState.Active) throw ApiException(HttpStatusCode.Gone, "Session is closed")
        return f.lock.withLock {
            if (f.done) return@withLock UploadResponse(f.spec.size, true)
            withContext(Dispatchers.IO) {
                f.finalFile.parentFile.mkdirs()
                RandomAccessFile(f.partFile, "rw").use { raf ->
                    val current = raf.length()
                    if (offset != current) {
                        throw ApiException(HttpStatusCode.Conflict, "Offset mismatch", expectedOffset = current)
                    }
                    raf.seek(offset)
                    var pos = offset
                    val buf = ByteArray(256 * 1024)
                    try {
                        while (true) {
                            val n = body.readAvailable(buf, 0, buf.size)
                            if (n < 0) break
                            if (n == 0) continue
                            if (pos + n > f.spec.size) {
                                throw ApiException(HttpStatusCode.BadRequest, "More data than declared size")
                            }
                            raf.write(buf, 0, n)
                            pos += n
                            f.received.set(pos)
                            session.receivedBytes.addAndGet(n.toLong())
                        }
                    } catch (e: ApiException) {
                        throw e
                    } catch (e: Exception) {
                        // Connection dropped: keep the .part file so the sender can resume.
                        f.failed = true
                        throw e
                    }
                    if (pos == f.spec.size) {
                        raf.fd.sync()
                    }
                }
                val length = f.partFile.length()
                if (length == f.spec.size) {
                    Files.move(f.partFile.toPath(), f.finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    if (f.spec.modified > 0) f.finalFile.setLastModified(f.spec.modified)
                    f.done = true
                    session.doneFiles.incrementAndGet()
                    UploadResponse(length, true)
                } else {
                    UploadResponse(length, false)
                }
            }
        }
    }

    /** How much of a file the receiver already has (used by the sender to resume after a dropped connection). */
    fun status(session: Session, fileId: Int): UploadResponse {
        val f = session.files[fileId] ?: throw ApiException(HttpStatusCode.NotFound, "Unknown file")
        if (f.done) return UploadResponse(f.spec.size, true)
        return UploadResponse(if (f.partFile.isFile) f.partFile.length() else 0L, false)
    }

    fun complete(session: Session): CompleteResponse {
        val done = session.files.values.count { it.done && it.decision != FileStatus.Skip }
        val failed = session.files.values.count { !it.done }
        if (session.state == SessionState.Active) {
            session.state = if (failed == 0) SessionState.Completed else SessionState.Incomplete
            session.endedAt = System.currentTimeMillis()
            publish()
            onFinished(session.view())
        }
        return CompleteResponse(received = done, skipped = session.skipped, failed = failed)
    }

    fun cancel(session: Session) {
        if (session.state != SessionState.Active) return
        session.state = SessionState.Cancelled
        session.endedAt = System.currentTimeMillis()
        session.files.values.filter { !it.done }.forEach { runCatching { it.partFile.delete() } }
        publish()
    }

    companion object {
        const val MAX_FILES = 200_000
        const val MAX_HISTORY = 20
        const val MTIME_TOLERANCE_MS = 3000L
    }
}
