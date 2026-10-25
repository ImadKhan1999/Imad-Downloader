package com.imad.downloader.desktop

import com.imad.downloader.core.MediaExtractor
import com.imad.downloader.core.MediaInfo
import com.imad.downloader.core.PlaylistInfo
import com.imad.downloader.core.YtDlpJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The official yt-dlp.exe (github.com/yt-dlp/yt-dlp), downloaded on first use into the app's data
 * folder, checked against the release's SHA2-256SUMS, and kept current with `yt-dlp -U`.
 */
class YtDlpTool(
    private val dir: File = File(AppDirs.data, "tools"),
    private val http: OkHttpClient = OkHttpClient.Builder().followRedirects(true).readTimeout(60, TimeUnit.SECONDS).build(),
    private val releaseBase: String = "https://github.com/yt-dlp/yt-dlp/releases/latest/download",
) {
    val exe: File get() = File(dir, "yt-dlp.exe")
    private val lock = Mutex()
    private val stamp get() = File(dir, "yt-dlp.checked")

    val installed: Boolean get() = exe.isFile && exe.length() > 1_000_000

    /** Makes sure yt-dlp.exe exists. [onProgress] gets bytes done / total while downloading. */
    suspend fun ensure(onProgress: (Long, Long) -> Unit = { _, _ -> }) = lock.withLock {
        if (installed) return@withLock
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val sums = get("$releaseBase/SHA2-256SUMS").use { it.body.string() }
            val expected = sums.lines().firstOrNull { it.trim().endsWith(" yt-dlp.exe") }?.substringBefore(' ')?.trim()?.lowercase()
                ?: throw IOException("Could not read yt-dlp checksums")
            val tmp = File(dir, "yt-dlp.exe.part")
            val md = MessageDigest.getInstance("SHA-256")
            get("$releaseBase/yt-dlp.exe").use { resp ->
                val total = resp.body.contentLength()
                resp.body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); md.update(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
            }
            val actual = md.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) { tmp.delete(); throw IOException("yt-dlp download did not match its checksum") }
            if (exe.exists()) exe.delete()
            if (!tmp.renameTo(exe)) throw IOException("Could not save yt-dlp")
            stamp.writeText(System.currentTimeMillis().toString())
        }
    }

    private fun get(url: String) = http.newCall(Request.Builder().url(url).header("User-Agent", "HyperDownloader").build()).execute().also {
        if (!it.isSuccessful) { it.close(); throw IOException("Download of yt-dlp failed (${it.code})") }
    }

    /** Sites change often; let yt-dlp update itself every few days (it verifies its own download). */
    suspend fun updateIfDue(everyMillis: Long = 3 * 24 * 3_600_000L) = withContext(Dispatchers.IO) {
        if (!installed) return@withContext
        val last = stamp.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0
        if (System.currentTimeMillis() - last < everyMillis) return@withContext
        lock.withLock { runCatching { run(listOf("-U"), 120) } }
        stamp.writeText(System.currentTimeMillis().toString())
    }

    /** "node" if Node.js is installed, else a downloaded deno.exe ("deno:<path>"). */
    suspend fun jsRuntime(): String = withContext(Dispatchers.IO) {
        if (runCatching { ProcessBuilder("node", "--version").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)) return@withContext "node"
        val deno = File(dir, "deno.exe")
        if (!deno.isFile) lock.withLock {
            if (deno.isFile) return@withLock
            val base = "https://github.com/denoland/deno/releases/latest/download/deno-x86_64-pc-windows-msvc.zip"
            val expected = get("$base.sha256sum").use { r -> Regex("[0-9A-Fa-f]{64}").find(r.body.string())?.value?.lowercase() }
                ?: throw IOException("Could not read the Deno checksum")
            val zip = File(dir, "deno.zip")
            get(base).use { r -> zip.outputStream().use { r.body.byteStream().copyTo(it) } }
            val actual = MessageDigest.getInstance("SHA-256").digest(zip.readBytes()).joinToString("") { "%02x".format(it) }
            if (actual != expected) { zip.delete(); throw IOException("Deno download did not match its checksum") }
            java.util.zip.ZipFile(zip).use { z ->
                val e = z.getEntry("deno.exe") ?: throw IOException("deno.exe missing in archive")
                z.getInputStream(e).use { input -> deno.outputStream().use { input.copyTo(it) } }
            }
            zip.delete()
        }
        "deno:${deno.path}"
    }

    fun version(): String? = runCatching { run(listOf("--version"), 30).trim() }.getOrNull()

    /** Runs yt-dlp and returns stdout; a failed run throws with yt-dlp's own error line. */
    fun run(args: List<String>, timeoutSeconds: Long): String {
        val p = ProcessBuilder(listOf(exe.path) + args).redirectErrorStream(false).start()
        val out = StringBuilder()
        val err = StringBuilder()
        val t1 = Thread { p.inputStream.bufferedReader().use { r -> r.forEachLine { out.appendLine(it) } } }.apply { isDaemon = true; start() }
        val t2 = Thread { p.errorStream.bufferedReader().use { r -> r.forEachLine { err.appendLine(it) } } }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { p.destroyForcibly(); throw IOException("The video site took too long to answer") }
        t1.join(2000); t2.join(2000)
        if (p.exitValue() != 0) {
            val line = err.lines().lastOrNull { it.startsWith("ERROR") }?.removePrefix("ERROR:")?.trim()
            throw IOException(friendly(line ?: err.toString().trim().ifBlank { "yt-dlp failed" }))
        }
        return out.toString()
    }

    private fun friendly(msg: String): String = when {
        "Unsupported URL" in msg -> "No video found on this page"
        "Private video" in msg -> "This video is private"
        "not a bot" in msg -> "YouTube wants a signed-in browser for this. Open the video in Chrome or Edge and click \"Download this video\" (the extension passes your sign-in), or pick your browser in Options > Video Sites."
        "Sign in" in msg || "logged-in" in msg -> "This site needs you to be signed in. Use the browser extension's \"Download this video\" button, or pick your browser in Options > Video Sites."
        "not available" in msg -> "This video is not available: $msg"
        else -> msg.take(300)
    }
}

