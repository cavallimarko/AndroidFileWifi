package hr.gd.androidfilewifi.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import hr.gd.androidfilewifi.data.MediaItem

/** Opens the normal Android share sheet for the given media. */
fun shareItems(context: Context, items: List<MediaItem>) {
    if (items.isEmpty()) return
    val uris = ArrayList<Uri>(items.map { it.uri })
    val allImages = items.none { it.isVideo }
    val allVideos = items.all { it.isVideo }
    val mime = when {
        allImages -> "image/*"
        allVideos -> "video/*"
        else -> "*/*"
    }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
    }
    intent.type = mime
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
