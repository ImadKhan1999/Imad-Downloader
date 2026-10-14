package com.imad.downloader.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.imad.downloader.core.Category
import com.imad.downloader.core.DownloadEngine
import com.imad.downloader.core.DownloadItem
import com.imad.downloader.core.DownloadRequest
import com.imad.downloader.core.DownloadStatus
import com.imad.downloader.core.DownloadStore
import com.imad.downloader.core.EngineEvent
import com.imad.downloader.core.FileNames
import com.imad.downloader.core.JsonFileStore
import com.imad.downloader.core.ProbeResult
import com.imad.downloader.core.QueueScheduler
import com.imad.downloader.core.StreamQuality
import com.imad.downloader.core.DownloadKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import java.io.File

/** Left-hand tree selection. */
sealed interface Filter {
    data object All : Filter
    data class OfCategory(val category: Category) : Filter
    data object Unfinished : Filter
    data object Finished : Filter
    data class InQueue(val queueId: String) : Filter

    fun matches(item: DownloadItem): Boolean = when (this) {
        All -> true
        is OfCategory -> item.category == category
        Unfinished -> item.status != DownloadStatus.COMPLETED
        Finished -> item.status == DownloadStatus.COMPLETED
        is InQueue -> item.queueId == queueId
    }
}

enum class Column(val title: String, val defaultWidth: Float) {
    NAME("File Name", 300f),
    SIZE("Size", 90f),
    STATUS("Status", 110f),
    TIME_LEFT("Time left", 100f),
    RATE("Transfer rate", 110f),
    LAST_TRY("Last Try Date", 140f),
    DESCRIPTION("Description", 200f),
}

/** State of the IDM "Download File Info" dialog. */
data class FileInfoState(
    val request: DownloadRequest,
    val probing: Boolean = true,
    val probe: ProbeResult? = null,
    val error: String? = null,
    val fileName: String = request.fileName ?: FileNames.fromUrl(request.url)?.let(FileNames::sanitize) ?: "download",
    val category: Category = Category.detect(fileName),
    val saveDir: String = "",
    val description: String = request.description,
    val rememberPath: Boolean = false,
    /** Stream qualities (HLS / DASH); empty for normal files. */
    val qualities: List<StreamQuality> = emptyList(),
    val streamHeight: Int = 0,
)

