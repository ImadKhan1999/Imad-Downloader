package com.imad.downloader.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

/** Parsed HLS (m3u8) playlists. */
object Hls {
    data class Variant(val url: String, val bandwidth: Long, val resolution: String?, val name: String?)

    data class Key(val method: String, val uri: String?, val iv: ByteArray?)

    data class Piece(
        val index: Int,
        val url: String,
        val duration: Double,
        val key: Key?,
        val sequence: Long,
        val byteRange: LongRange? = null,
    )

    data class MediaPlaylist(val pieces: List<Piece>, val initUrl: String?, val totalDuration: Double, val isLive: Boolean)

    fun isMaster(text: String) = text.lineSequence().any { it.startsWith("#EXT-X-STREAM-INF") }

    fun parseMaster(text: String, baseUrl: String): List<Variant> {
        val out = mutableListOf<Variant>()
        var pending: Map<String, String>? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> pending = attributes(line.substringAfter(':'))
                line.isNotEmpty() && !line.startsWith("#") && pending != null -> {
                    out += Variant(
                        url = resolve(baseUrl, line),
                        bandwidth = pending["BANDWIDTH"]?.toLongOrNull() ?: 0,
                        resolution = pending["RESOLUTION"],
                        name = pending["NAME"],
                    )
                    pending = null
                }
            }
        }
        return out.sortedByDescending { it.bandwidth }
    }

    fun parseMedia(text: String, baseUrl: String): MediaPlaylist {
        val pieces = mutableListOf<Piece>()
        var key: Key? = null
        var duration = 0.0
        var sequence = 0L
        var initUrl: String? = null
        var byteRange: LongRange? = null
        var lastRangeEnd = 0L
        var ended = false
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0
                line.startsWith("#EXT-X-KEY:") -> {
                    val a = attributes(line.substringAfter(':'))
                    val method = a["METHOD"] ?: "NONE"
                    key = if (method == "NONE") null else Key(
                        method = method,
                        uri = a["URI"]?.let { resolve(baseUrl, it) },
                        iv = a["IV"]?.let { hexToBytes(it) },
                    )
                }
                line.startsWith("#EXT-X-MAP:") -> initUrl = attributes(line.substringAfter(':'))["URI"]?.let { resolve(baseUrl, it) }
                line.startsWith("#EXTINF:") -> duration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    val v = line.substringAfter(':').trim()
                    val len = v.substringBefore('@').toLong()
                    val off = if ('@' in v) v.substringAfter('@').toLong() else lastRangeEnd
                    byteRange = off until off + len
                    lastRangeEnd = off + len
                }
                line == "#EXT-X-ENDLIST" -> ended = true
                line.isNotEmpty() && !line.startsWith("#") -> {
                    pieces += Piece(pieces.size, resolve(baseUrl, line), duration, key, sequence + pieces.size, byteRange)
                    byteRange = null
                    duration = 0.0
                }
            }
        }
        return MediaPlaylist(pieces, initUrl, pieces.sumOf { it.duration }, isLive = !ended)
    }

    /** Parses `KEY=value,KEY2="quoted, value"` attribute lists. */
    fun attributes(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < s.length) {
            val eq = s.indexOf('=', i)
            if (eq < 0) break
            val key = s.substring(i, eq).trim().trimStart(',').trim()
            var j = eq + 1
            val value: String
            if (j < s.length && s[j] == '"') {
                val close = s.indexOf('"', j + 1).let { if (it < 0) s.length else it }
                value = s.substring(j + 1, close)
                j = close + 1
            } else {
                val comma = s.indexOf(',', j).let { if (it < 0) s.length else it }
                value = s.substring(j, comma).trim()
                j = comma
            }
            out[key] = value
            i = j + 1
        }
        return out
    }

    fun resolve(base: String, ref: String): String =
        runCatching { base.toHttpUrl().resolve(ref)?.toString() }.getOrNull() ?: ref

    private fun hexToBytes(hex: String): ByteArray {
        val h = hex.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        return ByteArray(h.length / 2) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun ivForSequence(sequence: Long): ByteArray {
        val iv = ByteArray(16)
        var v = sequence
        for (i in 15 downTo 8) { iv[i] = (v and 0xFF).toByte(); v = v shr 8 }
        return iv
    }

    fun decryptAes128(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /** Output name for a stream: `video.m3u8` -> `video.ts` (or `.mp4` for fMP4 streams). */
    fun outputName(name: String, fmp4: Boolean): String {
        val base = if (name.endsWith(".m3u8", true)) name.dropLast(5) else name.substringBeforeLast('.', name)
        return base + if (fmp4) ".mp4" else ".ts"
    }
}

/** Downloads every piece of an HLS stream in parallel, decrypts AES-128, then joins them in order. */
internal class HlsDownloadTask(
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
                    if (!resp.isSuccessful) throw HttpStatusException(resp.code, "Server replied ${resp.code} for $url")
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
        var url = item.url
        var text = String(fetchBytes(url))
        if (Hls.isMaster(text)) {
            val best = Hls.parseMaster(text, url).firstOrNull() ?: throw IOException("Playlist has no streams")
            url = best.url
            text = String(fetchBytes(url))
        }
        val playlist = Hls.parseMedia(text, url)
        if (playlist.pieces.isEmpty()) throw IOException("Playlist has no media segments")
        pieceCount = playlist.pieces.size

        val dir = File(item.saveDir).apply { mkdirs() }
        var name = item.fileName
        val wanted = Hls.outputName(name, playlist.initUrl != null)
        if (wanted != name && item.piecesDone == 0) name = FileNames.unique(dir, wanted)
        item = callbacks.onMeta(item.copy(fileName = name, resumable = true, kind = DownloadKind.HLS, piecesTotal = pieceCount, category = Category.VIDEO))

        val partsDir = File(dir, item.fileName + FileNames.PART_SUFFIX + ".parts").apply { mkdirs() }
        fun pieceFile(i: Int) = File(partsDir, "%06d.seg".format(i))

        playlist.pieces.forEach { p ->
            val f = pieceFile(p.index)
            if (f.exists()) { done.incrementAndGet(); bytes.addAndGet(f.length()) }
        }
        meter.reset()
        val semaphore = Semaphore(item.maxConnections.coerceIn(1, 16))
        coroutineScope {
            val ticker = launch {
                while (isActive) {
                    delay(config.progressIntervalMillis)
                    meter.sample(System.currentTimeMillis(), bytes.get())
                    val p = progress()
                    callbacks.onProgress(p)
                }
            }
            val jobs = playlist.pieces.filter { !pieceFile(it.index).exists() }.map { piece ->
                launch {
                    semaphore.withPermit {
                        active.incrementAndGet()
                        try {
                            var data = fetchBytes(piece.url, piece.byteRange)
                            piece.key?.let { k ->
                                if (k.method != "AES-128") throw IOException("Unsupported encryption ${k.method}")
                                val keyBytes = keys.getOrPut(k.uri ?: "") { fetchBytes(k.uri ?: throw IOException("Key URI missing")) }
                                data = Hls.decryptAes128(data, keyBytes, k.iv ?: Hls.ivForSequence(piece.sequence))
                            }
                            itemLimiter.acquire(data.size)
                            globalLimiter.acquire(data.size)
                            val tmp = File(partsDir, pieceFile(piece.index).name + ".tmp")
                            tmp.writeBytes(data)
                            tmp.renameTo(pieceFile(piece.index))
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
        val part = File(dir, item.fileName + FileNames.PART_SUFFIX)
        part.outputStream().buffered().use { out ->
            playlist.initUrl?.let { out.write(fetchBytes(it)) }
            for (p in playlist.pieces) {
                coroutineContext.ensureActive()
                pieceFile(p.index).inputStream().use { it.copyTo(out) }
            }
        }
        partsDir.deleteRecursively()
        val target = File(dir, item.fileName)
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) { part.copyTo(target, true); part.delete() }
        val size = target.length()
        return item.copy(totalBytes = size, downloadedBytes = size, piecesDone = pieceCount, piecesTotal = pieceCount)
    }
}
