package com.imad.downloader.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/** A byte range being filled by one connection. Fields are guarded by `this`. */
internal class LiveSegment(start: Long, end: Long, downloaded: Long) {
    @Volatile var start: Long = start
    /** Inclusive; -1 while the total size is unknown. Shrinks when another connection takes over the tail. */
    @Volatile var end: Long = end
    @Volatile var pos: Long = start + downloaded
    @Volatile var assigned: Boolean = false

    val done: Boolean get() = end >= 0 && pos > end
    val remaining: Long get() = if (end < 0) Long.MAX_VALUE else (end - pos + 1).coerceAtLeast(0)
    fun snapshot() = SegmentState(start, end, pos - start)
}

/** Progress report from a running task. */
internal data class TaskProgress(
    val downloaded: Long,
    val total: Long,
    val segments: List<SegmentState>,
    val speedBps: Long,
    val etaSeconds: Long,
    val connections: Int,
    val piecesDone: Int = 0,
    val piecesTotal: Int = 0,
)

/** Thrown when the server stops honouring byte ranges in the middle of a download. */
internal class RangeNotSupportedException : IOException("Server does not support resuming")

internal interface TaskCallbacks {
    /** Called once the server has been probed; returns the item with updated metadata. */
    fun onMeta(item: DownloadItem): DownloadItem
    fun onProgress(progress: TaskProgress)
    fun onCheckpoint(progress: TaskProgress)
}

internal interface DownloadTask {
    /** Runs to completion (returns the finished item) or throws. Cancellation = pause. */
    suspend fun run(): DownloadItem
    fun progress(): TaskProgress
}

/**
 * Multi-connection HTTP download with IDM-style dynamic segmentation: every connection that
 * finishes its range steals the back half of the largest remaining range, so all connections
 * stay busy until the very end.
 */
