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
import com.imad.downloader.core.MediaChoice
import com.imad.downloader.core.MediaInfo
import com.imad.downloader.core.MediaOption
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
    /** Video pages (YouTube etc.) found through yt-dlp. */
    val extracting: Boolean = false,
    val extractNote: String? = null,
    val media: MediaInfo? = null,
    val mediaOptions: List<MediaOption> = emptyList(),
    val mediaOption: MediaOption? = null,
) {
    fun withOption(o: MediaOption): FileInfoState = copy(
        mediaOption = o,
        fileName = fileName.substringBeforeLast('.') + "." + o.extension,
        category = if (o.audioOnly) Category.MUSIC else Category.VIDEO,
    )
}

class AppState(
    val settingsStore: SettingsStore = SettingsStore(),
    store: DownloadStore = JsonFileStore(AppDirs.downloadsFile),
    /** Previews and UI tests show the stored state exactly as given. */
    preview: Boolean = false,
    detectBrowsers: () -> List<InstalledBrowser> = BrowserSetup::detect,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var settings by mutableStateOf(settingsStore.load())
        private set

    val ytDlp = YtDlpTool()
    val engine = DownloadEngine(settings.toEngineConfig(), store, ioScope, recoverOnLoad = !preview).also { e ->
        e.mediaExtractor = YtDlpExtractor(ytDlp, cookiesFromBrowser = { settings.videoCookiesBrowser.ifBlank { null } }) { done, total ->
            scope.launch {
                val pct = if (total > 0) " ${done * 100 / total}%" else ""
                fileInfo = fileInfo?.copy(extractNote = "Setting up video support (yt-dlp, one time)$pct")
            }
        }
    }
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
    val updater = DesktopUpdater(scope)
    var updateDialogOpen by mutableStateOf(false)

    fun checkForUpdates() { updateDialogOpen = true; updater.check() }

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
            PingReply("Hyper Downloader", APP_VERSION, settings.browserIntegration, settings.clipboardTypeSet.toList())
        },
        onSeen = { family ->
            scope.launch {
                val last = settings.connectedBrowsers[family] ?: 0
                if (System.currentTimeMillis() - last > 60_000) updateSettings(settings.copy(connectedBrowsers = settings.connectedBrowsers + (family to System.currentTimeMillis())))
            }
        },
    )

    var browserSetupOpen by mutableStateOf(false)
    val browsers by lazy(detectBrowsers)

    fun start() {
        integration.start()
        ioScope.launch { runCatching { ytDlp.updateIfDue() } }
        // First run: offer to add the extension to the browsers on this PC (once).
        if (!settings.browserSetupShown) ioScope.launch {
            val found = browsers
            scope.launch {
                if (found.isNotEmpty() && settings.connectedBrowsers.isEmpty()) browserSetupOpen = true
                updateSettings(settings.copy(browserSetupShown = true))
            }
        }
        if (settings.autoUpdate && System.currentTimeMillis() - settings.lastUpdateCheck > 24 * 3_600_000L) {
            updateSettings(settings.copy(lastUpdateCheck = System.currentTimeMillis()))
            updater.check { updateDialogOpen = true }
        }
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
        if (MediaChoice.isKnownSite(request.url) && request.mediaFormat == null) {
            loadMedia(request, quiet = false)
            return
        }
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
                // A web page instead of a file: maybe a video site yt-dlp knows.
                if (p != null && !p.isStream && p.mimeType?.startsWith("text/html") == true) loadMedia(request, quiet = true)
            }
        }
    }

    /** Asks yt-dlp for the video behind a page. [quiet]: unknown sites keep the normal dialog on failure. */
    private fun loadMedia(request: DownloadRequest, quiet: Boolean) {
        fileInfo = fileInfo?.copy(extracting = true, probing = false, extractNote = "Finding the video...")
        ioScope.launch {
            val r = runCatching { engine.extractMedia(request.url, buildHeaders(request)) }
            scope.launch {
                val cur = fileInfo ?: return@launch
                if (cur.request != request) return@launch
                fileInfo = r.fold(
                    onSuccess = { info ->
                        val opts = MediaChoice.options(info)
                        if (opts.isEmpty()) cur.copy(extracting = false, extractNote = null, error = "No downloadable video found on this page")
                        else {
                            val base = cur.copy(
                                extracting = false, extractNote = null, error = null, media = info, mediaOptions = opts,
                                fileName = MediaChoice.safeTitle(info.title) + ".mp4", description = cur.description.ifBlank { info.title },
                                saveDir = engine.defaultDirFor(Category.VIDEO),
                            )
                            base.withOption(opts.first())
                        }
                    },
                    onFailure = { e -> if (quiet) cur.copy(extracting = false, extractNote = null) else cur.copy(extracting = false, extractNote = null, error = e.message ?: "Could not read this video page") },
                )
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
                sizeHint = state.mediaOption?.sizeEstimate ?: state.probe?.totalBytes ?: state.request.sizeHint,
                streamHeight = state.mediaOption?.height ?: state.streamHeight,
                mediaFormat = state.mediaOption?.formatSpec,
                kind = when {
                    state.mediaOption != null -> DownloadKind.MEDIA
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
