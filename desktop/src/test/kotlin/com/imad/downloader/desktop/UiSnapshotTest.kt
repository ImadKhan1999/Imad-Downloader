package com.imad.downloader.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.imad.downloader.core.Category
import com.imad.downloader.core.DownloadItem
import com.imad.downloader.core.DownloadKind
import com.imad.downloader.core.DownloadQueue
import com.imad.downloader.core.DownloadRequest
import com.imad.downloader.core.DownloadStatus
import com.imad.downloader.core.EngineState
import com.imad.downloader.core.InMemoryStore
import com.imad.downloader.core.ProbeResult
import com.imad.downloader.core.SegmentState
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the real UI headlessly and writes PNGs to docs/screenshots. Doubles as a smoke test:
 * any crash while composing the main window or a dialog fails the build.
 */
class UiSnapshotTest {
    private val out = File(System.getProperty("snapshotDir") ?: "build/snapshots").apply { mkdirs() }

    private fun sample(): EngineState {
        val now = System.currentTimeMillis()
        val dir = "C:\\Users\\Imad\\Downloads\\Imad Downloader"
        fun segs(total: Long, n: Int, frac: Double) = (0 until n).map { i ->
            val len = total / n
            SegmentState(i * len, if (i == n - 1) total - 1 else (i + 1) * len - 1, (len * (frac + (i % 3) * 0.08)).toLong().coerceAtMost(len))
        }
        val big = 1_852_000_000L
        return EngineState(
            downloads = listOf(
                DownloadItem("1", "https://cdn.example.com/os/ubuntu-26.04-desktop-amd64.iso", "ubuntu-26.04-desktop-amd64.iso", "$dir\\Compressed", Category.COMPRESSED,
                    status = DownloadStatus.DOWNLOADING, totalBytes = big, downloadedBytes = (big * 0.62).toLong(), resumable = true,
                    segments = segs(big, 8, 0.55), speedBps = 9_650_000, etaSeconds = 73, activeConnections = 8, createdAt = now - 600_000, lastTryAt = now - 60_000),
                DownloadItem("2", "https://media.example.com/trailer-4k.mp4", "Movie Trailer 4K.mp4", "$dir\\Video", Category.VIDEO,
                    status = DownloadStatus.DOWNLOADING, totalBytes = 734_003_200, downloadedBytes = 211_000_000, resumable = true,
                    speedBps = 4_200_000, etaSeconds = 125, activeConnections = 8, createdAt = now - 300_000, lastTryAt = now - 30_000),
                DownloadItem("3", "https://dl.example.com/VSCodeUserSetup-x64.exe", "VSCodeUserSetup-x64.exe", "$dir\\Programs", Category.PROGRAMS,
                    status = DownloadStatus.COMPLETED, totalBytes = 103_809_024, downloadedBytes = 103_809_024, resumable = true, createdAt = now - 3_600_000, completedAt = now - 3_500_000, description = "Editor installer"),
                DownloadItem("4", "https://music.example.com/album/track01.flac", "01 - Opening Theme.flac", "$dir\\Music", Category.MUSIC,
                    status = DownloadStatus.PAUSED, totalBytes = 41_943_040, downloadedBytes = 18_000_000, resumable = true, createdAt = now - 7_200_000, lastTryAt = now - 7_000_000),
                DownloadItem("5", "https://papers.example.org/attention.pdf", "Research Paper.pdf", "$dir\\Documents", Category.DOCUMENTS,
                    status = DownloadStatus.COMPLETED, totalBytes = 2_215_000, downloadedBytes = 2_215_000, createdAt = now - 86_400_000, completedAt = now - 86_300_000),
                DownloadItem("6", "https://live.example.com/stream/master.m3u8", "Live Concert.ts", "$dir\\Video", Category.VIDEO, kind = DownloadKind.HLS,
                    status = DownloadStatus.DOWNLOADING, downloadedBytes = 96_000_000, piecesDone = 212, piecesTotal = 540, speedBps = 2_900_000, etaSeconds = 190, activeConnections = 6, createdAt = now - 200_000),
                DownloadItem("7", "https://files.example.com/expired/backup.zip", "backup-2026.zip", "$dir\\Compressed", Category.COMPRESSED,
                    status = DownloadStatus.FAILED, totalBytes = 5_400_000_000, downloadedBytes = 1_200_000_000, resumable = true,
                    error = "Access denied (403). The link may have expired; refresh the address.", createdAt = now - 172_800_000),
                DownloadItem("8", "https://img.example.com/wallpaper-8k.png", "wallpaper-8k.png", "$dir\\Images", Category.IMAGES,
                    status = DownloadStatus.QUEUED, totalBytes = 48_000_000, createdAt = now - 100_000, queueId = "night"),
                DownloadItem("9", "https://dl.example.com/android-studio.zip", "android-studio-2026.1.zip", "$dir\\Compressed", Category.COMPRESSED,
                    status = DownloadStatus.COMPLETED, totalBytes = 1_288_490_188, downloadedBytes = 1_288_490_188, createdAt = now - 250_000_000, completedAt = now - 249_000_000),
            ),
            queues = listOf(
                DownloadQueue(EngineState.MAIN_QUEUE_ID, "Main queue", 3),
                DownloadQueue("night", "Night downloads", 2, running = true),
            ),
        )
    }

