package com.imad.downloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.imad.downloader.browser.BrowserController
import com.imad.downloader.core.BatchUrls
import com.imad.downloader.core.DownloadRequest
import com.imad.downloader.ui.AppRoot

/** What an incoming intent asked for. */
sealed interface Incoming {
    data class Download(val request: DownloadRequest) : Incoming
    data class Browse(val url: String) : Incoming
}

class MainActivity : ComponentActivity() {
    private lateinit var browser: BrowserController
    var incoming by mutableStateOf<Incoming?>(null)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as ImadApp
        browser = BrowserController(this, app.settings, app.browserData)
        askPermissions()
        handle(intent)
        setContent { AppRoot(app, browser, this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onPause() { super.onPause(); browser.onPause() }
    override fun onResume() { super.onResume(); browser.onResume() }

    override fun onDestroy() {
        if (isFinishing) browser.destroy()
        super.onDestroy()
    }

    private fun askPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }

    private fun handle(intent: Intent?) {
        incoming = parseIntent(intent)
    }

    companion object {
        /** Shared links become downloads; plain web pages open in the browser. */
        fun parseIntent(intent: Intent?): Incoming? {
            intent ?: return null
            return when (intent.action) {
                Intent.ACTION_SEND -> {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                    BatchUrls.extractLinks(text).firstOrNull()?.let { Incoming.Download(DownloadRequest(it)) }
                }
                Intent.ACTION_VIEW -> {
                    val url = intent.dataString ?: return null
                    if (!url.startsWith("http")) return null
                    val type = intent.type
                    if (type != null && !type.startsWith("text/html")) Incoming.Download(DownloadRequest(url)) else Incoming.Browse(url)
                }
                else -> null
            }
        }
    }
}
