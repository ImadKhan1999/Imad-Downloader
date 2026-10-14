@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.imad.downloader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.imad.downloader.FileActions
import com.imad.downloader.R
import com.imad.downloader.core.Category
import com.imad.downloader.core.DownloadEngine
import com.imad.downloader.core.DownloadItem
import com.imad.downloader.core.DownloadStatus
import com.imad.downloader.core.Format

enum class StatusFilter(val label: String) { ALL("All"), ACTIVE("Downloading"), DONE("Completed"), FAILED("Failed") }

@Composable
fun DownloadsScreen(
    engine: DownloadEngine,
    onAdd: (String?) -> Unit,
    onOpenBrowser: () -> Unit,
    onDetails: (String) -> Unit,
    onBatch: () -> Unit,
    canStart: () -> Boolean,
    onQueues: () -> Unit = {},
) {
    val state by engine.state.collectAsState()
    var status by rememberSaveable { mutableStateOf(StatusFilter.ALL) }
    var category by rememberSaveable { mutableStateOf<Category?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val items = state.downloads
        .filter {
            when (status) {
                StatusFilter.ALL -> true
                StatusFilter.ACTIVE -> it.status != DownloadStatus.COMPLETED && it.status != DownloadStatus.FAILED
                StatusFilter.DONE -> it.status == DownloadStatus.COMPLETED
                StatusFilter.FAILED -> it.status == DownloadStatus.FAILED
            }
        }
        .filter { category == null || it.category == category }
        .filter { query.isBlank() || it.fileName.contains(query, true) || it.url.contains(query, true) }
        .sortedByDescending { it.createdAt }
    val speed = state.downloads.filter { it.status.isActive }.sumOf { it.speedBps }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            query, { query = it }, singleLine = true, placeholder = { Text("Search downloads") },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("search"),
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(R.drawable.logo), null, Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Imad Downloader", fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                                if (speed > 0) Text("↓ ${Format.speed(speed)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                },
                actions = {
                    IconButton({ searching = !searching; if (!searching) query = "" }) {
                        Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, "Search")
                    }
                    IconButton({ menu = true }, Modifier.testTag("downloads-menu")) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Resume all") }, onClick = { if (canStart()) engine.startAll(); menu = false })
                        DropdownMenuItem(text = { Text("Pause all") }, onClick = { engine.pauseAll(); menu = false })
                        DropdownMenuItem(text = { Text("Batch download") }, onClick = { onBatch(); menu = false })
                        DropdownMenuItem(text = { Text("Queues and schedule") }, onClick = { onQueues(); menu = false }, modifier = Modifier.testTag("menu-queues"))
                        DropdownMenuItem(text = { Text("Clear completed") }, onClick = { engine.clearCompleted(); menu = false })
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onAdd(FileActions.clipboardText(context)) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Add link") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("add-fab"),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusFilter.entries.forEach { f ->
                    val count = when (f) {
                        StatusFilter.ALL -> state.downloads.size
                        StatusFilter.ACTIVE -> state.downloads.count { it.status != DownloadStatus.COMPLETED && it.status != DownloadStatus.FAILED }
                        StatusFilter.DONE -> state.downloads.count { it.status == DownloadStatus.COMPLETED }
                        StatusFilter.FAILED -> state.downloads.count { it.status == DownloadStatus.FAILED }
                    }
                    FilterChip(status == f, { status = f }, label = { Text(if (count > 0) "${f.label} $count" else f.label) })
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Category.entries.filter { c -> state.downloads.any { it.category == c } }.forEach { c ->
                    FilterChip(
                        category == c, { category = if (category == c) null else c },
                        label = { Text(c.label) },
                        leadingIcon = { Icon(c.icon(), null, Modifier.size(16.dp), tint = c.tint()) },
                    )
                }
            }
            if (items.isEmpty()) {
                EmptyDownloads(state.downloads.isEmpty(), onAdd = { onAdd(FileActions.clipboardText(context)) }, onOpenBrowser)
            } else {
                LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items, key = { it.id }) { item ->
                        DownloadCard(item, engine, canStart, onClick = { onDetails(item.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDownloads(nothingAtAll: Boolean, onAdd: () -> Unit, onOpenBrowser: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(painterResource(R.drawable.logo), null, Modifier.size(88.dp).clip(RoundedCornerShape(22.dp)))
        Spacer(Modifier.height(16.dp))
        Text(if (nothingAtAll) "No downloads yet" else "Nothing matches this filter", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Paste a link, share one from any app, or find files and videos in the built-in browser.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onAdd) { Icon(Icons.Filled.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Paste link") }
            OutlinedButton(onOpenBrowser) { Icon(Icons.Filled.Public, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Open browser") }
        }
    }
}

fun statusLine(item: DownloadItem): String = when (item.status) {
    DownloadStatus.COMPLETED -> "Completed  •  ${Format.bytes(item.totalBytes)}"
    DownloadStatus.FAILED -> item.error ?: "Failed"
    DownloadStatus.QUEUED -> "Waiting  •  ${sizePart(item)}"
    DownloadStatus.CONNECTING -> "Connecting..."
    DownloadStatus.ASSEMBLING -> "Joining parts..."
    DownloadStatus.PAUSED -> "Paused  •  ${sizePart(item)}"
    DownloadStatus.DOWNLOADING -> buildString {
        append(sizePart(item))
        if (item.speedBps > 0) append("  •  ${Format.speed(item.speedBps)}")
        if (item.etaSeconds > 0) append("  •  ${Format.duration(item.etaSeconds)} left")
    }
}

private fun sizePart(item: DownloadItem): String = when {
    item.totalBytes > 0 -> "${Format.bytes(item.downloadedBytes)} / ${Format.bytes(item.totalBytes)}"
    item.piecesTotal > 0 -> "${item.piecesDone}/${item.piecesTotal} parts  •  ${Format.bytes(item.downloadedBytes)}"
    else -> Format.bytes(item.downloadedBytes)
}

@Composable
fun DownloadCard(item: DownloadItem, engine: DownloadEngine, canStart: () -> Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Card(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = onClick, onLongClick = onClick).testTag("item-${item.fileName}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FileBadge(item.category)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.fileName, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                AnimatedVisibility(item.status != DownloadStatus.COMPLETED && item.status != DownloadStatus.FAILED) {
                    Column {
                        LinearProgressIndicator(
                            progress = { item.progress },
                            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                            color = if (item.status == DownloadStatus.PAUSED) Warning else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            strokeCap = StrokeCap.Round, drawStopIndicator = {},
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.status == DownloadStatus.DOWNLOADING && (item.totalBytes > 0 || item.piecesTotal > 0)) {
                        Text("${(item.progress * 100).toInt()}%  ", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    }
                    if (item.queueId != null && item.status == DownloadStatus.PAUSED) {
                        Icon(Icons.Filled.Schedule, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(
                        statusLine(item), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = when (item.status) {
                            DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                            DownloadStatus.COMPLETED -> Success
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            when {
                item.status == DownloadStatus.COMPLETED ->
                    IconButton({ FileActions.open(context, item) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open", tint = MaterialTheme.colorScheme.primary) }
                item.status.isActive || item.status == DownloadStatus.QUEUED ->
                    IconButton({ engine.pause(item.id) }, Modifier.testTag("pause-${item.fileName}")) { Icon(Icons.Filled.Pause, "Pause") }
                item.status == DownloadStatus.FAILED ->
                    IconButton({ if (canStart()) engine.start(item.id) }) { Icon(Icons.Filled.Refresh, "Retry", tint = MaterialTheme.colorScheme.error) }
                else ->
                    IconButton({ if (canStart()) engine.start(item.id) }, Modifier.testTag("resume-${item.fileName}")) { Icon(Icons.Filled.PlayArrow, "Resume", tint = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

@Composable
fun FileBadge(category: Category, size: Int = 46) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.3).dp)).background(category.tint().copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) { Icon(category.icon(), null, Modifier.size((size * 0.55).dp), tint = category.tint()) }
}

/** Where each connection is in the file (the same map IDM shows). */
@Composable
fun SegmentMap(item: DownloadItem, modifier: Modifier = Modifier) {
    val done = MaterialTheme.colorScheme.primary
    val active = MaterialTheme.colorScheme.tertiary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(4.dp)).background(track)) {
        val total = item.totalBytes
        if (total <= 0 || item.segments.isEmpty()) {
            drawRect(done, size = Size(size.width * item.progress, size.height))
            return@Canvas
        }
        item.segments.forEach { s ->
            val x = s.start.toFloat() / total * size.width
            val w = s.downloaded.toFloat() / total * size.width
            drawRect(done, Offset(x, 0f), Size(w.coerceAtLeast(1f), size.height))
            if (!s.isDone && item.status == DownloadStatus.DOWNLOADING) drawRect(active, Offset(x + w - 2f, 0f), Size(4f, size.height))
        }
    }
}

@Suppress("unused")
private val keepShape = CircleShape