    private fun app(theme: ThemeMode = ThemeMode.LIGHT): AppState {
        val settingsFile = Files.createTempFile("settings", ".json").toFile().apply { delete() }
        val store = SettingsStore(settingsFile)
        store.save(DesktopSettings(theme = theme, saveDir = "C:\\Users\\Imad\\Downloads\\Imad Downloader"))
        return AppState(store, InMemoryStore(sample()), preview = true)
    }

    private fun snap(name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width, height, Density(1f)) {
            CompositionLocalProvider(LocalInlineDialogs provides true) { content() }
        }
        try {
            scene.render(0)
            scene.render(500_000_000)
            val img = scene.render(1_000_000_000)
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(out, "$name.png").writeBytes(png)
            assertTrue(png.size > 5_000, "$name rendered nearly empty")
        } finally {
            scene.close()
        }
    }

    @Test fun `main window light`() {
        val a = app()
        a.selection += "1"
        snap("windows-main-light", 1280, 720) { ImadTheme(ThemeMode.LIGHT) { MainContent(a) } }
    }

    @Test fun `main window dark`() {
        val a = app(ThemeMode.DARK)
        a.selection += "2"
        snap("windows-main-dark", 1280, 720) { ImadTheme(ThemeMode.DARK) { MainContent(a) } }
    }

    @Test fun `progress window`() {
        val a = app()
        val item = a.engine.get("1")!!
        snap("windows-progress", 600, 520) { ImadTheme(ThemeMode.LIGHT) { ProgressContent(a, item, true) {} } }
    }

    @Test fun `file info dialog`() {
        val a = app()
        val req = DownloadRequest("https://cdn.example.com/os/ubuntu-26.04-desktop-amd64.iso")
        a.fileInfo = FileInfoState(
            request = req, probing = false,
            probe = ProbeResult(req.url, "ubuntu-26.04-desktop-amd64.iso", 6_234_000_000, true, "application/x-iso9660-image", null, null, false),
            category = Category.COMPRESSED, saveDir = "C:\\Users\\Imad\\Downloads\\Imad Downloader\\Compressed",
        )
        snap("windows-file-info", 620, 380) { AllDialogs(a) }
    }

    @Test fun `options dialog`() {
        val a = app()
        a.optionsOpen = true
        snap("windows-options", 820, 600) { AllDialogs(a) }
    }

    @Test fun `scheduler dialog`() {
        val a = app()
        a.schedulerOpen = true
        snap("windows-scheduler", 860, 600) { AllDialogs(a) }
    }

    @Test fun `empty state`() {
        val settingsFile = Files.createTempFile("settings", ".json").toFile().apply { delete() }
        val a = AppState(SettingsStore(settingsFile), InMemoryStore(), preview = true)
        snap("windows-empty", 1100, 600) { ImadTheme(ThemeMode.LIGHT) { MainContent(a) } }
    }
}
