@file:OptIn(ExperimentalMaterial3Api::class)

package com.imad.downloader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.imad.downloader.FileActions
import com.imad.downloader.core.BatchUrls
import com.imad.downloader.core.Category
import com.imad.downloader.core.DownloadEngine
import com.imad.downloader.core.DownloadKind
import com.imad.downloader.core.DownloadRequest
import com.imad.downloader.core.DownloadStatus
import com.imad.downloader.core.FileNames
import com.imad.downloader.core.Format
import com.imad.downloader.core.ProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

// ------------------------------------------------------------------ add download

@Composable
fun AddDownloadSheet(
    engine: DownloadEngine,
    initial: DownloadRequest,
    defaultConnections: Int,
    onDismiss: () -> Unit,
    onAdded: (started: Boolean) -> Unit,
    canStart: () -> Boolean,
) {
    var url by remember { mutableStateOf(initial.url) }
    var name by remember { mutableStateOf(initial.fileName ?: FileNames.fromUrl(initial.url)?.let(FileNames::sanitize).orEmpty()) }
    var nameEdited by remember { mutableStateOf(initial.fileName != null) }
    var connections by remember { mutableStateOf(defaultConnections.toFloat()) }
    var probe by remember { mutableStateOf<ProbeResult?>(null) }
    var probing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = url.startsWith("http://", true) || url.startsWith("https://", true)

    LaunchedEffect(url) {
        if (!valid) return@LaunchedEffect
        probing = true; error = null; probe = null
        kotlinx.coroutines.delay(350)
        val headers = buildMap {
            putAll(initial.headers)
            initial.cookies?.let { put("Cookie", it) }
            initial.userAgent?.let { put("User-Agent", it) }
        }
        val r = withContext(Dispatchers.IO) { runCatching { engine.probe(url.trim(), headers, initial.referer) } }
        r.onSuccess { p -> probe = p; if (!nameEdited || name.isBlank()) name = p.fileName }
            .onFailure { error = it.message ?: "Could not reach the server" }
        probing = false
    }

    val category = Category.detect(name, probe?.mimeType)
    val folder = engine.defaultDirFor(category)
    fun add(start: Boolean) {
        val req = initial.copy(
            url = url.trim(),
            fileName = name.trim().ifBlank { null }?.takeIf { nameEdited || probe != null },
            maxConnections = connections.toInt(),
            sizeHint = probe?.totalBytes ?: initial.sizeHint,
            kind = if (probe?.isHls == true) DownloadKind.HLS else initial.kind,
        )
        runCatching { engine.add(req, start = start && canStart()) }
            .onSuccess { onAdded(start) }
            .onFailure { error = it.message }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileBadge(category, 52)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("New download", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    when {
                        probing -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp)); Text("Checking link...", fontSize = 13.sp)
                        }
                        error != null -> Text(error!!, fontSize = 13.sp, color = MaterialTheme.colorScheme.error, maxLines = 2)
                        probe != null -> Text(
                            buildString {
                                append(if (probe!!.totalBytes >= 0) Format.bytes(probe!!.totalBytes) else "Unknown size")
                                append("  •  ")
                                append(if (probe!!.isHls) "Video stream" else if (probe!!.resumable) "Resumable" else "Not resumable")
                            },
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(url, { url = it.trim() }, Modifier.fillMaxWidth().testTag("url-field"), label = { Text("Link") }, singleLine = true, isError = url.isNotEmpty() && !valid)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(name, { name = it; nameEdited = true }, Modifier.fillMaxWidth().testTag("name-field"), label = { Text("File name") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
            Text("Save to", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(folder, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            Text("Connections: ${connections.toInt()}", fontSize = 14.sp)
            Slider(connections, { connections = it }, valueRange = 1f..32f)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton({ add(false) }, Modifier.weight(1f), enabled = valid) { Text("Later") }
                Button({ add(true) }, Modifier.weight(1f).testTag("start-download"), enabled = valid && name.isNotBlank()) { Text("Download") }
            }
        }
    }
}

// ------------------------------------------------------------------ details

@Composable
fun DetailsSheet(engine: DownloadEngine, id: String, onDismiss: () -> Unit, canStart: () -> Boolean) {
    val state by engine.state.collectAsState()
    val item = state.downloads.firstOrNull { it.id == id } ?: run { onDismiss(); return }
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("dd MMM yyyy, HH:mm") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileBadge(item.category, 52)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.fileName, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(statusLine(item), fontSize = 13.sp, color = if (item.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }
            if (item.status != DownloadStatus.COMPLETED) {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { item.progress }, modifier = Modifier.fillMaxWidth().height(8.dp),
                    strokeCap = StrokeCap.Round, drawStopIndicator = {},
                )
                if (item.kind == DownloadKind.HTTP && item.segments.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    Text("Connections (${item.activeConnections} active)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    SegmentMap(item)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                when {
                    item.status == DownloadStatus.COMPLETED -> SheetAction(Icons.AutoMirrored.Filled.OpenInNew, "Open") { FileActions.open(context, item) }
                    item.status.isActive || item.status == DownloadStatus.QUEUED -> SheetAction(Icons.Filled.Pause, "Pause") { engine.pause(id) }
                    else -> SheetAction(Icons.Filled.PlayArrow, "Resume") { if (canStart()) engine.start(id) }
                }
                SheetAction(Icons.Filled.Share, "Share") { FileActions.share(context, item) }
                SheetAction(Icons.Filled.Replay, "Restart") { engine.redownload(id) }
                SheetAction(Icons.Filled.ContentCopy, "Copy link") { FileActions.copy(context, "Link", item.url) }
                SheetAction(Icons.Filled.Delete, "Delete") { confirmDelete = true }
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            InfoRow("Size", if (item.totalBytes >= 0) Format.bytes(item.totalBytes) else "Unknown")
            InfoRow("Downloaded", Format.bytes(item.downloadedBytes))
            InfoRow("Resumable", if (item.resumable) "Yes" else "No")
            InfoRow("Connections", item.maxConnections.toString())
            InfoRow("Folder", item.saveDir)
            InfoRow("Added", fmt.format(Date(item.createdAt)))
            if (item.completedAt > 0) InfoRow("Finished", fmt.format(Date(item.completedAt)))
            InfoRow("Link", item.url)
            item.referer?.let { InfoRow("Page", it) }
            if (item.status != DownloadStatus.COMPLETED) {
                TextButton({ refresh = true }) { Icon(Icons.Filled.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Refresh expired link") }
            }
        }
    }

    if (confirmDelete) {
        var alsoFile by remember { mutableStateOf(item.status == DownloadStatus.COMPLETED) }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete download?") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(alsoFile, { alsoFile = it })
                    Text("Also delete the file from storage")
                }
            },
            confirmButton = { TextButton({ engine.remove(id, alsoFile); confirmDelete = false; onDismiss() }) { Text("Delete") } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
        )
    }
    if (refresh) {
        var link by remember { mutableStateOf(item.url) }
        AlertDialog(
            onDismissRequest = { refresh = false },
            title = { Text("Refresh link") },
            text = {
                Column {
                    Text("Open the download page again, copy the new link and paste it here. Downloaded data is kept.", fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(link, { link = it.trim() }, singleLine = true)
                }
            },
            confirmButton = { TextButton({ engine.refreshAddress(id, link); refresh = false }, enabled = link.startsWith("http")) { Text("Save") } },
            dismissButton = { TextButton({ refresh = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalButton(onClick, Modifier.size(52.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Icon(icon, label)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 12.sp)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, Modifier.width(110.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

// ------------------------------------------------------------------ batch

@Composable
fun BatchSheet(engine: DownloadEngine, onDismiss: () -> Unit, canStart: () -> Boolean) {
    var template by remember { mutableStateOf("https://example.com/photos/img[001-020].jpg") }
    val urls = remember(template) { runCatching { BatchUrls.expand(template.trim()) }.getOrDefault(emptyList()).filter { it.startsWith("http") } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text("Batch download", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Use [1-50], [001-120] or [a-z] in the link.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(template, { template = it }, Modifier.fillMaxWidth(), label = { Text("Link pattern") })
            Spacer(Modifier.height(8.dp))
            Text("${urls.size} files", fontWeight = FontWeight.SemiBold)
            LazyColumn(Modifier.heightIn(max = 220.dp)) {
                items(urls.take(300)) { Text(it, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 2.dp)) }
            }
            Spacer(Modifier.height(10.dp))
            Button({
                val start = canStart()
                urls.forEach { runCatching { engine.add(DownloadRequest(it), start) } }
                onDismiss()
            }, Modifier.fillMaxWidth(), enabled = urls.isNotEmpty()) { Text("Add ${urls.size} downloads") }
        }
    }
}

@Suppress("unused")
private fun keep(f: File) = f
