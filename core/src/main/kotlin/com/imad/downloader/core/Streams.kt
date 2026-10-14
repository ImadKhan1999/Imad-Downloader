package com.imad.downloader.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** One choice in the quality picker. [height] 0 = audio only / unknown resolution. */
data class StreamQuality(val height: Int, val bandwidth: Long, val label: String, val separateAudio: Boolean)

/** A list of pieces that join into one file (video, audio, or both muxed together). */
internal data class StreamTrack(
    val role: String,
    val initUrl: String?,
    val initRange: LongRange?,
    val pieces: List<Hls.Piece>,
    val fmp4: Boolean,
    val extension: String,
)

/** Reads HLS / DASH manifests and turns a quality choice into tracks to download. */
internal object StreamResolver {
    suspend fun fetchText(client: OkHttpClient, url: String, headers: Map<String, String>, referer: String?, ua: String): String {
        client.newCall(Http.request(url, headers, referer, ua)).await().use { resp ->
            if (!resp.isSuccessful) throw HttpStatusException(resp.code, "Server replied ${resp.code} for the stream manifest")
            return resp.body.string()
        }
    }

    fun isDash(url: String, text: String) = url.substringBefore('?').endsWith(".mpd", true) || text.trimStart().let { it.startsWith("<?xml") || it.startsWith("<MPD") } && "<MPD" in text

    fun qualitiesFromHls(text: String, url: String): List<StreamQuality> {
        if (!Hls.isMaster(text)) return emptyList()
        return Hls.parseMaster(text, url)
            .groupBy { if (it.height > 0) it.height else -(it.bandwidth / 1000).toInt() }
            .map { (_, vs) -> vs.maxBy { it.bandwidth } }
            .sortedByDescending { it.bandwidth }
            .map { v -> StreamQuality(v.height, v.bandwidth, label(v.height, v.bandwidth), v.audioUrl != null) }
    }

    fun qualitiesFromDash(m: Dash.Manifest): List<StreamQuality> {
        val hasAudio = m.audios.isNotEmpty()
        return m.videos.groupBy { it.height }.map { (_, rs) -> rs.maxBy { it.bandwidth } }
            .sortedByDescending { it.bandwidth }
            .map { StreamQuality(it.height, it.bandwidth, label(it.height, it.bandwidth), hasAudio) }
            .ifEmpty { m.audios.take(1).map { StreamQuality(0, it.bandwidth, "Audio " + label(0, it.bandwidth), false) } }
    }

    private fun label(height: Int, bandwidth: Long) = when {
        height > 0 -> "${height}p"
        bandwidth > 0 -> if (bandwidth >= 1_000_000) "%.1f Mbps".format(bandwidth / 1e6) else "${bandwidth / 1000} kbps"
        else -> "Default"
    }

    /** Picks the best stream at or below [height] (0 = best available). */
    fun <T> pick(items: List<T>, height: Int, h: (T) -> Int, bw: (T) -> Long): T? {
        if (items.isEmpty()) return null
        if (height <= 0) return items.maxBy(bw)
        return items.filter { h(it) in 1..height }.maxByOrNull(bw) ?: items.minBy { if (h(it) > 0) h(it) else Int.MAX_VALUE }
    }

