package com.imad.downloader

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.imad.downloader.core.DownloadStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Foreground service that keeps the process alive and shows overall progress while downloading. */
class DownloadService : LifecycleService() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val app = application as ImadApp
        val active = app.engine.downloads.filter { it.status.isActive }
        ServiceCompat.startForeground(
            this, Notifications.PROGRESS_ID, app.notifications.progress(active),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (!started) {
            started = true
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ImadDownloader:download")
                .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
            lifecycleScope.launch { loop(app) }
        }
        return START_STICKY
    }

    private suspend fun loop(app: ImadApp) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        while (true) {
            val items = app.engine.downloads
            val busy = items.filter { it.status.isActive || it.status == DownloadStatus.QUEUED }
            if (busy.isEmpty()) {
                // Give the engine a moment: a queue may be about to start the next file.
                delay(1500)
                if (app.engine.downloads.none { it.status.isActive || it.status == DownloadStatus.QUEUED }) break
                continue
            }
            runCatching { nm.notify(Notifications.PROGRESS_ID, app.notifications.progress(busy.filter { it.status.isActive })) }
            delay(1000)
        }
        app.engine.state.first() // keep the reference warm; nothing to wait for
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        started = false
        super.onDestroy()
    }
}
