package com.imad.downloader.browser

import com.imad.downloader.core.FileNames

/** A video or audio stream seen while a page loads (1DM's "videos found" list). */
data class DetectedMedia(
    val url: String,
    val pageUrl: String,
    val title: String,
    val kind: Kind,
    val foundAt: Long = System.currentTimeMillis(),
) {
    enum class Kind { VIDEO, AUDIO, STREAM }

    val displayName: String
        get() = FileNames.fromUrl(url)?.takeIf { it.contains('.') } ?: title.ifBlank { "media" }
}

object MediaDetector {
    private val video = setOf("mp4", "m4v", "webm", "mkv", "mov", "3gp", "flv", "avi")
    private val audio = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")
    private val stream = setOf("m3u8", "mpd")
    /** Pieces of a stream; never listed on their own. */
    private val pieces = setOf("ts", "m4s", "aac?segment")

    fun classify(url: String): DetectedMedia.Kind? {
        if (!url.startsWith("http")) return null
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val ext = path.substringAfterLast('.', "")
        if (ext in pieces) return null
        return when {
            ext in stream -> DetectedMedia.Kind.STREAM
            ext in video -> DetectedMedia.Kind.VIDEO
            ext in audio -> DetectedMedia.Kind.AUDIO
            // Common CDN patterns without extensions.
            "videoplayback" in path || "/hls/" in path && "playlist" in path -> DetectedMedia.Kind.VIDEO
            else -> null
        }
    }

    /** Keeps the list short and free of duplicates (same file with different tokens). */
    fun merge(list: List<DetectedMedia>, item: DetectedMedia, max: Int = 40): List<DetectedMedia> {
        val key = item.url.substringBefore('?')
        if (list.any { it.url.substringBefore('?') == key }) return list
        return (list + item).takeLast(max)
    }

    /** Script that reports <video>/<audio> sources the network hook might miss (blob players excluded). */
    const val SCAN_SCRIPT = """
        (function() {
          var out = [];
          document.querySelectorAll('video, audio, video source, audio source').forEach(function(e) {
            var s = e.currentSrc || e.src;
            if (s && s.indexOf('http') === 0) out.push(s);
          });
          return JSON.stringify(out);
        })();
    """
}

/**
 * Small host-based ad blocker. Blocks requests whose host is (or is under) a known ad or
 * tracker domain. The list covers the networks behind most mobile web ads.
 */
object AdBlocker {
    private val hosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "google-analytics.com", "googletagservices.com", "adnxs.com", "adsrvr.org", "advertising.com",
        "amazon-adsystem.com", "taboola.com", "outbrain.com", "criteo.com", "criteo.net", "pubmatic.com",
        "rubiconproject.com", "openx.net", "media.net", "moatads.com", "scorecardresearch.com", "zedo.com",
        "propellerads.com", "popads.net", "popcash.net", "adsterra.com", "exoclick.com", "juicyads.com",
        "mgid.com", "revcontent.com", "yieldmo.com", "smartadserver.com", "adform.net", "bidswitch.net",
        "casalemedia.com", "contextweb.com", "sharethrough.com", "teads.tv", "33across.com", "onclickads.net",
        "hilltopads.net", "trafficjunky.net", "adcash.com", "clickadu.com", "admaven.com",
    )

    fun isAd(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        var h = host.lowercase()
        while (true) {
            if (h in hosts) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }
}
