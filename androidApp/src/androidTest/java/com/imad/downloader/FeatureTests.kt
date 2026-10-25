package com.imad.downloader

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.imad.downloader.core.DownloadEngine
import com.imad.downloader.core.DownloadRequest
import com.imad.downloader.core.DownloadStatus
import com.imad.downloader.core.EngineConfig
import com.imad.downloader.core.InMemoryStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/** Queues screen and quality picker, driven through the real UI. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class FeatureTests {
    @get:Rule(order = 0) val notifications: androidx.test.rule.GrantPermissionRule =
        androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var server: MiniServer
    private val app get() = compose.activity.application as ImadApp
    private lateinit var dir: File

    @Before fun setUp() {
        server = MiniServer()
        dir = File(compose.activity.cacheDir, "feature-test").apply { deleteRecursively(); mkdirs() }
        app.engine.downloads.forEach { app.engine.remove(it.id, deleteFile = true) }
        app.engine.queues.filter { it.id != "main" }.forEach { app.engine.removeQueue(it.id) }
        app.settings.update { it.copy(askBeforeDownload = true, clipboardMonitor = false, wifiOnly = false, autoUpdate = false, telegramPromptOff = true, saveDir = dir.path, categoryFolders = false) }
    }

    @After fun tearDown() { server.close() }

    private fun waitDone(id: String, timeout: Long = 30_000) {
        compose.waitUntil(timeout) { app.engine.get(id)?.status.let { it == DownloadStatus.COMPLETED || it == DownloadStatus.FAILED } }
    }

    @Test fun queueStartsFromQueuesScreen() {
        server.routes["/q1.bin"] = MiniServer.Route(MiniServer.content(300_000), "application/octet-stream")
        server.routes["/q2.bin"] = MiniServer.Route(MiniServer.content(200_000), "application/octet-stream")
        lateinit var ids: List<String>
        compose.runOnUiThread {
            val q = app.engine.addQueue("Night", maxConcurrent = 1)
            ids = listOf("/q1.bin", "/q2.bin").map { app.engine.add(DownloadRequest(server.base + it, queueId = q.id), start = false).id }
        }
        compose.onNodeWithTag("downloads-menu").performClick()
        compose.onNodeWithTag("menu-queues").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("queue-Night"), 10_000)
        compose.onNodeWithTag("start-Night").performClick()
        ids.forEach { waitDone(it) }
        ids.forEach { assertEquals(DownloadStatus.COMPLETED, app.engine.get(it)!!.status) }
    }

    @Test fun qualityPickerDownloadsTheChosenStream() {
        val hi = (0 until 3).map { MiniServer.content(5000 + it).also { b -> b[0] = 1 } }
        val lo = (0 until 3).map { MiniServer.content(3000 + it).also { b -> b[0] = 2 } }
        fun media(prefix: String) = "#EXTM3U\n" + (0 until 3).joinToString("") { "#EXTINF:2.0,\n$prefix-$it.ts\n" } + "#EXT-X-ENDLIST\n"
        hi.forEachIndexed { i, b -> server.routes["/s/hi-$i.ts"] = MiniServer.Route(b, "video/mp2t") }
        lo.forEachIndexed { i, b -> server.routes["/s/lo-$i.ts"] = MiniServer.Route(b, "video/mp2t") }
        server.routes["/s/hi.m3u8"] = MiniServer.Route(media("hi").toByteArray(), "application/vnd.apple.mpegurl")
        server.routes["/s/lo.m3u8"] = MiniServer.Route(media("lo").toByteArray(), "application/vnd.apple.mpegurl")
        server.routes["/s/clip.m3u8"] = MiniServer.Route(
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720\nhi.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlo.m3u8\n".toByteArray(),
            "application/vnd.apple.mpegurl",
        )
        compose.runOnUiThread { compose.activity.incoming = Incoming.Download(DownloadRequest("${server.base}/s/clip.m3u8")) }
        compose.waitUntilAtLeastOneExists(hasTestTag("quality-360"), 20_000)
        compose.onNodeWithTag("quality-360").performClick()
        compose.onNodeWithTag("start-download").performClick()
        compose.waitUntil(10_000) { app.engine.downloads.isNotEmpty() }
        val id = app.engine.downloads.first().id
        waitDone(id)
        val item = app.engine.get(id)!!
        assertEquals(item.error, DownloadStatus.COMPLETED, item.status)
        assertEquals("clip.ts", item.fileName)
        assertArrayEquals(lo.reduce { a, b -> a + b }, File(item.filePath).readBytes())
    }

    /**
     * Real stream from Apple's public test server: HEVC video and audio as separate fMP4 tracks.
     * Run with -Pandroid.testInstrumentationRunnerArguments.realNetwork=true
     */
    @Test fun realHlsWithSeparateAudioIsMergedIntoOnePlayableFile() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realNetwork") == "true")
        val engine = DownloadEngine(EngineConfig(defaultSaveDir = dir.path, categorySubfolders = false), InMemoryStore())
        val url = "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_adv_example_hevc/master.m3u8"
        val qualities = runBlocking { engine.streamQualities(url) }
        assertTrue(qualities.size > 3)
        val item = engine.add(DownloadRequest(url, streamHeight = qualities.filter { it.height > 0 }.minOf { it.height }, maxConnections = 8))
        val done = runBlocking {
            withTimeout(15 * 60_000) {
                engine.state.first { s -> s.downloads.first().status.let { it == DownloadStatus.COMPLETED || it == DownloadStatus.FAILED } }
            }.downloads.first()
        }
        assertEquals(done.error, DownloadStatus.COMPLETED, done.status)
        val ex = MediaExtractor()
        ex.setDataSource(done.filePath)
        val mimes = (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
        ex.release()
        assertTrue("tracks: $mimes", mimes.any { it.startsWith("video/") } && mimes.any { it.startsWith("audio/") })
        assertTrue(done.fileName.endsWith(".mp4"))
        android.util.Log.i("ImadTest", "real stream ok: ${done.fileName} ${done.totalBytes} bytes, tracks $mimes")
    }
}