    suspend fun tracks(client: OkHttpClient, url: String, headers: Map<String, String>, referer: String?, ua: String, height: Int): List<StreamTrack> {
        val text = fetchText(client, url, headers, referer, ua)
        if (isDash(url, text)) {
            val m = Dash.parse(text, url)
            val video = pick(m.videos.ifEmpty { m.representations.filter { it.type == Dash.Type.OTHER } }, height, { it.height }, { it.bandwidth })
            val audio = m.audios.maxByOrNull { it.bandwidth }
            return listOfNotNull(video, audio).map { r ->
                val ext = when {
                    r.isWebm -> "webm"
                    r.type == Dash.Type.AUDIO && video == null -> "m4a"
                    else -> "mp4"
                }
                StreamTrack(
                    if (r === audio) "audio" else "video", r.initUrl, r.initRange,
                    r.pieces.mapIndexed { i, (u, range) -> Hls.Piece(i, u, 0.0, null, i.toLong(), range) },
                    fmp4 = !r.isWebm, extension = ext,
                )
            }
        }
        var mediaUrl = url
        var mediaText = text
        var audioUrl: String? = null
        if (Hls.isMaster(text)) {
            val v = pick(Hls.parseMaster(text, url), height, { it.height }, { it.bandwidth }) ?: throw IOException("Playlist has no streams")
            mediaUrl = v.url
            audioUrl = v.audioUrl
            mediaText = fetchText(client, mediaUrl, headers, referer, ua)
        }
        val main = Hls.parseMedia(mediaText, mediaUrl)
        if (main.isLive) throw IOException("Live streams are not supported yet")
        if (main.pieces.isEmpty()) throw IOException("Playlist has no media segments")
        val out = mutableListOf(StreamTrack("video", main.initUrl, main.initRange, main.pieces, main.initUrl != null, if (main.initUrl != null) "mp4" else "ts"))
        if (audioUrl != null && audioUrl != mediaUrl) {
            val a = Hls.parseMedia(fetchText(client, audioUrl, headers, referer, ua), audioUrl)
            if (a.pieces.isNotEmpty()) {
                out += StreamTrack("audio", a.initUrl, a.initRange, a.pieces, a.initUrl != null, if (a.initUrl != null) "m4a" else "aac")
            }
        }
        return out
    }
}

/**
 * Downloads HLS and DASH streams: every piece in parallel (AES-128 decrypted), each track
 * joined in order, and a separate audio track merged into the video when both are fMP4.
 */
