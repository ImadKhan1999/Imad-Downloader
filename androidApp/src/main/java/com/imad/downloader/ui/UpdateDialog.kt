package com.imad.downloader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.imad.downloader.AppUpdater
import com.imad.downloader.BuildConfig
import com.imad.downloader.core.Format
import com.imad.downloader.core.UpdateState
import java.io.File

/** Update dialog: what's new, download progress, verify, then Android's installer. */
@Composable
fun UpdateDialog(updater: AppUpdater, onDismiss: () -> Unit) {
    val state by updater.state.collectAsState()
    val context = LocalContext.current
    var pending by remember { mutableStateOf<File?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val f = pending
        if (f != null && updater.canInstall()) { pending = null; context.startActivity(updater.installIntent(f)) }
    }
    LaunchedEffect(pending) { if (pending != null && !updater.canInstall()) permission.launch(updater.unknownSourcesIntent()) }

    fun install(f: File) { if (updater.canInstall()) context.startActivity(updater.installIntent(f)) else pending = f }

    val release = when (val s = state) {
        is UpdateState.Available -> s.release
        is UpdateState.Downloading -> s.release
        is UpdateState.Ready -> s.release
        is UpdateState.Failed -> s.release
        else -> null
    }
    val busy = state is UpdateState.Downloading || state == UpdateState.Verifying || state == UpdateState.Checking
    val mandatory = release?.mandatory == true

    AlertDialog(
        onDismissRequest = { if (!busy && !mandatory) onDismiss() },
        title = { Text(if (mandatory) "Update required" else if (release != null) "Update available" else "Updates") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (val s = state) {
                    UpdateState.Checking -> { Text("Checking for updates..."); Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    UpdateState.UpToDate -> Text("You have the latest version (${BuildConfig.VERSION_NAME}).")
                    else -> {
                        if (release != null) {
                            Text("Version ${release.version}", style = MaterialTheme.typography.titleMedium)
                            Text("You have ${BuildConfig.VERSION_NAME}  •  ${Format.bytes(release.size)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (release.changelog.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(release.changelog) }
                            Spacer(Modifier.height(12.dp))
                        }
                        when (s) {
                            is UpdateState.Downloading -> {
                                LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth().testTag("update-progress"))
                                Spacer(Modifier.height(6.dp))
                                Text("Downloading ${s.percent}%  (${Format.bytes(s.done)} / ${Format.bytes(s.total)})", style = MaterialTheme.typography.bodySmall)
                            }
                            UpdateState.Verifying -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Verifying...", style = MaterialTheme.typography.bodySmall) }
                            is UpdateState.Ready -> Text("Downloaded and verified. Tap Install to finish.", color = MaterialTheme.colorScheme.primary)
                            is UpdateState.Failed -> Text(s.reason, color = MaterialTheme.colorScheme.error)
                            else -> Unit
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (val s = state) {
                is UpdateState.Available -> Button({ updater.download(s.release) }, Modifier.testTag("update-download")) { Text("Download") }
                is UpdateState.Ready -> Button({ install(s.file) }, Modifier.testTag("update-install")) { Text("Install") }
                is UpdateState.Failed -> { val r = s.release; Button({ if (r != null) updater.download(r) else updater.check() }) { Text("Retry") } }
                UpdateState.UpToDate -> Button(onDismiss) { Text("OK") }
                else -> Button({}, enabled = false) { Text(if (state == UpdateState.Checking) "Checking..." else "Downloading...") }
            }
        },
        dismissButton = { if (!busy && !mandatory && state != UpdateState.UpToDate) TextButton(onDismiss) { Text("Later") } },
    )
}
