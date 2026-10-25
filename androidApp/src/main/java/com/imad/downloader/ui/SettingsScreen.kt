@file:OptIn(ExperimentalMaterial3Api::class)

package com.imad.downloader.ui

import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.imad.downloader.AppSettings
import com.imad.downloader.BuildConfig
import com.imad.downloader.R
import com.imad.downloader.SearchEngine
import com.imad.downloader.SettingsRepository
import com.imad.downloader.ThemeMode
import com.imad.downloader.browser.BrowserData
import com.imad.downloader.core.Format

@Composable
fun SettingsScreen(repo: SettingsRepository, browserData: BrowserData) {
    val s by repo.settings.collectAsState()
    val context = LocalContext.current
    var folderDialog by remember { mutableStateOf(false) }
    var choice by remember { mutableStateOf<Choice<*>?>(null) }
    fun set(f: (AppSettings) -> AppSettings) = repo.update(f)

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,topBar = {
        TopAppBar(title = { Text("Settings", fontWeight = FontWeight.SemiBold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface))
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Header("Downloads")
            Item("Download folder", s.saveDir) { folderDialog = true }
            Toggle("Sort into category folders", "Video, Music, Documents, Programs ...", s.categoryFolders) { v -> set { it.copy(categoryFolders = v) } }
            Toggle("Ask before downloading", "Show the download sheet for links from the browser", s.askBeforeDownload) { v -> set { it.copy(askBeforeDownload = v) } }
            SliderItem("Downloads at the same time", s.maxConcurrent, 1..10) { v -> set { it.copy(maxConcurrent = v) } }
            SliderItem("Connections per download", s.connections, 1..32) { v -> set { it.copy(connections = v) } }
            SliderItem("Retries per connection", s.maxRetries, 0..30) { v -> set { it.copy(maxRetries = v) } }
            Toggle("Download only on Wi-Fi", "Pauses on mobile data and resumes on Wi-Fi", s.wifiOnly) { v -> set { it.copy(wifiOnly = v) } }
            Toggle("Speed limit", if (s.speedLimitEnabled) Format.speed(s.speedLimitKBps * 1024) else "Off", s.speedLimitEnabled) { v -> set { it.copy(speedLimitEnabled = v) } }
            if (s.speedLimitEnabled) {
                val steps = listOf(64L, 128L, 256L, 512L, 1024L, 2048L, 5120L, 10240L)
                SliderItem("Maximum speed", steps.indexOf(steps.minBy { kotlin.math.abs(it - s.speedLimitKBps) }), steps.indices, label = { Format.speed(steps[it] * 1024) }) { i ->
                    set { it.copy(speedLimitKBps = steps[i]) }
                }
            }
            Toggle("Overwrite files with the same name", "Otherwise a number is added", s.overwrite) { v -> set { it.copy(overwrite = v) } }
            Toggle("Detect copied links", "Offers to download a link you copy", s.clipboardMonitor) { v -> set { it.copy(clipboardMonitor = v) } }

            Header("Notifications")
            Toggle("Notify when a download finishes", null, s.notifyOnComplete) { v -> set { it.copy(notifyOnComplete = v) } }
            Toggle("Vibrate when a download finishes", null, s.vibrateOnComplete) { v -> set { it.copy(vibrateOnComplete = v) } }

            Header("Browser")
            Item("Search engine", s.searchEngine.label) { choice = Choice("Search engine", SearchEngine.entries, s.searchEngine, { it.label }) { v -> set { it.copy(searchEngine = v) } } }
            Toggle("Block ads", "Blocks common ad and tracker networks", s.adBlock) { v -> set { it.copy(adBlock = v) } }
            Toggle("Block pop-ups", null, s.blockPopups) { v -> set { it.copy(blockPopups = v) } }
            Toggle("Desktop site by default", null, s.desktopModeDefault) { v -> set { it.copy(desktopModeDefault = v) } }
            Toggle("JavaScript", "Needed by most video sites", s.javascript) { v -> set { it.copy(javascript = v) } }
            Toggle("Save history", null, s.saveHistory) { v -> set { it.copy(saveHistory = v) } }
            Item("Clear history", null) { browserData.clearHistory(); Toast.makeText(context, "History cleared", Toast.LENGTH_SHORT).show() }
            Item("Clear cookies and site data", null) {
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                Toast.makeText(context, "Cookies and site data cleared", Toast.LENGTH_SHORT).show()
            }

            Header("Appearance")
            Item("Theme", s.theme.name.lowercase().replaceFirstChar(Char::uppercase)) {
                choice = Choice("Theme", ThemeMode.entries, s.theme, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { v -> set { it.copy(theme = v) } }
            }

            Header("About")
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.logo), null, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("iDownloader ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.SemiBold)
                    Text("by ImadLabs", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Item("ImadLabs on Telegram", TELEGRAM_URL.removePrefix("https://")) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_URL)))
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (folderDialog) {
        var path by remember { mutableStateOf(s.saveDir) }
        AlertDialog(
            onDismissRequest = { folderDialog = false },
            title = { Text("Download folder") },
            text = {
                Column {
                    OutlinedTextField(path, { path = it }, singleLine = false)
                    TextButton({ path = AppSettings.defaultSaveDir() }) { Text("Use Download/iDownloader") }
                    TextButton({ path = context.getExternalFilesDir(null)?.path ?: path }) { Text("Use app storage (hidden from gallery)") }
                }
            },
            confirmButton = { TextButton({ set { it.copy(saveDir = path.trim()) }; folderDialog = false }, enabled = path.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton({ folderDialog = false }) { Text("Cancel") } },
        )
    }
    choice?.let { c -> ChoiceDialog(c) { choice = null } }
}

private class Choice<T>(val title: String, val options: List<T>, val selected: T, val label: (T) -> String, val onPick: (T) -> Unit)

@Composable
private fun <T> ChoiceDialog(c: Choice<T>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(c.title) },
        text = {
            Column {
                c.options.forEach { o ->
                    Row(Modifier.fillMaxWidth().clickable { c.onPick(o); onDismiss() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(o == c.selected, { c.onPick(o); onDismiss() })
                        Text(c.label(o))
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
    )
}

@Composable
private fun Header(text: String) {
    Text(text, Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
}

@Composable
private fun Item(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(title, fontSize = 16.sp)
        subtitle?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            subtitle?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun SliderItem(title: String, value: Int, range: IntRange, label: (Int) -> String = { it.toString() }, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row {
            Text(title, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(label(value), fontSize = 15.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value.toFloat(), { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}
