package com.imad.downloader.core

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileNamesTest {
    @Test fun `content disposition plain quoted and extended`() {
        assertEquals("report.pdf", FileNames.fromContentDisposition("attachment; filename=\"report.pdf\""))
        assertEquals("report.pdf", FileNames.fromContentDisposition("attachment; filename=report.pdf"))
        assertEquals("naïve file.txt", FileNames.fromContentDisposition("attachment; filename=\"fallback.txt\"; filename*=UTF-8''na%C3%AFve%20file.txt"))
        assertEquals("a;b.zip", FileNames.fromContentDisposition("attachment; filename=\"a;b.zip\""))
        assertNull(FileNames.fromContentDisposition("inline"))
        assertNull(FileNames.fromContentDisposition(null))
    }

    @Test fun `file name from url is decoded and ignores query`() {
        assertEquals("my file.zip", FileNames.fromUrl("https://x.com/dl/my%20file.zip?token=abc#frag"))
        assertEquals("setup.exe", FileNames.fromUrl("https://x.com/a/b/setup.exe"))
        assertNull(FileNames.fromUrl("https://x.com/"))
    }

    @Test fun `sanitize removes illegal characters and reserved names`() {
        assertEquals("a_b_c_.txt", FileNames.sanitize("a<b>c?.txt"))
        assertEquals("_CON.txt", FileNames.sanitize("CON.txt"))
        assertEquals("download", FileNames.sanitize("   ...  "))
        val long = "x".repeat(400) + ".mp4"
        val s = FileNames.sanitize(long)
        assertTrue(s.length <= 200 && s.endsWith(".mp4"))
    }

    @Test fun `resolve adds extension from mime`() {
        assertEquals("watch.mp4", FileNames.resolve("https://v.com/watch", null, "video/mp4"))
        assertEquals("page", FileNames.resolve("https://v.com/page", null, "text/html"))
        assertEquals("x.bin", FileNames.resolve("https://v.com/a", "attachment; filename=x.bin", "video/mp4"))
    }

    @Test fun `unique appends counters`() {
        val dir = Files.createTempDirectory("names").toFile()
        File(dir, "a.zip").writeText("1")
        File(dir, "a (1).zip").writeText("1")
        assertEquals("a (2).zip", FileNames.unique(dir, "a.zip"))
        assertEquals("b (1).zip", FileNames.unique(dir, "b.zip", setOf("B.ZIP")))
        assertEquals("c", FileNames.unique(dir, "c"))
        dir.deleteRecursively()
    }
}

class CategoryTest {
    @Test fun `detects by extension then mime`() {
        assertEquals(Category.VIDEO, Category.detect("movie.MKV"))
        assertEquals(Category.COMPRESSED, Category.detect("a.7z"))
        assertEquals(Category.PROGRAMS, Category.detect("app.apk"))
        assertEquals(Category.MUSIC, Category.detect("song", "audio/mpeg"))
        assertEquals(Category.DOCUMENTS, Category.detect("x", "application/pdf"))
        assertEquals(Category.OTHER, Category.detect("x.unknown"))
    }
}

class SegmentPlannerTest {
    @Test fun `split covers every byte exactly once`() {
        for ((total, count) in listOf(10L to 3, 1L to 8, 1_000_003L to 8, 100L to 100)) {
            val r = SegmentPlanner.split(total, count)
            assertEquals(0, r.first().first)
            assertEquals(total - 1, r.last().last)
            r.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
            val sizes = r.map { it.last - it.first + 1 }
            assertTrue(sizes.max() - sizes.min() <= 1)
        }
        assertEquals(1, SegmentPlanner.split(1, 8).size)
    }
}

class BatchUrlsTest {
    @Test fun `expands numeric ranges with padding`() {
        val urls = BatchUrls.expand("http://x/img[008-011].jpg")
        assertEquals(listOf("http://x/img008.jpg", "http://x/img009.jpg", "http://x/img010.jpg", "http://x/img011.jpg"), urls)
        assertEquals(3, BatchUrls.expand("http://x/[1-3]").size)
    }

    @Test fun `expands letters and nested ranges`() {
        assertEquals(listOf("a", "b", "c").map { "http://x/$it.zip" }, BatchUrls.expand("http://x/[a-c].zip"))
        assertEquals(6, BatchUrls.expand("http://x/[1-2]/[a-c]").size)
    }

    @Test fun `extracts links from text`() {
        val text = "see https://a.com/x.zip, and (http://b.org/y.mp4). also https://a.com/x.zip"
        assertEquals(listOf("https://a.com/x.zip", "http://b.org/y.mp4"), BatchUrls.extractLinks(text))
    }
}

class ChecksumsTest {
    @Test fun `hashes and matches by length or prefix`() {
        val f = Files.createTempFile("sum", ".txt").toFile()
        f.writeText("hello")
        assertEquals("5d41402abc4b2a76b9719d911017c592", Checksums.hash(f, "MD5"))
        assertTrue(Checksums.matches(f, "5D41402ABC4B2A76B9719D911017C592"))
        assertTrue(Checksums.matches(f, "sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"))
        assertFalse(Checksums.matches(f, "sha1:0000000000000000000000000000000000000000"))
        f.delete()
    }
}

