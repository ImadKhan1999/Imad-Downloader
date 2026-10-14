package com.imad.downloader.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Builds tiny but structurally valid fragmented MP4 files for tests. */
object Fmp4 {
    fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, b -> a + b }
        return ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray()).put(body).array()
    }

    private fun u32(v: Long) = ByteBuffer.allocate(4).putInt(v.toInt()).array()
    private fun u64(v: Long) = ByteBuffer.allocate(8).putLong(v).array()
    private fun zeros(n: Int) = ByteArray(n)
    private fun full(version: Int, flags: Int) = u32((version.toLong() shl 24) or flags.toLong())

    fun init(trackId: Long, timescale: Long, handler: String): ByteArray {
        val mvhd = box("mvhd", full(0, 0), zeros(8), u32(1000), u32(0), zeros(80 - 20), u32(trackId + 1))
        val tkhd = box("tkhd", full(0, 3), zeros(8), u32(trackId), zeros(84 - 16))
        val mdhd = box("mdhd", full(0, 0), zeros(8), u32(timescale), u32(0), zeros(4))
        val hdlr = box("hdlr", full(0, 0), zeros(4), handler.toByteArray(), zeros(12), byteArrayOf(0))
        val trak = box("trak", tkhd, box("mdia", mdhd, hdlr))
        val trex = box("trex", full(0, 0), u32(trackId), u32(1), zeros(12))
        return box("ftyp", "iso6".toByteArray(), u32(0), "iso6".toByteArray()) + box("moov", mvhd, trak, box("mvex", trex))
    }

    /** One moof+mdat. data_offset points from the moof start to the mdat payload. */
    fun fragment(trackId: Long, sequence: Long, decodeTime: Long, data: ByteArray): ByteArray {
        fun moof(offset: Int): ByteArray {
            val mfhd = box("mfhd", full(0, 0), u32(sequence))
            val tfhd = box("tfhd", full(0, 0x020000), u32(trackId))
            val tfdt = box("tfdt", full(1, 0), u64(decodeTime))
            val trun = box("trun", full(0, 0x000201), u32(1), u32(offset.toLong()), u32(data.size.toLong()))
            return box("moof", mfhd, box("traf", tfhd, tfdt, trun))
        }
        val size = moof(0).size
        return moof(size + 8) + box("mdat", data)
    }

    fun styp() = box("styp", "msdh".toByteArray(), u32(0))

    fun file(trackId: Long, timescale: Long, handler: String, pieces: List<ByteArray>, step: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(init(trackId, timescale, handler))
        pieces.forEachIndexed { i, p -> out.write(fragment(trackId, i + 1L, i * step, p)) }
        return out.toByteArray()
    }
}

class Mp4MergeTest {
    private val dir = Files.createTempDirectory("merge").toFile()

    @AfterTest fun clean() { dir.deleteRecursively() }

    @Test fun `merges video and audio into one file with two tracks`() {
        val vData = (0 until 3).map { TestServer.content(1000 + it) }
        val aData = (0 until 3).map { ByteArray(300) { b -> (b + it).toByte() } }
        val v = File(dir, "v.mp4").apply { writeBytes(Fmp4.file(1, 90_000, "vide", vData, 180_000)) }
        val a = File(dir, "a.mp4").apply { writeBytes(Fmp4.file(1, 48_000, "soun", aData, 96_000)) }
        val out = File(dir, "out.mp4")
        Mp4Merge.merge(v, a, out)

        val idx = Mp4Merge.index(out)
        assertEquals("ftyp", idx[0].type)
        assertEquals("moov", idx[1].type)
        val moov = Mp4Merge.Box("moov", children = Mp4Merge.parse(out.readBytes(), (idx[1].offset + 8).toInt(), (idx[1].offset + idx[1].size).toInt()))
        assertEquals(listOf(1L, 2L), moov.all("trak").map(Mp4Merge::trackId))
        assertEquals(2, moov.child("mvex")!!.all("trex").size)
        val moofs = idx.filter { it.type == "moof" }
        assertEquals(6, moofs.size)
        // Interleaved by time (both are 2 s per fragment): v, a, v, a, v, a.
        val bytes = out.readBytes()
        val ids = moofs.map { e ->
            val moof = Mp4Merge.Box("moof", children = Mp4Merge.parse(bytes, (e.offset + 8).toInt(), (e.offset + e.size).toInt()))
            ByteBuffer.wrap(moof.child("traf")!!.child("tfhd")!!.payload, 4, 4).int
        }
        assertEquals(listOf(1, 2, 1, 2, 1, 2), ids)
        val mdats = idx.filter { it.type == "mdat" }.map { e -> bytes.copyOfRange((e.offset + 8).toInt(), (e.offset + e.size).toInt()) }
        assertContentEquals(vData[1], mdats[2])
        assertContentEquals(aData[2], mdats[5])
    }

