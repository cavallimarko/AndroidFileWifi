package hr.gd.androidfilewifi.data

import android.net.Uri

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val bucketId: Long,
    val bucketName: String,
    val isVideo: Boolean,
    val mimeType: String,
    /** Epoch millis: date taken, falling back to date added. */
    val dateMillis: Long,
    val sizeBytes: Long,
    val durationMs: Long,
)

data class Album(
    val bucketId: Long,
    val name: String,
    val cover: MediaItem,
    val count: Int,
    val photoCount: Int,
    val videoCount: Int,
)

enum class MediaFilter { All, Photos, Videos }
