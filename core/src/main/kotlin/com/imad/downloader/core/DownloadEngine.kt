package com.imad.downloader.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The download manager shared by the Windows and Android apps.
 *
 * All public methods are thread-safe and return immediately; work happens on [scope].
 * Observe [state] for the list and [events] for one-off notifications.
 */
class DownloadEngine(
    config: EngineConfig,
    private val store: DownloadStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    client: OkHttpClient? = null,
) {
    @Volatile var config: EngineConfig = config
        private set

    @Volatile private var client: OkHttpClient = client ?: Http.client(config)
    private val clientProvided = client != null
    private val globalLimiter = SpeedLimiter(config.globalSpeedLimit)

    private val _state = MutableStateFlow(recover(store.load()))
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    private val _queueFinished = MutableSharedFlow<DownloadQueue>(extraBufferCapacity = 8)
    /** Fires when a running queue has nothing left to download (IDM's "when done" actions hook here). */
    val queueFinished: SharedFlow<DownloadQueue> = _queueFinished.asSharedFlow()

    private class Running(val job: Job, val task: DownloadTask)
    private val running = ConcurrentHashMap<String, Running>()
    private val pumpLock = Any()
    private val saveSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) {
            for (ignored in saveSignal) runCatching { store.save(_state.value) }
        }
    }

    val downloads: List<DownloadItem> get() = _state.value.downloads
    fun get(id: String): DownloadItem? = _state.value.downloads.firstOrNull { it.id == id }
    fun isRunning(id: String) = running.containsKey(id)

    // ---------------------------------------------------------------- configuration

    fun updateConfig(newConfig: EngineConfig) {
        val old = config
        config = newConfig
        globalLimiter.bytesPerSecond = newConfig.globalSpeedLimit
        if (!clientProvided && (old.proxy != newConfig.proxy || old.connectTimeoutMillis != newConfig.connectTimeoutMillis ||
                old.readTimeoutMillis != newConfig.readTimeoutMillis)
        ) client = Http.client(newConfig)
        pump()
    }

    fun setGlobalSpeedLimit(bps: Long) = updateConfig(config.copy(globalSpeedLimit = bps.coerceAtLeast(0)))

    fun setSpeedLimit(id: String, bps: Long) {
        mutate(id) { it.copy(speedLimit = bps.coerceAtLeast(0)) }
        when (val t = running[id]?.task) {
            is HttpDownloadTask -> t.setSpeedLimit(bps)
            is HlsDownloadTask -> t.setSpeedLimit(bps)
        }
    }

    suspend fun probe(url: String, headers: Map<String, String> = emptyMap(), referer: String? = null): ProbeResult =
        Prober.probe(client, url, headers, referer, config.userAgent)

    // ---------------------------------------------------------------- adding

    fun add(request: DownloadRequest, start: Boolean = true): DownloadItem {
        val url = request.url.trim()
        require(url.startsWith("http://", true) || url.startsWith("https://", true)) { "Only http and https links are supported" }
        val kind = request.kind ?: if (looksLikeHls(url)) DownloadKind.HLS else DownloadKind.HTTP
        val rawName = request.fileName?.takeIf { it.isNotBlank() }?.let(FileNames::sanitize)
            ?: FileNames.fromUrl(url)?.let(FileNames::sanitize) ?: "download"
        val category = Category.detect(rawName)
        val dir = request.saveDir?.takeIf { it.isNotBlank() } ?: defaultDirFor(category)
        val taken = _state.value.downloads.filter { it.saveDir == dir && it.status != DownloadStatus.COMPLETED }.map { it.fileName }.toSet()
        val name = FileNames.unique(File(dir), rawName, taken)
        val headers = LinkedHashMap(request.headers)
        request.cookies?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
        request.userAgent?.takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
        val item = DownloadItem(
            id = UUID.randomUUID().toString(),
            url = url,
            fileName = name,
            saveDir = dir,
            category = category,
            kind = kind,
            status = if (start) DownloadStatus.QUEUED else DownloadStatus.PAUSED,
            totalBytes = request.sizeHint,
            maxConnections = (request.maxConnections ?: config.defaultConnections).coerceIn(1, 32),
            speedLimit = request.speedLimit,
            headers = headers,
            referer = request.referer,
            description = request.description,
            queueId = request.queueId,
            expectedChecksum = request.expectedChecksum,
            nameLocked = request.fileName != null,
        )
        _state.update { it.copy(downloads = it.downloads + item) }
        _events.tryEmit(EngineEvent.Added(item))
        requestSave()
        pump()
        return item
    }

    fun addBatch(urls: List<String>, template: DownloadRequest, start: Boolean = true): List<DownloadItem> =
        urls.map { add(template.copy(url = it, fileName = null), start) }

    fun defaultDirFor(category: Category): String =
        if (config.categorySubfolders) File(config.defaultSaveDir, category.folder).path else config.defaultSaveDir

    // ---------------------------------------------------------------- control

    fun start(id: String) {
        val item = get(id) ?: return
        if (item.status.isActive || item.status == DownloadStatus.COMPLETED) return
        mutate(id) { it.copy(status = DownloadStatus.QUEUED, error = null) }
        requestSave()
        pump()
    }

    fun startAll() {
        _state.value.downloads.filter { it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED }.forEach { start(it.id) }
    }

    fun pause(id: String) {
        val r = running[id]
        if (r != null) {
            r.job.cancel(PauseSignal())
        } else {
            mutate(id) { if (it.status == DownloadStatus.QUEUED) it.copy(status = DownloadStatus.PAUSED) else it }
            requestSave()
        }
    }

    fun pauseAll() {
        _state.value.downloads.filter { it.status == DownloadStatus.QUEUED || it.status.isActive }.forEach { pause(it.id) }
    }

    /** Removes from the list. With [deleteFile] the downloaded file goes too; partial data always does. */
    fun remove(id: String, deleteFile: Boolean = false) {
        val item = get(id) ?: return
        val r = running.remove(id)
        _state.update { s -> s.copy(downloads = s.downloads.filterNot { it.id == id }) }
        scope.launch {
            r?.job?.cancelAndJoin()
            deletePartials(item)
            if (deleteFile) File(item.saveDir, item.fileName).delete()
        }
        _events.tryEmit(EngineEvent.Removed(item))
        requestSave()
        pump()
    }

    /** Starts over from byte zero (IDM "Redownload"). */
    fun redownload(id: String) {
        val item = get(id) ?: return
        scope.launch {
            running.remove(id)?.job?.cancelAndJoin()
            deletePartials(item)
            mutate(id) {
                it.copy(
                    status = DownloadStatus.QUEUED, downloadedBytes = 0, segments = emptyList(), error = null,
                    completedAt = 0, piecesDone = 0, speedBps = 0, etaSeconds = -1,
                )
            }
            requestSave()
            pump()
        }
    }

    /** Points a download at a fresh link (expired tokens) while keeping what was downloaded. */
    fun refreshAddress(id: String, newUrl: String) {
        mutate(id) { it.copy(url = newUrl.trim(), error = null) }
        requestSave()
    }

    fun rename(id: String, newName: String) {
        val item = get(id) ?: return
        val clean = FileNames.sanitize(newName)
        if (item.status == DownloadStatus.COMPLETED) {
            val from = File(item.saveDir, item.fileName)
            val to = File(item.saveDir, clean)
            if (from.exists() && !to.exists() && from.renameTo(to)) mutate(id) { it.copy(fileName = clean, nameLocked = true) }
        } else if (!item.status.isActive && item.downloadedBytes == 0L) {
            mutate(id) { it.copy(fileName = clean, nameLocked = true) }
        }
        requestSave()
    }

    fun updateDescription(id: String, description: String) {
        mutate(id) { it.copy(description = description) }
        requestSave()
    }

    fun setConnections(id: String, connections: Int) {
        mutate(id) { it.copy(maxConnections = connections.coerceIn(1, 32)) }
        requestSave()
    }

    fun clearCompleted() {
        _state.update { s -> s.copy(downloads = s.downloads.filterNot { it.status == DownloadStatus.COMPLETED }) }
        requestSave()
    }

    // ---------------------------------------------------------------- queues

    val queues: List<DownloadQueue> get() = _state.value.queues

    fun addQueue(name: String, maxConcurrent: Int = 1): DownloadQueue {
        val q = DownloadQueue(UUID.randomUUID().toString(), name.trim().ifBlank { "Queue" }, maxConcurrent.coerceIn(1, 16))
        _state.update { it.copy(queues = it.queues + q) }
        requestSave()
        return q
    }

    fun updateQueue(queue: DownloadQueue) {
        _state.update { s -> s.copy(queues = s.queues.map { if (it.id == queue.id) queue else it }) }
        requestSave()
        pump()
    }

    fun removeQueue(id: String) {
        if (id == EngineState.MAIN_QUEUE_ID) return
        _state.update { s ->
            s.copy(
                queues = s.queues.filterNot { it.id == id },
                downloads = s.downloads.map { if (it.queueId == id) it.copy(queueId = null) else it },
            )
        }
        requestSave()
    }

    fun moveToQueue(ids: Collection<String>, queueId: String?) {
        _state.update { s -> s.copy(downloads = s.downloads.map { if (it.id in ids) it.copy(queueId = queueId) else it }) }
        requestSave()
    }

    fun startQueue(id: String) {
        _state.update { s ->
            s.copy(
                queues = s.queues.map { if (it.id == id) it.copy(running = true) else it },
                downloads = s.downloads.map {
                    if (it.queueId == id && (it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED)) {
                        it.copy(status = DownloadStatus.QUEUED, error = null)
                    } else it
                },
            )
        }
        requestSave()
        pump()
        checkQueueFinished()
    }

    fun stopQueue(id: String) {
        _state.update { s -> s.copy(queues = s.queues.map { if (it.id == id) it.copy(running = false) else it }) }
        _state.value.downloads.filter { it.queueId == id && (it.status == DownloadStatus.QUEUED || it.status.isActive) }.forEach { pause(it.id) }
        requestSave()
    }

    // ---------------------------------------------------------------- lifecycle

    /** Pauses everything and writes state synchronously. Call on app exit. */
    fun shutdown() {
        runBlocking {
            running.values.map { it.job }.forEach { it.cancel(PauseSignal()) }
            running.values.map { it.job }.forEach { runCatching { it.join() } }
        }
        saveNow()
    }

    fun saveNow() = store.save(_state.value)

    // ---------------------------------------------------------------- internals

    private class PauseSignal : CancellationException("paused")

    private fun requestSave() { saveSignal.trySend(Unit) }

    private fun mutate(id: String, f: (DownloadItem) -> DownloadItem) {
        _state.update { s -> s.copy(downloads = s.downloads.map { if (it.id == id) f(it) else it }) }
    }

    private fun recover(s: EngineState): EngineState = s.copy(
        downloads = s.downloads.map {
            if (it.status.isActive || it.status == DownloadStatus.QUEUED) {
                it.copy(status = DownloadStatus.PAUSED, speedBps = 0, etaSeconds = -1, activeConnections = 0)
            } else it.copy(speedBps = 0, etaSeconds = -1, activeConnections = 0)
        },
        queues = s.queues.map { it.copy(running = false) }.let { qs ->
            if (qs.none { it.id == EngineState.MAIN_QUEUE_ID }) listOf(DownloadQueue(EngineState.MAIN_QUEUE_ID, "Main queue", 3)) + qs else qs
        },
    )

    private fun looksLikeHls(url: String) = url.substringBefore('?').substringBefore('#').endsWith(".m3u8", true)

    private fun deletePartials(item: DownloadItem) {
        File(item.saveDir, item.fileName + FileNames.PART_SUFFIX).delete()
        File(item.saveDir, item.fileName + FileNames.PART_SUFFIX + ".parts").deleteRecursively()
    }

    /** Starts as many QUEUED downloads as the global and per-queue limits allow. */
    private fun pump() {
        synchronized(pumpLock) {
            val s = _state.value
            val queuesById = s.queues.associateBy { it.id }
            var free = config.maxConcurrentDownloads.coerceAtLeast(1) - running.size
            if (free <= 0) return
            val activePerQueue = HashMap<String, Int>()
            s.downloads.filter { running.containsKey(it.id) && it.queueId != null }.forEach {
                activePerQueue.merge(it.queueId!!, 1, Int::plus)
            }
            for (item in s.downloads.filter { it.status == DownloadStatus.QUEUED && !running.containsKey(it.id) }) {
                if (free <= 0) break
                val q = item.queueId?.let { queuesById[it] }
                if (q != null && q.running) {
                    val n = activePerQueue[q.id] ?: 0
                    if (n >= q.maxConcurrent) continue
                    activePerQueue[q.id] = n + 1
                }
                launch(item)
                free--
            }
        }
    }

    private fun launch(item: DownloadItem) {
        val id = item.id
        mutate(id) { it.copy(status = DownloadStatus.CONNECTING, lastTryAt = System.currentTimeMillis(), error = null) }
        val callbacks = object : TaskCallbacks {
            override fun onMeta(item: DownloadItem): DownloadItem {
                mutate(id) { cur ->
                    item.copy(status = DownloadStatus.DOWNLOADING, description = cur.description, queueId = cur.queueId, speedLimit = cur.speedLimit)
                }
                requestSave()
                _events.tryEmit(EngineEvent.Started(get(id) ?: item))
                return item
            }

            override fun onProgress(progress: TaskProgress) = applyProgress(id, progress)

            override fun onCheckpoint(progress: TaskProgress) {
                applyProgress(id, progress)
                requestSave()
            }
        }
        val current = get(id) ?: return
        val task: DownloadTask = when (current.kind) {
            DownloadKind.HLS -> HlsDownloadTask(current, config, client, globalLimiter, callbacks)
            DownloadKind.HTTP -> HttpDownloadTask(current, config, client, globalLimiter, callbacks)
        }
        val job = scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val finished = task.run()
                mutate(id) { cur ->
                    finished.copy(
                        status = DownloadStatus.COMPLETED, completedAt = System.currentTimeMillis(), speedBps = 0,
                        etaSeconds = 0, activeConnections = 0, error = null, description = cur.description, queueId = cur.queueId,
                    )
                }
                get(id)?.let { _events.tryEmit(EngineEvent.Completed(it)) }
            } catch (e: CancellationException) {
                val p = task.progress()
                if (get(id) != null) {
                    applyProgress(id, p)
                    mutate(id) { it.copy(status = DownloadStatus.PAUSED, speedBps = 0, etaSeconds = -1, activeConnections = 0) }
                }
            } catch (e: Throwable) {
                val p = task.progress()
                applyProgress(id, p)
                mutate(id) {
                    it.copy(status = DownloadStatus.FAILED, error = describe(e), speedBps = 0, etaSeconds = -1, activeConnections = 0)
                }
                get(id)?.let { _events.tryEmit(EngineEvent.Failed(it)) }
            } finally {
                running.remove(id)
                requestSave()
                pump()
                checkQueueFinished()
            }
        }
        running[id] = Running(job, task)
        job.start()
    }

    private fun applyProgress(id: String, p: TaskProgress) {
        mutate(id) { cur ->
            cur.copy(
                downloadedBytes = p.downloaded,
                totalBytes = if (p.total > 0 || cur.totalBytes <= 0) p.total else cur.totalBytes,
                segments = if (cur.kind == DownloadKind.HTTP && p.segments.isNotEmpty()) p.segments else cur.segments,
                speedBps = p.speedBps,
                etaSeconds = p.etaSeconds,
                activeConnections = p.connections,
                piecesDone = if (cur.kind == DownloadKind.HLS) p.piecesDone else cur.piecesDone,
                piecesTotal = if (cur.kind == DownloadKind.HLS) p.piecesTotal else cur.piecesTotal,
            )
        }
    }

    private fun checkQueueFinished() {
        val s = _state.value
        for (q in s.queues.filter { it.running }) {
            val pending = s.downloads.any {
                it.queueId == q.id && (it.status == DownloadStatus.QUEUED || it.status.isActive)
            }
            if (!pending) {
                _state.update { st -> st.copy(queues = st.queues.map { if (it.id == q.id) it.copy(running = false) else it }) }
                _queueFinished.tryEmit(q)
                requestSave()
            }
        }
    }

    private fun describe(e: Throwable): String = when (e) {
        is HttpStatusException -> when (e.code) {
            401 -> "Login required (401)"
            403 -> "Access denied (403). The link may have expired; refresh the address."
            404 -> "File not found on server (404)"
            410 -> "Link expired (410). Refresh the address."
            416 -> "Server rejected the byte range (416)"
            in 500..599 -> "Server error (${e.code})"
            else -> e.message ?: "HTTP ${e.code}"
        }
        is java.net.UnknownHostException -> "No internet connection or unknown host"
        is java.net.SocketTimeoutException -> "Connection timed out"
        is javax.net.ssl.SSLException -> "Secure connection failed: ${e.message}"
        else -> e.message ?: e::class.java.simpleName
    }
}
