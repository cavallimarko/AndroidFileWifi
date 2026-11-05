package hr.gd.androidfilewifi.transfer

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import hr.gd.androidfilewifi.data.MediaItem
import hr.gd.androidfilewifi.data.PairedPc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Single source of truth for send jobs: starts them, and exposes their live progress to the UI. */
class TransferRepository(context: Context) {
    private val appContext = context.applicationContext
    val store = JobStore(appContext)
    private val work get() = WorkManager.getInstance(appContext)

    private val _jobs = MutableStateFlow<List<JobView>>(emptyList())
    val jobs: StateFlow<List<JobView>> = _jobs

    private val cancelRequested = ConcurrentHashMap.newKeySet<String>()

    init { restore() }

    /** Re-creates the list after the process was killed: unfinished job files are shown as queued or interrupted. */
    private fun restore() {
        val list = store.all().map { job ->
            val active = runCatching {
                work.getWorkInfosByTag(tag(job.id)).get().any {
                    it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED
                }
            }.getOrDefault(false)
            JobView(
                job.id, job.pcName, job.createdAt,
                if (active) JobState.Queued else JobState.Failed,
                totalFiles = job.files.size,
                totalBytes = job.files.sumOf { it.size },
                message = if (active) null else "Interrupted. Tap Retry to continue.",
            )
        }
        _jobs.value = list.sortedByDescending { it.createdAt }
    }

    fun enqueue(pc: PairedPc, items: List<MediaItem>): String {
        val job = SendJob(
            id = UUID.randomUUID().toString(),
            pcId = pc.id,
            pcName = pc.name,
            createdAt = System.currentTimeMillis(),
            files = items.map {
                JobFile(
                    uri = it.uri.toString(),
                    relPath = "${it.bucketName}/${it.name}",
                    size = it.sizeBytes,
                    modified = it.dateMillis,
                    mime = it.mimeType,
                )
            },
        )
        store.save(job)
        update(JobView(job.id, job.pcName, job.createdAt, JobState.Queued, job.files.size, totalBytes = job.files.sumOf { it.size }))
        schedule(job.id)
        return job.id
    }

    fun retry(id: String) {
        val job = store.load(id) ?: return
        cancelRequested.remove(id)
        update(JobView(job.id, job.pcName, job.createdAt, JobState.Queued, job.files.size, totalBytes = job.files.sumOf { it.size }))
        schedule(id)
    }

    fun cancel(id: String) {
        cancelRequested.add(id)
        work.cancelAllWorkByTag(tag(id))
        // If the worker was not running there is nobody to report the cancellation, so do it here.
        val view = _jobs.value.firstOrNull { it.id == id }
        if (view != null && view.state == JobState.Queued) {
            update(view.copy(state = JobState.Cancelled))
            store.delete(id)
        }
    }

    /** Removes a finished job from the list. */
    fun dismiss(id: String) {
        store.delete(id)
        _jobs.value = _jobs.value.filter { it.id != id }
    }

    fun isCancelRequested(id: String) = id in cancelRequested

    fun update(view: JobView) {
        val current = _jobs.value
        _jobs.value = if (current.any { it.id == view.id }) current.map { if (it.id == view.id) view else it }
        else (listOf(view) + current).sortedByDescending { it.createdAt }
    }

    private fun schedule(id: String) {
        val request = OneTimeWorkRequestBuilder<SendWorker>()
            .setInputData(workDataOf(SendWorker.KEY_JOB to id))
            // No network constraint on purpose: CONNECTED means "validated internet", which a LAN-only
            // Wi-Fi never has. The worker reports an unreachable PC itself.
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, java.util.concurrent.TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(tag(id))
            .build()
        // Jobs run one after another.
        work.enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    companion object {
        private const val UNIQUE = "afw-send"
        fun tag(id: String) = "job-$id"
    }
}
