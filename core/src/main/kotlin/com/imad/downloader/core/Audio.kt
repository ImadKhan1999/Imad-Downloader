package com.imad.downloader.core

import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.GetAudio
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import net.sourceforge.jaad.aac.Decoder
import net.sourceforge.jaad.aac.SampleBuffer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/** Title, artist and cover picture written into downloaded audio files. */
data class AudioTags(val title: String, val artist: String = "", val cover: ByteArray? = null, val coverMime: String = "image/jpeg")

/**
 * Audio extras for downloads: cover art and tags in M4A and MP3 files, and M4A (AAC) to MP3
 * conversion. Pure JVM code, so the Windows and Android apps behave the same: AAC is decoded with
 * JAAD (public domain) and MP3 encoded with jump3r (a Java port of LAME, LGPL), driven through its
 * core classes directly because its convenience wrapper needs javax.sound, which Android lacks.
 */
object AudioTools {
    /** Fetches the cover picture (JPEG or PNG only, as tags expect); null when there is none. */
    fun fetchCover(client: OkHttpClient, url: String?): Pair<ByteArray, String>? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return null
                val bytes = r.body.bytes()
                when {
                    bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> bytes to "image/jpeg"
                    bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> bytes to "image/png"
                    else -> null
                }
            }
        }.getOrNull()
    }

    // ---------------------------------------------------------------- MP3 (ID3v2.3)

    /** Puts an ID3v2.3 tag (title, artist, front cover) at the start of an MP3. */
    fun tagMp3(file: File, tags: AudioTags) {
        val frames = ByteArrayOutputStream()
        fun frame(id: String, body: ByteArray) {
            frames.write(id.toByteArray(Charsets.ISO_8859_1))
            frames.write(ByteBuffer.allocate(4).putInt(body.size).array())
            frames.write(byteArrayOf(0, 0))
            frames.write(body)
        }
        fun text(s: String) = byteArrayOf(1) + byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0)
        if (tags.title.isNotBlank()) frame("TIT2", text(tags.title))
        if (tags.artist.isNotBlank()) frame("TPE1", text(tags.artist))
        tags.cover?.let { pic ->
            frame("APIC", byteArrayOf(0) + tags.coverMime.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 3, 0) + pic)
        }
        val body = frames.toByteArray()
        val n = body.size
        val header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            ((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte(),
        )
        val tmp = File(file.parentFile, file.name + ".tag")
        tmp.outputStream().buffered().use { out ->
            out.write(header); out.write(body)
            file.inputStream().buffered().use { it.copyTo(out) }
        }
        if (!file.delete() || !tmp.renameTo(file)) throw IOException("Could not write tags to ${file.name}")
    }

    // ---------------------------------------------------------------- M4A (iTunes-style ilst)

    /**
     * Adds title, artist and cover art to an M4A/MP4 (moov/udta/meta/ilst). Works for normal and
     * fragmented files; chunk offsets are moved when the bigger moov pushes the media data down.
     */
    fun tagMp4(file: File, tags: AudioTags) {
        val entries = Mp4Merge.index(file)
        val moovE = entries.firstOrNull { it.type == "moov" } ?: throw IOException("Not an MP4 file")
        val moovBytes = readBytes(file, moovE.offset + moovE.header, moovE.size - moovE.header)
        val moov = Mp4Merge.Box("moov", children = Mp4Merge.parse(moovBytes))
        moov.children!!.removeAll { it.type == "udta" }
        moov.children.add(Mp4Merge.Box("udta", children = mutableListOf(metaBox(tags))))
        val delta = moov.size() - moovE.size
        val mdatAfter = entries.any { it.type == "mdat" && it.offset > moovE.offset }
        if (mdatAfter && delta != 0L) moveChunkOffsets(moov, delta)
        val tmp = File(file.parentFile, file.name + ".tag")
        RandomAccessFile(file, "r").use { src ->
            tmp.outputStream().buffered().use { out ->
                fun copy(from: Long, len: Long) {
                    src.seek(from)
                    val buf = ByteArray(1 shl 16)
                    var left = len
                    while (left > 0) {
                        val n = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) break
                        out.write(buf, 0, n); left -= n
                    }
                }
                copy(0, moovE.offset)
                moov.write(out)
                copy(moovE.offset + moovE.size, src.length() - moovE.offset - moovE.size)
            }
        }
        if (!file.delete() || !tmp.renameTo(file)) throw IOException("Could not write tags to ${file.name}")
    }

    private fun metaBox(tags: AudioTags): Mp4Merge.Box {
        fun item(type: String, dataType: Int, payload: ByteArray) = Mp4Merge.Box(type, children = mutableListOf(
            Mp4Merge.Box("data", ByteBuffer.allocate(8).putInt(dataType).putInt(0).array() + payload),
        ))
        val ilst = mutableListOf<Mp4Merge.Box>()
        if (tags.title.isNotBlank()) ilst += item("©nam", 1, tags.title.toByteArray(Charsets.UTF_8))
        if (tags.artist.isNotBlank()) ilst += item("©ART", 1, tags.artist.toByteArray(Charsets.UTF_8))
        tags.cover?.let { ilst += item("covr", if (tags.coverMime == "image/png") 14 else 13, it) }
        val hdlr = Mp4Merge.Box("hdlr", ByteArray(8) + "mdirappl".toByteArray(Charsets.ISO_8859_1) + ByteArray(9))
        // "meta" is a full box: 4 bytes of version/flags come before its children.
        return MetaBox(mutableListOf(hdlr, Mp4Merge.Box("ilst", children = ilst)))
    }

    /** A full box with children (meta): writes version/flags before them. */
    private class MetaBox(kids: MutableList<Mp4Merge.Box>) : Mp4Merge.Box("meta", children = kids) {
        override fun size(): Long = 12 + children!!.sumOf { it.size() }
        override fun write(out: java.io.OutputStream) {
            out.write(ByteBuffer.allocate(4).putInt(size().toInt()).array())
            out.write("meta".toByteArray(Charsets.ISO_8859_1))
            out.write(ByteArray(4))
            children!!.forEach { it.write(out) }
        }
    }

    private fun moveChunkOffsets(box: Mp4Merge.Box, delta: Long) {
        box.children?.forEach { c ->
            when (c.type) {
                "stco" -> {
                    val b = ByteBuffer.wrap(c.payload)
                    val n = b.getInt(4)
                    for (i in 0 until n) b.putInt(8 + i * 4, (b.getInt(8 + i * 4).toLong() + delta).toInt())
                }
                "co64" -> {
                    val b = ByteBuffer.wrap(c.payload)
                    val n = b.getInt(4)
                    for (i in 0 until n) b.putLong(8 + i * 8, b.getLong(8 + i * 8) + delta)
                }
                else -> if (c.children != null) moveChunkOffsets(c, delta)
            }
        }
    }

    // ---------------------------------------------------------------- AAC -> MP3

    /** Converts the AAC track of an M4A (normal or fragmented) to an MP3 at [kbps]. */
    fun m4aToMp3(input: File, output: File, kbps: Int = 192, onProgress: (Double) -> Unit = {}) {
        val track = AacTrack.read(input)
        val decoder = Decoder(track.config)
        val buf = SampleBuffer().apply { setBigEndian(false) }
        var encoder: Mp3Encoder? = null
        output.outputStream().buffered().use { out ->
            RandomAccessFile(input, "r").use { raf ->
                track.samples.forEachIndexed { i, (offset, size) ->
                    val frame = ByteArray(size)
                    raf.seek(offset); raf.readFully(frame)
                    decoder.decodeFrame(frame, buf)
                    val pcm = buf.data
                    if (pcm.isEmpty()) return@forEachIndexed
                    val enc = encoder ?: Mp3Encoder(buf.sampleRate, buf.channels, kbps).also { encoder = it }
                    out.write(enc.encode(pcm, buf.channels))
                    if (i % 200 == 0) onProgress(i.toDouble() / track.samples.size)
                }
            }
            encoder?.let { out.write(it.finish()) } ?: throw IOException("No audio found to convert")
        }
        onProgress(1.0)
    }

    private fun readBytes(file: File, offset: Long, len: Long): ByteArray = RandomAccessFile(file, "r").use { raf ->
        ByteArray(len.toInt()).also { raf.seek(offset); raf.readFully(it) }
    }

    /** Where each AAC frame of an MP4's sound track lies, plus the decoder setup bytes. */
    private class AacTrack(val config: ByteArray, val samples: List<Pair<Long, Int>>) {
        companion object {
            fun read(file: File): AacTrack {
                val entries = Mp4Merge.index(file)
                val moovE = entries.firstOrNull { it.type == "moov" } ?: throw IOException("Not an MP4 file")
                val moov = Mp4Merge.parse(readBytes(file, moovE.offset + moovE.header, moovE.size - moovE.header))
                val trak = moov.filter { it.type == "trak" }.firstOrNull { t ->
                    t.child("mdia")?.child("hdlr")?.payload?.let { String(it, 8, 4, Charsets.ISO_8859_1) } == "soun"
                } ?: throw IOException("No sound track in this file")
                val trackId = Mp4Merge.trackId(trak)
                val stbl = trak.child("mdia")?.child("minf")?.child("stbl") ?: throw IOException("Broken MP4 sound track")
                val config = esdsConfig(stbl.child("stsd")?.payload ?: throw IOException("Broken MP4 sound track"))
                val samples = ArrayList<Pair<Long, Int>>()
                // Normal MP4: sample table.
                val stsz = stbl.child("stsz")?.payload
                val sizesInTable = stsz?.let { ByteBuffer.wrap(it).getInt(8) } ?: 0
                if (stsz != null && sizesInTable > 0) {
                    val b = ByteBuffer.wrap(stsz)
                    val fixed = b.getInt(4)
                    val sizes = IntArray(sizesInTable) { if (fixed != 0) fixed else b.getInt(12 + it * 4) }
                    val chunkOffsets = stbl.child("stco")?.payload?.let { p -> val bb = ByteBuffer.wrap(p); LongArray(bb.getInt(4)) { bb.getInt(8 + it * 4).toLong() and 0xFFFFFFFFL } }
                        ?: stbl.child("co64")?.payload?.let { p -> val bb = ByteBuffer.wrap(p); LongArray(bb.getInt(4)) { bb.getLong(8 + it * 8) } }
                        ?: throw IOException("Broken MP4 sound track")
                    val stsc = ByteBuffer.wrap(stbl.child("stsc")?.payload ?: throw IOException("Broken MP4 sound track"))
                    val runs = stsc.getInt(4)
                    var s = 0
                    for (r in 0 until runs) {
                        val first = stsc.getInt(8 + r * 12) - 1
                        val perChunk = stsc.getInt(12 + r * 12)
                        val last = if (r + 1 < runs) stsc.getInt(8 + (r + 1) * 12) - 1 else chunkOffsets.size
                        for (c in first until last) {
                            var off = chunkOffsets[c]
                            repeat(perChunk) { if (s < sizes.size) { samples += off to sizes[s]; off += sizes[s]; s++ } }
                        }
                    }
                } else {
                    // Fragmented MP4 (YouTube and other DASH audio): moof / traf / trun.
                    val trex = moov.firstOrNull { it.type == "mvex" }?.all("trex")?.firstOrNull { ByteBuffer.wrap(it.payload).getInt(4).toLong() == trackId }
                    val defaultSizeTrex = trex?.let { ByteBuffer.wrap(it.payload).getInt(16) } ?: 0
                    for (e in entries.filter { it.type == "moof" }) {
                        val moofBytes = readBytes(file, e.offset + e.header, e.size - e.header)
                        for (traf in Mp4Merge.parse(moofBytes).filter { it.type == "traf" }) {
                            val tfhd = ByteBuffer.wrap(traf.child("tfhd")?.payload ?: continue)
                            if ((tfhd.getInt(4).toLong() and 0xFFFFFFFFL) != trackId) continue
                            val tfFlags = tfhd.getInt(0) and 0xFFFFFF
                            var p = 8
                            var base = e.offset
                            if (tfFlags and 0x1 != 0) { base = tfhd.getLong(p); p += 8 }
                            if (tfFlags and 0x2 != 0) p += 4
                            if (tfFlags and 0x8 != 0) p += 4
                            val defaultSize = if (tfFlags and 0x10 != 0) tfhd.getInt(p) else defaultSizeTrex
                            for (trunBox in traf.all("trun")) {
                                val t = ByteBuffer.wrap(trunBox.payload)
                                val flags = t.getInt(0) and 0xFFFFFF
                                val count = t.getInt(4)
                                var q = 8
                                var dataOffset = 0L
                                if (flags and 0x1 != 0) { dataOffset = t.getInt(q).toLong(); q += 4 }
                                if (flags and 0x4 != 0) q += 4
                                var off = base + dataOffset
                                repeat(count) {
                                    if (flags and 0x100 != 0) q += 4
                                    val size = if (flags and 0x200 != 0) t.getInt(q).also { q += 4 } else defaultSize
                                    if (flags and 0x400 != 0) q += 4
                                    if (flags and 0x800 != 0) q += 4
                                    samples += off to size
                                    off += size
                                }
                            }
                        }
                    }
                }
                if (samples.isEmpty()) throw IOException("No audio frames found")
                return AacTrack(config, samples)
            }

            /** AudioSpecificConfig from stsd > mp4a > esds (MPEG-4 descriptor 0x05). */
            private fun esdsConfig(stsd: ByteArray): ByteArray {
                val at = indexOf(stsd, "esds".toByteArray(Charsets.ISO_8859_1))
                if (at < 0) throw IOException("This audio is not AAC, so it cannot be turned into MP3 here")
                var i = at + 8 // skip "esds" + version/flags
                fun len(): Int { var n = 0; repeat(4) { val b = stsd[i++].toInt() and 0xFF; n = (n shl 7) or (b and 0x7F); if (b and 0x80 == 0) return n }; return n }
                while (i < stsd.size) {
                    val tag = stsd[i++].toInt() and 0xFF
                    val l = len()
                    when (tag) {
                        0x03 -> { i += 2; val f = stsd[i++].toInt(); if (f and 0x80 != 0) i += 2; if (f and 0x40 != 0) i += 1 + (stsd[i].toInt() and 0xFF); if (f and 0x20 != 0) i += 2 }
                        0x04 -> i += 13
                        0x05 -> return stsd.copyOfRange(i, i + l)
                        else -> i += l
                    }
                }
                throw IOException("No AAC setup found in this file")
            }

            private fun indexOf(a: ByteArray, b: ByteArray): Int {
                outer@ for (i in 0..a.size - b.size) { for (j in b.indices) if (a[i + j] != b[j]) continue@outer; return i }
                return -1
            }
        }
    }

    /** LAME (jump3r) wired up by hand, as its LameEncoder wrapper would (minus javax.sound). */
    private class Mp3Encoder(sampleRate: Int, private val channels: Int, kbps: Int) {
        private val lame = Lame()
        private val gfp: LameGlobalFlags
        private val out = ByteArray(256 * 1024)

        init {
            val ga = GainAnalysis(); val bs = BitStream(); val p = Presets(); val qupvt = QuantizePVT(); val qu = Quantize()
            val vbr = VBRTag(); val ver = Version(); val id3 = ID3Tag(); val rv = Reservoir(); val tak = Takehiro()
            val parse = Parse(); val mpg = MPGLib(); val intf = Interface(); val common = Common(); val gaud = GetAudio()
            lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpg)
            bs.setModules(ga, mpg, ver, vbr)
            id3.setModules(bs, ver)
            p.setModules(lame)
            qu.setModules(bs, rv, qupvt, tak)
            qupvt.setModules(tak, rv, lame.enc.psy)
            rv.setModules(bs)
            tak.setModules(qupvt)
            vbr.setModules(lame, bs, ver)
            gaud.setModules(parse, mpg)
            parse.setModules(ver, id3, p)
            mpg.setModules(intf, common)
            intf.setModules(vbr, common)
            gfp = lame.lame_init()
            gfp.num_channels = channels.coerceIn(1, 2)
            gfp.in_samplerate = sampleRate
            gfp.mode = if (channels == 1) MPEGMode.MONO else MPEGMode.JOINT_STEREO
            gfp.brate = kbps
            gfp.quality = 5
            id3.id3tag_init(gfp)
            gfp.write_id3tag_automatic = false
            gfp.findReplayGain = false
            if (lame.lame_init_params(gfp) < 0) throw IOException("MP3 encoder setup failed")
        }

        /** 16-bit little-endian interleaved PCM in, MP3 bytes out. */
        fun encode(pcm: ByteArray, ch: Int): ByteArray {
            val frames = pcm.size / (2 * ch)
            val left = IntArray(frames); val right = IntArray(frames)
            for (f in 0 until frames) {
                val l = ((pcm[f * 2 * ch + 1].toInt() shl 8) or (pcm[f * 2 * ch].toInt() and 0xFF)).toShort().toInt()
                val r = if (ch > 1) ((pcm[f * 2 * ch + 3].toInt() shl 8) or (pcm[f * 2 * ch + 2].toInt() and 0xFF)).toShort().toInt() else l
                left[f] = l shl 16; right[f] = r shl 16
            }
            val n = lame.lame_encode_buffer_int(gfp, left, right, frames, out, 0, out.size)
            if (n < 0) throw IOException("MP3 encoding failed ($n)")
            return out.copyOf(n)
        }

        fun finish(): ByteArray {
            val n = lame.lame_encode_flush(gfp, out, 0, out.size)
            lame.lame_close(gfp)
            return if (n > 0) out.copyOf(n) else ByteArray(0)
        }
    }
}
