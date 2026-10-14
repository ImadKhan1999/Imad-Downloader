package com.imad.downloader.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.imad.downloader.core.Format
import com.imad.downloader.core.ReleaseInfo
import com.imad.downloader.core.UpdateClient
import com.imad.downloader.core.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Windows in-app updates: reads update/version.json, downloads the new MSI with progress,
 * checks its SHA-256, then closes the app, upgrades it in place with msiexec and starts it again.
 */
class DesktopUpdater(
    private val scope: CoroutineScope,
    private val client: UpdateClient = UpdateClient(),
    private val currentVersion: String = APP_VERSION,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null

    /** The installed launcher; null when running from Gradle (updates only work when installed). */
    val installedExe: String? get() = System.getProperty("jpackage.app-path")

    fun reset() { _state.value = UpdateState.Idle }

    fun check(onFound: (ReleaseInfo) -> Unit = {}) {
        if (job?.isActive == true) return
        _state.value = UpdateState.Checking
        job = scope.launch {
            _state.value = runCatching { client.manifest().windows }.fold(
                onSuccess = { r ->
                    if (r != null && UpdateClient.isNewer(r.version, currentVersion)) UpdateState.Available(r).also { onFound(r) }
                    else UpdateState.UpToDate
                },
                onFailure = { UpdateState.Failed(it.message ?: "Could not check for updates") },
            )
        }
    }

    fun download(release: ReleaseInfo) {
        // A finished check may still be wrapping up; only a running download blocks a new one.
        if (_state.value is UpdateState.Downloading || _state.value == UpdateState.Verifying) return
        job?.cancel()
        _state.value = UpdateState.Downloading(release, 0, release.size)
        job = scope.launch {
            _state.value = runCatching {
                val dir = File(System.getProperty("java.io.tmpdir"), "ImadDownloaderUpdate")
                client.download(release, dir) { done, total -> _state.value = UpdateState.Downloading(release, done, total) }
            }.fold({ UpdateState.Ready(release, it) }, { UpdateState.Failed(it.message ?: "Download failed", release) })
        }
    }

    /** Starts the upgrade and returns true; the caller then exits so the installer can replace files. */
    fun install(file: File): Boolean {
        val exe = installedExe ?: run {
            _state.value = UpdateState.Failed("Updates install only into the installed app. Run the downloaded installer: ${file.path}")
            return false
        }
        val script = File(file.parentFile, "imad-update.cmd")
        script.writeText(
            """
            @echo off
            rem Imad Downloader update: wait for the app to close, upgrade, start it again.
            timeout /t 2 /nobreak >nul
            msiexec /i "${file.path}" /passive /norestart
            start "" "$exe"
            """.trimIndent().replace("\n", "\r\n"),
        )
        ProcessBuilder("cmd", "/c", "start", "ImadUpdate", "/min", script.path).start()
        return true
    }
}

@Composable
fun UpdateDialog(app: AppState) {
    val state by app.updater.state.collectAsState()
    if (state == UpdateState.Idle || state == UpdateState.Checking && !app.updateDialogOpen) return
    if (!app.updateDialogOpen) return
    fun close() { app.updateDialogOpen = false; if (state !is UpdateState.Downloading) app.updater.reset() }
    AppDialog("Imad Downloader update", 480.dp, 330.dp, app.settings.theme, ::close) {
        val release = when (val s = state) {
            is UpdateState.Available -> s.release
            is UpdateState.Downloading -> s.release
            is UpdateState.Ready -> s.release
            is UpdateState.Failed -> s.release
            else -> null
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (val s = state) {
                UpdateState.Checking -> { Text("Checking for updates...", fontSize = 14.sp); Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                UpdateState.UpToDate -> Text("You have the latest version ($APP_VERSION).", fontSize = 14.sp)
                else -> {
                    if (release != null) {
                        Text("Version ${release.version} is available", style = MaterialTheme.typography.titleMedium)
                        Text("You have $APP_VERSION.  Size: ${Format.bytes(release.size)}", fontSize = 12.sp, color = LocalPalette.current.muted)
                        if (release.changelog.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(release.changelog, fontSize = 13.sp) }
                        Spacer(Modifier.height(12.dp))
                    }
                    when (s) {
                        is UpdateState.Downloading -> {
                            LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(6.dp))
                            Text("Downloading ${s.percent}%  (${Format.bytes(s.done)} of ${Format.bytes(s.total)})", fontSize = 12.sp)
                        }
                        UpdateState.Verifying -> Text("Verifying...", fontSize = 12.sp)
                        is UpdateState.Ready -> Text("Downloaded and verified. Imad Downloader will close, update and open again.", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = LocalPalette.current.complete)
                        is UpdateState.Failed -> Text(s.reason, fontSize = 13.sp, color = LocalPalette.current.error)
                        else -> Unit
                    }
                }
            }
        }
        ButtonRow {
            val s = state
            if (s !is UpdateState.Downloading && !(release?.mandatory == true && s !is UpdateState.Failed)) TextButton(::close) { Text(if (release == null) "Close" else "Later") }
            when (s) {
                is UpdateState.Available -> Button({ app.updater.download(s.release) }) { Text("Download") }
                is UpdateState.Ready -> Button({ if (app.updater.install(s.file)) app.exitApp() }) { Text("Install and restart") }
                is UpdateState.Failed -> { val r = s.release; if (r != null) Button({ app.updater.download(r) }) { Text("Retry") } else Button({ app.updater.check() }) { Text("Retry") } }
                is UpdateState.Downloading -> Button({}, enabled = false) { Text("Downloading...") }
                else -> Unit
            }
        }
    }
}
