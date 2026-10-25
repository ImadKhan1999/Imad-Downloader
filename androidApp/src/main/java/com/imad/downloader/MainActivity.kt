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
        val splash = installSplashScreen()
        // Startup animation: let the icon animation (Android 12+) play once, then zoom and fade out.
        val shownAt = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= 31 && savedInstanceState == null) {
            splash.setKeepOnScreenCondition { System.currentTimeMillis() - shownAt < 1_100 }
        }
        splash.setOnExitAnimationListener { provider ->
            val icon = provider.iconView
            icon.animate().scaleX(1.35f).scaleY(1.35f).alpha(0f).setDuration(260).start()
            provider.view.animate().alpha(0f).setStartDelay(80).setDuration(260).withEndAction { provider.remove() }.start()
        }
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
                Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                    // Apps share "Look at this! https://..." or just the link (sometimes as a subject or clip).
                    val text = listOfNotNull(
                        intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
                        intent.getStringArrayListExtra(Intent.EXTRA_TEXT)?.joinToString(" "),
                        intent.getStringExtra(Intent.EXTRA_SUBJECT),
                        intent.clipData?.let { c -> (0 until c.itemCount).joinToString(" ") { c.getItemAt(it).text?.toString().orEmpty() } },
                    ).joinToString(" ")
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
