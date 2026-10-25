package com.imad.downloader

import android.content.Context
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.imad.downloader.core.MediaExtractor
import com.imad.downloader.core.MediaInfo
import com.imad.downloader.core.PlaylistInfo
import com.imad.downloader.core.YtDlpJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * yt-dlp on Android: the copy built into the app (Python via Chaquopy) or, once downloaded, the
 * latest official yt-dlp from GitHub (checked against SHA2-256SUMS), so sites keep working
 * between app releases. A new copy is used from the next extraction on.
 */
class AndroidYtDlp(private val context: Context) : MediaExtractor {
    private val dir = File(context.filesDir, "ytdlp").apply { mkdirs() }
    private val override = File(dir, "yt-dlp.zip")
    private val stamp = File(dir, "checked")
    private val lock = Mutex()
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

    private fun py(): Python {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        return Python.getInstance()
    }

    private fun overridePath(): String? = override.takeIf { it.isFile && it.length() > 1_000_000 }?.path

    suspend fun version(): String = withContext(Dispatchers.IO) {
        lock.withLock { py().getModule("imad_ytdlp").callAttr("version", overridePath()).toString() }
    }

    override suspend fun extract(url: String, headers: Map<String, String>): MediaInfo =
        YtDlpJson.parse(withRetry { call("extract", url, headers) })

    override suspend fun extractPlaylist(url: String, headers: Map<String, String>): PlaylistInfo =
        YtDlpJson.parsePlaylist(withRetry { call("extract_playlist", url, headers) })

    private suspend fun withRetry(block: suspend () -> String): String = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: PyException) {
            // Sites (YouTube above all) change often and a newer yt-dlp usually copes: fetch the
            // newest one now (unless it was fetched in the last hour) and try once more.
            val updated = runCatching { updateIfDue(everyMillis = 3_600_000L) }.getOrDefault(false)
            if (!updated) throw IOException(friendly(e.message.orEmpty()))
            try { block() } catch (e2: PyException) { throw IOException(friendly(e2.message.orEmpty())) }
        }
    }

    private suspend fun call(function: String, url: String, headers: Map<String, String>): String {
        val cookie = headers.entries.firstOrNull { it.key.equals("Cookie", true) }?.value
        // yt-dlp picks the right browser identity per site (YouTube's player clients each need
        // their own); the in-app browser's phone User-Agent would override that, so it is not sent.
        val plain = headers.filterKeys { !it.equals("Cookie", true) && !it.equals("User-Agent", true) }
        val headersJson = JsonObject(plain.mapValues { JsonPrimitive(it.value) }).toString()
        var cookieFile: File? = null
        try {
            if (!cookie.isNullOrBlank()) {
                cookieFile = File.createTempFile("cookies", ".txt", context.cacheDir).apply { writeText(netscape(url, cookie)) }
            }
            return lock.withLock {
                py().getModule("imad_ytdlp").callAttr(function, url, headersJson, cookieFile?.path, overridePath()).toString()
            }
        } finally {
            cookieFile?.delete()
        }
    }

    /**
     * Downloads the newest official yt-dlp when the last check is older than [everyMillis].
     * It is used from the next extraction on, without restarting the app.
     */
    suspend fun updateIfDue(everyMillis: Long = 24 * 3_600_000L): Boolean = withContext(Dispatchers.IO) {
        val last = stamp.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0
        if (System.currentTimeMillis() - last < everyMillis) return@withContext false
        stamp.writeText(System.currentTimeMillis().toString()) // one try per period, even when offline
        val base = "https://github.com/yt-dlp/yt-dlp/releases/latest/download"
        fun get(u: String) = http.newCall(Request.Builder().url(u).header("User-Agent", "iDownloader").build()).execute().also {
            if (!it.isSuccessful) { it.close(); throw IOException("yt-dlp update failed (${it.code})") }
        }
        val expected = get("$base/SHA2-256SUMS").use { r ->
            r.body.string().lines().firstOrNull { it.trim().endsWith(" yt-dlp") }?.substringBefore(' ')?.lowercase()
        } ?: throw IOException("Could not read yt-dlp checksums")
        val current = runCatching { version() }.getOrNull()
        val latest = runCatching { get("https://github.com/yt-dlp/yt-dlp/releases/latest").use { it.request.url.pathSegments.lastOrNull() } }.getOrNull()
        if (current != null && latest != null && latest.trimStart('v') == current) return@withContext false
        val tmp = File(dir, "yt-dlp.zip.part")
        get("$base/yt-dlp").use { r -> tmp.outputStream().use { r.body.byteStream().copyTo(it) } }
        val actual = MessageDigest.getInstance("SHA-256").digest(tmp.readBytes()).joinToString("") { "%02x".format(it) }
        if (actual != expected) { tmp.delete(); throw IOException("yt-dlp update did not match its checksum") }
        lock.withLock {
            if (override.exists()) override.delete()
            tmp.renameTo(override)
        }
        true
    }

    private fun friendly(msg: String): String {
        val line = msg.lines().lastOrNull { "ERROR" in it || "Error" in it }?.substringAfter("ERROR:")?.trim() ?: msg.lines().lastOrNull().orEmpty()
        return when {
            "Unsupported URL" in line -> "No video found on this page"
            "not a bot" in line -> "YouTube wants you signed in for this. Open YouTube in the app's browser, sign in, then use the download button there."
            "Sign in" in line || "logged-in" in line -> "This site needs you to be signed in. Sign in using the app's browser, then try again from there."
            "Private video" in line -> "This video is private"
            "impersonat" in line -> "This site blocks apps that are not a desktop browser, which the phone app cannot pretend to be yet. Use iDownloader on Windows for this site."
            else -> line.take(300).ifBlank { "Could not read this video page" }
        }
    }

    companion object {
        fun netscape(url: String, cookieHeader: String): String {
            val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty().removePrefix("www.").removePrefix("m.")
            val expires = System.currentTimeMillis() / 1000 + 3600
            return buildString {
                appendLine("# Netscape HTTP Cookie File")
                cookieHeader.split(';').map { it.trim() }.filter { '=' in it }.forEach { kv ->
                    appendLine(listOf(".$host", "TRUE", "/", "TRUE", expires.toString(), kv.substringBefore('='), kv.substringAfter('=')).joinToString("\t"))
                }
            }
        }

        @Suppress("unused")
        private val json = Json
    }
}
