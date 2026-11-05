package hr.gd.androidfilewifi.transfer

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class JobFile(
    val uri: String,
    /** Path the file gets on the PC below the phone's folder, e.g. "Camera/IMG_0001.jpg". */
    val relPath: String,
    val size: Long,
    val modified: Long,
    val mime: String,
)

@Serializable
data class SendJob(
    val id: String,
    val pcId: String,
    val pcName: String,
    val createdAt: Long,
    val files: List<JobFile>,
)

enum class JobState { Queued, Connecting, Sending, Done, Failed, Cancelled }

/** What the UI shows for one send job. */
data class JobView(
    val id: String,
    val pcName: String,
    val createdAt: Long,
    val state: JobState,
    val totalFiles: Int,
    val doneFiles: Int = 0,
    val skippedFiles: Int = 0,
    val totalBytes: Long = 0,
    val sentBytes: Long = 0,
    val speedBytesPerSec: Long = 0,
    val message: String? = null,
) {
    val fraction: Float
        get() = if (totalBytes > 0) (sentBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        else if (state == JobState.Done) 1f else 0f
    val isFinished: Boolean get() = state == JobState.Done || state == JobState.Failed || state == JobState.Cancelled
}

/** Persists jobs as JSON files so a send survives process death (WorkManager only carries the job id). */
class JobStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "jobs").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    private fun file(id: String) = File(dir, "$id.json")

    fun save(job: SendJob) {
        val tmp = File(dir, "${job.id}.tmp")
        tmp.writeText(json.encodeToString(SendJob.serializer(), job))
        tmp.renameTo(file(job.id))
    }

    fun load(id: String): SendJob? = runCatching {
        json.decodeFromString(SendJob.serializer(), file(id).readText())
    }.getOrNull()

    fun delete(id: String) { file(id).delete() }

    fun all(): List<SendJob> = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { f -> runCatching { json.decodeFromString(SendJob.serializer(), f.readText()) }.getOrNull() }
        .sortedBy { it.createdAt }
}
