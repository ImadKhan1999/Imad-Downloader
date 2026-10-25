package com.imad.downloader.core

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Joins a fragmented-MP4 video track and a fragmented-MP4 audio track (what DASH and modern
 * HLS deliver separately) into one playable MP4, without re-encoding and without ffmpeg.
 *
 * The audio track gets the next free track id, its `trak`/`trex` are added to the video's
 * `moov`, and the fragments of both files are interleaved by decode time.
 */
object Mp4Merge {
    class NotMergeable(message: String) : IOException(message)

    /** An MP4 box. Containers keep [children]; leaves keep their payload bytes. */
    open class Box(val type: String, var payload: ByteArray = ByteArray(0), val children: MutableList<Box>? = null) {
        fun child(t: String) = children?.firstOrNull { it.type == t }
        fun all(t: String) = children?.filter { it.type == t }.orEmpty()

        open fun size(): Long = 8 + (children?.sumOf { it.size() } ?: payload.size.toLong())

        open fun write(out: java.io.OutputStream) {
            val s = size()
            require(s <= UInt.MAX_VALUE.toLong()) { "box too large to rewrite" }
            out.write(ByteBuffer.allocate(8).putInt(s.toInt()).put(type.toByteArray(Charsets.ISO_8859_1)).array())
            if (children != null) children.forEach { it.write(out) } else out.write(payload)
        }
    }

