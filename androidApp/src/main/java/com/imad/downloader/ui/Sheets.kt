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
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.horizontalScroll
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
    var qualities by remember { mutableStateOf<List<com.imad.downloader.core.StreamQuality>>(emptyList()) }
    var height by remember { mutableStateOf(initial.streamHeight) }
    var queueId by remember { mutableStateOf(initial.queueId ?: com.imad.downloader.core.EngineState.MAIN_QUEUE_ID) }
    // Video pages (YouTube etc.) read through yt-dlp.
    var extracting by remember { mutableStateOf(false) }
    var media by remember { mutableStateOf<com.imad.downloader.core.MediaInfo?>(null) }
    var mediaOptions by remember { mutableStateOf<List<com.imad.downloader.core.MediaOption>>(emptyList()) }
    var mediaOption by remember { mutableStateOf<com.imad.downloader.core.MediaOption?>(null) }
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
        media = null; mediaOptions = emptyList(); mediaOption = null
        suspend fun tryMedia(quiet: Boolean) {
            extracting = true
            val m = withContext(Dispatchers.IO) { runCatching { engine.extractMedia(url.trim(), headers) } }
            extracting = false
            m.onSuccess { info ->
                val opts = com.imad.downloader.core.MediaChoice.options(info)
                if (opts.isEmpty()) { if (!quiet) error = "No downloadable video found on this page"; return@onSuccess }
                val pick = com.imad.downloader.core.MediaChoice.defaultOption(opts) ?: opts.first()
                media = info; mediaOptions = opts; mediaOption = pick; error = null
                if (!nameEdited) name = com.imad.downloader.core.MediaChoice.safeTitle(info.title) + "." + pick.extension
            }.onFailure { if (!quiet) error = it.message ?: "Could not read this video page" }
        }
        if (com.imad.downloader.core.MediaChoice.isKnownSite(url) && initial.mediaFormat == null) {
            probing = false
            tryMedia(quiet = false)
            return@LaunchedEffect
        }
        val r = withContext(Dispatchers.IO) { runCatching { engine.probe(url.trim(), headers, initial.referer) } }
        r.onSuccess { p -> probe = p; if (!nameEdited || name.isBlank()) name = p.fileName }
            .onFailure { error = it.message ?: "Could not reach the server" }
        probing = false
        val p = r.getOrNull()
        qualities = if (p != null && p.isStream) {
            withContext(Dispatchers.IO) { runCatching { engine.streamQualities(p.finalUrl, headers, initial.referer) }.getOrDefault(emptyList()) }
        } else emptyList()
        // A web page instead of a file: maybe a video site yt-dlp knows.
        if (p != null && !p.isStream && p.mimeType?.startsWith("text/html") == true) tryMedia(quiet = true)
    }

    val category = when {
        mediaOption?.audioOnly == true -> Category.MUSIC
        media != null || probe?.isStream == true -> Category.VIDEO
        else -> Category.detect(name, probe?.mimeType)
    }
    val folder = engine.defaultDirFor(category)
    fun add(start: Boolean) {
        val req = initial.copy(
            url = url.trim(),
            fileName = name.trim().ifBlank { null }?.takeIf { nameEdited || probe != null },
            maxConnections = connections.toInt(),
            sizeHint = probe?.totalBytes ?: initial.sizeHint,
            kind = when {
                mediaOption != null -> DownloadKind.MEDIA
                probe?.isDash == true -> DownloadKind.DASH
                probe?.isHls == true -> DownloadKind.HLS
                else -> initial.kind
            },
            streamHeight = mediaOption?.height ?: height,
            mediaFormat = mediaOption?.formatSpec,
            queueId = if (start) initial.queueId else queueId,
        ).let { if (mediaOption != null) it.copy(fileName = name.trim(), sizeHint = mediaOption!!.sizeEstimate) else it }
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
                        extracting -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp)); Text("Finding the video...", fontSize = 13.sp)
                        }
                        media != null -> Text(
                            "${media!!.extractor.ifBlank { "Video" }}  •  ${media!!.title}",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        probing -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp)); Text("Checking link...", fontSize = 13.sp)
                        }
                        error != null -> Text(error!!, fontSize = 13.sp, color = MaterialTheme.colorScheme.error, maxLines = 2)
                        probe != null -> Text(
                            buildString {
                                if (probe!!.isStream) { append("Video stream"); if (qualities.size > 1) append("  •  ${qualities.size} qualities"); return@buildString }
                                append(if (probe!!.totalBytes >= 0) Format.bytes(probe!!.totalBytes) else "Unknown size")
                                append("  •  ")
                                append(if (probe!!.isStream) "Video stream" else if (probe!!.resumable) "Resumable" else "Not resumable")
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
            if (mediaOptions.isNotEmpty()) {
                Text("Quality", fontSize = 14.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    mediaOptions.forEach { o ->
                        FilterChip(mediaOption == o, {
                            mediaOption = o
                            name = name.substringBeforeLast('.') + "." + o.extension
                        }, label = {
                            Text(if (o.sizeEstimate > 0) "${o.label} · ${Format.bytes(o.sizeEstimate)}" else o.label)
                        }, modifier = Modifier.testTag("media-${o.label}"))
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            if (qualities.size > 1) {
                Text("Quality", fontSize = 14.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(height == 0, { height = 0 }, label = { Text("Best") }, modifier = Modifier.testTag("quality-best"))
                    qualities.filter { it.height > 0 }.distinctBy { it.height }.forEach { q ->
                        FilterChip(height == q.height, { height = q.height }, label = { Text(q.label) }, modifier = Modifier.testTag("quality-${q.height}"))
                    }
                }
                if (qualities.any { it.separateAudio }) Text("Video and audio are joined into one file.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
            }
            val queues = engine.queues
            if (queues.size > 1) {
                Text("\"Later\" puts it in", fontSize = 14.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    queues.forEach { q -> FilterChip(queueId == q.id, { queueId = q.id }, label = { Text(q.name) }) }
                }
                Spacer(Modifier.height(12.dp))
            }
            Text("Connections: ${connections.toInt()}", fontSize = 14.sp)
            Slider(connections, { connections = it }, valueRange = 1f..32f)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton({ add(false) }, Modifier.weight(1f), enabled = valid) { Text("Later") }
                Button({ add(true) }, Modifier.weight(1f).testTag("start-download"), enabled = valid && name.isNotBlank() && !extracting) { Text("Download") }
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
                Text("Queue", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(item.queueId == null, { engine.moveToQueue(listOf(id), null) }, label = { Text("None") })
                    engine.queues.forEach { q -> FilterChip(item.queueId == q.id, { engine.moveToQueue(listOf(id), q.id) }, label = { Text(q.name) }) }
                }
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