class FormatTest {
    @Test fun `formats sizes and durations`() {
        assertEquals("512 B", Format.bytes(512))
        assertEquals("1.00 KB", Format.bytes(1024))
        assertEquals("1.50 MB", Format.bytes(1572864))
        assertEquals("Unknown", Format.bytes(-1))
        assertEquals("5 sec", Format.duration(5))
        assertEquals("2 min 05 sec", Format.duration(125))
        assertEquals("1 h 01 min", Format.duration(3660))
    }
}

class SpeedTest {
    @Test fun `limiter holds the configured rate`() = runTest {
        val limiter = SpeedLimiter(100_000) { testScheduler.currentTime * 1_000_000 }
        val start = testScheduler.currentTime
        repeat(30) { limiter.acquire(10_000) } // 300 KB at 100 KB/s
        val elapsed = testScheduler.currentTime - start
        assertTrue(elapsed in 1_900..3_100, "elapsed $elapsed ms")
    }

    @Test fun `unlimited limiter never waits`() = runTest {
        val limiter = SpeedLimiter(0) { testScheduler.currentTime * 1_000_000 }
        repeat(100) { limiter.acquire(1_000_000) }
        assertEquals(0, testScheduler.currentTime)
    }

    @Test fun `meter computes speed and eta`() {
        val m = SpeedMeter()
        m.sample(0, 0)
        m.sample(1000, 500_000)
        m.sample(2000, 1_000_000)
        assertEquals(500_000, m.bytesPerSecond())
        assertEquals(4, m.etaSeconds(2_000_000))
    }
}

class JsonStoreTest {
    @Test fun `round trips state and survives a corrupt main file`() {
        val dir = Files.createTempDirectory("store").toFile()
        val f = File(dir, "state.json")
        val store = JsonFileStore(f)
        val item = DownloadItem(id = "1", url = "http://x/a.zip", fileName = "a.zip", saveDir = dir.path, segments = listOf(SegmentState(0, 9, 4)))
        store.save(EngineState(downloads = listOf(item)))
        store.save(EngineState(downloads = listOf(item, item.copy(id = "2"))))
        assertEquals(2, store.load().downloads.size)
        f.writeText("{corrupt")
        assertEquals(1, store.load().downloads.size, "falls back to .bak")
        dir.deleteRecursively()
    }
}

class SchedulerTest {
    private fun q(running: Boolean = false, start: Int = 120, stop: Int? = 300, days: Set<Int> = emptySet()) =
        DownloadQueue("q", "Night", schedule = QueueSchedule(start, stop, days), running = running)

    @Test fun `starts once inside the window and stops after it`() {
        val engine = DownloadEngine(EngineConfig(defaultSaveDir = "."), InMemoryStore())
        val s = QueueScheduler(engine)
        val day = LocalDateTime.of(2026, 10, 14, 0, 0) // Wednesday
        assertTrue(s.evaluate(listOf(q()), day.withHour(1)).isEmpty())
        assertEquals(listOf<QueueScheduler.Action>(QueueScheduler.Action.Start("q")), s.evaluate(listOf(q()), day.withHour(2).withMinute(1)))
        assertTrue(s.evaluate(listOf(q()), day.withHour(3)).isEmpty(), "only once per day")
        assertEquals(listOf<QueueScheduler.Action>(QueueScheduler.Action.Stop("q")), s.evaluate(listOf(q(running = true)), day.withHour(5).withMinute(1)))
    }

    @Test fun `respects weekdays`() {
        val engine = DownloadEngine(EngineConfig(defaultSaveDir = "."), InMemoryStore())
        val s = QueueScheduler(engine)
        val wednesday = LocalDateTime.of(2026, 10, 14, 2, 30)
        assertTrue(s.evaluate(listOf(q(days = setOf(6, 7))), wednesday).isEmpty())
        assertEquals(1, s.evaluate(listOf(q(days = setOf(3))), wednesday).size)
    }
}

class HlsParserTest {
    @Test fun `parses master and media playlists`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2"
            https://cdn.x.com/hi/index.m3u8
        """.trimIndent()
        assertTrue(Hls.isMaster(master))
        val v = Hls.parseMaster(master, "https://x.com/v/master.m3u8")
        assertEquals("https://cdn.x.com/hi/index.m3u8", v[0].url)
        assertEquals("1280x720", v[0].resolution)
        assertEquals("https://x.com/v/low/index.m3u8", v[1].url)

        val media = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:7
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:4.0,
            s0.ts
            #EXTINF:4.5,
            s1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val p = Hls.parseMedia(media, "https://x.com/v/hi/index.m3u8")
        assertEquals(2, p.pieces.size)
        assertEquals("https://x.com/v/hi/s1.ts", p.pieces[1].url)
        assertEquals(8L, p.pieces[1].sequence)
        assertEquals("https://x.com/v/hi/key.bin", p.pieces[0].key?.uri)
        assertEquals(8.5, p.totalDuration)
        assertFalse(p.isLive)
        assertEquals("clip.ts", Hls.outputName("clip.m3u8", false))
        assertEquals("clip.mp4", Hls.outputName("clip.m3u8", true))
    }
}
