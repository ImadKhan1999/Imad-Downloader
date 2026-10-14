package com.imad.downloader

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import com.imad.downloader.browser.BrowserData
import com.imad.downloader.core.DownloadEngine
import com.imad.downloader.core.DownloadStatus
import com.imad.downloader.core.EngineEvent
import com.imad.downloader.core.JsonFileStore
import com.imad.downloader.core.QueueScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

class ImadApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var settings: SettingsRepository
        private set
    lateinit var engine: DownloadEngine
        private set
    lateinit var browserData: BrowserData
        private set
    lateinit var notifications: Notifications
        private set

    /** Downloads this app paused because Wi-Fi went away (resumed when it comes back). */
    private val pausedForWifi = mutableSetOf<String>()
    @Volatile var onUnmeteredNetwork: Boolean = true
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsRepository(File(filesDir, "settings.json"))
        engine = DownloadEngine(settings.value.toEngineConfig(), JsonFileStore(File(filesDir, "downloads.json")), CoroutineScope(SupervisorJob() + Dispatchers.IO))
        browserData = BrowserData(File(filesDir, "browser.json"))
        notifications = Notifications(this)
        QueueScheduler(engine).start(scope)

        scope.launch { settings.settings.drop(1).collect { engine.updateConfig(it.toEngineConfig()) } }

        // Keep a foreground service alive while anything downloads, so Android does not kill us.
        scope.launch {
            engine.state.map { s -> s.downloads.any { it.status.isActive || it.status == DownloadStatus.QUEUED } }
                .distinctUntilChanged()
                .collect { busy -> if (busy) startDownloadService() }
        }
        scope.launch {
            engine.events.collect { e ->
                when (e) {
                    is EngineEvent.Completed -> {
                        MediaScannerConnection.scanFile(this@ImadApp, arrayOf(e.item.filePath), null, null)
                        if (settings.value.notifyOnComplete) notifications.completed(e.item)
                        if (settings.value.vibrateOnComplete) vibrate()
                    }
                    is EngineEvent.Failed -> notifications.failed(e.item)
                    else -> Unit
                }
            }
        }
        watchNetwork()
    }

    fun startDownloadService() {
        runCatching { ContextCompat.startForegroundService(this, Intent(this, DownloadService::class.java)) }
    }

    /** Starts a download unless "Wi-Fi only" forbids it right now. Returns false when held back. */
    fun canDownloadNow(): Boolean = !settings.value.wifiOnly || onUnmeteredNetwork

    private fun watchNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                if (unmetered == onUnmeteredNetwork) return
                onUnmeteredNetwork = unmetered
                if (!settings.value.wifiOnly) return
                if (!unmetered) {
                    engine.downloads.filter { it.status.isActive || it.status == DownloadStatus.QUEUED }.forEach {
                        pausedForWifi += it.id
                        engine.pause(it.id)
                    }
                } else {
                    pausedForWifi.toList().forEach(engine::start)
                    pausedForWifi.clear()
                }
            }
        })
    }

    private fun vibrate() {
        val v = getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    companion object {
        lateinit var instance: ImadApp
            private set
    }
}