    @Test fun `refuses files that are not fragmented`() {
        val v = File(dir, "v.mp4").apply { writeBytes(Fmp4.box("ftyp", ByteArray(8)) + Fmp4.box("mdat", ByteArray(10))) }
        val a = File(dir, "a.mp4").apply { writeBytes(Fmp4.file(1, 48_000, "soun", listOf(ByteArray(5)), 1)) }
        assertFailsWith<Mp4Merge.NotMergeable> { Mp4Merge.merge(v, a, File(dir, "o.mp4")) }
    }
}

class DashParserTest {
    @Test fun `segment template with numbers and padding`() {
        val xml = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT10S">
              <BaseURL>https://cdn.x.com/v/</BaseURL>
              <Period>
                <AdaptationSet mimeType="video/mp4">
                  <SegmentTemplate initialization="${'$'}RepresentationID${'$'}/init.mp4" media="${'$'}RepresentationID${'$'}/seg-${'$'}Number%05d${'$'}.m4s" startNumber="1" timescale="1000" duration="4000"/>
                  <Representation id="720" bandwidth="2000000" width="1280" height="720"/>
                  <Representation id="360" bandwidth="600000" width="640" height="360"/>
                </AdaptationSet>
                <AdaptationSet mimeType="audio/mp4">
                  <Representation id="aud" bandwidth="128000">
                    <SegmentTemplate initialization="a/init.mp4" media="a/${'$'}Time${'$'}.m4s" timescale="48000">
                      <SegmentTimeline><S t="0" d="96000" r="2"/><S d="48000"/></SegmentTimeline>
                    </SegmentTemplate>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val m = Dash.parse(xml, "https://cdn.x.com/manifest.mpd")
        val v = m.videos.first()
        assertEquals(720, v.height)
        assertEquals("https://cdn.x.com/v/720/init.mp4", v.initUrl)
        assertEquals(3, v.pieces.size) // ceil(10 / 4)
        assertEquals("https://cdn.x.com/v/720/seg-00003.m4s", v.pieces[2].first)
        val a = m.audios.single()
        assertEquals(listOf("0", "96000", "192000", "288000"), a.pieces.map { it.first.substringAfterLast('/').removeSuffix(".m4s") })
        assertEquals(10.0, m.durationSeconds)
        assertEquals(listOf(720, 360), StreamResolver.qualitiesFromDash(m).map { it.height })
    }

    @Test fun `segment list and single file representations`() {
        val xml = """
            <MPD type="static" mediaPresentationDuration="PT1M2.5S"><Period>
              <AdaptationSet contentType="video">
                <Representation id="1" bandwidth="1" height="480" mimeType="video/mp4">
                  <BaseURL>video.mp4</BaseURL>
                  <SegmentList><Initialization sourceURL="video.mp4" range="0-99"/>
                    <SegmentURL mediaRange="100-199"/><SegmentURL mediaRange="200-299"/></SegmentList>
                </Representation>
                <Representation id="2" bandwidth="2" height="240" mimeType="video/webm"><BaseURL>low.webm</BaseURL></Representation>
              </AdaptationSet>
            </Period></MPD>
        """.trimIndent()
        val m = Dash.parse(xml, "https://x.com/a/b.mpd")
        val list = m.representations.first { it.id == "1" }
        assertEquals(0L..99L, list.initRange)
        assertEquals(listOf("https://x.com/a/video.mp4" to 100L..199L, "https://x.com/a/video.mp4" to 200L..299L), list.pieces)
        val single = m.representations.first { it.id == "2" }
        assertEquals(listOf("https://x.com/a/low.webm" to null), single.pieces)
        assertTrue(single.isWebm)
        assertEquals(62.5, m.durationSeconds)
    }

    @Test fun `live manifests are refused`() {
        assertFailsWith<java.io.IOException> { Dash.parse("<MPD type=\"dynamic\"><Period/></MPD>", "https://x/a.mpd") }
    }

    @Test fun `quality pick prefers the best stream at or below the wanted height`() {
        val hs = listOf(1080 to 5L, 720 to 3L, 480 to 2L, 240 to 1L)
        assertEquals(1080, StreamResolver.pick(hs, 0, { it.first }, { it.second })!!.first)
        assertEquals(720, StreamResolver.pick(hs, 720, { it.first }, { it.second })!!.first)
        assertEquals(480, StreamResolver.pick(hs, 600, { it.first }, { it.second })!!.first)
        assertEquals(240, StreamResolver.pick(hs, 144, { it.first }, { it.second })!!.first)
    }
}

class StreamDownloadTest {
    private lateinit var server: TestServer
    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @BeforeTest fun setUp() { server = TestServer(); dir = Files.createTempDirectory("streams").toFile() }
    @AfterTest fun tearDown() { scope.cancel(); server.close(); dir.deleteRecursively() }

    private fun engine() = DownloadEngine(
        EngineConfig(defaultSaveDir = dir.path, categorySubfolders = false, progressIntervalMillis = 50, retryDelayMillis = 50),
        InMemoryStore(), scope,
    )

