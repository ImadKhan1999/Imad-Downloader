package com.imad.downloader.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.imad.downloader.core.BatchUrls
import com.imad.downloader.core.Category
import com.imad.downloader.core.DownloadRequest
import com.imad.downloader.core.DownloadStatus
import com.imad.downloader.core.FileNames
import com.imad.downloader.core.Format
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date

@Composable
fun AllDialogs(app: AppState) {
    val theme = app.settings.theme
    app.addUrlOpen?.let { initial -> AddUrlDialog(app, initial, theme) }
    app.fileInfo?.let { FileInfoDialog(app, it, theme) }
    app.progressWindows.toList().forEach { id -> androidx.compose.runtime.key(id) { ProgressWindow(app, id) } }
    app.completeDialogs.toList().forEach { id -> androidx.compose.runtime.key(id) { CompleteDialog(app, id, theme) } }
    if (app.optionsOpen) OptionsDialog(app)
    if (app.schedulerOpen) SchedulerDialog(app)
    if (app.batchOpen) BatchDialog(app, theme)
    if (app.aboutOpen) AboutDialog(app, theme)
    app.propertiesFor?.let { PropertiesDialog(app, it, theme) }
    app.linksDialog?.let { LinksDialog(app, it, theme) }
    app.confirmDelete?.let { ConfirmDeleteDialog(app, it, theme) }
    app.refreshAddressFor?.let { RefreshAddressDialog(app, it, theme) }
    app.message?.let { msg ->
        AppDialog("Imad Downloader", 420.dp, 180.dp, theme, { app.message = null }) {
            Text(msg, fontSize = 14.sp, modifier = Modifier.weight(1f))
            ButtonRow { Button({ app.message = null }) { Text("OK") } }
        }
    }
}

// ------------------------------------------------------------------ Add URL