    private val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "mvex", "moof", "traf", "edts", "dinf")

    /** Parses boxes from memory, descending into the containers this merger needs. */
    fun parse(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): MutableList<Box> {
        val out = mutableListOf<Box>()
        var p = from
        while (p + 8 <= to) {
            var size = ByteBuffer.wrap(bytes, p, 4).int.toLong() and 0xFFFFFFFFL
            val type = String(bytes, p + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) { size = ByteBuffer.wrap(bytes, p + 8, 8).long; header = 16 }
            if (size == 0L) size = (to - p).toLong()
            if (size < header || p + size > to) throw IOException("Broken MP4 box $type")
            val start = p + header
            val end = (p + size).toInt()
            out += if (type in containers) Box(type, children = parse(bytes, start, end))
            else Box(type, bytes.copyOfRange(start, end))
            p = end
        }
        return out
    }

    /** Top-level box positions of a file, without loading media data. */
    data class Entry(val type: String, val offset: Long, val size: Long, val header: Int)

    fun index(file: File): List<Entry> = RandomAccessFile(file, "r").use { raf ->
        val out = mutableListOf<Entry>()
        var p = 0L
        val len = raf.length()
        val head = ByteArray(16)
        while (p + 8 <= len) {
            raf.seek(p)
            raf.readFully(head, 0, 8)
            var size = ByteBuffer.wrap(head, 0, 4).int.toLong() and 0xFFFFFFFFL
            val type = String(head, 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) { raf.readFully(head, 8, 8); size = ByteBuffer.wrap(head, 8, 8).long; header = 16 }
            if (size == 0L) size = len - p
            if (size < header || p + size > len) throw IOException("Broken MP4 file ${file.name}")
            out += Entry(type, p, size, header)
            p += size
        }
        out
    }

    private fun read(file: File, e: Entry): ByteArray = RandomAccessFile(file, "r").use { raf ->
        val b = ByteArray((e.size - e.header).toInt())
        raf.seek(e.offset + e.header)
        raf.readFully(b)
        b
    }

    private fun ByteArray.u32(at: Int) = ByteBuffer.wrap(this, at, 4).int.toLong() and 0xFFFFFFFFL
    private fun ByteArray.put32(at: Int, v: Long) { ByteBuffer.wrap(this, at, 4).putInt(v.toInt()) }

    fun trackId(trak: Box): Long {
        val tkhd = trak.child("tkhd") ?: throw NotMergeable("track without tkhd")
        return tkhd.payload.u32(if (tkhd.payload[0].toInt() == 1) 20 else 12)
    }

    private fun setTrackId(trak: Box, id: Long) {
        val tkhd = trak.child("tkhd")!!
        tkhd.payload = tkhd.payload.copyOf().also { it.put32(if (it[0].toInt() == 1) 20 else 12, id) }
    }

    fun timescale(trak: Box): Long {
        val mdhd = trak.child("mdia")?.child("mdhd") ?: return 1
        return mdhd.payload.u32(if (mdhd.payload[0].toInt() == 1) 20 else 12).coerceAtLeast(1)
    }

    /** One fragment: its moof plus the raw boxes (mdat ...) that follow it. */
    private class Fragment(val file: File, val moof: Box, val raw: List<Entry>, val seconds: Double, val order: Int)

    private fun fragments(file: File, entries: List<Entry>, timescales: Map<Long, Long>, newTrackId: Long?): List<Fragment> {
        val out = mutableListOf<Fragment>()
        var i = 0
        while (i < entries.size) {
            val e = entries[i]
            if (e.type != "moof") { i++; continue }
            val moof = Box("moof", children = parse(read(file, e)))
            var seconds = -1.0
            for (traf in moof.all("traf")) {
                val tfhd = traf.child("tfhd") ?: throw NotMergeable("fragment without tfhd")
                val flags = tfhd.payload.u32(0) and 0xFFFFFF
                if (flags and 0x1L != 0L) throw NotMergeable("absolute data offsets are not supported")
                val oldId = tfhd.payload.u32(4)
                if (newTrackId != null) tfhd.payload = tfhd.payload.copyOf().also { it.put32(4, newTrackId) }
                traf.child("tfdt")?.let { tfdt ->
                    val t = if (tfdt.payload[0].toInt() == 1) ByteBuffer.wrap(tfdt.payload, 4, 8).long else tfdt.payload.u32(4)
                    val s = t.toDouble() / (timescales[oldId] ?: timescales.values.firstOrNull() ?: 1L)
                    if (seconds < 0 || s < seconds) seconds = s
                }
            }
            val raw = mutableListOf<Entry>()
            var j = i + 1
            while (j < entries.size && entries[j].type != "moof") {
                if (entries[j].type !in setOf("sidx", "mfra", "styp", "free", "skip")) raw += entries[j]
                j++
            }
            out += Fragment(file, moof, raw, seconds, out.size)
            i = j
        }
        if (out.isEmpty()) throw NotMergeable("${file.name} is not a fragmented MP4")
        return out
    }

    fun merge(video: File, audio: File, output: File) {
        val vIdx = index(video)
        val aIdx = index(audio)
        val ftyp = vIdx.firstOrNull { it.type == "ftyp" }
        val vMoovE = vIdx.firstOrNull { it.type == "moov" } ?: throw NotMergeable("video has no moov")
        val aMoovE = aIdx.firstOrNull { it.type == "moov" } ?: throw NotMergeable("audio has no moov")
        val vMoov = Box("moov", children = parse(read(video, vMoovE)))
        val aMoov = Box("moov", children = parse(read(audio, aMoovE)))
        val vTraks = vMoov.all("trak")
        val aTrak = aMoov.all("trak").singleOrNull() ?: throw NotMergeable("audio must have exactly one track")
        val vMvex = vMoov.child("mvex") ?: throw NotMergeable("video is not fragmented")
        val aMvex = aMoov.child("mvex") ?: throw NotMergeable("audio is not fragmented")

        val vTimescales = vTraks.associate { trackId(it) to timescale(it) }
        val oldAudioId = trackId(aTrak)
        val newAudioId = (vTimescales.keys.maxOrNull() ?: 0) + 1
        val aTimescales = mapOf(oldAudioId to timescale(aTrak))

        setTrackId(aTrak, newAudioId)
        for (trex in aMvex.all("trex")) {
            if (trex.payload.u32(4) == oldAudioId) {
                vMvex.children!! += Box("trex", trex.payload.copyOf().also { it.put32(4, newAudioId) })
            }
        }
        vMoov.child("mvhd")?.let { mvhd -> mvhd.payload = mvhd.payload.copyOf().also { it.put32(it.size - 4, newAudioId + 1) } }
        val kids = vMoov.children!!
        kids.add(kids.indexOf(vTraks.last()) + 1, aTrak)

        val all = fragments(video, vIdx, vTimescales, null) + fragments(audio, aIdx, aTimescales, newAudioId)
        val ordered = if (all.all { it.seconds >= 0 }) all.sortedWith(compareBy({ it.seconds }, { it.file != video }, { it.order }))
        else all // no timing info: keep video then audio

        output.outputStream().buffered(1 shl 16).use { out ->
            if (ftyp != null) out.write(rawBytes(video, ftyp))
            vMoov.write(out)
            var sequence = 1L
            for (f in ordered) {
                f.moof.child("mfhd")?.let { m -> m.payload = m.payload.copyOf().also { it.put32(4, sequence++) } }
                f.moof.write(out)
                RandomAccessFile(f.file, "r").use { raf ->
                    val buf = ByteArray(1 shl 16)
                    for (e in f.raw) {
                        raf.seek(e.offset)
                        var left = e.size
                        while (left > 0) {
                            val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) throw IOException("Unexpected end of ${f.file.name}")
                            out.write(buf, 0, n)
                            left -= n
                        }
                    }
                }
            }
        }
    }

    private fun rawBytes(file: File, e: Entry): ByteArray = RandomAccessFile(file, "r").use { raf ->
        val b = ByteArray(e.size.toInt())
        raf.seek(e.offset)
        raf.readFully(b)
        b
    }

    /** Quick check: does the file start like an MP4 (ftyp / styp / moov)? */
    fun looksLikeMp4(file: File): Boolean = runCatching {
        val type = index(file).firstOrNull()?.type
        type == "ftyp" || type == "styp" || type == "moov"
    }.getOrDefault(false)
}
