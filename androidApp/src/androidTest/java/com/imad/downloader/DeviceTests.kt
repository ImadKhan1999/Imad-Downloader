package com.imad.downloader

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The shared engine running on a real Android device / emulator. */
@RunWith(AndroidJUnit4::class)
class EngineOnDeviceTest {
    private lateinit var server: MiniServer
    private lateinit var dir: File

    @Before fun setUp() {
        server = MiniServer()
        dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "engine-test").apply { deleteRecursively(); mkdirs() }
    }

    @After fun tearDown() { server.close(); dir.deleteRecursively() }

    private fun DownloadEngine.awaitStatus(id: String, vararg s: DownloadStatus) = runBlocking {
        withTimeout(60_000) { state.first { st -> st.downloads.first { it.id == id }.status in s } }.downloads.first { it.id == id }
    }

    @Test fun multiConnectionDownloadIsByteIdentical() {
        val body = MiniServer.content(4 * 1024 * 1024 + 7)
        server.routes["/big.bin"] = MiniServer.Route(body, "application/octet-stream")
        val engine = DownloadEngine(EngineConfig(defaultSaveDir = dir.path, categorySubfolders = false, minSplitBytes = 128 * 1024), InMemoryStore())
        val item = engine.add(DownloadRequest("${server.base}/big.bin", maxConnections = 8))
        val done = engine.awaitStatus(item.id, DownloadStatus.COMPLETED, DownloadStatus.FAILED)
        assertEquals(done.error, DownloadStatus.COMPLETED, done.status)
        assertTrue(done.segments.size >= 8)
        assertArrayEquals(body, File(dir, "big.bin").readBytes())
    }

    @Test fun pauseAndResumeOnDevice() {
        val body = MiniServer.content(2 * 1024 * 1024)
        server.routes["/slow.bin"] = MiniServer.Route(body, "application/octet-stream", bytesPerSecond = 300_000)
        val engine = DownloadEngine(EngineConfig(defaultSaveDir = dir.path, categorySubfolders = false, progressIntervalMillis = 100), InMemoryStore())
        val item = engine.add(DownloadRequest("${server.base}/slow.bin", maxConnections = 4))
        runBlocking { withTimeout(30_000) { engine.state.first { s -> s.downloads.first().downloadedBytes > 200_000 } } }
        engine.pause(item.id)
        val paused = engine.awaitStatus(item.id, DownloadStatus.PAUSED)
        assertTrue(paused.downloadedBytes in 1 until body.size)
        engine.start(item.id)
        val done = engine.awaitStatus(item.id, DownloadStatus.COMPLETED, DownloadStatus.FAILED)
        assertEquals(DownloadStatus.COMPLETED, done.status)
        assertArrayEquals(body, File(dir, "slow.bin").readBytes())
    }
}

/** The real app UI, driven like a user. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AppUiTest {
    // Granted up front so the permission prompt cannot cover the app.
    @get:Rule(order = 0) val notifications: androidx.test.rule.GrantPermissionRule =
        androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var server: MiniServer
    private val app get() = compose.activity.application as ImadApp

    @Before fun setUp() {
        server = MiniServer()
        app.engine.downloads.forEach { app.engine.remove(it.id, deleteFile = true) }
        app.settings.update { it.copy(askBeforeDownload = true, clipboardMonitor = false, wifiOnly = false, autoUpdate = false) }
    }

    @After fun tearDown() = server.close()

    @Test fun downloadAppearsInListAndCompletes() {
        server.routes["/files/report.pdf"] = MiniServer.Route(MiniServer.content(600_000), "application/pdf")
        val dir = File(compose.activity.cacheDir, "ui-test").apply { mkdirs() }
        compose.runOnUiThread { app.engine.add(DownloadRequest("${server.base}/files/report.pdf", saveDir = dir.path)) }
        compose.waitUntilAtLeastOneExists(hasTestTag("item-report.pdf"), 15_000)
        compose.waitUntilAtLeastOneExists(hasText("Completed", substring = true), 30_000)
        assertEquals(600_000L, File(dir, "report.pdf").length())
    }

    @Test fun browserCapturesPageDownloadAndDetectsVideo() {
        server.routes["/clip.mp4"] = MiniServer.Route(MiniServer.content(400_000), "video/mp4")
        server.routes["/files/setup.zip"] = MiniServer.Route(MiniServer.content(900_000), "application/zip", attachmentName = "setup.zip")
        server.routes["/page.html"] = MiniServer.Route(
            """
            <html><head><title>Test page</title></head><body>
            <h1>Files</h1>
            <video src="/clip.mp4" preload="auto" width="320" height="180"></video>
            <a id="dl" href="/files/setup.zip">Download setup</a>
            <script>setTimeout(function(){ location.href = '/files/setup.zip'; }, 1500);</script>
            </body></html>
            """.trimIndent().toByteArray(),
            "text/html",
        )
        compose.onNodeWithTag("nav-browser").performClick()
        compose.onNodeWithTag("address-bar").performClick()
        compose.onNodeWithTag("address-bar").performTextInput("${server.base}/page.html")
        compose.onNodeWithTag("address-bar").performImeAction()

        // The page's <video> is detected and the floating media button appears.
        compose.waitUntilAtLeastOneExists(hasTestTag("media-fab"), 20_000)
        // The page starts a download: the add sheet opens with the server's file name.
        compose.waitUntilAtLeastOneExists(hasText("New download"), 20_000)
        compose.waitUntilAtLeastOneExists(hasText("setup.zip"), 10_000)
        compose.onNodeWithTag("start-download").performClick()
        compose.waitUntil(15_000) { app.engine.downloads.any { it.url.endsWith("/files/setup.zip") } }
        val added = app.engine.downloads.first { it.url.endsWith("/files/setup.zip") }

        compose.onNodeWithTag("nav-downloads").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("item-${added.fileName}"), 15_000)
        compose.waitUntil(30_000) { app.engine.get(added.id)?.status == DownloadStatus.COMPLETED || app.engine.get(added.id)?.status == DownloadStatus.FAILED }
        val item = app.engine.get(added.id)!!
        assertEquals("error: ${item.error}", DownloadStatus.COMPLETED, item.status)
        assertTrue(item.fileName.startsWith("setup"))
        assertEquals("${server.base}/page.html", item.referer)
        assertEquals(900_000L, File(item.filePath).length())
    }
}
