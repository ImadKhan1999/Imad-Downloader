@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)

package com.imad.downloader.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.imad.downloader.core.Category
import com.imad.downloader.core.DownloadItem
import com.imad.downloader.core.DownloadStatus
import com.imad.downloader.core.Format
import java.awt.Cursor
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun MainContent(app: AppState) {
    val state by app.engine.state.collectAsState()
    val p = LocalPalette.current
    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = p.window, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxSize()) {
            Toolbar(app)
            Row(Modifier.weight(1f)) {
                Sidebar(app, state.downloads, state.queues)
                Box(Modifier.width(1.dp).fillMaxHeight().background(p.toolbarBorder))
                DownloadTable(app, app.visibleItems(state.downloads))
            }
            StatusBar(app, state.downloads)
        }
    }
}

// ------------------------------------------------------------------ toolbar

@Composable
private fun Toolbar(app: AppState) {
    val p = LocalPalette.current
    val sel = app.selection.mapNotNull { app.engine.get(it) }
    val canResume = sel.any { it.status.canStart }
    val canStop = sel.any { it.status.isActive || it.status == DownloadStatus.QUEUED }
    val queueId = (app.filter as? Filter.InQueue)?.queueId ?: com.imad.downloader.core.EngineState.MAIN_QUEUE_ID
    Row(
        Modifier.fillMaxWidth().background(p.toolbar).border(0.5.dp, p.toolbarBorder).padding(horizontal = 6.dp, vertical = 4.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolButton(Icons.Filled.Add, "Add URL", tint = Brand) { app.addUrlOpen = Platform.clipboardText()?.trim().orEmpty() }
        ToolButton(Icons.Filled.PlayArrow, "Resume", enabled = canResume, tint = Color(0xFF2E9D4F)) { app.resumeSelected() }
        ToolButton(Icons.Filled.Pause, "Stop", enabled = canStop, tint = Color(0xFFE09B12)) { app.stopSelected() }
        ToolButton(Icons.Filled.StopCircle, "Stop All", tint = Color(0xFFD84315)) { app.engine.pauseAll() }
        ToolSeparator()
        ToolButton(Icons.Filled.Delete, "Delete", enabled = sel.isNotEmpty(), tint = Color(0xFFD32F2F)) { app.requestDelete(app.selection.toList()) }
        ToolButton(Icons.Filled.ClearAll, "Delete Co...", tint = Color(0xFF6D4C41)) { app.deleteCompleted() }
        ToolSeparator()
        ToolButton(Icons.Filled.Settings, "Options", tint = Color(0xFF546E7A)) { app.optionsOpen = true }
        ToolButton(Icons.Filled.Schedule, "Scheduler", tint = Color(0xFF6A1B9A)) { app.schedulerOpen = true }
        ToolButton(Icons.Filled.PlaylistPlay, "Start Queue", tint = Color(0xFF00897B)) { app.engine.startQueue(queueId) }
        ToolButton(Icons.Filled.Stop, "Stop Queue", tint = Color(0xFF8D6E63)) { app.engine.stopQueue(queueId) }
        ToolSeparator()
        SpeedLimiterButton(app)
        Spacer(Modifier.weight(1f))
        SearchBox(app)
    }
}

@Composable
private fun ToolSeparator() {
    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(44.dp).background(LocalPalette.current.toolbarBorder))
}

@Composable
fun ToolButton(icon: ImageVector, label: String, enabled: Boolean = true, tint: Color = Brand, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val bg = if (hovered && enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent
    Column(
        Modifier.width(72.dp).clip(RoundedCornerShape(6.dp)).background(bg)
            .hoverable(src).clickable(enabled = enabled, onClick = onClick)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, Modifier.size(28.dp), tint = if (enabled) tint else tint.copy(alpha = 0.3f))
        Text(
            label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.9f else 0.35f),
        )
    }
}

@Composable
private fun SpeedLimiterButton(app: AppState) {
    var open by remember { mutableStateOf(false) }
    val s = app.settings
    Box {
        ToolButton(Icons.Filled.Speed, if (s.speedLimitEnabled) "${s.speedLimitKBps} KB/s" else "Speed Limit", tint = if (s.speedLimitEnabled) Color(0xFFE65100) else Color(0xFF455A64)) { open = true }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(if (s.speedLimitEnabled) "Turn off limiter" else "Turn on limiter (${s.speedLimitKBps} KB/s)") }, onClick = {
                app.updateSettings(s.copy(speedLimitEnabled = !s.speedLimitEnabled)); open = false
            })
            HorizontalDivider()
            listOf(64L, 128L, 256L, 512L, 1024L, 2048L, 5120L).forEach { kb ->
                DropdownMenuItem(text = { Text(if (kb >= 1024) "${kb / 1024} MB/s" else "$kb KB/s") }, onClick = {
                    app.updateSettings(s.copy(speedLimitEnabled = true, speedLimitKBps = kb)); open = false
                })
            }
        }
    }
}

