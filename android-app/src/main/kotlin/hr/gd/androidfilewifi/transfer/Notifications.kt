package hr.gd.androidfilewifi.transfer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import hr.gd.androidfilewifi.MainActivity
import hr.gd.androidfilewifi.R

object Notifications {
    private const val CHANNEL_PROGRESS = "transfers"
    private const val CHANNEL_DONE = "transfers_done"
    const val PROGRESS_ID = 4711

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Transfers in progress", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Finished transfers", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_TRANSFERS, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun progress(context: Context, view: JobView, cancelIntent: PendingIntent?): android.app.Notification {
        val text = when (view.state) {
            JobState.Connecting -> "Connecting to ${view.pcName}…"
            else -> buildString {
                append("${view.doneFiles + view.skippedFiles} / ${view.totalFiles} files")
                if (view.speedBytesPerSec > 0) append(" · ${formatBytes(view.speedBytesPerSec)}/s")
            }
        }
        val b = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sending to ${view.pcName}")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp(context))
            .setProgress(1000, (view.fraction * 1000).toInt(), view.state != JobState.Sending)
        if (cancelIntent != null) b.addAction(0, "Cancel", cancelIntent)
        return b.build()
    }

    fun finished(context: Context, view: JobView) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val (title, text) = when (view.state) {
            JobState.Done -> "Sent to ${view.pcName}" to
                "${view.doneFiles} file(s) sent" + if (view.skippedFiles > 0) ", ${view.skippedFiles} already on the PC" else ""
            JobState.Failed -> "Transfer to ${view.pcName} stopped" to (view.message ?: "Something went wrong")
            else -> return
        }
        val n = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(view.id.hashCode(), n) }
    }
}

fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024L * 1024 -> "%.0f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}