    private fun DownloadEngine.awaitDone(id: String) = runBlocking {
        withTimeout(30_000) {
            state.first { s -> s.downloads.first { it.id == id }.status.let { it == DownloadStatus.COMPLETED || it == DownloadStatus.FAILED } }
        }.downloads.first { it.id == id }
    }

    /** Serves fMP4 init + fragments for one track under /path/. Returns the media bytes. */
    private fun serveTrack(path: String, handler: String, timescale: Long, count: Int, seed: Int): List<ByteArray> {
        server.static["/$path/init.mp4"] = Fmp4.init(1, timescale, handler) to "video/mp4"
        return (0 until count).map { i ->
            val data = TestServer.content(2000 + seed + i).also { it[0] = (seed + i).toByte() }
            server.static["/$path/seg-${i + 1}.m4s"] = Fmp4.fragment(1, i + 1L, i * timescale * 2, data) to "video/mp4"
            data
        }
    }

    @Test fun `dash download picks quality and merges audio`() {
        val hi = serveTrack("d/1080", "vide", 90_000, 3, 10)
        val lo = serveTrack("d/360", "vide", 90_000, 3, 50)
        val au = serveTrack("d/aud", "soun", 48_000, 3, 90)
        val mpd = """
            <MPD type="static" mediaPresentationDuration="PT6S"><Period>
              <AdaptationSet mimeType="video/mp4">
                <SegmentTemplate initialization="${'$'}RepresentationID${'$'}/init.mp4" media="${'$'}RepresentationID${'$'}/seg-${'$'}Number${'$'}.m4s" timescale="1" duration="2"/>
                <Representation id="1080" bandwidth="5000000" height="1080"/>
                <Representation id="360" bandwidth="700000" height="360"/>
              </AdaptationSet>
              <AdaptationSet mimeType="audio/mp4">
                <SegmentTemplate initialization="aud/init.mp4" media="aud/seg-${'$'}Number${'$'}.m4s" timescale="1" duration="2"/>
                <Representation id="a" bandwidth="128000"/>
              </AdaptationSet>
            </Period></MPD>
        """.trimIndent()
        server.static["/d/movie.mpd"] = mpd.toByteArray() to "application/dash+xml"

        val e = engine()
        val qualities = runBlocking { e.streamQualities("${server.base}/d/movie.mpd") }
        assertEquals(listOf("1080p", "360p"), qualities.map { it.label })
        assertTrue(qualities.all { it.separateAudio })

        val item = e.add(DownloadRequest("${server.base}/d/movie.mpd", streamHeight = 360))
        assertEquals(DownloadKind.DASH, item.kind)
        val done = e.awaitDone(item.id)
        assertEquals(DownloadStatus.COMPLETED, done.status, done.error)
        assertEquals("movie.mp4", done.fileName)
        val out = File(dir, "movie.mp4")
        val bytes = out.readBytes()
        val idx = Mp4Merge.index(out)
        val mdats = idx.filter { it.type == "mdat" }.map { bytes.copyOfRange((it.offset + 8).toInt(), (it.offset + it.size).toInt()) }
        assertEquals(6, mdats.size)
        assertTrue(mdats.any { it.contentEquals(lo[0]) }, "low quality video is used")
        assertTrue(mdats.none { it.contentEquals(hi[0]) }, "high quality video is not fetched")
        assertTrue(mdats.any { it.contentEquals(au[2]) }, "audio is merged in")
    }

    @Test fun `hls with separate fmp4 audio is merged`() {
        val video = serveTrack("h/v", "vide", 90_000, 2, 5)
        val audio = serveTrack("h/a", "soun", 48_000, 2, 70)
        fun media(p: String) = "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:2.0,\nseg-1.m4s\n#EXTINF:2.0,\nseg-2.m4s\n#EXT-X-ENDLIST\n"
        server.static["/h/v/index.m3u8"] = media("v").toByteArray() to "application/vnd.apple.mpegurl"
        server.static["/h/a/index.m3u8"] = media("a").toByteArray() to "application/vnd.apple.mpegurl"
        server.static["/h/master.m3u8"] = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",DEFAULT=YES,URI="a/index.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360,AUDIO="aac"
            v/index.m3u8
        """.trimIndent().toByteArray() to "application/vnd.apple.mpegurl"

        val e = engine()
        val q = runBlocking { e.streamQualities("${server.base}/h/master.m3u8") }
        assertEquals(listOf(StreamQuality(360, 900_000, "360p", true)), q)
        val item = e.add(DownloadRequest("${server.base}/h/master.m3u8"))
        val done = e.awaitDone(item.id)
        assertEquals(DownloadStatus.COMPLETED, done.status, done.error)
        assertEquals("master.mp4", done.fileName)
        val out = File(dir, "master.mp4")
        val bytes = out.readBytes()
        val mdats = Mp4Merge.index(out).filter { it.type == "mdat" }.map { bytes.copyOfRange((it.offset + 8).toInt(), (it.offset + it.size).toInt()) }
        assertEquals(4, mdats.size)
        assertTrue(mdats.any { it.contentEquals(video[1]) } && mdats.any { it.contentEquals(audio[0]) })
    }
}