@Composable
private fun SearchBox(app: AppState) {
    val p = LocalPalette.current
    Row(
        Modifier.padding(end = 8.dp).width(220.dp).height(32.dp).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, Modifier.size(18.dp), tint = p.muted)
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f)) {
            if (app.search.isEmpty()) Text("Search downloads", color = p.muted, fontSize = 13.sp)
            BasicTextField(
                app.search, { app.search = it }, singleLine = true,
                textStyle = CellText.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ------------------------------------------------------------------ sidebar

@Composable
private fun Sidebar(app: AppState, items: List<DownloadItem>, queues: List<com.imad.downloader.core.DownloadQueue>) {
    val p = LocalPalette.current
    Column(Modifier.width(200.dp).fillMaxHeight().background(p.sidebar).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        SidebarHeader("Categories")
        SidebarItem(app, Filter.All, Icons.Filled.Folder, "All Downloads", items.size, Brand, 0)
        Category.entries.forEach { c ->
            SidebarItem(app, Filter.OfCategory(c), c.icon(), c.label, items.count { it.category == c }, c.tint(), 1)
        }
        SidebarItem(app, Filter.Unfinished, Icons.Filled.HourglassTop, "Unfinished", items.count { it.status != DownloadStatus.COMPLETED }, Color(0xFFE09B12), 0)
        SidebarItem(app, Filter.Finished, Icons.Filled.CheckCircle, "Finished", items.count { it.status == DownloadStatus.COMPLETED }, Color(0xFF2E9D4F), 0)
        Spacer(Modifier.height(10.dp))
        SidebarHeader("Queues")
        queues.forEach { q ->
            val label = if (q.running) "${q.name}  ▶" else q.name
            SidebarItem(app, Filter.InQueue(q.id), Icons.Filled.Queue, label, items.count { it.queueId == q.id }, Color(0xFF00897B), 0)
        }
    }
}

@Composable
private fun SidebarHeader(text: String) {
    Text(
        text.uppercase(), Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp),
        fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = LocalPalette.current.muted,
    )
}

@Composable
private fun SidebarItem(app: AppState, filter: Filter, icon: ImageVector, label: String, count: Int, tint: Color, indent: Int) {
    val selected = app.filter == filter
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.13f) else Color.Transparent
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(6.dp)).background(bg)
            .clickable { app.filter = filter; app.selection.clear() }
            .padding(start = (8 + indent * 16).dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Spacer(Modifier.width(8.dp))
        Text(label, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        if (count > 0) Text(count.toString(), fontSize = 11.sp, color = LocalPalette.current.muted)
    }
}

// ------------------------------------------------------------------ table

