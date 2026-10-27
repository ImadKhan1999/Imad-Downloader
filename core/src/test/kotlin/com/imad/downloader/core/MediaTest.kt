package com.imad.downloader.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class YtDlpJsonTest {
    private val sample = """
        WARNING: some warning line
        {"id":"abc","title":"My  Video: Part/1","webpage_url":"https://www.youtube.com/watch?v=abc","extractor_key":"Youtube",
         "duration":100,"thumbnail":"https://i.ytimg.com/x.jpg",
         "formats":[
          {"format_id":"sb0","ext":"mhtml","protocol":"mhtml","url":"https://x/sb","vcodec":"none","acodec":"none"},
          {"format_id":"140","ext":"m4a","protocol":"https","url":"https://g/140","vcodec":"none","acodec":"mp4a.40.2","tbr":129.5,"filesize":1600000},
          {"format_id":"251","ext":"webm","protocol":"https","url":"https://g/251","vcodec":"none","acodec":"opus","tbr":140},
          {"format_id":"18","ext":"mp4","protocol":"https","url":"https://g/18","vcodec":"avc1.42001E","acodec":"mp4a.40.2","height":360,"width":640,"tbr":500},
          {"format_id":"136","ext":"mp4","protocol":"https","url":"https://g/136","vcodec":"avc1.4d401f","acodec":"none","height":720,"tbr":1500,"filesize":18000000},
          {"format_id":"398","ext":"mp4","protocol":"https","url":"https://g/398","vcodec":"av01.0.05M.08","acodec":"none","height":720,"tbr":1200},
          {"format_id":"137","ext":"mp4","protocol":"https","url":"https://g/137","vcodec":"avc1.640028","acodec":"none","height":1080,"tbr":3000,
           "http_headers":{"User-Agent":"UA-1","Accept":"*/*"}},
          {"format_id":"401","ext":"mp4","protocol":"https","url":"https://g/401","vcodec":"av01.0.12M.08","acodec":"none","height":2160,"tbr":12000},
          {"format_id":"313","ext":"webm","protocol":"https","url":"https://g/313","vcodec":"vp9","acodec":"none","height":2160,"tbr":15000},
          {"format_id":"hls-1","ext":"mp4","protocol":"m3u8_native","url":"https://g/x.m3u8","vcodec":"avc1","acodec":"mp4a","height":480,"tbr":900}
         ]}
    """.trimIndent()

    @Test fun `parses formats and skips unsupported protocols`() {
        val info = YtDlpJson.parse(sample)
        assertEquals("My  Video: Part/1", info.title)
        assertEquals("Youtube", info.extractor)
        assertEquals(100.0, info.durationSeconds)
        assertTrue(info.formats.none { it.id == "sb0" })
        assertEquals("UA-1", info.formats.first { it.id == "137" }.headers["User-Agent"])
    }

    @Test fun `builds a quality list with merged mp4, progressive, hls and audio`() {
        val opts = MediaChoice.options(YtDlpJson.parse(sample))
        assertEquals(listOf("2160p", "1080p", "720p", "480p", "360p", "Audio only (m4a)", "MP3"), opts.map { it.label })
        assertEquals("140>mp3", opts.last().formatSpec)
        assertEquals("401+140", opts[0].formatSpec, "4K only exists as AV1 mp4 (VP9 webm cannot be joined)")
        assertEquals("137+140", opts[1].formatSpec)
        assertEquals("136+140", opts[2].formatSpec, "AVC is preferred over AV1 at the same height")
        assertEquals("hls-1", opts[3].formatSpec)
        assertEquals("18", opts[4].formatSpec)
        assertEquals(18_000_000L + 1_600_000L, opts[2].sizeEstimate)
        assertTrue(opts.last().audioOnly)
        assertEquals("My Video_ Part_1", MediaChoice.safeTitle("My  Video: Part/1"))
    }

    @Test fun `pick keeps the chosen spec and falls back by height`() {
        val info = YtDlpJson.parse(sample)
        assertEquals("137+140", MediaChoice.pick(info, "137+140", 0)!!.formatSpec)
        assertEquals("136+140", MediaChoice.pick(info, "999+140", 720)!!.formatSpec, "format gone: same height")
        assertEquals("401+140", MediaChoice.pick(info, null, 0)!!.formatSpec)
        assertEquals("18", MediaChoice.pick(info, null, 400)!!.formatSpec)
    }

    @Test fun `single file sites and playlists`() {
        val single = YtDlpJson.parse("""{"title":"clip","url":"https://cdn/clip.mp4","ext":"mp4","protocol":"https","format_id":"0"}""")
        assertEquals(1, single.formats.size)
        assertEquals("0", MediaChoice.options(single).single().formatSpec)
        val pl = YtDlpJson.parse("""{"_type":"playlist","entries":[{"title":"first","url":"https://cdn/a.mp4","ext":"mp4","format_id":"x"}]}""")
        assertEquals("first", pl.title)
        assertFailsWith<java.io.IOException> { YtDlpJson.parse("nothing here") }
    }

    @Test fun `known video sites`() {
        assertTrue(MediaChoice.isKnownSite("https://www.youtube.com/watch?v=1"))
        assertTrue(MediaChoice.isKnownSite("https://youtu.be/1"))
        assertTrue(MediaChoice.isKnownSite("https://m.facebook.com/watch/1"))
        assertTrue(!MediaChoice.isKnownSite("https://example.com/a.zip"))
    }

    @Test fun `mainstream apps and music sites`() {
        for (u in listOf("https://vm.tiktok.com/ZMabc/", "https://www.instagram.com/reel/Chunk8-jurw/", "https://m.facebook.com/watch/?v=1",
                "https://x.com/user/status/1", "https://music.youtube.com/watch?v=1", "https://www.threads.net/@a/post/1", "https://pin.it/abc")) {
            assertTrue(MediaChoice.isKnownSite(u), u)
        }
        assertTrue(MediaChoice.isMusicSite("https://music.youtube.com/watch?v=1"))
        assertTrue(!MediaChoice.isMusicSite("https://www.youtube.com/watch?v=1"))
        assertTrue(MediaChoice.isKnownYouTube("https://youtu.be/x") && !MediaChoice.isKnownYouTube("https://vimeo.com/1"))
        assertTrue(MediaChoice.isBotCheck("ERROR: [youtube] x: Sign in to confirm you're not a bot."))
        assertTrue(!MediaChoice.isBotCheck("ERROR: Unsupported URL"))
    }

    @Test fun `only single YouTube videos count as video pages`() {
        assertTrue(MediaChoice.isVideoPage("https://m.youtube.com/watch?v=aqz-KE-bpKQ&pp=x"))
        assertTrue(MediaChoice.isVideoPage("https://www.youtube.com/shorts/abc123"))
        assertTrue(MediaChoice.isVideoPage("https://youtu.be/abc123"))
        assertTrue(!MediaChoice.isVideoPage("https://m.youtube.com/"))
        assertTrue(!MediaChoice.isVideoPage("https://m.youtube.com/results?search_query=cats"))
        assertTrue(!MediaChoice.isVideoPage("https://www.youtube.com/@channel"))
        assertTrue(MediaChoice.isVideoPage("https://vimeo.com/123"))
    }

    @Test fun `phones start at 1080p and skip loudness-compressed audio`() {
        val base = YtDlpJson.parse(sample)
        val info = base.copy(formats = base.formats + MediaFormat("140-drc", "m4a", "https://g/140d", acodec = "mp4a.40.2", tbr = 135.0))
        val opts = MediaChoice.options(info)
        assertEquals("137+140", MediaChoice.defaultOption(opts)!!.formatSpec, "1080p AVC, not 4K AV1, and the normal audio track")
        assertEquals("136+140", MediaChoice.defaultOption(opts, maxHeight = 720)!!.formatSpec)
    }
}

