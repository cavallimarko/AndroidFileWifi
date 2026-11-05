package hr.gd.androidfilewifi.transfer

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import hr.gd.androidfilewifi.Services
import hr.gd.androidfilewifi.data.PairedPc
import hr.gd.androidfilewifi.shared.client.PcClient
import hr.gd.androidfilewifi.shared.client.PcHttpException
import hr.gd.androidfilewifi.shared.FileSpec
import hr.gd.androidfilewifi.shared.FileStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Sends one [SendJob] to a PC: asks the PC what it already has, then streams the rest with a few
 * parallel uploads, resuming interrupted files from where the PC stopped.
 */
class SendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val repo get() = Services.transfers
    private val resolver: ContentResolver get() = applicationContext.contentResolver

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(
        JobView("", "your PC", 0, JobState.Connecting, 0)
    )

    private fun foreground(view: JobView): ForegroundInfo {
        val cancel = androidx.work.WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val n = Notifications.progress(applicationContext, view, cancel)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(Notifications.PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(Notifications.PROGRESS_ID, n)
    }

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB) ?: return Result.failure()
        val job = repo.store.load(jobId) ?: return Result.failure()
        var view = JobView(job.id, job.pcName, job.createdAt, JobState.Connecting, job.files.size,
            totalBytes = job.files.sumOf { it.size })
        repo.update(view)
        runCatching { setForeground(foreground(view)) }

        val pc = Services.pcStore.get(job.pcId)
        if (pc == null) return fail(job, view, "This PC was removed. Pair it again.")

        var sessionId: String? = null
        var client: PcClient? = null
        try {
            client = connect(pc) ?: return fail(job, view,
                "Can't reach ${pc.name}. Is the PC on the same Wi-Fi with AndroidFileWifi running?")
            val c = client

            // 1. Real sizes (what we will actually stream) and the session on the PC.
            val sizes = withContext(Dispatchers.IO) { job.files.map { actualSize(it) } }
            val specs = job.files.mapIndexed { i, f -> FileSpec(i, f.relPath, sizes[i], f.modified, f.mime) }
            val session = c.createSession(specs)
            sessionId = session.sessionId
            val decisions = session.files.associateBy { it.id }

            val todo = specs.filter { decisions[it.id]?.status != FileStatus.Skip }
            val skipped = specs.size - todo.size
            val totalBytes = todo.sumOf { it.size }
            val perFile = AtomicLongArray(specs.size)
            todo.forEach { perFile.set(it.id, decisions[it.id]?.offset ?: 0L) }
            val doneFiles = AtomicInteger(0)
            val failedFiles = AtomicInteger(0)
            val aborted = AtomicBoolean(false)
            var lastError: String? = null

            fun sent(): Long = todo.sumOf { perFile.get(it.id) }

            coroutineScope {
                // progress reporter
                val reporter = launch {
                    var lastBytes = sent()
                    var lastTime = System.nanoTime()
                    var speed = 0L
                    while (isActive) {
                        delay(500)
                        val now = System.nanoTime()
                        val bytes = sent()
                        val inst = ((bytes - lastBytes) / ((now - lastTime) / 1e9)).toLong()
                        speed = if (speed == 0L) inst else (speed * 0.6 + inst * 0.4).toLong()
                        lastBytes = bytes; lastTime = now
                        view = view.copy(
                            state = JobState.Sending, doneFiles = doneFiles.get(), skippedFiles = skipped,
                            totalBytes = totalBytes, sentBytes = bytes, speedBytesPerSec = speed,
                        )
                        repo.update(view)
                        runCatching { setForeground(foreground(view)) }
                    }
                }

                val gate = Semaphore(PARALLEL)
                todo.map { spec ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            if (aborted.get()) return@withPermit
                            try {
                                uploadFile(c, session.sessionId, job.files[spec.id], spec, decisions[spec.id]?.offset ?: 0L,
                                    perFile, aborted)
                                doneFiles.incrementAndGet()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failedFiles.incrementAndGet()
                                lastError = e.message
                                // A network-level failure means the PC is gone: stop instead of
                                // retrying every remaining file for minutes.
                                if (e !is PcHttpException) aborted.set(true)
                            }
                        }
                    }
                }.awaitAll()
                reporter.cancel()
            }

            val failed = todo.size - doneFiles.get()
            if (failed > 0) {
                val msg = if (aborted.get()) "Connection to ${pc.name} was lost. Tap Retry to continue where it stopped."
                else "${failed} file(s) could not be sent" + (lastError?.let { ": $it" } ?: "")
                return fail(job, view.copy(sentBytes = sent(), doneFiles = doneFiles.get(), skippedFiles = skipped, totalBytes = totalBytes), msg)
            }

            runCatching { c.complete(session.sessionId) }
            val done = view.copy(
                state = JobState.Done, doneFiles = doneFiles.get(), skippedFiles = skipped,
                totalBytes = totalBytes, sentBytes = totalBytes, speedBytesPerSec = 0, message = null,
            )
            repo.update(done)
            repo.store.delete(job.id)
            Notifications.finished(applicationContext, done)
            return Result.success()
        } catch (e: CancellationException) {
            val userCancelled = repo.isCancelRequested(job.id) ||
                (Build.VERSION.SDK_INT >= 31 && stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP)
            withContext(NonCancellable) {
                if (userCancelled) {
                    runCatching { sessionId?.let { client?.cancel(it) } }
                    repo.update(view.copy(state = JobState.Cancelled, speedBytesPerSec = 0))
                    repo.store.delete(job.id)
                } else {
                    repo.update(view.copy(state = JobState.Queued, speedBytesPerSec = 0, message = "Waiting…"))
                }
            }
            throw e
        } catch (e: PcHttpException) {
            val msg = when (e.code) {
                401 -> "${pc.name} no longer recognises this phone. Remove and pair it again."
                else -> e.message ?: "Error ${e.code}"
            }
            return fail(job, view, msg)
        } catch (e: IOException) {
            return fail(job, view, "Connection to ${pc.name} failed: ${e.message ?: "network error"}")
        }
    }

    private fun fail(job: SendJob, view: JobView, message: String): Result {
        val failed = view.copy(state = JobState.Failed, speedBytesPerSec = 0, message = message)
        repo.update(failed)
        Notifications.finished(applicationContext, failed)
        return Result.failure()
    }

    /** Finds the PC: at the last known address first, otherwise through mDNS (the IP may have changed). */
    private suspend fun connect(pc: PairedPc): PcClient? {
        val direct = PcClient(pc.host, pc.port, pc.id, pc.token)
        if (runCatching { direct.ping() }.isSuccess) return direct
        val found = Services.discovery.find(pc.id, 5000) ?: return null
        val moved = PcClient(found.host, found.port, pc.id, pc.token)
        if (runCatching { moved.ping() }.isFailure) return null
        Services.pcStore.updateAddress(pc.id, found.host, found.port)
        return moved
    }

    private suspend fun uploadFile(
        client: PcClient,
        sessionId: String,
        file: JobFile,
        spec: FileSpec,
        initialOffset: Long,
        perFile: AtomicLongArray,
        aborted: AtomicBoolean,
    ) {
        var offset = initialOffset
        var attempt = 0
        while (true) {
            if (aborted.get()) throw IOException("Aborted")
            perFile.set(spec.id, offset)
            try {
                val body = StreamBody(resolver, openOriginal(applicationContext, Uri.parse(file.uri)), offset, spec.size - offset) { n ->
                    perFile.addAndGet(spec.id, n.toLong())
                }
                val r = client.upload(sessionId, spec.id, offset, body)
                if (r.done) { perFile.set(spec.id, spec.size); return }
                // The PC holds fewer bytes than we sent (should not happen): continue from its offset.
                offset = r.received
                if (++attempt > MAX_ATTEMPTS) throw IOException("Incomplete upload")
            } catch (e: CancellationException) {
                throw e
            } catch (e: PcHttpException) {
                val expected = e.expectedOffset
                if (e.code == 409 && expected != null && attempt < MAX_ATTEMPTS) {
                    offset = expected; attempt++
                } else throw e
            } catch (e: IOException) {
                if (++attempt > MAX_ATTEMPTS) throw e
                delay(1000L shl (attempt - 1).coerceAtMost(4))
                // How much did the PC keep of the interrupted attempt?
                val status = runCatching { client.fileStatus(sessionId, spec.id) }.getOrNull()
                if (status?.done == true) { perFile.set(spec.id, spec.size); return }
                offset = status?.received ?: offset
            }
        }
    }

    /** The byte length that will really be streamed (MediaStore's SIZE can be stale). */
    private fun actualSize(file: JobFile): Long {
        val uri = Uri.parse(file.uri)
        return runCatching {
            resolver.openAssetFileDescriptor(original(uri), "r")?.use { afd ->
                afd.length.takeIf { it >= 0 }
            }
        }.getOrNull() ?: file.size
    }

    private fun original(uri: Uri): Uri = openOriginal(applicationContext, uri)

    companion object {
        const val KEY_JOB = "job"
        private const val PARALLEL = 3
        private const val MAX_ATTEMPTS = 5
    }
}

/**
 * Since Android 10 media files are returned with location EXIF removed unless the app holds
 * ACCESS_MEDIA_LOCATION and asks for the original. Phone photos should arrive on the PC unchanged.
 */
internal fun openOriginal(context: Context, uri: Uri): Uri {
    if (Build.VERSION.SDK_INT >= 29 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        uri.authority == MediaStore.AUTHORITY
    ) {
        return runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
    }
    return uri
}

/** Streams a byte range of a content URI into the request without loading it into memory. */
internal class StreamBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val offset: Long,
    private val length: Long,
    private val onBytes: (Int) -> Unit,
) : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaType()
    override fun contentLength() = length

    override fun writeTo(sink: BufferedSink) {
        val input: InputStream = resolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        input.use {
            var toSkip = offset
            while (toSkip > 0) {
                val s = it.skip(toSkip)
                if (s <= 0) throw IOException("Cannot seek in source")
                toSkip -= s
            }
            val buf = ByteArray(128 * 1024)
            var remaining = length
            while (remaining > 0) {
                val n = it.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) throw IOException("Source ended early")
                sink.write(buf, 0, n)
                remaining -= n
                onBytes(n)
            }
        }
    }
}
