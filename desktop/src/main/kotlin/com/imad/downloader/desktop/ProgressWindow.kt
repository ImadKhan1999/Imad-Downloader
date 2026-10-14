package com.imad.downloader.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.imad.downloader.core.DownloadItem
import com.imad.downloader.core.DownloadKind
import com.imad.downloader.core.DownloadStatus
import com.imad.downloader.core.Format

/** The IDM-style per-download window: status, segment map, connections, limiter. */
@Composable
fun ProgressWindow(app: AppState, id: String) {
    val state by app.engine.state.collectAsState()
    val item = state.downloads.firstOrNull { it.id == id }
    if (item == null) {
        LaunchedEffect(id) { app.progressWindows.remove(id) }
        return
    }
    var details by remember { mutableStateOf(true) }
    val windowState = rememberWindowState(size = DpSize(600.dp, 520.dp), position = WindowPosition(Alignment.Center))
    LaunchedEffect(details) { windowState.size = DpSize(600.dp, if (details) 520.dp else 330.dp) }
    val pct = (item.progress * 100).toInt()
    Window(
        onCloseRequest = { app.progressWindows.remove(id) },
        state = windowState,
        title = if (item.status == DownloadStatus.DOWNLOADING) "$pct% ${item.fileName}" else item.fileName,
        icon = painterResource("icons/app.png"),
        resizable = false,
    ) {
        ImadTheme(app.settings.theme) { ProgressContent(app, item, details) { details = !details } }
    }
}

@Composable
fun ProgressContent(app: AppState, item: DownloadItem, details: Boolean, onToggleDetails: () -> Unit) {
    val id = item.id
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize()) {
                    var tab by remember { mutableStateOf(0) }
                    PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
                        listOf("Download status", "Speed Limiter", "Options on completion").forEachIndexed { i, t ->
                            Tab(tab == i, { tab = i }, text = { Text(t, fontSize = 13.sp) })
                        }
                    }
                    Column(Modifier.weight(1f).padding(14.dp)) {
                        when (tab) {
                            0 -> StatusTab(item, details)
                            1 -> LimiterTab(app, item)
                            else -> CompletionTab(app)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onToggleDetails) { Text(if (details) "<< Hide details" else "Show details >>") }
                        Spacer(Modifier.weight(1f))
                        when {
                            item.status == DownloadStatus.COMPLETED -> {
                                OutlinedButton({ Platform.showInFolder(item.filePath) }) { Text("Open folder") }
                                Spacer(Modifier.width(8.dp))
                                Button({ Platform.openFile(item.filePath); app.progressWindows.remove(id) }) { Text("Open") }
                            }
                            item.status.isActive || item.status == DownloadStatus.QUEUED ->
                                OutlinedButton({ app.engine.pause(id) }, Modifier.width(100.dp)) { Text("Pause") }
                            else -> Button({ app.engine.start(id) }, Modifier.width(100.dp)) { Text("Resume") }
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton({
                            if (item.status != DownloadStatus.COMPLETED) app.engine.pause(id)
                            app.progressWindows.remove(id)
                        }, Modifier.width(100.dp)) { Text(if (item.status == DownloadStatus.COMPLETED) "Close" else "Cancel") }
                    }
                }
            }
}

@Composable
private fun StatusTab(item: DownloadItem, details: Boolean) {
    val p = LocalPalette.current
    Text(item.url, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    VSpace(8.dp)
    val statusLabel = when (item.status) {
        DownloadStatus.DOWNLOADING -> "Receiving data..."
        DownloadStatus.CONNECTING -> "Connecting..."
        DownloadStatus.QUEUED -> "Waiting in queue..."
        DownloadStatus.PAUSED -> "Paused"
        DownloadStatus.FAILED -> "Error: ${item.error}"
        DownloadStatus.ASSEMBLING -> "Joining parts..."
        DownloadStatus.COMPLETED -> "Download complete"
    }
    InfoRow("Status", statusLabel, if (item.status == DownloadStatus.FAILED) p.error else null)
    InfoRow("File size", if (item.totalBytes > 0) Format.bytes(item.totalBytes) else "Unknown")
    val downloaded = if (item.totalBytes > 0) "${Format.bytes(item.downloadedBytes)} (%.2f %%)".format(item.progress * 100) else Format.bytes(item.downloadedBytes)
    InfoRow("Downloaded", downloaded)
    InfoRow("Transfer rate", if (item.status == DownloadStatus.DOWNLOADING) Format.speed(item.speedBps).ifEmpty { "-" } else "-")
    InfoRow("Time left", if (item.status == DownloadStatus.DOWNLOADING) Format.duration(item.etaSeconds).ifEmpty { "-" } else "-")
    InfoRow("Resume capability", if (item.resumable) "Yes" else if (item.totalBytes < 0) "Unknown" else "No")
    if (item.kind == DownloadKind.HLS) InfoRow("Video pieces", "${item.piecesDone} / ${item.piecesTotal}")
    VSpace(8.dp)
    LinearProgressIndicator(
        progress = { item.progress },
        modifier = Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(3.dp)),
        color = if (item.status == DownloadStatus.PAUSED) p.paused else p.progress,
        trackColor = p.progressTrack, strokeCap = StrokeCap.Butt, drawStopIndicator = {},
    )
    if (details) {
        VSpace(10.dp)
        Text("Start positions and download progress by connections", fontSize = 12.sp, color = p.muted)
        VSpace(4.dp)
        SegmentMap(item)
        VSpace(8.dp)
        ConnectionsTable(item)
    }
}