class PlaylistTest {
    private val flat = """
        {"_type":"playlist","title":"Settlers","entries":[
         {"_type":"url","ie_key":"Youtube","id":"BIRCCQHz-B8","url":"https://www.youtube.com/watch?v=BIRCCQHz-B8","title":"Settlers - Modeling: Prep","duration":1103},
         {"_type":"url","ie_key":"Youtube","id":"priv1","url":"https://www.youtube.com/watch?v=priv1","title":"[Private video]","duration":null},
         {"_type":"url","ie_key":"Youtube","id":"x2","title":"Settlers - Setup","duration":927}
        ]}
    """.trimIndent()

    @Test fun `reads a flat playlist and skips private videos`() {
        val pl = YtDlpJson.parsePlaylist("WARNING: x\n$flat")
        assertEquals("Settlers", pl.title)
        assertEquals(listOf("https://www.youtube.com/watch?v=BIRCCQHz-B8", "https://www.youtube.com/watch?v=x2"), pl.entries.map { it.url })
        assertEquals(1103.0, pl.entries[0].durationSeconds)
        assertFailsWith<java.io.IOException> { YtDlpJson.parsePlaylist("""{"_type":"playlist","entries":[]}""") }
    }

    @Test fun `knows playlist links`() {
        assertTrue(MediaChoice.isPlaylist("https://www.youtube.com/playlist?list=PLa1F2ddGya_8Pn07ZxzgxFRw5iQbovXZv"))
        assertTrue(MediaChoice.isPlaylist("https://m.youtube.com/watch?v=abc&list=PLx1"))
        assertTrue(!MediaChoice.isPlaylist("https://www.youtube.com/watch?v=abc"))
        assertTrue(!MediaChoice.isPlaylist("https://example.com/playlist?list=1"))
        assertTrue(MediaChoice.isVideoPage("https://m.youtube.com/playlist?list=PLx1"))
    }