class AppState(
    val settingsStore: SettingsStore = SettingsStore(),
    store: DownloadStore = JsonFileStore(AppDirs.downloadsFile),
    /** Previews and UI tests show the stored state exactly as given. */
    preview: Boolean = false,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var settings by mutableStateOf(settingsStore.load())
        private set

    val engine = DownloadEngine(settings.toEngineConfig(), store, ioScope, recoverOnLoad = !preview)
    val scheduler = QueueScheduler(engine)

    // ----- main window UI state
    var filter by mutableStateOf<Filter>(Filter.All)
    var search by mutableStateOf("")
    val selection = mutableStateListOf<String>()
    var sortColumn by mutableStateOf<Column?>(null)
    var sortAscending by mutableStateOf(true)
    var windowVisible by mutableStateOf(true)
    var bringToFrontTick by mutableStateOf(0)

    // ----- dialogs
    var addUrlOpen by mutableStateOf<String?>(null)
    var fileInfo by mutableStateOf<FileInfoState?>(null)
    val progressWindows = mutableStateListOf<String>()
    val completeDialogs = mutableStateListOf<String>()
    var optionsOpen by mutableStateOf(false)
    var schedulerOpen by mutableStateOf(false)
    var batchOpen by mutableStateOf(false)
    var aboutOpen by mutableStateOf(false)
    var propertiesFor by mutableStateOf<String?>(null)
    var linksDialog by mutableStateOf<CapturedLinks?>(null)
    var confirmDelete by mutableStateOf<List<String>?>(null)
    var refreshAddressFor by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var notifier: ((title: String, text: String) -> Unit)? = null
    var exitApp: () -> Unit = {}

    private val clipboard = ClipboardMonitor(
        enabled = { settings.monitorClipboard },
        types = { settings.clipboardTypeSet },
        onLink = { link -> scope.launch { if (fileInfo == null && addUrlOpen == null) showFileInfo(DownloadRequest(link)) } },
    )

    val integration = IntegrationServer(
        onDownload = { d -> scope.launch { handleCaptured(d) } },
        onLinks = { l -> scope.launch { linksDialog = l; showWindow() } },
        onShow = { scope.launch { showWindow() } },
        ping = {
            PingReply("Imad Downloader", APP_VERSION, settings.browserIntegration, settings.clipboardTypeSet.toList())
        },
    )

    fun start() {
        integration.start()
        clipboard.start(scope)
        scheduler.start(ioScope)
        scope.launch {
            engine.events.collect { e ->
                when (e) {
                    is EngineEvent.Completed -> onCompleted(e.item)
                    is EngineEvent.Failed -> notifier?.invoke("Download failed", "${e.item.fileName}: ${e.item.error}")
                    else -> Unit
                }
            }
        }
        scope.launch {
            engine.queueFinished.collect { q ->
                notifier?.invoke("Queue finished", "\"${q.name}\" has finished downloading.")
                val action = settings.queueCompletionAction
                if (action != CompletionAction.NOTHING) Platform.runCompletionAction(action) { exitApp() }
            }
        }
    }

    fun updateSettings(new: DesktopSettings) {
        val old = settings
        settings = new
        runCatching { settingsStore.save(new) }
        engine.updateConfig(new.toEngineConfig())
        if (old.launchOnStartup != new.launchOnStartup) Platform.setLaunchOnStartup(new.launchOnStartup)
    }

    fun showWindow() {
        windowVisible = true
        bringToFrontTick++
    }

    // ----- adding downloads

    private fun handleCaptured(d: CapturedDownload) {
        val req = DownloadRequest(
            url = d.url,
            fileName = d.fileName?.takeIf { it.isNotBlank() },
            referer = d.referer,
            cookies = d.cookies,
            userAgent = d.userAgent,
            headers = d.headers,
            sizeHint = d.size,
            streamHeight = d.streamHeight,
        )
        when {
            d.mode == "silent" || !settings.showStartDialog -> startDownload(req, start = true)
            d.mode == "later" -> startDownload(req, start = false)
            else -> { showWindow(); showFileInfo(req) }
        }
    }

    /** Opens the IDM "Download File Info" dialog and probes the server in the background. */
    fun showFileInfo(request: DownloadRequest) {
        val initial = FileInfoState(request)
        fileInfo = initial.copy(saveDir = engine.defaultDirFor(initial.category))
        ioScope.launch {
            val result = runCatching { engine.probe(request.url, buildHeaders(request), request.referer) }
            scope.launch {
                val cur = fileInfo ?: return@launch
                if (cur.request != request) return@launch
                fileInfo = result.fold(
                    onSuccess = { p ->
                        val name = request.fileName ?: p.fileName
                        val cat = p.category.let { if (it == Category.OTHER) Category.detect(name, p.mimeType) else it }
                        val keepDir = cur.saveDir != engine.defaultDirFor(cur.category)
                        cur.copy(
                            probing = false, probe = p, fileName = name, category = cat,
                            saveDir = if (keepDir) cur.saveDir else engine.defaultDirFor(cat),
                        )
                    },
                    onFailure = { cur.copy(probing = false, error = it.message ?: "Could not reach the server") },
                )
                val p = result.getOrNull()
                if (p != null && p.isStream) loadQualities(request, p.finalUrl)
            }
        }
    }

    private fun loadQualities(request: DownloadRequest, url: String) {
        ioScope.launch {
            val q = runCatching { engine.streamQualities(url, buildHeaders(request), request.referer) }.getOrDefault(emptyList())
            scope.launch {
                val cur = fileInfo ?: return@launch
                if (cur.request == request) fileInfo = cur.copy(qualities = q, category = Category.VIDEO)
            }
        }
    }

    private fun buildHeaders(r: DownloadRequest): Map<String, String> = buildMap {
        putAll(r.headers)
        r.cookies?.let { put("Cookie", it) }
        r.userAgent?.let { put("User-Agent", it) }
    }

    fun confirmFileInfo(state: FileInfoState, start: Boolean, queueId: String? = null) {
        fileInfo = null
        val item = startDownload(
            state.request.copy(
                fileName = state.fileName,
                saveDir = state.saveDir,
                description = state.description,
                queueId = queueId ?: state.request.queueId,
                sizeHint = state.probe?.totalBytes ?: state.request.sizeHint,
                streamHeight = state.streamHeight,
                kind = when {
                    state.probe?.isDash == true -> DownloadKind.DASH
                    state.probe?.isHls == true -> DownloadKind.HLS
                    else -> state.request.kind
                },
            ),
            start = start,
        )
        if (state.rememberPath && item != null) {
            updateSettings(settings.copy(saveDir = if (settings.categorySubfolders) File(state.saveDir).parent ?: state.saveDir else state.saveDir))
        }
    }

    fun startDownload(request: DownloadRequest, start: Boolean): DownloadItem? = runCatching {
        val item = engine.add(request, start)
        if (start && settings.showProgressWindow) openProgress(item.id)
        item
    }.onFailure { message = it.message }.getOrNull()

    fun addBatch(urls: List<String>, saveDir: String?, start: Boolean) {
        urls.forEach { url -> runCatching { engine.add(DownloadRequest(url, saveDir = saveDir), start) } }
    }

    fun addLinks(links: CapturedLinks, chosen: List<CapturedLink>, saveDir: String?, start: Boolean) {
        chosen.forEach { l ->
            runCatching {
                engine.add(
                    DownloadRequest(
                        url = l.url, fileName = l.name?.takeIf { it.isNotBlank() }?.let(FileNames::sanitize),
                        saveDir = saveDir, referer = links.referer, cookies = links.cookies, userAgent = links.userAgent,
                    ),
                    start,
                )
            }
        }
    }

    fun openProgress(id: String) {
        if (id !in progressWindows) progressWindows += id
    }

    private fun onCompleted(item: DownloadItem) {
        progressWindows.remove(item.id)
        if (settings.soundOnComplete) Platform.beep()
        if (settings.showCompleteDialog && item.queueId == null) {
            if (item.id !in completeDialogs) completeDialogs += item.id
        } else if (settings.notifications) {
            notifier?.invoke("Download complete", item.fileName)
        }
    }

    // ----- list operations on the selection

    fun visibleItems(all: List<DownloadItem>): List<DownloadItem> {
        val q = search.trim()
        val filtered = all.filter { filter.matches(it) && (q.isEmpty() || it.fileName.contains(q, true) || it.url.contains(q, true)) }
        val col = sortColumn ?: return filtered.sortedByDescending { it.createdAt }
        val cmp: Comparator<DownloadItem> = when (col) {
            Column.NAME -> compareBy { it.fileName.lowercase() }
            Column.SIZE -> compareBy { it.totalBytes }
            Column.STATUS -> compareBy<DownloadItem> { it.status.ordinal }.thenBy { it.progress }
            Column.TIME_LEFT -> compareBy { if (it.etaSeconds < 0) Long.MAX_VALUE else it.etaSeconds }
            Column.RATE -> compareBy { it.speedBps }
            Column.LAST_TRY -> compareBy { maxOf(it.lastTryAt, it.createdAt) }
            Column.DESCRIPTION -> compareBy { it.description.lowercase() }
        }
        return filtered.sortedWith(if (sortAscending) cmp else cmp.reversed())
    }

    fun resumeSelected() = selection.toList().forEach { id ->
        engine.start(id)
        if (settings.showProgressWindow && selection.size == 1) openProgress(id)
    }

    fun stopSelected() = selection.toList().forEach(engine::pause)

    fun requestDelete(ids: List<String>) {
        if (ids.isEmpty()) return
        if (settings.confirmDelete) confirmDelete = ids else ids.forEach { engine.remove(it, false) }
    }

    fun deleteCompleted() = engine.clearCompleted()

    fun sortBy(c: Column) {
        if (sortColumn == c) sortAscending = !sortAscending else { sortColumn = c; sortAscending = true }
    }

    fun shutdown() {
        runCatching { integration.close() }
        engine.shutdown()
    }
}
