package com.imad.downloader

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.imad.downloader.core.DownloadItem
import com.imad.downloader.core.Format

class Notifications(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, context.getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, context.getString(R.string.channel_done), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun allowed() = android.os.Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(action: String, id: String?, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context, code, Intent(context, NotificationActionReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The ongoing notification the foreground service shows. */
    fun progress(active: List<DownloadItem>): android.app.Notification {
        val total = active.sumOf { maxOf(it.totalBytes, 0) }
        val done = active.sumOf { it.downloadedBytes }
        val speed = active.sumOf { it.speedBps }
        val title = when (active.size) {
            0 -> "Preparing downloads"
            1 -> active[0].fileName
            else -> "Downloading ${active.size} files"
        }
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        val text = buildString {
            if (total > 0) append("$pct%  •  ${Format.bytes(done)} / ${Format.bytes(total)}") else append(Format.bytes(done))
            if (speed > 0) append("  •  ${Format.speed(speed)}")
        }
        return NotificationCompat.Builder(context, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setColor(0xFF1565C0.toInt())
            .setProgress(100, pct, total <= 0 && active.isNotEmpty())
            .setContentIntent(openApp())
            .addAction(0, "Pause all", action(ACTION_PAUSE_ALL, null, 1))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun completed(item: DownloadItem) {
        if (!allowed()) return
        // A file outside the shared folders cannot be handed to other apps; then just open ours.
        val open = runCatching {
            PendingIntent.getActivity(
                context, item.id.hashCode(), FileActions.openIntent(context, item),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }.getOrElse { openApp() }
        val n = NotificationCompat.Builder(context, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Download complete")
            .setContentText("${item.fileName}  •  ${Format.bytes(item.totalBytes)}")
            .setColor(0xFF2E9D4F.toInt())
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { manager.notify(item.id.hashCode(), n) }
    }

    fun needsAttention(title: String, text: String) {
        if (!allowed()) return
        val n = NotificationCompat.Builder(context, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setColor(0xFF1565C0.toInt())
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        runCatching { manager.notify(ATTENTION_ID, n) }
    }

    fun failed(item: DownloadItem) {
        if (!allowed()) return
        val n = NotificationCompat.Builder(context, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Download failed")
            .setContentText("${item.fileName}: ${item.error}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${item.fileName}\n${item.error}"))
            .setColor(0xFFD32F2F.toInt())
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .addAction(0, "Retry", action(ACTION_RESUME, item.id, item.id.hashCode()))
            .build()
        runCatching { manager.notify(item.id.hashCode(), n) }
    }

    companion object {
        const val CH_PROGRESS = "progress"
        const val CH_DONE = "done"
        const val PROGRESS_ID = 1001
        const val ATTENTION_ID = 1002
        const val ACTION_PAUSE_ALL = "com.imad.downloader.PAUSE_ALL"
        const val ACTION_RESUME = "com.imad.downloader.RESUME"
        const val EXTRA_ID = "id"
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as ImadApp
        when (intent.action) {
            Notifications.ACTION_PAUSE_ALL -> app.engine.pauseAll()
            Notifications.ACTION_RESUME -> intent.getStringExtra(Notifications.EXTRA_ID)?.let {
                NotificationManagerCompat.from(context).cancel(it.hashCode())
                app.engine.start(it)
            }
        }
    }
}