    @Test fun `one download per ticked video in the chosen quality`() {
        val pl = YtDlpJson.parsePlaylist(flat)
        val base = DownloadRequest("https://www.youtube.com/playlist?list=PL1", cookies = "a=b", queueId = "night")
        val video = MediaChoice.playlistRequests(base, pl.entries, PlaylistQuality.all.first { it.height == 720 })
        assertEquals(2, video.size)
        assertEquals("Settlers - Modeling_ Prep.mp4", video[0].fileName)
        assertEquals(720, video[0].streamHeight)
        assertEquals(null, video[0].mediaFormat)
        assertEquals(DownloadKind.MEDIA, video[0].kind)
        assertEquals("a=b", video[1].cookies)
        assertEquals("night", video[1].queueId)
        val audio = MediaChoice.playlistRequests(base, pl.entries.take(1), PlaylistQuality.all.first { it.label == "Audio only" })
        assertEquals("Settlers - Modeling_ Prep.mp3", MediaChoice.playlistRequests(base, pl.entries.take(1), PlaylistQuality.all.last()).single().fileName)
        assertEquals("Settlers - Modeling_ Prep.m4a", audio.single().fileName)
        assertEquals(MediaChoice.AUDIO_ONLY, audio.single().mediaFormat)
    }

    @Test fun `video lengths read like a player`() {
        assertEquals("0:07", Format.clock(7))
        assertEquals("18:23", Format.clock(1103))
        assertEquals("1:02:45", Format.clock(3765))
    }

    @Test fun `audio only picks the sound track`() {
        val info = MediaInfo("t", "u", "Youtube", 10.0, null, listOf(
            MediaFormat("137", "mp4", "https://g/137", vcodec = "avc1", height = 1080, tbr = 3000.0),
            MediaFormat("140", "m4a", "https://g/140", acodec = "mp4a.40.2", tbr = 129.0),
        ))
        assertEquals("140", MediaChoice.pick(info, MediaChoice.AUDIO_ONLY, 0)!!.formatSpec)
        assertEquals("137+140", MediaChoice.pick(info, null, 720)!!.formatSpec)
    }
}

class MediaDownloadTest {
    private lateinit var server: TestServer
    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val extractCalls = AtomicInteger()

    @BeforeTest fun setUp() { server = TestServer(); dir = Files.createTempDirectory("media").toFile() }
    @AfterTest fun tearDown() { scope.cancel(); server.close(); dir.deleteRecursively() }

    private fun video(seed: Int, frags: Int) = Fmp4.file(1, 90_000, "vide", (0 until frags).map { TestServer.content(60_000 + seed + it) }, 180_000)
    private fun audio(frags: Int) = Fmp4.file(1, 48_000, "soun", (0 until frags).map { ByteArray(20_000) { b -> (b * 3 + it).toByte() } }, 96_000)

    /** A fake yt-dlp: hands out the local test files, with a fresh token each call like YouTube. */
    private fun extractor() = object : MediaExtractor {
        override suspend fun extract(url: String, headers: Map<String, String>): MediaInfo {
            val n = extractCalls.incrementAndGet()
            return MediaInfo(
                title = "Test: Video", pageUrl = url, extractor = "Fake", durationSeconds = 8.0,
                formats = listOf(
                    MediaFormat("v1080", "mp4", "${server.base}/m/v1080.mp4?sig=$n", vcodec = "avc1", height = 1080, tbr = 4000.0),
                    MediaFormat("v360", "mp4", "${server.base}/m/v360.mp4?sig=$n&bps=150000", vcodec = "avc1", height = 360, tbr = 800.0),
                    MediaFormat("a", "m4a", "${server.base}/m/a.m4a?sig=$n", acodec = "mp4a", tbr = 128.0),
                ),
            )
        }
    }