@Composable
private fun InfoRow(label: String, value: String, color: androidx.compose.ui.graphics.Color? = null) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, Modifier.width(150.dp), fontSize = 13.sp, color = LocalPalette.current.muted)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = color ?: MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One horizontal bar: each segment's downloaded part painted at its byte position. */
@Composable
private fun SegmentMap(item: DownloadItem) {
    val p = LocalPalette.current
    val total = item.totalBytes
    Canvas(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(2.dp)).background(p.progressTrack).border(0.5.dp, p.gridLine)) {
        if (total <= 0) {
            drawRect(p.segmentDone, size = Size(size.width * item.progress, size.height))
            return@Canvas
        }
        val w = size.width
        item.segments.forEach { s ->
            val x = s.start.toFloat() / total * w
            val dw = s.downloaded.toFloat() / total * w
            drawRect(p.segmentDone, Offset(x, 0f), Size(dw.coerceAtLeast(0.5f), size.height))
            if (!s.isDone && item.status == DownloadStatus.DOWNLOADING) {
                drawRect(p.segmentActive, Offset(x + dw - 1.5f, 0f), Size(3f, size.height))
            }
        }
    }
}

@Composable
private fun ConnectionsTable(item: DownloadItem) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().height(120.dp).border(1.dp, p.gridLine, RoundedCornerShape(4.dp))) {
        Row(Modifier.fillMaxWidth().background(p.tableHeader).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text("N.", Modifier.width(40.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("Downloaded", Modifier.width(160.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("Info", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        LazyColumn {
            itemsIndexed(item.segments) { i, s ->
                val info = when {
                    s.isDone -> "Finished"
                    item.status == DownloadStatus.DOWNLOADING -> "Receiving data..."
                    else -> "Paused"
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("${i + 1}", Modifier.width(40.dp), fontSize = 12.sp)
                    Text(Format.bytes(s.downloaded), Modifier.width(160.dp), fontSize = 12.sp)
                    Text(info, fontSize = 12.sp, color = if (s.isDone) p.complete else p.muted)
                }
            }
        }
    }
}

@Composable
private fun LimiterTab(app: AppState, item: DownloadItem) {
    var on by remember(item.id) { mutableStateOf(item.speedLimit > 0) }
    var kb by remember(item.id) { mutableStateOf(if (item.speedLimit > 0) (item.speedLimit / 1024).toString() else "256") }
    Hint("Limit only this download. The global limiter in the toolbar applies to all downloads together.")
    VSpace()
    CheckRow("Use speed limiter for this download", on) { on = it }
    FormRow("Maximum speed", 140.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(kb, { kb = it.take(7) }, Modifier.width(100.dp), numeric = true)
            Spacer(Modifier.width(6.dp))
            Text("KB/s", fontSize = 13.sp)
        }
    }
    VSpace()
    Button({ app.engine.setSpeedLimit(item.id, if (on) (kb.toLongOrNull() ?: 0) * 1024 else 0) }) { Text("Apply") }
}

@Composable
private fun CompletionTab(app: AppState) {
    val s = app.settings
    CheckRow("Show download complete dialog", s.showCompleteDialog) { app.updateSettings(s.copy(showCompleteDialog = it)) }
    CheckRow("Play a sound when finished", s.soundOnComplete) { app.updateSettings(s.copy(soundOnComplete = it)) }
    VSpace()
    Hint("Actions after a whole queue finishes are set in the Scheduler.")
    TextButton({ app.schedulerOpen = true }) { Text("Open Scheduler") }
}

@Suppress("unused")
private val keepBox: @Composable () -> Unit = { Box {} }
