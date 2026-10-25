package com.imad.downloader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.imad.downloader.core.DownloadEngine
import com.imad.downloader.core.DownloadRequest
import com.imad.downloader.core.DownloadStatus
import com.imad.downloader.core.EngineConfig
import com.imad.downloader.core.InMemoryStore
import com.imad.downloader.core.MediaChoice
import com.imad.downloader.core.UpdateClient
import com.imad.downloader.core.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class SitesAndUpdatesTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val realNetwork = InstrumentationRegistry.getArguments().getString("realNetwork") == "true"

    /** The updater accepts an APK signed like the app and gets it ready for Android's installer. */
    @Test fun updaterDownloadsVerifiesAndPreparesInstall() {
        val apk = File(ctx.packageCodePath).readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
        MiniServer().use { server ->
            server.routes["/rel/app.apk"] = MiniServer.Route(apk, "application/vnd.android.package-archive")
            server.routes["/update/version.json"] = MiniServer.Route(
                """{"android":{"version":"99.0.0","versionCode":99999,"url":"${server.base}/rel/app.apk","sha256":"$sha","size":${apk.size},"changelog":"Test"}}""".toByteArray(),
                "application/json",
            )
            val updater = AppUpdater(ctx, CoroutineScope(SupervisorJob() + Dispatchers.IO), UpdateClient(manifestUrl = "${server.base}/update/version.json"))
            updater.check()
            val available = runBlocking { withTimeout(20_000) { updater.state.first { it is UpdateState.Available || it is UpdateState.Failed } } }
            assertTrue("$available", available is UpdateState.Available)
            updater.download((available as UpdateState.Available).release)
            val ready = runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(240_000) { updater.state.first { it is UpdateState.Ready || it is UpdateState.Failed } } ?: updater.state.value
            }
            assertTrue("$ready", ready is UpdateState.Ready)
            val intent = updater.installIntent((ready as UpdateState.Ready).file)
            assertEquals("application/vnd.android.package-archive", intent.type)
            assertTrue(intent.resolveActivity(ctx.packageManager) != null)
        }
    }

    @Test fun updaterRefusesAnApkWithTheWrongChecksum() {
        MiniServer().use { server ->
            server.routes["/rel/app.apk"] = MiniServer.Route(ByteArray(5000) { 1 }, "application/vnd.android.package-archive")
            server.routes["/update/version.json"] = MiniServer.Route(
                """{"android":{"version":"99.0.0","versionCode":99999,"url":"${server.base}/rel/app.apk","sha256":"${"ab".repeat(32)}"}}""".toByteArray(),
                "application/json",
            )
            val updater = AppUpdater(ctx, CoroutineScope(SupervisorJob() + Dispatchers.IO), UpdateClient(manifestUrl = "${server.base}/update/version.json"))
            updater.check()
            val available = runBlocking { withTimeout(20_000) { updater.state.first { it is UpdateState.Available } } } as UpdateState.Available
            updater.download(available.release)
            val failed = runBlocking { withTimeout(30_000) { updater.state.first { it is UpdateState.Failed || it is UpdateState.Ready } } }
            assertTrue("$failed", failed is UpdateState.Failed && failed.reason.contains("checksum"))
        }
    }

    @Test fun ytDlpRunsInsideTheApp() {
        val v = runBlocking { AndroidYtDlp(ctx).version() }
        assertTrue("yt-dlp version $v", Regex("""\d{4}\.\d{2}\.\d{2}.*""").matches(v))
    }

    /** Real sites through the app's own yt-dlp: needs -Pandroid.testInstrumentationRunnerArguments.realNetwork=true */
    @Test fun realSiteVideoAndAudioDownloadOnThePhone() {
        assumeTrue(realNetwork)
        val dir = File(ctx.cacheDir, "sites").apply { deleteRecursively(); mkdirs() }
        val engine = DownloadEngine(EngineConfig(defaultSaveDir = dir.path, categorySubfolders = false), InMemoryStore(), mediaExtractor = AndroidYtDlp(ctx))
        for ((url, wantAudio) in listOf("https://soundcloud.com/forss/flickermood" to true, "https://archive.org/details/BigBuckBunny_124" to false)) {
            val info = runBlocking { engine.extractMedia(url) }
            val options = MediaChoice.options(info)
            android.util.Log.i("ImadTest", "$url -> ${options.map { it.label + "=" + it.formatSpec }}")
            val opt = if (wantAudio) options.first { it.audioOnly } else options.filter { !it.audioOnly }.minBy { it.sizeEstimate.takeIf { s -> s > 0 } ?: Long.MAX_VALUE }
            val item = engine.add(DownloadRequest(url, fileName = MediaChoice.safeTitle(info.title) + "." + opt.extension, mediaFormat = opt.formatSpec))
            val done = runBlocking {
                withTimeout(300_000) { engine.state.first { s -> s.downloads.first { it.id == item.id }.status.let { it == DownloadStatus.COMPLETED || it == DownloadStatus.FAILED } } }
                    .downloads.first { it.id == item.id }
            }
            assertEquals(done.error, DownloadStatus.COMPLETED, done.status)
            assertTrue(File(done.filePath).length() > 100_000)
            android.util.Log.i("ImadTest", "saved ${done.fileName} ${File(done.filePath).length()} bytes")
        }
        // YouTube: works, or explains the sign-in check clearly (some networks are flagged as bots).
        val yt = runCatching { runBlocking { engine.extractMedia("https://www.youtube.com/watch?v=jNQXAC9IVRw") } }
        android.util.Log.i("ImadTest", "youtube: ${yt.fold({ "ok ${it.title} ${MediaChoice.options(it).map { o -> o.label }}" }, { "refused: ${it.message}" })}")
        yt.onFailure { assertTrue(it.message!!, it.message!!.contains("signed in") || it.message!!.contains("Sign")) }
    }

    /** YouTube on the phone: a playlist is listed and its first video downloads with sound (realNetwork). */
    @Test fun youtubePlaylistOnThePhone() {
        assumeTrue(realNetwork)
        val dir = File(ctx.cacheDir, "playlist").apply { deleteRecursively(); mkdirs() }
        val engine = DownloadEngine(EngineConfig(defaultSaveDir = dir.path, categorySubfolders = false), InMemoryStore(), mediaExtractor = AndroidYtDlp(ctx))
        val url = "https://m.youtube.com/playlist?list=PLa1F2ddGya_8Pn07ZxzgxFRw5iQbovXZv"
        val pl = runCatching { runBlocking { engine.extractPlaylist(url) } }
        pl.onFailure { e -> assumeTrue("YouTube asked this network to sign in: ${e.message}", e.message?.contains("signed in") != true) }
        val playlist = pl.getOrThrow()
        android.util.Log.i("ImadTest", "playlist ${playlist.title}: ${playlist.entries.size} videos")
        assertTrue(playlist.entries.size >= 5)
        val req = MediaChoice.playlistRequests(DownloadRequest(url), playlist.entries.take(1), com.imad.downloader.core.PlaylistQuality.all.first { it.height == 360 }).single()
        val item = engine.add(req)
        val done = runBlocking {
            withTimeout(600_000) { engine.state.first { s -> s.downloads.first { it.id == item.id }.status.let { it == DownloadStatus.COMPLETED || it == DownloadStatus.FAILED } } }
                .downloads.first { it.id == item.id }
        }
        // YouTube sometimes asks a busy network to "confirm you're not a bot"; that is YouTube, not the app.
        assumeTrue("YouTube asked this network to sign in: ${done.error}", done.error?.contains("signed in") != true)
        assertEquals(done.error, DownloadStatus.COMPLETED, done.status)
        val f = File(done.filePath)
        android.util.Log.i("ImadTest", "saved ${done.fileName} ${f.length()} bytes")
        val ex = android.media.MediaExtractor().apply { setDataSource(f.path) }
        val mimes = (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME).orEmpty() }
        ex.release()
        assertTrue("tracks $mimes", mimes.any { it.startsWith("video/") } && mimes.any { it.startsWith("audio/") })
    }
}
