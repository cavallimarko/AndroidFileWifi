package hr.gd.androidfilewifi.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import hr.gd.androidfilewifi.data.MediaItem

/** Turns an ACTION_SEND / ACTION_SEND_MULTIPLE intent (the system share sheet) into items we can send. */
object SharedIntent {
    fun parse(context: Context, intent: Intent): List<MediaItem> {
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(streamExtra(intent))
            Intent.ACTION_SEND_MULTIPLE -> streamListExtra(intent)
            else -> emptyList()
        }
        val now = System.currentTimeMillis()
        return uris.mapIndexedNotNull { index, uri ->
            if (uri.scheme != "content") return@mapIndexedNotNull null
            var name = "shared_${now}_$index"
            var size = 0L
            runCatching {
                context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            }
            val mime = context.contentResolver.getType(uri) ?: intent.type ?: "application/octet-stream"
            MediaItem(
                id = -(index + 1).toLong(),
                uri = uri,
                name = name,
                bucketId = 0,
                bucketName = "Shared",
                isVideo = mime.startsWith("video/"),
                mimeType = mime,
                dateMillis = now,
                sizeBytes = size,
                durationMs = 0,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamListExtra(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
}
