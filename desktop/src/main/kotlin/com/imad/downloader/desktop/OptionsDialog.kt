package com.imad.downloader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.imad.downloader.core.DownloadQueue
import kotlinx.coroutines.launch
import com.imad.downloader.core.EngineState
import com.imad.downloader.core.Format
import com.imad.downloader.core.ProxyConfig
import com.imad.downloader.core.ProxyType
import com.imad.downloader.core.QueueSchedule

private enum class OptionsPage(val title: String, val icon: ImageVector) {
    GENERAL("General", Icons.Filled.Settings),
    SAVE("Save To", Icons.Filled.Folder),
    CONNECTION("Connection", Icons.Filled.SettingsEthernet),
    PROXY("Proxy / Socks", Icons.Filled.Public),
    BROWSER("Browser Integration", Icons.Filled.Extension),
    VIDEO("Video Sites", Icons.Filled.OndemandVideo),
    NOTIFY("Notifications", Icons.Filled.Notifications),
    LOOK("Appearance", Icons.Filled.Palette),
}

@Composable
fun OptionsDialog(app: AppState) {
    var s by remember { mutableStateOf(app.settings) }
    var page by remember { mutableStateOf(OptionsPage.GENERAL) }
    AppDialog("Imad Downloader Configuration", 820.dp, 600.dp, app.settings.theme, { app.optionsOpen = false }) {
        Row(Modifier.weight(1f)) {
            Column(Modifier.width(190.dp).fillMaxHeight()) {
                OptionsPage.entries.forEach { pg ->
                    val sel = pg == page
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.13f) else Color.Transparent)
                            .clickable { page = pg }.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(pg.icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(pg.title, fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                SectionTitle(page.title)
                when (page) {
                    OptionsPage.GENERAL -> {
                        CheckRow("Launch Imad Downloader when Windows starts", s.launchOnStartup) { s = s.copy(launchOnStartup = it) }
                        CheckRow("Keep running in the system tray when the window is closed", s.minimizeToTray) { s = s.copy(minimizeToTray = it) }
                        CheckRow("Watch the clipboard for download links", s.monitorClipboard) { s = s.copy(monitorClipboard = it) }
                        CheckRow("Show the file info dialog before downloading", s.showStartDialog) { s = s.copy(showStartDialog = it) }
                        CheckRow("Show the progress window when a download starts", s.showProgressWindow) { s = s.copy(showProgressWindow = it) }
                        CheckRow("Ask before deleting downloads", s.confirmDelete) { s = s.copy(confirmDelete = it) }
                        CheckRow("Check for app updates automatically (once a day)", s.autoUpdate) { s = s.copy(autoUpdate = it) }
                        CheckRow("Overwrite files with the same name (otherwise rename)", s.overwriteExisting) { s = s.copy(overwriteExisting = it) }
                        VSpace()
                        Text("File types captured from the clipboard and browser", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Field(s.clipboardExtensions, { s = s.copy(clipboardExtensions = it) }, Modifier.fillMaxWidth(), singleLine = false, minHeight = 90.dp)
                        TextButton({ s = s.copy(clipboardExtensions = DesktopSettings.DEFAULT_TYPES) }) { Text("Restore defaults") }
                    }
                    OptionsPage.SAVE -> {
                        FormRow("Default folder", 130.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Field(s.saveDir, { s = s.copy(saveDir = it) }, Modifier.weight(1f))
                                Spacer(Modifier.width(6.dp))
                                OutlinedButton({ Platform.chooseFolder(s.saveDir)?.let { s = s.copy(saveDir = it) } }, Modifier.height(32.dp)) { Text("Browse") }
                            }
                        }
                        CheckRow("Sort files into category folders (Video, Music, Programs ...)", s.categorySubfolders) { s = s.copy(categorySubfolders = it) }
                        Hint("Free space: ${Format.bytes(Platform.freeSpace(s.saveDir))}")
                    }
                    OptionsPage.CONNECTION -> {
                        FormRow("Connections per download", 220.dp) {
                            Dropdown(s.connections, listOf(1, 2, 4, 8, 12, 16, 24, 32), { "$it" }, Modifier.width(100.dp)) { s = s.copy(connections = it) }
                        }
                        FormRow("Downloads at the same time", 220.dp) {
                            Dropdown(s.maxConcurrent, (1..10).toList(), { "$it" }, Modifier.width(100.dp)) { s = s.copy(maxConcurrent = it) }
                        }
                        FormRow("Retries per connection", 220.dp) {
                            Dropdown(s.maxRetries, listOf(0, 3, 5, 8, 10, 20, 50), { "$it" }, Modifier.width(100.dp)) { s = s.copy(maxRetries = it) }
                        }
                        CheckRow("Global speed limit", s.speedLimitEnabled) { s = s.copy(speedLimitEnabled = it) }
                        FormRow("Maximum speed (KB/s)", 220.dp) {
                            Field(s.speedLimitKBps.toString(), { s = s.copy(speedLimitKBps = it.toLongOrNull() ?: 0) }, Modifier.width(100.dp), numeric = true)
                        }
                        FormRow("User agent", 220.dp) { Field(s.userAgent, { s = s.copy(userAgent = it) }, Modifier.fillMaxWidth()) }
                    }
                    OptionsPage.PROXY -> {
                        val proxy = s.proxy ?: ProxyConfig(host = "", port = 8080)
                        CheckRow("Use a proxy server", s.proxyEnabled) { s = s.copy(proxyEnabled = it, proxy = proxy) }
                        FormRow("Type", 130.dp) { Dropdown(proxy.type, ProxyType.entries, { it.name }, Modifier.width(120.dp)) { s = s.copy(proxy = proxy.copy(type = it)) } }
                        FormRow("Address", 130.dp) { Field(proxy.host, { s = s.copy(proxy = proxy.copy(host = it.trim())) }, Modifier.fillMaxWidth()) }
                        FormRow("Port", 130.dp) { Field(proxy.port.toString(), { s = s.copy(proxy = proxy.copy(port = it.toIntOrNull() ?: 0)) }, Modifier.width(100.dp), numeric = true) }
                        FormRow("Username", 130.dp) { Field(proxy.username.orEmpty(), { s = s.copy(proxy = proxy.copy(username = it)) }, Modifier.fillMaxWidth()) }
                        FormRow("Password", 130.dp) { Field(proxy.password.orEmpty(), { s = s.copy(proxy = proxy.copy(password = it)) }, Modifier.fillMaxWidth(), password = true) }
                    }
                    OptionsPage.BROWSER -> {
                        CheckRow("Take over downloads from Chrome and Edge", s.browserIntegration) { s = s.copy(browserIntegration = it) }
                        VSpace()
                        val port = app.integration.port
                        Text(if (port > 0) "Integration service is running on port $port." else "Integration service is not running.",
                            fontSize = 13.sp, color = if (port > 0) LocalPalette.current.complete else LocalPalette.current.error)
                        VSpace()
                        Text("Install the extension", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Hint("1. Open chrome://extensions (or edge://extensions) and turn on Developer mode.")
                        Hint("2. Click \"Load unpacked\" and pick the folder below.")
                        Hint("3. Downloads, video links and \"Download with Imad Downloader\" in the right-click menu now come here.")
                        val ext = extensionFolder()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Field(ext ?: "Extension folder not found", {}, Modifier.weight(1f), readOnly = true)
                            Spacer(Modifier.width(6.dp))
                            OutlinedButton({ ext?.let(Platform::showInFolder) }, enabled = ext != null) { Text("Open") }
                        }
                        VSpace()
                        Row {
                            OutlinedButton({ Platform.browse("chrome://extensions") }) { Text("Chrome extensions") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton({ Platform.browse("edge://extensions") }) { Text("Edge extensions") }
                        }
                    }
                    OptionsPage.VIDEO -> {
                        Hint("YouTube and over a thousand other sites work through yt-dlp, a free open-source tool the app downloads from github.com/yt-dlp and keeps up to date.")
                        var version by remember { mutableStateOf<String?>(null) }
                        var busy by remember { mutableStateOf(false) }
                        val scope = androidx.compose.runtime.rememberCoroutineScope()
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            version = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { if (app.ytDlp.installed) app.ytDlp.version() else null }
                        }
                        Text(if (version != null) "yt-dlp $version is installed." else "yt-dlp is not installed yet; it is set up the first time you add a video page.", fontSize = 13.sp)
                        VSpace(4.dp)
                        OutlinedButton({
                            busy = true
                            scope.launch {
                                version = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    runCatching { app.ytDlp.ensure(); app.ytDlp.updateIfDue(0); app.ytDlp.version() }.getOrNull()
                                }
                                busy = false
                            }
                        }, enabled = !busy) { Text(if (busy) "Updating..." else if (version == null) "Install now" else "Update now") }
                        VSpace()
                        Text("Sign-in for video sites", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Hint("Some videos need a signed-in browser (YouTube may ask \"confirm you're not a bot\"). The extension's \"Download this video\" button sends your sign-in automatically. For links pasted here, yt-dlp can read cookies from a browser:")
                        val browsers = listOf("", "firefox", "edge", "chrome", "brave")
                        FormRow("Use cookies from", 130.dp) {
                            Dropdown(s.videoCookiesBrowser, browsers, { if (it.isEmpty()) "No browser" else it.replaceFirstChar(Char::uppercase) }, Modifier.width(180.dp)) {
                                s = s.copy(videoCookiesBrowser = it)
                            }
                        }
                        Hint("Firefox works best; Chrome and Edge lock their cookies while they are open.")
                    }                    OptionsPage.NOTIFY -> {
                        CheckRow("Show a notification when a download finishes", s.notifications) { s = s.copy(notifications = it) }
                        CheckRow("Show the download complete dialog", s.showCompleteDialog) { s = s.copy(showCompleteDialog = it) }
                        CheckRow("Play a sound when a download finishes", s.soundOnComplete) { s = s.copy(soundOnComplete = it) }
                    }
                    OptionsPage.LOOK -> {
                        FormRow("Theme", 130.dp) {
                            Dropdown(s.theme, ThemeMode.entries, { it.name.lowercase().replaceFirstChar(Char::uppercase) }, Modifier.width(160.dp)) { s = s.copy(theme = it) }
                        }
                    }
                }
            }
        }
        ButtonRow {
            TextButton({ app.optionsOpen = false }) { Text("Cancel") }
            Button({ app.updateSettings(s); app.optionsOpen = false }) { Text("OK") }
        }
    }
}

/** The unpacked extension ships next to the app (installed) or in the repo (development). */
fun extensionFolder(): String? {
    val candidates = buildList {
        System.getProperty("compose.application.resources.dir")?.let { add(java.io.File(it, "extension")) }
        System.getProperty("jpackage.app-path")?.let { add(java.io.File(it).parentFile.resolve("app/resources/extension")) }
        add(java.io.File("extension"))
        add(java.io.File("../extension"))
    }
    return candidates.firstOrNull { java.io.File(it, "manifest.json").exists() }?.canonicalPath
}

// ------------------------------------------------------------------ Scheduler

@Composable
fun SchedulerDialog(app: AppState) {
    val state by app.engine.state.collectAsState()
    var selectedId by remember { mutableStateOf(state.queues.first().id) }
    val queue = state.queues.firstOrNull { it.id == selectedId } ?: state.queues.first()
    var draft by remember(queue.id) { mutableStateOf(queue) }
    var startText by remember(queue.id) { mutableStateOf(minutesText(queue.schedule?.startMinute ?: 23 * 60)) }
    var stopText by remember(queue.id) { mutableStateOf(queue.schedule?.stopMinute?.let(::minutesText) ?: "07:00") }
    var useStop by remember(queue.id) { mutableStateOf(queue.schedule?.stopMinute != null) }
    var newName by remember { mutableStateOf("") }

    AppDialog("Scheduler", 860.dp, 600.dp, app.settings.theme, { app.schedulerOpen = false }) {
        Row(Modifier.weight(1f)) {
            Column(Modifier.width(220.dp).fillMaxHeight()) {
                Text("Queues", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                VSpace(4.dp)
                Column(Modifier.weight(1f).fillMaxWidth().border(1.dp, LocalPalette.current.gridLine, RoundedCornerShape(5.dp)).padding(4.dp)) {
                    state.queues.forEach { q ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                                .background(if (q.id == queue.id) MaterialTheme.colorScheme.primary.copy(alpha = 0.13f) else Color.Transparent)
                                .clickable { selectedId = q.id }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(q.name, Modifier.weight(1f), fontSize = 13.sp)
                            if (q.running) Text("running", fontSize = 11.sp, color = LocalPalette.current.complete)
                        }
                    }
                }
                VSpace(6.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(newName, { newName = it }, Modifier.weight(1f), placeholder = "New queue name")
                    IconButton({ if (newName.isNotBlank()) { selectedId = app.engine.addQueue(newName).id; newName = "" } }) { Icon(Icons.Filled.Add, "Add queue") }
                }
                TextButton({ app.engine.removeQueue(queue.id); selectedId = EngineState.MAIN_QUEUE_ID }, enabled = queue.id != EngineState.MAIN_QUEUE_ID) {
                    Icon(Icons.Filled.Delete, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Delete queue")
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                FormRow("Queue name", 170.dp) { Field(draft.name, { draft = draft.copy(name = it) }, Modifier.fillMaxWidth()) }
                FormRow("Files at the same time", 170.dp) {
                    Dropdown(draft.maxConcurrent, (1..8).toList(), { "$it" }, Modifier.width(90.dp)) { draft = draft.copy(maxConcurrent = it) }
                }
                VSpace()
                CheckRow("Start this queue on a schedule", draft.schedule?.enabled == true) { on ->
                    draft = draft.copy(schedule = (draft.schedule ?: QueueSchedule(parseMinutes(startText) ?: 0)).copy(enabled = on))
                }
                FormRow("Start at (HH:mm)", 170.dp) { Field(startText, { startText = it.take(5) }, Modifier.width(90.dp)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CheckRow("Stop at (HH:mm)", useStop) { useStop = it }
                    Spacer(Modifier.width(26.dp))
                    Field(stopText, { stopText = it.take(5) }, Modifier.width(90.dp))
                }
                VSpace(4.dp)
                Text("Days", fontSize = 13.sp)
                val days = draft.schedule?.days ?: emptySet()
                Row {
                    listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEachIndexed { i, d ->
                        val n = i + 1
                        CheckRow(d, days.isEmpty() || n in days) { on ->
                            val all = (1..7).toSet()
                            val cur = if (days.isEmpty()) all else days
                            val next = if (on) cur + n else cur - n
                            draft = draft.copy(schedule = (draft.schedule ?: QueueSchedule(parseMinutes(startText) ?: 0)).copy(days = if (next == all) emptySet() else next))
                        }
                    }
                }
                VSpace()
                FormRow("When a queue finishes", 170.dp) {
                    Dropdown(app.settings.queueCompletionAction, CompletionAction.entries, { it.label }, Modifier.fillMaxWidth()) {
                        app.updateSettings(app.settings.copy(queueCompletionAction = it))
                    }
                }
                VSpace()
                val inQueue = state.downloads.filter { it.queueId == queue.id }
                Text("Files in this queue (${inQueue.size})", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Box(Modifier.fillMaxWidth().height(130.dp).border(1.dp, LocalPalette.current.gridLine, RoundedCornerShape(5.dp))) {
                    LazyColumn(Modifier.padding(6.dp)) {
                        items(inQueue, key = { it.id }) {
                            Row {
                                Text(it.fileName, Modifier.weight(1f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(statusText(it), fontSize = 12.sp, color = LocalPalette.current.muted)
                            }
                        }
                    }
                }
            }
        }
        ButtonRow {
            OutlinedButton({ app.engine.startQueue(queue.id) }, enabled = !queue.running) { Text("Start now") }
            OutlinedButton({ app.engine.stopQueue(queue.id) }, enabled = queue.running) { Text("Stop") }
            Spacer(Modifier.weight(1f))
            TextButton({ app.schedulerOpen = false }) { Text("Close") }
            Button({
                val start = parseMinutes(startText)
                val stop = if (useStop) parseMinutes(stopText) else null
                if (start == null || (useStop && stop == null)) {
                    app.message = "Times must look like 23:30."
                } else {
                    val sched = draft.schedule?.copy(startMinute = start, stopMinute = stop)
                    app.engine.updateQueue(draft.copy(schedule = sched, running = queue.running))
                }
            }) { Text("Apply") }
        }
    }
}

fun minutesText(m: Int) = "%02d:%02d".format(m / 60, m % 60)

fun parseMinutes(text: String): Int? {
    val parts = text.trim().split(':')
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

@Suppress("unused")
private fun keep(q: DownloadQueue) = q
