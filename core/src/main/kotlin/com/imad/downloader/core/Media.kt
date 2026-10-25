package com.imad.downloader.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.net.URI

/** One downloadable rendition of a video page, as yt-dlp reports it. */
@Serializable
data class MediaFormat(
    val id: String,
    val ext: String,
    val url: String,
    val protocol: String = "https",
    val height: Int = 0,
    val width: Int = 0,
    val fps: Double = 0.0,
    val vcodec: String = "none",
    val acodec: String = "none",
    val tbr: Double = 0.0,
    val fileSize: Long = -1,
    val headers: Map<String, String> = emptyMap(),
    val note: String = "",
) {
    val hasVideo: Boolean get() = vcodec != "none" && vcodec.isNotBlank()
    val hasAudio: Boolean get() = acodec != "none" && acodec.isNotBlank()
    val isHls: Boolean get() = protocol.startsWith("m3u8")
    val isPlainHttp: Boolean get() = protocol == "https" || protocol == "http"
    /** Fragmented MP4 tracks can be joined by [Mp4Merge]. */
    val mp4Family: Boolean get() = ext in setOf("mp4", "m4a", "m4v")
    val isDrc: Boolean get() = id.endsWith("-drc")
}

/** Everything known about a video page. */
@Serializable
data class MediaInfo(
    val title: String,
    val pageUrl: String,
    val extractor: String = "",
    val durationSeconds: Double = 0.0,
    val thumbnail: String? = null,
    val formats: List<MediaFormat> = emptyList(),
)

/** A line in the quality picker. [formatSpec] is "video+audio" or a single format id. */
@Serializable
data class MediaOption(
    val label: String,
    val height: Int,
    val formatSpec: String,
    val extension: String,
    val sizeEstimate: Long,
    val audioOnly: Boolean = false,
)

/** One video of a playlist (title and page only; formats are read when it downloads). */
@Serializable
data class PlaylistEntry(val url: String, val title: String, val durationSeconds: Double = 0.0)

/** A playlist page (YouTube playlists and the like). */
@Serializable
data class PlaylistInfo(val title: String, val entries: List<PlaylistEntry>)

/** One quality for a whole playlist: each video takes the best format up to [height]. */
data class PlaylistQuality(val label: String, val height: Int, val audioOnly: Boolean = false) {
    /** What goes into [DownloadRequest.mediaFormat] (null = pick by height). */
    val mediaFormat: String? get() = if (audioOnly) MediaChoice.AUDIO_ONLY else null

    companion object {
        val all = listOf(
            PlaylistQuality("Best", 0), PlaylistQuality("1080p", 1080), PlaylistQuality("720p", 720),
            PlaylistQuality("480p", 480), PlaylistQuality("360p", 360), PlaylistQuality("Audio only", 0, audioOnly = true),
        )
        val default = all[1]
    }
}

/** Finds the real media behind a page URL (YouTube, Vimeo, X, Facebook, ...). */
interface MediaExtractor {
    suspend fun extract(url: String, headers: Map<String, String> = emptyMap()): MediaInfo

    /** The videos of a playlist page, without reading each one. */
    suspend fun extractPlaylist(url: String, headers: Map<String, String> = emptyMap()): PlaylistInfo =
        throw IOException("Playlists are not supported here")
}

/** Reads `yt-dlp -J` output. Shared by the Windows (yt-dlp.exe) and Android (youtubedl-android) extractors. */
object YtDlpJson {
    private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

    fun parse(text: String): MediaInfo {
        // yt-dlp may print warnings before the JSON line.
        val start = text.indexOf('{')
        if (start < 0) throw IOException("No video information found")
        val root = json.parseToJsonElement(text.substring(start)) as? JsonObject ?: throw IOException("No video information found")
        if (root.str("_type") == "playlist") {
            val first = (root["entries"] as? JsonArray)?.firstOrNull() as? JsonObject ?: throw IOException("Playlists are not supported yet")
            return fromObject(first)
        }
        return fromObject(root)
    }