    private fun engine() = DownloadEngine(
        EngineConfig(defaultSaveDir = dir.path, categorySubfolders = false, progressIntervalMillis = 50, saveIntervalMillis = 100, minSplitBytes = 32 * 1024),
        InMemoryStore(), scope, mediaExtractor = extractor(),
    )

    private fun DownloadEngine.await(id: String, cond: (DownloadItem) -> Boolean) = runBlocking {
        withTimeout(60_000) { state.first { s -> s.downloads.firstOrNull { it.id == id }?.let(cond) == true } }.downloads.first { it.id == id }
    }

    @Test fun `downloads the chosen quality and joins video and audio`() {
        server.static["/m/v1080.mp4"] = video(1, 4) to "video/mp4"
        server.static["/m/v360.mp4"] = video(9, 4) to "video/mp4"
        server.static["/m/a.m4a"] = audio(4) to "audio/mp4"
        val e = engine()
        val info = runBlocking { e.extractMedia("https://www.youtube.com/watch?v=t") }
        val opt = MediaChoice.options(info).first { it.height == 1080 }
        val item = e.add(DownloadRequest("https://www.youtube.com/watch?v=t", fileName = "Test_ Video.mp4", mediaFormat = opt.formatSpec))
        assertEquals(DownloadKind.MEDIA, item.kind)
        val done = e.await(item.id) { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED }
        assertEquals(DownloadStatus.COMPLETED, done.status, done.error)
        assertEquals("Test_ Video.mp4", done.fileName)
        assertEquals(Category.VIDEO, done.category)
        val out = File(dir, done.fileName)
        val moovEntry = Mp4Merge.index(out).first { it.type == "moov" }
        val moov = Mp4Merge.Box("moov", children = Mp4Merge.parse(out.readBytes(), (moovEntry.offset + 8).toInt(), (moovEntry.offset + moovEntry.size).toInt()))
        assertEquals(2, moov.all("trak").size)
        assertEquals(8, Mp4Merge.index(out).count { it.type == "moof" })
        assertTrue(!File(dir, done.fileName + FileNames.PART_SUFFIX + ".parts").exists())
    }

    @Test fun `pause and resume re-extracts fresh links and keeps finished parts`() {
        server.static["/m/v360.mp4"] = video(9, 12) to "video/mp4"
        server.static["/m/a.m4a"] = audio(12) to "audio/mp4"
        val e = engine()
        val item = e.add(DownloadRequest("https://www.youtube.com/watch?v=t", fileName = "clip.mp4", mediaFormat = "v360+a", maxConnections = 2))
        e.await(item.id) { it.downloadedBytes > 100_000 }
        e.pause(item.id)
        val paused = e.await(item.id) { it.status == DownloadStatus.PAUSED }
        assertTrue(paused.parts.isNotEmpty(), "part progress is saved")
        val callsBefore = extractCalls.get()
        e.start(item.id)
        val done = e.await(item.id) { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED }
        assertEquals(DownloadStatus.COMPLETED, done.status, done.error)
        assertTrue(extractCalls.get() > callsBefore, "links are fetched again on resume")
        assertEquals(24, Mp4Merge.index(File(dir, "clip.mp4")).count { it.type == "moof" })
    }

    @Test fun `audio only and missing extractor`() {
        server.static["/m/a.m4a"] = audio(3) to "audio/mp4"
        val e = engine()
        val item = e.add(DownloadRequest("https://www.youtube.com/watch?v=t", fileName = "song.m4a", mediaFormat = "a"))
        val done = e.await(item.id) { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED }
        assertEquals(DownloadStatus.COMPLETED, done.status, done.error)
        assertEquals(Category.MUSIC, done.category)
        // Same audio, plus the title written into the file's tags.
        val saved = File(dir, "song.m4a")
        assertTrue(saved.length() > audio(3).size)
        assertTrue(String(saved.readBytes(), Charsets.ISO_8859_1).contains("©nam"))
        assertEquals(Mp4Merge.index(saved).count { it.type == "moof" }, Mp4Merge.index(File.createTempFile("orig", ".m4a").apply { writeBytes(audio(3)) }).count { it.type == "moof" })

        val bare = DownloadEngine(EngineConfig(defaultSaveDir = dir.path, categorySubfolders = false), InMemoryStore(), scope)
        val x = bare.add(DownloadRequest("https://www.youtube.com/watch?v=t", mediaFormat = "a"))
        val failed = bare.await(x.id) { it.status == DownloadStatus.FAILED }
        assertTrue(failed.error!!.contains("not available"))
    }
}
