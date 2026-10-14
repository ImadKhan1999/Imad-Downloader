package com.imad.downloader.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EngineTest {
    private lateinit var server: TestServer
    private lateinit var dir: File
    private val scopes = mutableListOf<CoroutineScope>()

    @BeforeTest fun setUp() {
        server = TestServer()
        dir = Files.createTempDirectory("imdl").toFile()
    }

    @AfterTest fun tearDown() {
        scopes.forEach { it.cancel() }
        server.close()
        dir.deleteRecursively()
    }

    private fun engine(
        store: DownloadStore = InMemoryStore(),
        config: EngineConfig = EngineConfig(
            defaultSaveDir = dir.path, categorySubfolders = false, minSplitBytes = 64 * 1024,
            retryDelayMillis = 50, progressIntervalMillis = 50, saveIntervalMillis = 100,
        ),
    ): DownloadEngine {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopes += scope
        return DownloadEngine(config, store, scope)
    }

    private fun DownloadEngine.await(id: String, timeoutMs: Long = 30_000, cond: (DownloadItem) -> Boolean): DownloadItem =
        runBlocking {
            withTimeout(timeoutMs) {
                state.first { s -> s.downloads.firstOrNull { it.id == id }?.let(cond) == true }
            }.downloads.first { it.id == id }
        }

    private fun DownloadEngine.awaitDone(id: String, timeoutMs: Long = 30_000) =
        await(id, timeoutMs) { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED }

    @Test fun `multi connection download produces identical bytes`() {
        val size = 3 * 1024 * 1024 + 123
        val e = engine()
        val item = e.add(DownloadRequest(server.url("big.bin", size), maxConnections = 8))
        val done = e.awaitDone(item.id)
        assertEquals(DownloadStatus.COMPLETED, done.status, done.error)
        assertEquals(size.toLong(), done.totalBytes)
        assertTrue(done.resumable)
        assertTrue(done.segments.size >= 8, "segments: ${done.segments.size}")
        assertContentEquals(TestServer.content(size), File(dir, "big.bin").readBytes())
        assertFalse(File(dir, "big.bin" + FileNames.PART_SUFFIX).exists())
    }

    @Test fun `server without range support uses one stream`() {
        val size = 700_000
        val e = engine()
        val item = e.add(DownloadRequest(server.url("plain.bin", size, "norange" to 1)))
        val done = e.awaitDone(item.id)
        assertEquals(DownloadStatus.COMPLETED, done.status, done.error)
        assertFalse(done.resumable)
        assertContentEquals(TestServer.content(size), File(dir, "plain.bin").readBytes())
    }

    @Test fun `unknown size stream completes`() {
        val size = 300_000
        val e = engine()
        val item = e.add(DownloadRequest(server.url("stream.bin", size, "nolength" to 1)))
        val done = e.awaitDone(item.id)
        assertEquals(DownloadStatus.COMPLETED, done.status, done.error)
        assertEquals(size.toLong(), done.totalBytes)
        assertContentEquals(TestServer.content(size), File(dir, "stream.bin").readBytes())
    }

    @Test fun `pause and resume keeps downloaded bytes`() {
        val size = 2 * 1024 * 1024
        val e = engine()
        val item = e.add(DownloadRequest(server.url("slow.bin", size, "bps" to 400_000), maxConnections = 4))
        e.await(item.id) { it.downloadedBytes > 200_000 }
        e.pause(item.id)
        val paused = e.await(item.id) { it.status == DownloadStatus.PAUSED }
        assertTrue(paused.downloadedBytes in 1 until size, "downloaded ${paused.downloadedBytes}")
        assertTrue(File(dir, "slow.bin" + FileNames.PART_SUFFIX).exists())
        val before = server.requests.get()
        val resumedFrom = paused.downloadedBytes
        e.start(item.id)
        val done = e.awaitDone(item.id, 60_000)
        assertEquals(DownloadStatus.COMPLETED, done.status, done.error)
        assertTrue(server.requests.get() > before)
        assertTrue(resumedFrom > 0)
        assertContentEquals(TestServer.content(size), File(dir, "slow.bin").readBytes())
    }

    @Test fun `resume survives an app restart`() {
        val size = 1536 * 1024
        val storeFile = File(dir, "state/downloads.json")
        val e1 = engine(JsonFileStore(storeFile))
        val item = e1.add(DownloadRequest(server.url("restart.bin", size, "bps" to 500_000), maxConnections = 3))
        e1.await(item.id) { it.downloadedBytes > 150_000 }
        e1.shutdown()
        val saved = JsonFileStore(storeFile).load().downloads.single()
        assertEquals(DownloadStatus.PAUSED, saved.status)
        assertTrue(saved.segments.isNotEmpty() && saved.downloadedBytes > 0)

        val e2 = engine(JsonFileStore(storeFile))
        assertEquals(DownloadStatus.PAUSED, e2.get(item.id)?.status)
        e2.start(item.id)
        val done = e2.awaitDone(item.id, 60_000)
        assertEquals(DownloadStatus.COMPLETED, done.status, done.error)
        assertContentEquals(TestServer.content(size), File(dir, "restart.bin").readBytes())
    }

    @Test fun `dropped connections are retried`() {
        val size = 1_000_000
        val e = engine()
        val item = e.add(DownloadRequest(server.url("flaky.bin", size, "drop" to 100_000, "dropTimes" to 3), maxConnections = 1))
        val done = e.awaitDone(item.id)
        assertEquals(DownloadStatus.COMPLETED, done.status, done.error)
        assertContentEquals(TestServer.content(size), File(dir, "flaky.bin").readBytes())
    }

    @Test fun `busy server answering 429 is retried`() {
        val size = 300_000
        val e = engine()
        val item = e.add(DownloadRequest(server.url("busy.bin", size, "busy" to 3)))
        val done = e.awaitDone(item.id)
        assertEquals(DownloadStatus.COMPLETED, done.status, done.error)
        assertContentEquals(TestServer.content(size), File(dir, "busy.bin").readBytes())
    }

    @Test fun `connection limit makes extra connections step back`() {
        val size = 2_000_000
        val e = engine()
        val item = e.add(DownloadRequest(server.url("limited2.bin", size, "limit" to 2, "bps" to 2_000_000), maxConnections = 8))
        val done = e.awaitDone(item.id, 60_000)
        assertEquals(DownloadStatus.COMPLETED, done.status, done.error)
        assertContentEquals(TestServer.content(size), File(dir, "limited2.bin").readBytes())
    }

    @Test fun `missing file fails with a readable error`() {
        val e = engine()
        val item = e.add(DownloadRequest("${server.base}/status/404"))
        val done = e.awaitDone(item.id)
        assertEquals(DownloadStatus.FAILED, done.status)
        assertTrue(done.error!!.contains("404"))
    }

    @Test fun `follows redirects and uses content disposition name`() {
        val target = server.url("x", 5000, "cd" to "Real Name.zip")
        val e = engine()
        val item = e.add(DownloadRequest("${server.base}/redirect?to=" + java.net.URLEncoder.encode(target, "UTF-8")))
        val done = e.awaitDone(item.id)
        assertEquals(DownloadStatus.COMPLETED, done.status, done.error)
        assertEquals("Real Name.zip", done.fileName)
        assertEquals(Category.COMPRESSED, done.category)
        assertTrue(File(dir, "Real Name.zip").exists())
    }

    @Test fun `user chosen name is kept and duplicates are renamed`() {
        val e = engine()
        val a = e.add(DownloadRequest(server.url("dup.bin", 4000, "cd" to "server.bin"), fileName = "mine.bin"))
        assertEquals(DownloadStatus.COMPLETED, e.awaitDone(a.id).status)
        val b = e.add(DownloadRequest(server.url("dup.bin", 4000), fileName = "mine.bin"))
        val done = e.awaitDone(b.id)
        assertEquals("mine (1).bin", done.fileName)
        assertTrue(File(dir, "mine.bin").exists() && File(dir, "mine (1).bin").exists())
    }

    @Test fun `checksum mismatch fails and match passes`() {
        val size = 10_000
        val good = java.security.MessageDigest.getInstance("SHA-256").digest(TestServer.content(size)).joinToString("") { "%02x".format(it) }
        val e = engine()
        val ok = e.add(DownloadRequest(server.url("sum.bin", size), expectedChecksum = good))
        assertEquals(DownloadStatus.COMPLETED, e.awaitDone(ok.id).status)
        val bad = e.add(DownloadRequest(server.url("sum2.bin", size), expectedChecksum = "0".repeat(64)))
        val failed = e.awaitDone(bad.id)
        assertEquals(DownloadStatus.FAILED, failed.status)
        assertTrue(failed.error!!.contains("Checksum"))
    }

    @Test fun `global speed limit slows the download`() {
        val size = 600_000
        val e = engine(config = EngineConfig(defaultSaveDir = dir.path, categorySubfolders = false, globalSpeedLimit = 300_000, progressIntervalMillis = 50))
        val t0 = System.currentTimeMillis()
        val item = e.add(DownloadRequest(server.url("limited.bin", size)))
        val done = e.awaitDone(item.id)
        val elapsed = System.currentTimeMillis() - t0
        assertEquals(DownloadStatus.COMPLETED, done.status, done.error)
        assertTrue(elapsed >= 1_200, "took $elapsed ms")
    }

    @Test fun `concurrency limit holds extra downloads in the queue`() {
        val e = engine(config = EngineConfig(defaultSaveDir = dir.path, categorySubfolders = false, maxConcurrentDownloads = 1, progressIntervalMillis = 50))
        val a = e.add(DownloadRequest(server.url("a.bin", 400_000, "bps" to 800_000)))
        val b = e.add(DownloadRequest(server.url("b.bin", 1000)))
        e.await(a.id) { it.status == DownloadStatus.DOWNLOADING }
        assertEquals(DownloadStatus.QUEUED, e.get(b.id)?.status)
        assertEquals(DownloadStatus.COMPLETED, e.awaitDone(a.id).status)
        assertEquals(DownloadStatus.COMPLETED, e.awaitDone(b.id).status)
    }

    @Test fun `queue runs its items and reports when finished`() = runBlocking {
        val e = engine()
        val q = e.addQueue("Night", maxConcurrent = 1)
        val ids = (1..3).map { e.add(DownloadRequest(server.url("q$it.bin", 50_000), queueId = q.id), start = false).id }
        ids.forEach { assertEquals(DownloadStatus.PAUSED, e.get(it)?.status) }
        val finished = async { withTimeout(30_000) { e.queueFinished.first() } }
        kotlinx.coroutines.yield()
        e.startQueue(q.id)
        assertEquals(q.id, finished.await().id)
        ids.forEach { assertEquals(DownloadStatus.COMPLETED, e.get(it)?.status) }
        assertFalse(e.queues.first { it.id == q.id }.running)
    }

    @Test fun `remove deletes partial data and redownload starts over`() {
        val size = 1_000_000
        val e = engine()
        val item = e.add(DownloadRequest(server.url("r.bin", size, "bps" to 300_000)))
        e.await(item.id) { it.downloadedBytes > 50_000 }
        e.remove(item.id)
        Thread.sleep(300)
        assertEquals(null, e.get(item.id))
        assertFalse(File(dir, "r.bin" + FileNames.PART_SUFFIX).exists())

        val again = e.add(DownloadRequest(server.url("r2.bin", 20_000)))
        assertEquals(DownloadStatus.COMPLETED, e.awaitDone(again.id).status)
        e.redownload(again.id)
        e.await(again.id) { it.status != DownloadStatus.COMPLETED }
        val done = e.awaitDone(again.id)
        assertEquals(DownloadStatus.COMPLETED, done.status)
        assertContentEquals(TestServer.content(20_000), File(dir, "r2.bin").readBytes())
    }

    @Test fun `category sub folders are used by default`() {
        val e = engine(config = EngineConfig(defaultSaveDir = dir.path, progressIntervalMillis = 50))
        val item = e.add(DownloadRequest(server.url("song.mp3", 2000)))
        val done = e.awaitDone(item.id)
        assertEquals(DownloadStatus.COMPLETED, done.status, done.error)
        assertTrue(File(dir, "Music/song.mp3").exists())
    }

    @Test fun `hls stream with aes-128 is decrypted and joined`() {
        val key = ByteArray(16) { (it * 7).toByte() }
        val plain = (0 until 5).map { TestServer.content(10_000 + it * 1000).also { b -> b[0] = it.toByte() } }
        val mediaSeq = 3L
        plain.forEachIndexed { i, bytes ->
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(Hls.ivForSequence(mediaSeq + i)))
            server.static["/hls/s$i.ts"] = c.doFinal(bytes) to "video/mp2t"
        }
        server.static["/hls/key.bin"] = key to "application/octet-stream"
        val media = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-MEDIA-SEQUENCE:$mediaSeq")
            appendLine("#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"")
            plain.indices.forEach { appendLine("#EXTINF:2.0,"); appendLine("s$it.ts") }
            appendLine("#EXT-X-ENDLIST")
        }
        server.static["/hls/media.m3u8"] = media.toByteArray() to "application/vnd.apple.mpegurl"
        server.static["/hls/master.m3u8"] = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nmedia.m3u8\n".toByteArray() to "application/vnd.apple.mpegurl"

        val e = engine()
        val item = e.add(DownloadRequest("${server.base}/hls/master.m3u8"))
        assertEquals(DownloadKind.HLS, item.kind)
        val done = e.awaitDone(item.id)
        assertEquals(DownloadStatus.COMPLETED, done.status, done.error)
        assertEquals("master.ts", done.fileName)
        assertEquals(5, done.piecesTotal)
        val expected = plain.fold(ByteArray(0)) { acc, b -> acc + b }
        assertContentEquals(expected, File(dir, "master.ts").readBytes())
    }

    @Test fun `probe reports size name and resumability`() = runBlocking {
        val e = engine()
        val p = e.probe(server.url("probe.iso", 12345, "cd" to "disk.iso"))
        assertEquals(12345, p.totalBytes)
        assertTrue(p.resumable)
        assertEquals("disk.iso", p.fileName)
        assertEquals(Category.COMPRESSED, p.category)
        val n = e.probe(server.url("p2.bin", 999, "norange" to 1))
        assertFalse(n.resumable)
        assertEquals(999, n.totalBytes)
    }

    @Test fun `events fire for added and completed`() = runBlocking {
        val e = engine()
        val completed = async { withTimeout(20_000) { e.events.first { it is EngineEvent.Completed } } }
        kotlinx.coroutines.yield()
        val item = e.add(DownloadRequest(server.url("ev.bin", 1000)))
        assertEquals(item.id, completed.await().item.id)
        assertNotNull(e.get(item.id))
    }
}
