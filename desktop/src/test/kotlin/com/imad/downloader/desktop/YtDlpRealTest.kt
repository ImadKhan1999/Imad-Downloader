package com.imad.downloader.desktop

import com.imad.downloader.core.DownloadEngine
import com.imad.downloader.core.DownloadItem
import com.imad.downloader.core.DownloadRequest
import com.imad.downloader.core.DownloadStatus
import com.imad.downloader.core.EngineConfig
import com.imad.downloader.core.InMemoryStore
import com.imad.downloader.core.MediaChoice
import com.imad.downloader.core.MediaOption
import com.imad.downloader.core.Mp4Merge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real yt-dlp against real sites. Needs the internet, so it only runs with -PrealNetwork=true
 * (`gradlew :desktop:test -PrealNetwork=true --tests *YtDlpRealTest*`).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YtDlpRealTest {
    private val enabled = System.getProperty("realNetwork") == "true"
    private lateinit var dir: File
    private lateinit var tool: YtDlpTool
    private lateinit var engine: DownloadEngine
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @BeforeAll fun setUp() {
        dir = Files.createTempDirectory("ytdlp").toFile()
        tool = YtDlpTool(File(System.getProperty("java.io.tmpdir"), "imad-ytdlp-test-tools"))
        engine = DownloadEngine(EngineConfig(defaultSaveDir = dir.path, categorySubfolders = false), InMemoryStore(), scope, mediaExtractor = YtDlpExtractor(tool))
    }

    @AfterAll fun tearDown() { scope.cancel(); dir.deleteRecursively() }

    private fun download(url: String, choose: (List<MediaOption>) -> MediaOption): Pair<DownloadItem, MediaOption> {
        val info = runBlocking { engine.extractMedia(url) }
        val options = MediaChoice.options(info)
        println("[$url] ${info.extractor}: ${info.title} -> ${options.map { "${it.label}=${it.formatSpec}" }}")
        assertTrue(options.isNotEmpty())
        val opt = choose(options)
        val item = engine.add(DownloadRequest(url, fileName = MediaChoice.safeTitle(info.title) + "." + opt.extension, mediaFormat = opt.formatSpec))
        val done = runBlocking {
            withTimeout(300_000) { engine.state.first { s -> s.downloads.first { it.id == item.id }.status.let { it == DownloadStatus.COMPLETED || it == DownloadStatus.FAILED } } }
                .downloads.first { it.id == item.id }
        }
        assertEquals(DownloadStatus.COMPLETED, done.status, done.error)
        println("  saved ${done.fileName}: ${File(done.filePath).length()} bytes (${opt.label})")
        return done to opt
    }

    @Test fun `youtube playlist lists its videos and two of them download as audio`() {
        assumeTrue(enabled)
        val url = "https://www.youtube.com/playlist?list=PLa1F2ddGya_8Pn07ZxzgxFRw5iQbovXZv"
        val pl = runBlocking { engine.extractPlaylist(url) }
        println("[$url] ${pl.title}: ${pl.entries.size} videos, first ${pl.entries.first().title}")
        assertTrue(pl.entries.size >= 5)
        val reqs = MediaChoice.playlistRequests(DownloadRequest(url), pl.entries.take(2), com.imad.downloader.core.PlaylistQuality.all.last())
        val items = reqs.map { engine.add(it) }
        val done = runBlocking {
            withTimeout(600_000) {
                engine.state.first { s -> items.all { i -> s.downloads.first { it.id == i.id }.status.let { it == DownloadStatus.COMPLETED || it == DownloadStatus.FAILED } } }
            }.downloads.filter { d -> items.any { it.id == d.id } }
        }
        done.forEach { d ->
            assertEquals(DownloadStatus.COMPLETED, d.status, d.error)
            println("  saved ${d.fileName}: ${File(d.filePath).length()} bytes")
            assertTrue(d.fileName.endsWith(".m4a") && File(d.filePath).length() > 500_000)
        }
    }

    @Test fun `archive org progressive video`() {
        assumeTrue(enabled)
        val (done, _) = download("https://archive.org/details/BigBuckBunny_124") { opts -> opts.filter { !it.audioOnly }.minBy { it.sizeEstimate.takeIf { s -> s > 0 } ?: Long.MAX_VALUE } }
        assertTrue(File(done.filePath).length() > 1_000_000)
    }

    @Test fun `dailymotion hls video at the lowest quality`() {
        assumeTrue(enabled)
        val (done, _) = download("https://www.dailymotion.com/video/x8j6h8m") { opts -> opts.filter { !it.audioOnly }.minBy { it.height } }
        assertTrue(File(done.filePath).length() > 100_000)
    }

    @Test fun `soundcloud audio`() {
        assumeTrue(enabled)
        val (done, opt) = download("https://soundcloud.com/forss/flickermood") { opts -> opts.first() }
        assertTrue(opt.audioOnly)
        assertTrue(File(done.filePath).length() > 100_000)
    }

    @Test fun `youtube is joined into one mp4 or explains the sign-in check`() {
        assumeTrue(enabled)
        val url = "https://www.youtube.com/watch?v=jNQXAC9IVRw" // "Me at the zoo", 19 s
        val r = runCatching { download(url) { opts -> opts.firstOrNull { '+' in it.formatSpec && it.height <= 480 } ?: opts.first() } }
        r.onFailure { e ->
            println("  YouTube refused this network: ${e.message}")
            assertTrue(e.message!!.contains("signed-in browser"), "a clear message is shown: ${e.message}")
        }
        r.onSuccess { (done, opt) ->
            if ('+' in opt.formatSpec) {
                val out = File(done.filePath)
                val moovE = Mp4Merge.index(out).first { it.type == "moov" }
                val moov = Mp4Merge.Box("moov", children = Mp4Merge.parse(out.readBytes(), (moovE.offset + 8).toInt(), (moovE.offset + moovE.size).toInt()))
                assertEquals(2, moov.all("trak").size)
            }
        }
    }
}