internal class StreamDownloadTask(
    initial: DownloadItem,
    private val config: EngineConfig,
    private val client: OkHttpClient,
    private val globalLimiter: SpeedLimiter,
    private val callbacks: TaskCallbacks,
) : DownloadTask {
    @Volatile private var item = initial
    private val itemLimiter = SpeedLimiter(initial.speedLimit)
    private val meter = SpeedMeter()
    private val bytes = AtomicLong(0)
    private val done = AtomicInteger(0)
    private val active = AtomicInteger(0)
    @Volatile private var pieceCount = 0
    private val keys = ConcurrentHashMap<String, ByteArray>()

    fun setSpeedLimit(bps: Long) { itemLimiter.bytesPerSecond = bps }

    override fun progress(): TaskProgress {
        val d = done.get()
        val total = if (d > 0 && pieceCount > 0) bytes.get() * pieceCount / d else -1
        val remaining = if (total > 0) total - bytes.get() else -1
        return TaskProgress(bytes.get(), total, emptyList(), meter.bytesPerSecond(), meter.etaSeconds(remaining), active.get(), d, pieceCount)
    }

    private fun ua() = item.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: config.userAgent

    private suspend fun fetchBytes(url: String, range: LongRange? = null): ByteArray {
        var attempt = 0
        while (true) {
            try {
                client.newCall(Http.request(url, item.headers, item.referer, ua(), range = range)).await().use { resp ->
                    if (!resp.isSuccessful) throw HttpStatusException(resp.code, "Server replied ${resp.code} for a stream piece")
                    return resp.body.bytes()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (e is HttpStatusException && e.code in setOf(401, 403, 404, 410)) throw e
                attempt++
                if (attempt > config.maxRetries) throw e
                delay((config.retryDelayMillis shl (attempt - 1).coerceAtMost(5)).coerceAtMost(30_000))
            }
        }
    }

    override suspend fun run(): DownloadItem {
        var tracks: List<StreamTrack>? = null
        var attempt = 0
        while (tracks == null) {
            try {
                tracks = StreamResolver.tracks(client, item.url, item.headers, item.referer, ua(), item.streamHeight)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpStatusException) {
                if (e.code in setOf(401, 403, 404, 410) || ++attempt > config.maxRetries) throw e
                delay((config.retryDelayMillis shl (attempt - 1).coerceAtMost(5)).coerceAtMost(30_000))
            }
        }
        val video = tracks.first()
        val audio = tracks.getOrNull(1)
        pieceCount = tracks.sumOf { it.pieces.size }

        val dir = File(item.saveDir).apply { mkdirs() }
        val outExt = when {
            audio != null && video.fmp4 && audio.fmp4 -> "mp4"
            else -> video.extension
        }
        var name = item.fileName
        val base = when {
            name.endsWith(".m3u8", true) -> name.dropLast(5)
            name.endsWith(".mpd", true) -> name.dropLast(4)
            name.substringAfterLast('.', "").lowercase() in setOf("mp4", "ts", "webm", "m4a", "aac", "mkv") -> name.substringBeforeLast('.')
            else -> name
        }
        val wanted = "$base.$outExt"
        if (wanted != name && item.piecesDone == 0) name = FileNames.unique(dir, wanted)
        item = callbacks.onMeta(item.copy(fileName = name, resumable = true, piecesTotal = pieceCount, category = Category.VIDEO))

        val partsDir = File(dir, item.fileName + FileNames.PART_SUFFIX + ".parts").apply { mkdirs() }
        fun pieceFile(t: Int, i: Int) = File(partsDir, "t$t-%06d.seg".format(i))

        tracks.forEachIndexed { t, tr ->
            tr.pieces.forEach { p ->
                val f = pieceFile(t, p.index)
                if (f.exists()) { done.incrementAndGet(); bytes.addAndGet(f.length()) }
            }
        }
        meter.reset()
        val semaphore = Semaphore(item.maxConnections.coerceIn(1, 16))
        coroutineScope {
            val ticker = launch {
                while (isActive) {
                    delay(config.progressIntervalMillis)
                    meter.sample(System.currentTimeMillis(), bytes.get())
                    callbacks.onProgress(progress())
                }
            }
            val jobs = tracks.flatMapIndexed { t, tr -> tr.pieces.map { t to it } }
                .filter { (t, p) -> !pieceFile(t, p.index).exists() }
                .map { (t, piece) ->
                    launch {
                        semaphore.withPermit {
                            active.incrementAndGet()
                            try {
                                var data = fetchBytes(piece.url, piece.byteRange)
                                piece.key?.let { k ->
                                    if (k.method != "AES-128") throw IOException("This stream is protected (${k.method}) and cannot be downloaded")
                                    val keyBytes = keys.getOrPut(k.uri ?: "") { fetchBytes(k.uri ?: throw IOException("Key URI missing")) }
                                    data = Hls.decryptAes128(data, keyBytes, k.iv ?: Hls.ivForSequence(piece.sequence))
                                }
                                itemLimiter.acquire(data.size)
                                globalLimiter.acquire(data.size)
                                val tmp = File(partsDir, pieceFile(t, piece.index).name + ".tmp")
                                tmp.writeBytes(data)
                                tmp.renameTo(pieceFile(t, piece.index))
                                bytes.addAndGet(data.size.toLong())
                                done.incrementAndGet()
                            } finally {
                                active.decrementAndGet()
                            }
                        }
                    }
                }
            jobs.forEach { it.join() }
            ticker.cancel()
        }
        callbacks.onProgress(progress())

        // Join each track's pieces into one file.
        val joined = tracks.mapIndexed { t, tr ->
            val f = File(partsDir, "track$t.${tr.extension}")
            f.outputStream().buffered(1 shl 16).use { out ->
                tr.initUrl?.let { out.write(fetchBytes(it, tr.initRange)) }
                for (p in tr.pieces) {
                    coroutineContext.ensureActive()
                    pieceFile(t, p.index).inputStream().use { it.copyTo(out) }
                }
            }
            f
        }

        val target = File(dir, item.fileName)
        if (target.exists()) target.delete()
        var note: String? = null
        if (audio == null) {
            move(joined[0], target)
        } else {
            val merged = File(partsDir, "merged.mp4")
            val ok = video.fmp4 && audio.fmp4 && runCatching { Mp4Merge.merge(joined[0], joined[1], merged) }.isSuccess
            if (ok) {
                move(merged, target)
            } else {
                // Cannot join these formats without re-encoding: keep both files side by side.
                move(joined[0], target)
                val audioFile = File(dir, FileNames.unique(dir, target.nameWithoutExtension + ".audio." + audio.extension))
                move(joined[1], audioFile)
                note = "Audio saved separately as ${audioFile.name}"
            }
        }
        partsDir.deleteRecursively()
        val size = target.length()
        return item.copy(
            totalBytes = size, downloadedBytes = size, piecesDone = pieceCount, piecesTotal = pieceCount,
            description = note?.let { if (item.description.isBlank()) it else item.description + " - " + it } ?: item.description,
        )
    }

    private fun move(from: File, to: File) {
        if (!from.renameTo(to)) { from.copyTo(to, true); from.delete() }
    }
}