@Composable
private fun AddUrlDialog(app: AppState, initial: String, theme: ThemeMode) {
    val start = remember { BatchUrls.extractLinks(initial).firstOrNull().orEmpty() }
    var url by remember { mutableStateOf(start) }
    var auth by remember { mutableStateOf(false) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val valid = url.trim().let { it.startsWith("http://", true) || it.startsWith("https://", true) }
    fun ok() {
        if (!valid) return
        val headers = if (auth && user.isNotBlank()) {
            mapOf("Authorization" to "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray()))
        } else emptyMap()
        app.addUrlOpen = null
        app.showFileInfo(DownloadRequest(url.trim(), headers = headers))
    }
    AppDialog("Enter new address to download", 560.dp, if (auth) 300.dp else 210.dp, theme, { app.addUrlOpen = null }) {
        Text("Address", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        VSpace(4.dp)
        Field(url, { url = it }, Modifier.fillMaxWidth(), placeholder = "https://example.com/file.zip")
        VSpace(6.dp)
        CheckRow("Use authorization", auth) { auth = it }
        if (auth) {
            FormRow("Login", 80.dp) { Field(user, { user = it }, Modifier.fillMaxWidth()) }
            FormRow("Password", 80.dp) { Field(pass, { pass = it }, Modifier.fillMaxWidth(), password = true) }
        }
        Spacer(Modifier.weight(1f))
        ButtonRow {
            TextButton({ app.addUrlOpen = null }) { Text("Cancel") }
            Button(::ok, enabled = valid) { Text("OK") }
        }
    }
}

// ------------------------------------------------------------------ Download File Info

@Composable
private fun FileInfoDialog(app: AppState, s: FileInfoState, theme: ThemeMode) {
    val queues = app.engine.queues
    var queue by remember { mutableStateOf(queues.first()) }
    fun update(f: (FileInfoState) -> FileInfoState) { app.fileInfo = app.fileInfo?.let(f) }
    AppDialog("Download File Info", 620.dp, 380.dp, theme, { app.fileInfo = null }) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(s.category.tint().copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { Icon(s.category.icon(), null, Modifier.size(38.dp), tint = s.category.tint()) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                FormRow("URL", 90.dp) { Field(s.request.url, {}, Modifier.fillMaxWidth(), readOnly = true) }
                FormRow("Category", 90.dp) {
                    Dropdown(s.category, Category.entries, { it.label }, Modifier.fillMaxWidth()) { c ->
                        update { it.copy(category = c, saveDir = app.engine.defaultDirFor(c)) }
                    }
                }
                FormRow("Save As", 90.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Field(File(s.saveDir, s.fileName).path, { v ->
                            val f = File(v)
                            update { it.copy(saveDir = f.parent ?: it.saveDir, fileName = f.name) }
                        }, Modifier.weight(1f))
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton({
                            Platform.chooseSaveFile(s.saveDir, s.fileName)?.let { f ->
                                update { it.copy(saveDir = f.parent, fileName = f.name) }
                            }
                        }, Modifier.height(32.dp)) { Text("...") }
                    }
                }
                CheckRow("Remember this path as the default", s.rememberPath) { v -> update { it.copy(rememberPath = v) } }
                FormRow("Description", 90.dp) { Field(s.description, { v -> update { it.copy(description = v) } }, Modifier.fillMaxWidth()) }
            }
        }
        VSpace(6.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                s.probing -> {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Connecting to the server...", fontSize = 13.sp)
                }
                s.error != null -> Text("Could not get file info: ${s.error}. You can still try to download.", fontSize = 13.sp, color = LocalPalette.current.error)
                else -> {
                    val pr = s.probe!!
                    Text("Size: ", fontSize = 13.sp)
                    Text(if (pr.totalBytes >= 0) Format.bytes(pr.totalBytes) else "Unknown", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(18.dp))
                    Text("Resume: ", fontSize = 13.sp)
                    Text(if (pr.resumable) "Yes" else "No", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = if (pr.resumable) LocalPalette.current.complete else LocalPalette.current.paused)
                    Spacer(Modifier.width(18.dp))
                    val free = Platform.freeSpace(s.saveDir)
                    if (free >= 0) Text("Free disk space: ${Format.bytes(free)}", fontSize = 12.sp, color = LocalPalette.current.muted)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        ButtonRow {
            Dropdown(queue, queues, { it.name }, Modifier.width(150.dp)) { queue = it }
            OutlinedButton({ app.confirmFileInfo(s, start = false, queueId = queue.id) }) { Text("Download Later") }
            Spacer(Modifier.weight(1f))
            TextButton({ app.fileInfo = null }) { Text("Cancel") }
            Button({ app.confirmFileInfo(s, start = true) }, enabled = s.fileName.isNotBlank()) { Text("Start Download") }
        }
    }
}

// ------------------------------------------------------------------ Download complete

@Composable
private fun CompleteDialog(app: AppState, id: String, theme: ThemeMode) {
    val state by app.engine.state.collectAsState()
    val item = state.downloads.firstOrNull { it.id == id }
    fun close() { app.completeDialogs.remove(id) }
    if (item == null) { close(); return }
    AppDialog("Download complete", 520.dp, 290.dp, theme, ::close) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, null, Modifier.size(40.dp), tint = LocalPalette.current.complete)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Download complete", style = MaterialTheme.typography.titleMedium)
                Text(item.fileName, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        VSpace(10.dp)
        FormRow("Downloaded", 100.dp) { Text("${Format.bytes(item.totalBytes)}", fontSize = 13.sp) }
        FormRow("Address", 100.dp) { Text(item.url, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        FormRow("Saved to", 100.dp) { Text(item.filePath, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CheckRow("Don't show this dialog again", !app.settings.showCompleteDialog) { app.updateSettings(app.settings.copy(showCompleteDialog = !it)) }
            Spacer(Modifier.weight(1f))
            OutlinedButton({ Platform.showInFolder(item.filePath); close() }) { Icon(Icons.Filled.FolderOpen, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Open folder") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton({ Platform.openWith(item.filePath); close() }) { Text("Open with...") }
            Spacer(Modifier.width(6.dp))
            Button({ Platform.openFile(item.filePath); close() }) { Text("Open") }
        }
    }
}

// ------------------------------------------------------------------ Delete confirmation

@Composable
private fun ConfirmDeleteDialog(app: AppState, ids: List<String>, theme: ThemeMode) {
    var alsoFiles by remember { mutableStateOf(false) }
    AppDialog("Confirm deletion", 440.dp, 200.dp, theme, { app.confirmDelete = null }) {
        Text(if (ids.size == 1) "Remove \"${app.engine.get(ids[0])?.fileName}\" from the list?" else "Remove ${ids.size} downloads from the list?", fontSize = 14.sp)
        VSpace()
        CheckRow("Also delete the files from disk", alsoFiles) { alsoFiles = it }
        Spacer(Modifier.weight(1f))
        ButtonRow {
            TextButton({ app.confirmDelete = null }) { Text("Cancel") }
            Button({
                ids.forEach { app.engine.remove(it, alsoFiles) }
                app.selection.removeAll(ids)
                app.confirmDelete = null
            }) { Text("Delete") }
        }
    }
}

// ------------------------------------------------------------------ Refresh address

@Composable
private fun RefreshAddressDialog(app: AppState, id: String, theme: ThemeMode) {
    val item = app.engine.get(id)
    var url by remember { mutableStateOf(item?.url.orEmpty()) }
    AppDialog("Refresh download address", 560.dp, 220.dp, theme, { app.refreshAddressFor = null }) {
        Hint("If the link expired, open the download page again, copy the new link and paste it here. Downloaded data is kept.")
        VSpace()
        Field(url, { url = it }, Modifier.fillMaxWidth())
        Spacer(Modifier.weight(1f))
        ButtonRow {
            TextButton({ app.refreshAddressFor = null }) { Text("Cancel") }
            Button({
                app.engine.refreshAddress(id, url)
                app.refreshAddressFor = null
            }, enabled = url.startsWith("http")) { Text("Save") }
        }
    }
}

// ------------------------------------------------------------------ Properties

@Composable
private fun PropertiesDialog(app: AppState, id: String, theme: ThemeMode) {
    val state by app.engine.state.collectAsState()
    val item = state.downloads.firstOrNull { it.id == id } ?: run { app.propertiesFor = null; return }
    var name by remember { mutableStateOf(item.fileName) }
    var desc by remember { mutableStateOf(item.description) }
    var conns by remember { mutableStateOf(item.maxConnections.toString()) }
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    AppDialog("Properties", 600.dp, 470.dp, theme, { app.propertiesFor = null }) {
        FormRow("File name", 130.dp) { Field(name, { name = it }, Modifier.fillMaxWidth(), readOnly = item.status.isActive) }
        FormRow("Address", 130.dp) { Field(item.url, {}, Modifier.fillMaxWidth(), readOnly = true) }
        FormRow("Saved to", 130.dp) { Field(item.saveDir, {}, Modifier.fillMaxWidth(), readOnly = true) }
        FormRow("Referrer", 130.dp) { Text(item.referer ?: "-", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        FormRow("Size", 130.dp) { Text("${Format.bytes(item.totalBytes)}  (${Format.bytes(item.downloadedBytes)} downloaded)", fontSize = 13.sp) }
        FormRow("Status", 130.dp) { Text(statusText(item) + (item.error?.let { "  ($it)" } ?: ""), fontSize = 13.sp) }
        FormRow("Resume support", 130.dp) { Text(if (item.resumable) "Yes" else "No / unknown", fontSize = 13.sp) }
        FormRow("Category", 130.dp) { Text(item.category.label, fontSize = 13.sp) }
        FormRow("Added", 130.dp) { Text(fmt.format(Date(item.createdAt)), fontSize = 13.sp) }
        if (item.completedAt > 0) FormRow("Completed", 130.dp) { Text(fmt.format(Date(item.completedAt)), fontSize = 13.sp) }
        FormRow("Connections", 130.dp) { Field(conns, { conns = it.take(2) }, Modifier.width(70.dp), numeric = true) }
        FormRow("Description", 130.dp) { Field(desc, { desc = it }, Modifier.fillMaxWidth()) }
        Spacer(Modifier.weight(1f))
        ButtonRow {
            TextButton({ Platform.copy(item.url) }) { Text("Copy address") }
            Spacer(Modifier.weight(1f))
            TextButton({ app.propertiesFor = null }) { Text("Cancel") }
            Button({
                if (name != item.fileName) app.engine.rename(id, name)
                if (desc != item.description) app.engine.updateDescription(id, desc)
                conns.toIntOrNull()?.let { if (it != item.maxConnections) app.engine.setConnections(id, it) }
                app.propertiesFor = null
            }) { Text("OK") }
        }
    }
}

// ------------------------------------------------------------------ Batch download

@Composable
private fun BatchDialog(app: AppState, theme: ThemeMode) {
    var template by remember { mutableStateOf("https://example.com/files/image[001-020].jpg") }
    var dir by remember { mutableStateOf(app.settings.saveDir) }
    val urls = remember(template) { runCatching { BatchUrls.expand(template.trim()) }.getOrDefault(emptyList()).filter { it.startsWith("http") } }
    AppDialog("Add batch download", 620.dp, 470.dp, theme, { app.batchOpen = false }) {
        Hint("Use [1-50], [001-120] or [a-z] in the address. Every combination is added as its own download.")
        VSpace(4.dp)
        Field(template, { template = it }, Modifier.fillMaxWidth())
        VSpace()
        Text("${urls.size} files", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp).border(1.dp, LocalPalette.current.gridLine, RoundedCornerShape(5.dp))) {
            LazyColumn(Modifier.padding(6.dp)) {
                items(urls.take(500)) { Text(it, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        FormRow("Save to", 70.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field(dir, { dir = it }, Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                OutlinedButton({ Platform.chooseFolder(dir)?.let { dir = it } }, Modifier.height(32.dp)) { Text("...") }
            }
        }
        ButtonRow {
            TextButton({ app.batchOpen = false }) { Text("Cancel") }
            OutlinedButton({ app.addBatch(urls, dir, start = false); app.batchOpen = false }, enabled = urls.isNotEmpty()) { Text("Add paused") }
            Button({ app.addBatch(urls, dir, start = true); app.batchOpen = false }, enabled = urls.isNotEmpty()) { Text("Download all") }
        }
    }
}

// ------------------------------------------------------------------ Links from the browser

@Composable
private fun LinksDialog(app: AppState, links: CapturedLinks, theme: ThemeMode) {
    val checked = remember(links) { mutableStateListOf<String>() }
    var filter by remember { mutableStateOf("") }
    var dir by remember { mutableStateOf(app.settings.saveDir) }
    val shown = links.links.filter { l ->
        filter.isBlank() || filter.split(' ', ',').filter { it.isNotBlank() }.any { ext ->
            (FileNames.fromUrl(l.url) ?: l.url).endsWith(ext.trim().trimStart('*'), ignoreCase = true)
        }
    }
    AppDialog("Download selected links", 720.dp, 560.dp, theme, { app.linksDialog = null }, resizable = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Filter by type", fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            Field(filter, { filter = it }, Modifier.width(220.dp), placeholder = ".zip .mp4 .pdf")
            Spacer(Modifier.weight(1f))
            TextButton({ checked.clear(); checked.addAll(shown.map { it.url }) }) { Text("Check all") }
            TextButton({ checked.clear() }) { Text("Uncheck all") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp).border(1.dp, LocalPalette.current.gridLine, RoundedCornerShape(5.dp))) {
            LazyColumn {
                items(shown, key = { it.url }) { l ->
                    val name = l.name?.takeIf { it.isNotBlank() } ?: FileNames.fromUrl(l.url) ?: l.url
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.material3.Checkbox(l.url in checked, { if (it) checked += l.url else checked.remove(l.url) }, Modifier.size(32.dp))
                        Icon(Category.detect(name).icon(), null, Modifier.size(16.dp), tint = Category.detect(name).tint())
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(l.url, fontSize = 11.sp, color = LocalPalette.current.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        FormRow("Save to", 70.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field(dir, { dir = it }, Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                OutlinedButton({ Platform.chooseFolder(dir)?.let { dir = it } }, Modifier.height(32.dp)) { Text("...") }
            }
        }
        ButtonRow {
            Text("${checked.size} of ${links.links.size} selected", fontSize = 12.sp, color = LocalPalette.current.muted)
            Spacer(Modifier.weight(1f))
            TextButton({ app.linksDialog = null }) { Text("Cancel") }
            OutlinedButton({ app.addLinks(links, links.links.filter { it.url in checked }, dir, false); app.linksDialog = null }, enabled = checked.isNotEmpty()) { Text("Add paused") }
            Button({ app.addLinks(links, links.links.filter { it.url in checked }, dir, true); app.linksDialog = null }, enabled = checked.isNotEmpty()) { Text("Download") }
        }
    }
}

// ------------------------------------------------------------------ About

@Composable
private fun AboutDialog(app: AppState, theme: ThemeMode) {
    AppDialog("About Imad Downloader", 440.dp, 330.dp, theme, { app.aboutOpen = false }) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource("icons/app.png"), null, Modifier.size(84.dp))
            VSpace(10.dp)
            Text("Imad Downloader", style = MaterialTheme.typography.titleLarge)
            Text("Version $APP_VERSION", fontSize = 13.sp, color = LocalPalette.current.muted)
            VSpace(10.dp)
            Text("Fast multi-connection download manager for Windows and Android.", fontSize = 13.sp)
            Text("Browser extension port: ${app.integration.port.takeIf { it > 0 } ?: "not running"}", fontSize = 12.sp, color = LocalPalette.current.muted)
            Text("Data folder: ${AppDirs.data.path}", fontSize = 12.sp, color = LocalPalette.current.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            VSpace(4.dp)
            Text("© 2026 Imad Khan", fontSize = 12.sp, color = LocalPalette.current.muted)
        }
        Spacer(Modifier.weight(1f))
        ButtonRow {
            TextButton({ Platform.browse("https://github.com/ImadKhan1999/Imad-Downloader") }) { Text("Website") }
            Button({ app.aboutOpen = false }) { Text("Close") }
        }
    }
}

@Suppress("unused")
private val keepStatus = DownloadStatus.COMPLETED
@Suppress("unused")
private val keepArrangement = Arrangement.Start
