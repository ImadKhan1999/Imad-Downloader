package com.imad.downloader.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.imad.downloader.FileActions
import com.imad.downloader.ImadApp
import com.imad.downloader.Incoming
import com.imad.downloader.MainActivity
import com.imad.downloader.browser.BrowserController
import com.imad.downloader.core.BatchUrls
import com.imad.downloader.core.DownloadRequest
import com.imad.downloader.core.DownloadStatus
import com.imad.downloader.core.FileNames

enum class Screen(val label: String) { DOWNLOADS("Downloads"), BROWSER("Browser"), SETTINGS("Settings") }

@Composable
fun AppRoot(app: ImadApp, browser: BrowserController, activity: MainActivity) {
    val settings by app.settings.settings.collectAsState()
    val engineState by app.engine.state.collectAsState()
    var screen by rememberSaveable { mutableStateOf(Screen.DOWNLOADS) }
    var addRequest by remember { mutableStateOf<DownloadRequest?>(null) }
    var details by remember { mutableStateOf<String?>(null) }
    var batch by remember { mutableStateOf(false) }
    var queues by rememberSaveable { mutableStateOf(false) }
    var showUpdate by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val s = app.settings.value
        if (s.autoUpdate && System.currentTimeMillis() - s.lastUpdateCheck > 24 * 3_600_000L) {
            app.settings.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
            app.updater.check { showUpdate = true }
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var lastClip by rememberSaveable { mutableStateOf<String?>(null) }
    val canStart: () -> Boolean = {
        app.canDownloadNow().also { ok -> if (!ok) Toast.makeText(context, "Waiting for Wi-Fi (Wi-Fi only is on)", Toast.LENGTH_SHORT).show() }
    }

    fun requestDownload(req: DownloadRequest, fromBrowser: Boolean) {
        if (fromBrowser && !app.settings.value.askBeforeDownload) {
            runCatching { app.engine.add(req, start = app.canDownloadNow()) }
            Toast.makeText(context, "Downloading ${req.fileName ?: FileNames.fromUrl(req.url) ?: "file"}", Toast.LENGTH_SHORT).show()
        } else addRequest = req
    }

    // Links shared to us or opened from other apps.
    LaunchedEffect(activity.incoming) {
        when (val inc = activity.incoming) {
            is Incoming.Download -> { screen = Screen.DOWNLOADS; addRequest = inc.request }
            is Incoming.Browse -> { screen = Screen.BROWSER; browser.newTab(inc.url) }
            null -> Unit
        }
        activity.incoming = null
    }

    // 1DM-style: offer a freshly copied download link when the app comes to the front.
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            kotlinx.coroutines.delay(600)
            if (!app.settings.value.clipboardMonitor) return@repeatOnLifecycle
            val clip = FileActions.clipboardText(context) ?: return@repeatOnLifecycle
            if (clip == lastClip) return@repeatOnLifecycle
            lastClip = clip
            val link = BatchUrls.extractLinks(clip).firstOrNull { l ->
                val ext = FileNames.fromUrl(l)?.substringAfterLast('.', "")?.lowercase().orEmpty()
                ext.length in 2..5 && com.imad.downloader.core.Category.detect("x.$ext") != com.imad.downloader.core.Category.OTHER
            } ?: return@repeatOnLifecycle
            if (app.engine.downloads.any { it.url == link }) return@repeatOnLifecycle
            val r = snackbar.showSnackbar("Download copied link?", actionLabel = "Download", duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) addRequest = DownloadRequest(link)
        }
    }

    ImadTheme(settings.theme) {
        Scaffold(
            // Each screen handles the status bar itself; the navigation bar handles the bottom inset.
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (browser.fullscreenView == null) {
                    NavigationBar {
                        Screen.entries.forEach { s ->
                            NavigationBarItem(
                                selected = screen == s,
                                onClick = { screen = s },
                                icon = {
                                    when (s) {
                                        Screen.DOWNLOADS -> {
                                            val active = engineState.downloads.count { it.status.isActive || it.status == DownloadStatus.QUEUED }
                                            BadgedBox(badge = { if (active > 0) Badge { Text(active.toString()) } }) { Icon(Icons.Filled.Download, null) }
                                        }
                                        Screen.BROWSER -> Icon(Icons.Filled.Public, null)
                                        Screen.SETTINGS -> Icon(Icons.Filled.Settings, null)
                                    }
                                },
                                label = { Text(s.label) },
                                modifier = Modifier.testTag("nav-${s.name.lowercase()}"),
                            )
                        }
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                // Tabs keep their WebViews in the controller, so pages survive switching screens.
                when (screen) {
                    Screen.BROWSER -> BrowserScreen(browser, app.browserData, onDownload = { requestDownload(it, fromBrowser = true) }, active = true)
                    Screen.DOWNLOADS -> DownloadsScreen(
                        app.engine,
                        onAdd = { clip -> addRequest = DownloadRequest(BatchUrls.extractLinks(clip.orEmpty()).firstOrNull().orEmpty()) },
                        onOpenBrowser = { screen = Screen.BROWSER },
                        onDetails = { details = it },
                        onBatch = { batch = true },
                        canStart = canStart,
                        onQueues = { queues = true },
                    )
                    Screen.SETTINGS -> SettingsScreen(app.settings, app.browserData, onCheckUpdates = { showUpdate = true; app.updater.check() })
                }
            }
        }

        addRequest?.let { req ->
            AddDownloadSheet(
                app.engine, req, settings.connections,
                onDismiss = { addRequest = null },
                onAdded = { started ->
                    addRequest = null
                    Toast.makeText(context, if (started) "Download started" else "Added to the list", Toast.LENGTH_SHORT).show()
                },
                canStart = { app.canDownloadNow() },
            )
        }
        details?.let { id -> DetailsSheet(app.engine, id, onDismiss = { details = null }, canStart = canStart) }
        if (batch) BatchSheet(app.engine, onDismiss = { batch = false }, canStart = canStart)
        if (showUpdate) UpdateDialog(app.updater) { showUpdate = false; app.updater.reset() }
        if (queues) androidx.compose.material3.Surface(Modifier.fillMaxSize()) { QueuesScreen(app.engine, onBack = { queues = false }, canStart = canStart) }
    }
}