internal class HttpDownloadTask(
    initial: DownloadItem,
    private val config: EngineConfig,
    private val client: OkHttpClient,
    private val globalLimiter: SpeedLimiter,
    private val callbacks: TaskCallbacks,
) : DownloadTask {
    @Volatile private var item = initial
    private val segments = mutableListOf<LiveSegment>()
    private val itemLimiter = SpeedLimiter(initial.speedLimit)
    private val meter = SpeedMeter()
    private val connections = AtomicInteger(0)
    @Volatile private var total: Long = initial.totalBytes

    fun setSpeedLimit(bps: Long) { itemLimiter.bytesPerSecond = bps }

    override fun progress(): TaskProgress {
        val segs = synchronized(segments) { segments.map { it.snapshot() } }
        val downloaded = segs.sumOf { it.downloaded }
        val remaining = if (total > 0) total - downloaded else -1
        return TaskProgress(downloaded, total, segs, meter.bytesPerSecond(), meter.etaSeconds(remaining), connections.get())
    }

    override suspend fun run(): DownloadItem {
        val dir = File(item.saveDir).apply { mkdirs() }
        val probe = Prober.probe(client, item.url, item.headers, item.referer, userAgent())
        val fresh = item.segments.isEmpty() || item.totalBytes != probe.totalBytes || !item.resumable ||
            !probe.resumable || changedOnServer(probe)

        var name = item.fileName
        if (fresh && !item.nameLocked && probe.fileName != name && item.downloadedBytes == 0L) {
            name = FileNames.unique(dir, probe.fileName)
        }
        item = callbacks.onMeta(
            item.copy(
                fileName = name,
                totalBytes = probe.totalBytes,
                resumable = probe.resumable,
                mimeType = probe.mimeType ?: item.mimeType,
                etag = probe.etag,
                lastModified = probe.lastModified,
                category = if (item.category == Category.OTHER) Category.detect(name, probe.mimeType) else item.category,
            ),
        )
        total = probe.totalBytes
        val part = File(dir, item.fileName + FileNames.PART_SUFFIX)

        synchronized(segments) {
            segments.clear()
            if (fresh || !part.exists()) {
                part.delete()
                segments += plan(total, probe.resumable)
            } else {
                item.segments.forEach { segments += LiveSegment(it.start, it.end, it.downloaded) }
            }
        }

        while (true) {
            try {
                transfer(part)
                break
            } catch (e: RangeNotSupportedException) {
                // The server lied about ranges: fall back to one plain stream from the start.
                item = callbacks.onMeta(item.copy(resumable = false))
                synchronized(segments) {
                    segments.clear()
                    segments += LiveSegment(0, if (total >= 0) total - 1 else -1, 0)
                }
            }
        }

        val finalProgress = progress()
        callbacks.onCheckpoint(finalProgress)
        if (total >= 0 && finalProgress.downloaded < total) throw IOException("Download ended early")

        item.expectedChecksum?.let { expected ->
            if (!Checksums.matches(part, expected)) throw IOException("Checksum mismatch")
        }
        val finalName = finalNameFor(dir, item.fileName)
        val target = File(dir, finalName)
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        return item.copy(
            fileName = finalName,
            totalBytes = total,
            downloadedBytes = total,
            segments = finalProgress.segments,
        )
    }

    private suspend fun transfer(part: File) {
        RandomAccessFile(part, "rw").use { raf ->
            if (total > 0 && raf.length() != total) raf.setLength(total)
            val channel = raf.channel
            val workers = if (item.resumable && total > 0) item.maxConnections.coerceIn(1, 32) else 1
            meter.reset()
            coroutineScope {
                val ticker = launch { tick() }
                val jobs = (0 until workers).map { launch { workerLoop(channel) } }
                jobs.forEach { it.join() }
                ticker.cancel()
            }
            channel.force(false)
            if (total < 0) {
                total = synchronized(segments) { segments.sumOf { it.pos - it.start } }
            }
            if (raf.length() != total) raf.setLength(total)
        }
    }

    private fun changedOnServer(p: ProbeResult): Boolean =
        (item.etag != null && p.etag != null && item.etag != p.etag) ||
            (item.lastModified != null && p.lastModified != null && item.lastModified != p.lastModified)

    private fun finalNameFor(dir: File, name: String): String =
        if (config.duplicateAction == DuplicateAction.OVERWRITE || !File(dir, name).exists()) name
        else FileNames.unique(dir, name)

    private fun userAgent() = item.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: config.userAgent

    /** Splits a fresh download into equal ranges, never smaller than [EngineConfig.minSplitBytes]. */
    private fun plan(total: Long, resumable: Boolean): List<LiveSegment> {
        if (!resumable || total <= 0) return listOf(LiveSegment(0, if (total >= 0) total - 1 else -1, 0))
        val count = item.maxConnections.coerceIn(1, 32).toLong()
            .coerceAtMost(maxOf(1L, total / config.minSplitBytes)).toInt()
        return SegmentPlanner.split(total, count).map { LiveSegment(it.first, it.last, 0) }
    }

    /** Picks an idle segment, or splits the largest busy one (dynamic segmentation). */
    private fun nextSegment(): LiveSegment? = synchronized(segments) {
        segments.firstOrNull { !it.assigned && !it.done }?.let { it.assigned = true; return it }
        if (!item.resumable || total <= 0) return null
        val victim = segments.filter { it.assigned && !it.done }.maxByOrNull { it.remaining } ?: return null
        synchronized(victim) {
            val remaining = victim.remaining
            if (remaining < config.minSplitBytes * 2) return null
            val mid = victim.pos + remaining / 2
            val tail = LiveSegment(mid, victim.end, 0).apply { assigned = true }
            victim.end = mid - 1
            segments.add(segments.indexOf(victim) + 1, tail)
            tail
        }
    }

    private suspend fun workerLoop(channel: FileChannel) {
        while (coroutineContext.isActive) {
            val seg = nextSegment() ?: return
            try {
                fetchWithRetry(seg, channel)
            } finally {
                synchronized(segments) { if (!seg.done) seg.assigned = false }
            }
        }
    }

    private suspend fun fetchWithRetry(seg: LiveSegment, channel: FileChannel) {
        var attempt = 0
        while (!seg.done) {
            coroutineContext.ensureActive()
            val before = seg.pos
            try {
                val finished = fetch(seg, channel)
                if (finished) return
            } catch (e: CancellationException) {
                throw e
            } catch (e: RangeNotSupportedException) {
                throw e
            } catch (e: HttpStatusException) {
                if (e.code in FATAL_CODES) throw e
                attempt = retryOrThrow(e, attempt, seg.pos > before)
            } catch (e: IOException) {
                attempt = retryOrThrow(e, attempt, seg.pos > before)
            }
        }
    }

    private suspend fun retryOrThrow(e: IOException, attempt: Int, madeProgress: Boolean): Int {
        val next = if (madeProgress) 0 else attempt + 1
        if (next > config.maxRetries) throw e
        val wait = (config.retryDelayMillis shl (next - 1).coerceIn(0, 5)).coerceAtMost(30_000)
        if (next > 0) delay(wait)
        return next
    }

    /** Streams one connection into [seg]. Returns true when the segment (or the whole file) is done. */
    private suspend fun fetch(seg: LiveSegment, channel: FileChannel): Boolean {
        val resumable = item.resumable && total > 0
        val req = when {
            resumable -> Http.request(item.url, item.headers, item.referer, userAgent(), range = seg.pos..seg.end)
            else -> Http.request(item.url, item.headers, item.referer, userAgent())
        }
        if (!resumable && seg.pos != seg.start) {
            // Server cannot resume: start this single stream over.
            synchronized(seg) { seg.pos = seg.start }
        }
        connections.incrementAndGet()
        try {
            client.newCall(req).await().use { resp ->
                if (resp.code == 416 && seg.done) return true
                if (!resp.isSuccessful) throw HttpStatusException(resp.code, "Server replied ${resp.code}")
                if (resumable && resp.code != 206) {
                    if (seg.pos == 0L && segments.size == 1) {
                        // Fine: the whole body is exactly what we need.
                    } else throw RangeNotSupportedException()
                }
                val source = resp.body.source()
                val buf = ByteArray(config.bufferSize)
                while (true) {
                    coroutineContext.ensureActive()
                    val want = if (seg.end >= 0) minOf(buf.size.toLong(), seg.remaining).toInt() else buf.size
                    if (want <= 0) return true
                    val n = source.read(buf, 0, want)
                    if (n < 0) {
                        if (seg.end < 0) {
                            synchronized(seg) { seg.end = seg.pos - 1 }
                            return true
                        }
                        return seg.done
                    }
                    itemLimiter.acquire(n)
                    globalLimiter.acquire(n)
                    synchronized(seg) {
                        val allowed = if (seg.end >= 0) minOf(n.toLong(), seg.end - seg.pos + 1).toInt() else n
                        if (allowed > 0) {
                            var written = 0
                            val bb = ByteBuffer.wrap(buf, 0, allowed)
                            while (bb.hasRemaining()) written += channel.write(bb, seg.pos + written)
                            seg.pos += allowed
                        }
                    }
                    if (seg.done) return true
                }
            }
        } finally {
            connections.decrementAndGet()
        }
    }

    private suspend fun tick() {
        var lastSave = System.currentTimeMillis()
        while (coroutineContext.isActive) {
            delay(config.progressIntervalMillis)
            val now = System.currentTimeMillis()
            val p0 = progress()
            meter.sample(now, p0.downloaded)
            val p = progress()
            callbacks.onProgress(p)
            if (now - lastSave >= config.saveIntervalMillis) {
                callbacks.onCheckpoint(p)
                lastSave = now
            }
        }
    }

    companion object {
        private val FATAL_CODES = setOf(400, 401, 403, 404, 405, 410, 451)
    }
}

object SegmentPlanner {
    /** Splits [0, total) into [count] contiguous inclusive ranges whose sizes differ by at most one byte. */
    fun split(total: Long, count: Int): List<LongRange> {
        require(total > 0) { "total must be positive" }
        val n = count.coerceIn(1, total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val base = total / n
        val extra = total % n
        val out = ArrayList<LongRange>(n)
        var start = 0L
        for (i in 0 until n) {
            val len = base + if (i < extra) 1 else 0
            out += start until start + len
            start += len
        }
        return out
    }
}
