package com.imad.downloader.core

import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.Charset

/** Turns URLs and HTTP headers into safe, unique file names. */
object FileNames {
    private val illegal = Regex("""[\\/:*?"<>|\u0000-\u001F]""")
    private val reserved = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )
    private const val MAX_LENGTH = 200

    private val mimeExtensions = mapOf(
        "application/pdf" to "pdf",
        "application/zip" to "zip",
        "application/x-rar-compressed" to "rar",
        "application/vnd.rar" to "rar",
        "application/x-7z-compressed" to "7z",
        "application/gzip" to "gz",
        "application/vnd.android.package-archive" to "apk",
        "application/x-msdownload" to "exe",
        "application/x-msi" to "msi",
        "application/json" to "json",
        "application/vnd.apple.mpegurl" to "m3u8",
        "application/x-mpegurl" to "m3u8",
        "video/mp4" to "mp4",
        "video/webm" to "webm",
        "video/x-matroska" to "mkv",
        "video/quicktime" to "mov",
        "video/mp2t" to "ts",
        "audio/mpeg" to "mp3",
        "audio/mp4" to "m4a",
        "audio/ogg" to "ogg",
        "audio/wav" to "wav",
        "audio/flac" to "flac",
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "image/svg+xml" to "svg",
        "text/plain" to "txt",
        "text/html" to "html",
        "text/csv" to "csv",
    )

    fun extensionForMime(mime: String?): String? =
        mime?.substringBefore(';')?.trim()?.lowercase()?.let { mimeExtensions[it] }

    /**
     * Parses a Content-Disposition header. Supports RFC 6266 / RFC 5987 `filename*=UTF-8''...`
     * (preferred) as well as plain and quoted `filename=`.
     */
    fun fromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val params = splitParams(header)
        params["filename*"]?.let { ext ->
            val parts = ext.split("'", limit = 3)
            if (parts.size == 3) {
                val charset = runCatching { Charset.forName(parts[0].ifBlank { "UTF-8" }) }.getOrDefault(Charsets.UTF_8)
                val decoded = runCatching { URLDecoder.decode(parts[2].replace("+", "%2B"), charset) }.getOrNull()
                if (!decoded.isNullOrBlank()) return decoded
            }
        }
        val plain = params["filename"] ?: return null
        // Some servers percent-encode the plain filename too.
        val decoded = if (plain.contains('%')) {
            runCatching { URLDecoder.decode(plain.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(plain)
        } else plain
        return decoded.ifBlank { null }
    }

    private fun splitParams(header: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        val s = header
        // Skip the disposition type ("attachment" / "inline").
        while (i < s.length && s[i] != ';') i++
        while (i < s.length) {
            if (s[i] == ';' || s[i].isWhitespace()) { i++; continue }
            val eq = s.indexOf('=', i)
            if (eq < 0) break
            val key = s.substring(i, eq).trim().lowercase()
            i = eq + 1
            while (i < s.length && s[i].isWhitespace()) i++
            val value: String
            if (i < s.length && s[i] == '"') {
                val sb = StringBuilder()
                i++
                while (i < s.length && s[i] != '"') {
                    if (s[i] == '\\' && i + 1 < s.length) i++
                    sb.append(s[i]); i++
                }
                i++ // closing quote
                value = sb.toString()
            } else {
                val end = s.indexOf(';', i).let { if (it < 0) s.length else it }
                value = s.substring(i, end).trim()
                i = end
            }
            out.putIfAbsent(key, value)
        }
        return out
    }

    /** Last path segment of a URL, percent-decoded. */
    fun fromUrl(url: String): String? {
        val path = runCatching { URI(url.trim().replace(" ", "%20")).rawPath }.getOrNull()
            ?: url.substringBefore('?').substringBefore('#').substringAfter("://").substringAfter('/', "")
        val last = path.trimEnd('/').substringAfterLast('/')
        if (last.isBlank()) return null
        return runCatching { URLDecoder.decode(last.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(last)
    }

    /** Removes characters Windows / Android file systems reject and trims the result. */
    fun sanitize(name: String): String {
        var n = name.replace(illegal, "_").trim().trim('.', ' ')
        if (n.isEmpty()) n = "download"
        val base = n.substringBeforeLast('.')
        if (base.uppercase() in reserved) n = "_$n"
        if (n.length > MAX_LENGTH) {
            val ext = n.substringAfterLast('.', "")
            n = if (ext.isNotEmpty() && ext.length < 12) {
                n.substring(0, MAX_LENGTH - ext.length - 1) + "." + ext
            } else n.substring(0, MAX_LENGTH)
        }
        return n
    }

    /** Best file name for a response: header, then URL, then MIME-based fallback. */
    fun resolve(url: String, contentDisposition: String?, mime: String?): String {
        val raw = fromContentDisposition(contentDisposition) ?: fromUrl(url) ?: "download"
        var name = sanitize(raw)
        val ext = extensionForMime(mime)
        if (!name.contains('.') && ext != null && ext != "html") name = "$name.$ext"
        return name
    }

    /** "file.zip" -> "file (1).zip" -> "file (2).zip" until nothing on disk (or in [taken]) collides. */
    fun unique(dir: File, name: String, taken: Set<String> = emptySet()): String {
        fun free(candidate: String) =
            !File(dir, candidate).exists() &&
                !File(dir, candidate + PART_SUFFIX).exists() &&
                taken.none { it.equals(candidate, ignoreCase = true) }
        if (free(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = "$base ($i)$ext"
            if (free(candidate)) return candidate
            i++
        }
    }

    const val PART_SUFFIX = ".imdl"
}