@Composable
private fun DownloadTable(app: AppState, rows: List<DownloadItem>) {
    val p = LocalPalette.current
    val widths = remember {
        mutableStateMapOf<Column, Float>().apply {
            Column.entries.forEach { c -> put(c, app.settings.columnWidths[c.name] ?: c.defaultWidth) }
        }
    }
    val hScroll = rememberScrollState()
    val listState = rememberLazyListState()
    var menuFor by remember { mutableStateOf<Pair<String, DpOffset>?>(null) }
    var anchor by remember { mutableStateOf(-1) }
    val modifiers = LocalWindowInfo.current.keyboardModifiers
    val density = LocalDensity.current

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
            // header
            Row(Modifier.background(p.tableHeader).height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                Column.entries.forEach { c ->
                    HeaderCell(app, c, widths[c] ?: c.defaultWidth) { delta ->
                        widths[c] = ((widths[c] ?: c.defaultWidth) + delta).coerceIn(50f, 900f)
                    }
                }
            }
            Box(Modifier.width(widths.values.sum().dp).height(1.dp).background(p.gridLine))
            if (rows.isEmpty()) {
                EmptyState(app, Modifier.width(widths.values.sum().dp.coerceAtLeast(600.dp)))
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f).width(widths.values.sum().dp.coerceAtLeast(600.dp))) {
                itemsIndexed(rows, key = { _, it -> it.id }) { index, item ->
                    val selected = item.id in app.selection
                    val bg = when {
                        selected -> p.rowSelected
                        index % 2 == 1 -> p.rowAlt
                        else -> Color.Transparent
                    }
                    Box {
                        Row(
                            Modifier.background(bg).height(28.dp)
                                .onPointerEvent(PointerEventType.Press) { e ->
                                    if (e.buttons.isSecondaryPressed) {
                                        if (item.id !in app.selection) { app.selection.clear(); app.selection += item.id }
                                        val pos = e.changes.first().position
                                        menuFor = item.id to with(density) { DpOffset(pos.x.toDp(), pos.y.toDp() - 28.dp) }
                                    }
                                }
                                .combinedClickable(
                                    onClick = {
                                        when {
                                            modifiers.isCtrlPressed -> if (selected) app.selection.remove(item.id) else app.selection += item.id
                                            modifiers.isShiftPressed && anchor in rows.indices -> {
                                                val range = if (anchor <= index) anchor..index else index..anchor
                                                app.selection.clear(); app.selection.addAll(rows.slice(range).map { it.id })
                                            }
                                            else -> { app.selection.clear(); app.selection += item.id; anchor = index }
                                        }
                                    },
                                    onDoubleClick = { openItem(app, item) },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column.entries.forEach { c -> Cell(c, item, widths[c] ?: c.defaultWidth) }
                        }
                        if (menuFor?.first == item.id) {
                            RowContextMenu(app, item, menuFor!!.second) { menuFor = null }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(widths.toMap()) {
        kotlinx.coroutines.delay(800)
        val map = widths.mapKeys { it.key.name }
        if (map != app.settings.columnWidths) app.updateSettings(app.settings.copy(columnWidths = map))
    }
}

fun openItem(app: AppState, item: DownloadItem) {
    if (item.status == DownloadStatus.COMPLETED) Platform.openFile(item.filePath)
    else {
        if (item.status.canStart) app.engine.start(item.id)
        app.openProgress(item.id)
    }
}

@Composable
private fun HeaderCell(app: AppState, c: Column, width: Float, onResize: (Float) -> Unit) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    Row(Modifier.width(width.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).fillMaxHeight().clickable { app.sortBy(c) }.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(c.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (app.sortColumn == c) {
                Icon(if (app.sortAscending) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown, null, Modifier.size(16.dp), tint = p.muted)
            }
        }
        Box(
            Modifier.width(5.dp).fillMaxHeight()
                .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                .draggable(rememberDraggableState { px -> onResize(with(density) { px.toDp().value }) }, Orientation.Horizontal),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.width(1.dp).height(16.dp).background(p.gridLine)) }
    }
}

@Composable
private fun Cell(c: Column, item: DownloadItem, width: Float) {
    val p = LocalPalette.current
    Box(Modifier.width(width.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
        when (c) {
            Column.NAME -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(item.category.icon(), null, Modifier.size(16.dp), tint = item.category.tint())
                Spacer(Modifier.width(6.dp))
                Text(item.fileName, style = CellText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column.SIZE -> Text(if (item.totalBytes > 0) Format.bytes(item.totalBytes) else "", style = CellText, maxLines = 1)
            Column.STATUS -> StatusCell(item)
            Column.TIME_LEFT -> Text(if (item.status == DownloadStatus.DOWNLOADING) Format.duration(item.etaSeconds) else "", style = CellText, maxLines = 1)
            Column.RATE -> Text(if (item.status == DownloadStatus.DOWNLOADING) Format.speed(item.speedBps) else "", style = CellText, maxLines = 1)
            Column.LAST_TRY -> Text(dateFormat.format(Date(maxOf(item.lastTryAt, item.createdAt))), style = CellText, maxLines = 1, color = p.muted)
            Column.DESCRIPTION -> Text(item.description, style = CellText, maxLines = 1, overflow = TextOverflow.Ellipsis, color = p.muted)
        }
    }
}

private val dateFormat = SimpleDateFormat("MMM dd, yyyy  HH:mm")

fun statusText(item: DownloadItem): String = when (item.status) {
    DownloadStatus.COMPLETED -> "Complete"
    DownloadStatus.FAILED -> "Error"
    DownloadStatus.QUEUED -> "Queued"
    DownloadStatus.CONNECTING -> "Connecting..."
    DownloadStatus.ASSEMBLING -> "Assembling..."
    DownloadStatus.PAUSED -> if (item.downloadedBytes > 0 && item.totalBytes > 0) "%.2f%% Paused".format(item.progress * 100) else "Paused"
    DownloadStatus.DOWNLOADING -> if (item.totalBytes > 0 || item.piecesTotal > 0) "%.2f%%".format(item.progress * 100) else Format.bytes(item.downloadedBytes)
}

@Composable
private fun StatusCell(item: DownloadItem) {
    val p = LocalPalette.current
    val color = when (item.status) {
        DownloadStatus.COMPLETED -> p.complete
        DownloadStatus.FAILED -> p.error
        DownloadStatus.PAUSED -> p.paused
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(verticalArrangement = Arrangement.Center) {
        Text(statusText(item), style = CellText, color = color, maxLines = 1)
        if (item.status == DownloadStatus.DOWNLOADING || (item.status == DownloadStatus.PAUSED && item.progress > 0)) {
            LinearProgressIndicator(
                progress = { item.progress }, modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = if (item.status == DownloadStatus.PAUSED) p.paused else p.progress, trackColor = p.progressTrack,
                strokeCap = StrokeCap.Round, drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun EmptyState(app: AppState, modifier: Modifier) {
    Column(modifier.padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource("icons/app.png"), null, Modifier.size(84.dp))
        Spacer(Modifier.height(14.dp))
        Text("No downloads here yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Click Add URL, copy a link, or click a download in your browser.",
            color = LocalPalette.current.muted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(14.dp))
        androidx.compose.material3.Button(onClick = { app.addUrlOpen = Platform.clipboardText()?.trim().orEmpty() }) {
            Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add URL")
        }
    }
}

@Composable
private fun RowContextMenu(app: AppState, item: DownloadItem, offset: DpOffset, onDismiss: () -> Unit) {
    val e = app.engine
    val ids = app.selection.toList().ifEmpty { listOf(item.id) }
    DropdownMenu(true, onDismissRequest = onDismiss, offset = offset) {
        if (item.status == DownloadStatus.COMPLETED) {
            MenuEntry("Open", Icons.Filled.FolderOpen) { Platform.openFile(item.filePath); onDismiss() }
            MenuEntry("Open with...") { Platform.openWith(item.filePath); onDismiss() }
        }
        MenuEntry("Open folder", Icons.Filled.Folder) { Platform.showInFolder(item.filePath); onDismiss() }
        HorizontalDivider()
        MenuEntry("Resume download", Icons.Filled.PlayArrow, enabled = ids.any { e.get(it)?.status?.canStart == true }) { app.resumeSelected(); onDismiss() }
        MenuEntry("Stop download", Icons.Filled.PauseCircle, enabled = ids.any { e.get(it)?.status?.let { s -> s.isActive || s == DownloadStatus.QUEUED } == true }) { app.stopSelected(); onDismiss() }
        MenuEntry("Show progress", Icons.Filled.DownloadForOffline, enabled = item.status != DownloadStatus.COMPLETED) { app.openProgress(item.id); onDismiss() }
        MenuEntry("Redownload") { ids.forEach(e::redownload); onDismiss() }
        HorizontalDivider()
        if (e.queues.size > 1 || item.queueId == null) {
            e.queues.forEach { q ->
                MenuEntry("Move to \"${q.name}\"", Icons.Filled.Queue, enabled = item.queueId != q.id) { e.moveToQueue(ids, q.id); onDismiss() }
            }
        }
        if (item.queueId != null) MenuEntry("Remove from queue") { e.moveToQueue(ids, null); onDismiss() }
        HorizontalDivider()
        MenuEntry("Copy address") { Platform.copy(item.url); onDismiss() }
        MenuEntry("Refresh download address", enabled = item.status != DownloadStatus.COMPLETED) { app.refreshAddressFor = item.id; onDismiss() }
        MenuEntry("Delete", Icons.Filled.Delete) { app.requestDelete(ids); onDismiss() }
        HorizontalDivider()
        MenuEntry("Properties") { app.propertiesFor = item.id; onDismiss() }
    }
}

@Composable
fun MenuEntry(label: String, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontSize = 13.sp) },
        onClick = onClick,
        enabled = enabled,
        leadingIcon = icon?.let { { Icon(it, null, Modifier.size(18.dp)) } },
        modifier = Modifier.height(34.dp),
    )
}

// ------------------------------------------------------------------ status bar

@Composable
private fun StatusBar(app: AppState, items: List<DownloadItem>) {
    val p = LocalPalette.current
    val active = items.filter { it.status.isActive }
    val speed = active.sumOf { it.speedBps }
    Row(
        Modifier.fillMaxWidth().height(26.dp).background(p.toolbar).border(0.5.dp, p.toolbarBorder).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${items.size} items", fontSize = 12.sp, color = p.muted)
        Text("   |   Downloading: ${active.size}", fontSize = 12.sp, color = p.muted)
        Text("   |   Queued: ${items.count { it.status == DownloadStatus.QUEUED }}", fontSize = 12.sp, color = p.muted)
        Spacer(Modifier.weight(1f))
        if (app.settings.speedLimitEnabled) Text("Limiter: ${app.settings.speedLimitKBps} KB/s   ", fontSize = 12.sp, color = Color(0xFFE65100))
        Text(if (speed > 0) "↓ ${Format.speed(speed)}" else "Idle", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (speed > 0) p.progress else p.muted)
    }
}