/**
 * [MediaExtractor] backed by yt-dlp.exe. YouTube needs a JavaScript runtime: Node.js when it is
 * installed, otherwise the official Deno build is fetched next to yt-dlp. Cookies from the browser
 * extension (or a browser chosen in Options) let yt-dlp pass sites' sign-in and bot checks.
 */
class YtDlpExtractor(
    private val tool: YtDlpTool,
    private val cookiesFromBrowser: () -> String? = { null },
    private val onInstalling: (Long, Long) -> Unit = { _, _ -> },
) : MediaExtractor {
    override suspend fun extract(url: String, headers: Map<String, String>): MediaInfo =
        YtDlpJson.parse(run(url, headers, listOf("--no-playlist")))

    override suspend fun extractPlaylist(url: String, headers: Map<String, String>): PlaylistInfo =
        YtDlpJson.parsePlaylist(run(url, headers, listOf("--yes-playlist", "--flat-playlist")))

    private suspend fun run(url: String, headers: Map<String, String>, mode: List<String>): String {
        tool.ensure(onInstalling)
        val js = runCatching { tool.jsRuntime() }.getOrNull()
        return withContext(Dispatchers.IO) {
            val args = mutableListOf("-J", "--no-warnings", "--no-progress")
            args += mode
            js?.let { args += "--js-runtimes"; args += it }
            headers.forEach { (k, v) -> if (!k.equals("Cookie", true)) { args += "--add-header"; args += "$k:$v" } }
            var cookieFile: File? = null
            val cookie = headers.entries.firstOrNull { it.key.equals("Cookie", true) }?.value
            if (!cookie.isNullOrBlank()) {
                cookieFile = File.createTempFile("imad-cookies", ".txt").also { it.writeText(CookieFile.netscape(url, cookie)) }
                args += "--cookies"; args += cookieFile.path
            } else cookiesFromBrowser()?.takeIf { it.isNotBlank() }?.let { args += "--cookies-from-browser"; args += it }
            args += url
            try {
                tool.run(args, 180)
            } finally {
                cookieFile?.delete()
            }
        }
    }
}

/** Writes a cookie header as a Netscape cookies.txt that yt-dlp understands. */
object CookieFile {
    fun netscape(url: String, cookieHeader: String): String {
        val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty().removePrefix("www.").removePrefix("m.")
        val domain = ".$host"
        val expires = System.currentTimeMillis() / 1000 + 3600
        return buildString {
            appendLine("# Netscape HTTP Cookie File")
            cookieHeader.split(';').map { it.trim() }.filter { '=' in it }.forEach { kv ->
                appendLine(listOf(domain, "TRUE", "/", "TRUE", expires.toString(), kv.substringBefore('='), kv.substringAfter('=')).joinToString("\t"))
            }
        }
    }
}
