package com.imad.downloader.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** One platform's latest release, as published in update/version.json. */
@Serializable
data class ReleaseInfo(
    val version: String,
    /** Android only: the APK's versionCode. */
    val versionCode: Int = 0,
    val url: String,
    val sha256: String,
    val size: Long = -1,
    val changelog: String = "",
    val mandatory: Boolean = false,
)

@Serializable
data class UpdateManifest(val android: ReleaseInfo? = null, val windows: ReleaseInfo? = null)

/** Where an in-app update is. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val done: Long, val total: Long) : UpdateState {
        val percent: Int get() = if (total > 0) (done * 100 / total).toInt() else 0
    }
    data object Verifying : UpdateState
    data class Ready(val release: ReleaseInfo, val file: File) : UpdateState
    data class Failed(val reason: String, val release: ReleaseInfo? = null) : UpdateState
}

/**
 * Checks update/version.json in the GitHub repo and downloads the new installer with progress,
 * refusing it unless its SHA-256 matches the manifest. Shared by the Windows and Android apps
 * (each adds its own install step).
 */
class UpdateClient(
    private val http: OkHttpClient = OkHttpClient(),
    val manifestUrl: String = DEFAULT_MANIFEST,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun manifest(): UpdateManifest = withContext(Dispatchers.IO) {
        // Cache-busting query: raw.githubusercontent.com caches for a few minutes.
        val url = manifestUrl + (if ('?' in manifestUrl) "&" else "?") + "t=" + System.currentTimeMillis() / 60_000
        http.newCall(Request.Builder().url(url).header("User-Agent", "iDownloader-Updater").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Update check failed (${r.code})")
            json.decodeFromString(UpdateManifest.serializer(), r.body.string())
        }
    }

    /** Downloads [release] into [dir], reporting progress; returns the verified file. */
    suspend fun download(release: ReleaseInfo, dir: File, onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val name = release.url.substringAfterLast('/').substringBefore('?').let { java.net.URLDecoder.decode(it, "UTF-8") }.ifBlank { "update.bin" }
        val out = File(dir, FileNames.sanitize(name))
        if (out.isFile && sha256(out).equals(release.sha256, ignoreCase = true)) return@withContext out
        val tmp = File(dir, out.name + ".part")
        http.newCall(Request.Builder().url(release.url).header("User-Agent", "iDownloader-Updater").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download failed (${r.code})")
            val total = r.body.contentLength().takeIf { it > 0 } ?: release.size
            val md = MessageDigest.getInstance("SHA-256")
            r.body.byteStream().use { input ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (done - lastReport > 256 * 1024 || done == total) { onProgress(done, total); lastReport = done }
                    }
                    onProgress(done, total)
                }
            }
            val actual = md.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(release.sha256, ignoreCase = true)) {
                tmp.delete()
                throw IOException("The download is damaged or not genuine (checksum mismatch). Not installing.")
            }
        }
        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) { tmp.copyTo(out, true); tmp.delete() }
        out
    }

    companion object {
        const val DEFAULT_MANIFEST = "https://raw.githubusercontent.com/ImadKhan1999/Imad-Downloader/main/update/version.json"

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i ->
                val b = ByteArray(256 * 1024)
                while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** "1.10.0" > "1.9.3"; missing parts count as 0. */
        fun isNewer(candidate: String, current: String): Boolean {
            val a = candidate.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
            val b = current.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