    /** Reads `yt-dlp -J --flat-playlist` output; private and deleted videos are left out. */
    fun parsePlaylist(text: String): PlaylistInfo {
        val start = text.indexOf('{')
        if (start < 0) throw IOException("No playlist found")
        val root = json.parseToJsonElement(text.substring(start)) as? JsonObject ?: throw IOException("No playlist found")
        val entries = (root["entries"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val title = o.str("title").orEmpty()
            if (title == "[Private video]" || title == "[Deleted video]") return@mapNotNull null
            val url = o.str("url")?.takeIf { it.startsWith("http") }
                ?: o.str("webpage_url")
                ?: o.str("id")?.takeIf { (o.str("ie_key") ?: o.str("extractor_key") ?: "Youtube").startsWith("Youtube") }?.let { "https://www.youtube.com/watch?v=$it" }
                ?: return@mapNotNull null
            PlaylistEntry(url, title.ifBlank { "video" }, o.num("duration") ?: 0.0)
        }
        if (entries.isEmpty()) throw IOException("This playlist has no videos that can be downloaded")
        return PlaylistInfo(root.str("title")?.takeIf { it.isNotBlank() } ?: "Playlist", entries)
    }

    private fun format(o: JsonObject, fallbackHeaders: Map<String, String>): MediaFormat? {
        val url = o.str("url") ?: return null
        val headers = (o["http_headers"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap()
            ?: fallbackHeaders
        val proto = o.str("protocol") ?: "https"
        // Formats that need special fetchers (fragmented DASH, RTMP, ...) are skipped.
        if (proto !in setOf("https", "http", "m3u8", "m3u8_native")) return null
        return MediaFormat(
            id = o.str("format_id") ?: return null,
            ext = o.str("ext") ?: "mp4",
            url = url,
            protocol = proto,
            height = o.long("height")?.toInt() ?: 0,
            width = o.long("width")?.toInt() ?: 0,
            fps = o.num("fps") ?: 0.0,
            // Missing codec info (archive.org, many small sites): guess from the extension.
            vcodec = o.str("vcodec") ?: if ((o.str("ext") ?: "") in AUDIO_EXT) "none" else "unknown",
            acodec = o.str("acodec") ?: "unknown",
            tbr = o.num("tbr") ?: 0.0,
            fileSize = o.long("filesize") ?: o.long("filesize_approx") ?: -1,
            headers = headers,
            note = o.str("format_note") ?: "",
        )
    }

    private fun fromObject(o: JsonObject): MediaInfo {
        val headers = (o["http_headers"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()
        var formats = (o["formats"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { f -> format(f, headers) } }
        if (formats.isEmpty()) {
            // Single-file sites: the info dict itself is the format.
            format(o, headers)?.let { f ->
                formats = listOf(if (f.vcodec == "none" && f.acodec == "none") f.copy(vcodec = "unknown", acodec = "unknown") else f)
            }
        }
        if (formats.isEmpty()) throw IOException("No downloadable formats found")
        return MediaInfo(
            title = o.str("title")?.takeIf { it.isNotBlank() } ?: "video",
            pageUrl = o.str("webpage_url") ?: o.str("original_url") ?: "",
            extractor = o.str("extractor_key") ?: o.str("extractor") ?: "",
            durationSeconds = o.num("duration") ?: 0.0,
            thumbnail = o.str("thumbnail"),
            formats = formats,
        )
    }
}

/** Turns a format list into the quality picker. */
object MediaChoice {
    private fun codecRank(f: MediaFormat) = when {
        f.vcodec.startsWith("avc") -> 0 // plays everywhere
        f.vcodec.startsWith("hev") || f.vcodec.startsWith("hvc") -> 1
        f.vcodec.startsWith("av01") -> 2
        else -> 3
    }

    private fun size(f: MediaFormat?, duration: Double): Long = when {
        f == null -> 0
        f.fileSize > 0 -> f.fileSize
        f.tbr > 0 && duration > 0 -> (f.tbr * 1000 / 8 * duration).toLong()
        else -> 0
    }

    fun options(info: MediaInfo): List<MediaOption> {
        val d = info.durationSeconds
        val audioAll = info.formats.filter { it.hasAudio && !it.hasVideo }
        // Direct files first; streamed audio (SoundCloud, ...) only when nothing else exists.
        val audio = audioAll.filter { it.isPlainHttp }.ifEmpty { audioAll.filter { it.isHls } }
        // YouTube also offers "-drc" (loudness-compressed) copies of each track; prefer the normal one.
        val bestM4a = audio.filter { it.mp4Family && it.isPlainHttp }.sortedWith(compareBy<MediaFormat> { it.isDrc }.thenByDescending { it.tbr }).firstOrNull()
        val out = LinkedHashMap<Int, MediaOption>()

        // Video-only mp4 + m4a audio, joined after download (YouTube style).
        if (bestM4a != null) {
            info.formats.filter { it.hasVideo && !it.hasAudio && it.mp4Family && it.isPlainHttp && it.height > 0 }
                .groupBy { it.height }
                .forEach { (h, fs) ->
                    val v = fs.sortedWith(compareBy<MediaFormat> { codecRank(it) }.thenByDescending { it.tbr }).first()
                    out[h] = MediaOption("${h}p", h, "${v.id}+${bestM4a.id}", "mp4", size(v, d) + size(bestM4a, d))
                }
        }
        // Formats that already contain sound (progressive files and HLS).
        info.formats.filter { it.hasVideo && it.hasAudio }.groupBy { it.height }.forEach { (h, fs) ->
            val f = fs.sortedWith(compareBy<MediaFormat> { if (it.isPlainHttp) 0 else 1 }.thenBy { codecRank(it) }.thenByDescending { it.tbr }).first()
            if (h !in out) {
                val ext = if (f.isHls) "mp4" else f.ext
                out[h] = MediaOption(if (h > 0) "${h}p" else "Video", h, f.id, ext, size(f, d))
            }
        }
        // Sites that do not say what a format contains.
        if (out.isEmpty()) {
            info.formats.filter { it.vcodec == "unknown" }.maxByOrNull { it.tbr }?.let { f ->
                out[f.height] = MediaOption(if (f.height > 0) "${f.height}p" else "Video", f.height, f.id, f.ext, size(f, d))
            }
        }
        val video = out.values.sortedByDescending { it.height }
        val audioOpt = (bestM4a ?: audio.maxByOrNull { it.tbr })?.let {
            listOf(MediaOption("Audio only (${it.ext})", 0, it.id, it.ext, size(it, d), audioOnly = true))
        }.orEmpty()
        return video + audioOpt
    }

    /**
     * The quality selected first in a phone's download sheet: the best video up to [maxHeight]
     * (1080p: plays smoothly on phones and keeps the file a sensible size), else the smallest one.
     */
    fun defaultOption(options: List<MediaOption>, maxHeight: Int = 1080): MediaOption? {
        val videos = options.filter { !it.audioOnly }
        return videos.filter { it.height in 1..maxHeight }.maxByOrNull { it.height }
            ?: videos.minByOrNull { it.height } ?: options.firstOrNull()
    }

    /** Picks the option matching [height] (0 = best) from a fresh format list, e.g. on resume. */
    fun pick(info: MediaInfo, spec: String?, height: Int): MediaOption? {
        val opts = options(info)
        if (spec == AUDIO_ONLY) return opts.firstOrNull { it.audioOnly } ?: opts.firstOrNull()
        if (spec != null) {
            val ids = spec.split('+')
            if (ids.all { id -> info.formats.any { it.id == id } }) {
                return opts.firstOrNull { it.formatSpec == spec } ?: MediaOption("custom", height, spec, "mp4", 0)
            }
        }
        val videos = opts.filter { !it.audioOnly }
        if (videos.isEmpty()) return opts.firstOrNull()
        if (height <= 0) return videos.first()
        return videos.filter { it.height in 1..height }.maxByOrNull { it.height } ?: videos.last()
    }

    private val known = listOf(
        "youtube.com", "youtu.be", "vimeo.com", "dailymotion.com", "facebook.com", "fb.watch", "instagram.com",
        "x.com", "twitter.com", "tiktok.com", "twitch.tv", "soundcloud.com", "reddit.com", "bilibili.com",
        "streamable.com", "rumble.com", "ok.ru", "vk.com", "pinterest.com", "linkedin.com", "bandcamp.com",
        // More of the sites yt-dlp reads (the same engine and site list ytdlnis uses); any other page is tried too.
        "threads.net", "threads.com", "pin.it", "dai.ly", "snapchat.com", "kick.com", "bsky.app", "9gag.com", "imgur.com", "tumblr.com", "audiomack.com", "mixcloud.com", "likee.video", "kwai.com", "douyin.com", "weibo.com", "bilibili.tv", "nicovideo.jp", "odysee.com", "bitchute.com", "rutube.ru", "loom.com", "ted.com", "coub.com", "vidio.com", "zee5.com", "mxplayer.in",
    )

    /**
     * One download per chosen playlist video, all in [quality]. They keep [base]'s cookies,
     * referer, folder and queue; the exact format is chosen when each one starts.
     */
    fun playlistRequests(base: DownloadRequest, entries: List<PlaylistEntry>, quality: PlaylistQuality): List<DownloadRequest> =
        entries.map { e ->
            base.copy(
                url = e.url,
                fileName = safeTitle(e.title) + if (quality.audioOnly) ".m4a" else ".mp4",
                description = e.title,
                kind = DownloadKind.MEDIA,
                mediaFormat = quality.mediaFormat,
                streamHeight = quality.height,
                sizeHint = -1,
            )
        }

    /** YouTube player clients tried when the normal one hits YouTube's bot check (see the yt-dlp bridges). */
    val YOUTUBE_FALLBACK_CLIENTS = listOf("tv_embedded", "android_vr", "mweb", "ios", "tv_simply")

    fun isKnownYouTube(url: String): Boolean {
        val host = runCatching { URI(url.trim()).host?.lowercase() }.getOrNull() ?: return false
        return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
    }

    /** yt-dlp messages that another YouTube player client may get past. */
    fun isBotCheck(message: String): Boolean {
        val m = message.lowercase()
        return "not a bot" in m || "sign in" in m || "page needs to be reloaded" in m || "requested format is not available" in m ||
            "signed-in" in m || "signed in" in m
    }

    /** YouTube Music pages: the sound is what people want, so audio comes first. */
    fun isMusicSite(url: String): Boolean {
        val host = runCatching { URI(url.trim()).host?.lowercase() }.getOrNull() ?: return false
        return host == "music.youtube.com" || host == "soundcloud.com" || host.endsWith(".soundcloud.com") ||
            host.endsWith("bandcamp.com") || host == "audiomack.com" || host == "mixcloud.com" || host.endsWith(".mixcloud.com")
    }

    /** [DownloadRequest.mediaFormat] for "just the sound" (playlists in audio-only mode). */
    const val AUDIO_ONLY = "audio-only"

    /** YouTube playlist pages, and videos opened from a playlist (their URL carries `list=`). */
    fun isPlaylist(url: String): Boolean {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val host = u.host?.lowercase() ?: return false
        if (host != "youtube.com" && !host.endsWith(".youtube.com") && host != "youtu.be") return false
        return u.rawQuery?.split('&')?.any { it.startsWith("list=") && it.length > 5 } == true
    }

    /** False for YouTube pages that are not a single video (home, search, channels), true elsewhere. */
    fun isVideoPage(url: String): Boolean {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val host = u.host?.lowercase() ?: return false
        val path = u.path.orEmpty()
        return when {
            host == "youtu.be" -> path.length > 1
            host == "youtube.com" || host.endsWith(".youtube.com") ->
                (path == "/watch" && u.rawQuery?.contains("v=") == true) || (path == "/playlist" && isPlaylist(url)) ||
                    listOf("/shorts/", "/live/", "/embed/").any { path.startsWith(it) && path.length > it.length }
            else -> true
        }
    }

    /** Pages that are certainly video sites (others are tried when they return HTML). */
    fun isKnownSite(url: String): Boolean {
        val host = runCatching { URI(url.trim()).host?.lowercase() }.getOrNull() ?: return false
        return known.any { host == it || host.endsWith(".$it") }
    }

    fun safeTitle(title: String): String = FileNames.sanitize(title.replace(Regex("""\s+"""), " ").take(150))
}

/**
 * Downloads a video page through a [MediaExtractor]: fresh URLs on every run (they expire), each
 * part with the normal multi-connection downloader (resume kept per part), then video and audio
 * are joined into one MP4.
 */
internal class MediaDownloadTask(
    initial: DownloadItem,
    private val config: EngineConfig,
    private val client: OkHttpClient,
    private val globalLimiter: SpeedLimiter,
    private val callbacks: TaskCallbacks,
    private val extractor: MediaExtractor,
) : DownloadTask {
    @Volatile private var item = initial
    @Volatile private var current: DownloadTask? = null
    @Volatile private var parts: MutableList<PartState> = initial.parts.toMutableList()
    private val meter = SpeedMeter()

    override fun progress(): TaskProgress {
        val sub = current?.progress()
        val doneBytes = parts.filter { it.done }.sumOf { it.totalBytes.coerceAtLeast(0) }
        val now = doneBytes + (sub?.downloaded ?: 0)
        val knownTotal = parts.sumOf { it.totalBytes.coerceAtLeast(0) }
        val total = if (parts.isNotEmpty() && parts.all { it.totalBytes > 0 }) knownTotal else -1
        return TaskProgress(
            now, total, sub?.segments.orEmpty(), sub?.speedBps ?: 0,
            if (total > 0 && (sub?.speedBps ?: 0) > 0) (total - now) / sub!!.speedBps else -1,
            sub?.connections ?: 0,
        )
    }

    override suspend fun run(): DownloadItem {
        val info = extractor.extract(item.url, item.headers)
        val option = MediaChoice.pick(info, item.mediaFormat, item.streamHeight) ?: throw IOException("No downloadable video found on this page")
        val ids = option.formatSpec.split('+')
        val formats = ids.map { id -> info.formats.firstOrNull { it.id == id } ?: throw IOException("Format $id is no longer offered") }

        val dir = File(item.saveDir).apply { mkdirs() }
        val merged = formats.size > 1
        val ext = if (merged) "mp4" else if (formats[0].isHls) "mp4" else formats[0].ext
        var name = item.fileName
        if (!item.nameLocked || name.substringAfterLast('.', "").lowercase() != ext) {
            val base = if (item.nameLocked) name.substringBeforeLast('.') else MediaChoice.safeTitle(info.title)
            if (item.downloadedBytes == 0L) name = FileNames.unique(dir, "$base.$ext")
        }
        val partsDir = File(dir, name + FileNames.PART_SUFFIX + ".parts").apply { mkdirs() }
        // Keep finished/started parts only if they are still the same formats.
        parts = formats.mapIndexed { i, f ->
            parts.firstOrNull { it.formatId == f.id } ?: PartState(f.id, "part$i.${if (f.isHls) "ts" else f.ext}")
        }.toMutableList()
        item = callbacks.onMeta(
            item.copy(
                fileName = name, mediaFormat = option.formatSpec, parts = parts.toList(), resumable = true,
                category = if (option.audioOnly) Category.MUSIC else Category.VIDEO,
                description = item.description.ifBlank { info.title },
            ),
        )

        for ((i, f) in formats.withIndex()) {
            val part = parts[i]
            if (part.done && File(partsDir, part.fileName).exists()) continue
            val sub = DownloadItem(
                id = "${item.id}-p$i", url = f.url, fileName = part.fileName, saveDir = partsDir.path,
                kind = if (f.isHls) DownloadKind.HLS else DownloadKind.HTTP,
                totalBytes = part.totalBytes, downloadedBytes = part.downloadedBytes, resumable = part.resumable,
                segments = part.segments, maxConnections = item.maxConnections, speedLimit = item.speedLimit,
                headers = item.headers + f.headers, referer = item.referer, nameLocked = true,
            )
            val subCallbacks = object : TaskCallbacks {
                override fun onMeta(item: DownloadItem): DownloadItem {
                    parts[i] = parts[i].copy(totalBytes = item.totalBytes, resumable = item.resumable)
                    return item
                }
                override fun onProgress(progress: TaskProgress) = callbacks.onProgress(this@MediaDownloadTask.progress())
                override fun onCheckpoint(progress: TaskProgress) {
                    parts[i] = parts[i].copy(downloadedBytes = progress.downloaded, segments = progress.segments, totalBytes = maxOf(parts[i].totalBytes, progress.total))
                    persist()
                    callbacks.onCheckpoint(this@MediaDownloadTask.progress())
                }
            }
            val task: DownloadTask = if (f.isHls) StreamDownloadTask(sub, config, client, globalLimiter, subCallbacks)
            else HttpDownloadTask(sub, config, client, globalLimiter, subCallbacks)
            current = task
            try {
                val done = task.run()
                parts[i] = parts[i].copy(fileName = done.fileName, totalBytes = done.totalBytes, downloadedBytes = done.totalBytes, done = true, segments = emptyList())
            } finally {
                if (!parts[i].done) {
                    val p = task.progress()
                    parts[i] = parts[i].copy(downloadedBytes = p.downloaded, segments = p.segments)
                }
                current = null
                persist()
            }
        }

        val files = parts.map { File(partsDir, it.fileName) }
        // A single streamed part: name it after what it really is (fMP4 or MPEG-TS).
        if (files.size == 1 && formats[0].isHls && item.downloadedBytes >= 0) {
            val real = if (Mp4Merge.looksLikeMp4(files[0])) (if (option.audioOnly) "m4a" else "mp4") else "ts"
            if (item.fileName.substringAfterLast('.', "").lowercase() != real) {
                item = item.copy(fileName = FileNames.unique(dir, item.fileName.substringBeforeLast('.') + "." + real))
            }
        }
        val target = File(dir, item.fileName)
        if (target.exists()) target.delete()
        var note: String? = null
        if (files.size == 1) {
            move(files[0], target)
        } else {
            val out = File(partsDir, "merged.mp4")
            val ok = formats.all { it.mp4Family } && runCatching { Mp4Merge.merge(files[0], files[1], out) }.isSuccess
            if (ok) move(out, target) else {
                move(files[0], target)
                val audio = File(dir, FileNames.unique(dir, target.nameWithoutExtension + ".audio." + formats[1].ext))
                move(files[1], audio)
                note = "Audio saved separately as ${audio.name}"
            }
        }
        partsDir.deleteRecursively()
        val size = target.length()
        return item.copy(
            totalBytes = size, downloadedBytes = size, parts = emptyList(),
            description = note?.let { "${item.description} - $it" } ?: item.description,
        )
    }

    private fun persist() {
        item = item.copy(parts = parts.toList())
        callbacks.onPersist(item)
    }

    private fun move(from: File, to: File) {
        if (!from.renameTo(to)) { from.copyTo(to, true); from.delete() }
    }
}
