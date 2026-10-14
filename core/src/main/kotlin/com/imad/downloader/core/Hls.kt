package com.imad.downloader.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Parsed HLS (m3u8) playlists. */
object Hls {
    data class Variant(
        val url: String,
        val bandwidth: Long,
        val resolution: String?,
        val name: String?,
        val codecs: String? = null,
        /** Media playlist of the separate audio rendition, when the variant has one. */
        val audioUrl: String? = null,
    ) {
        val height: Int get() = resolution?.substringAfter('x', "")?.toIntOrNull() ?: 0
    }

    data class Key(val method: String, val uri: String?, val iv: ByteArray?)

    data class Piece(
        val index: Int,
        val url: String,
        val duration: Double,
        val key: Key?,
        val sequence: Long,
        val byteRange: LongRange? = null,
    )

    data class MediaPlaylist(
        val pieces: List<Piece>,
        val initUrl: String?,
        val totalDuration: Double,
        val isLive: Boolean,
        val initRange: LongRange? = null,
    )

    fun isMaster(text: String) = text.lineSequence().any { it.startsWith("#EXT-X-STREAM-INF") }

    fun parseMaster(text: String, baseUrl: String): List<Variant> {
        // Audio renditions: GROUP-ID -> URI of the default (or first) member.
        val audio = LinkedHashMap<String, String>()
        val audioDefault = HashSet<String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (!line.startsWith("#EXT-X-MEDIA:")) continue
            val a = attributes(line.substringAfter(':'))
            if (a["TYPE"] != "AUDIO") continue
            val group = a["GROUP-ID"] ?: continue
            val uri = a["URI"]?.let { resolve(baseUrl, it) } ?: continue
            val isDefault = a["DEFAULT"] == "YES"
            if (group !in audio || (isDefault && group !in audioDefault)) {
                audio[group] = uri
                if (isDefault) audioDefault += group
            }
        }
        val out = mutableListOf<Variant>()
        var pending: Map<String, String>? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> pending = attributes(line.substringAfter(':'))
                line.isNotEmpty() && !line.startsWith("#") && pending != null -> {
                    out += Variant(
                        url = resolve(baseUrl, line),
                        bandwidth = pending["BANDWIDTH"]?.toLongOrNull() ?: 0,
                        resolution = pending["RESOLUTION"],
                        name = pending["NAME"],
                        codecs = pending["CODECS"],
                        audioUrl = pending["AUDIO"]?.let { audio[it] },
                    )
                    pending = null
                }
            }
        }
        return out.sortedByDescending { it.bandwidth }
    }

    fun parseMedia(text: String, baseUrl: String): MediaPlaylist {
        val pieces = mutableListOf<Piece>()
        var key: Key? = null
        var duration = 0.0
        var sequence = 0L
        var initUrl: String? = null
        var initRange: LongRange? = null
        var byteRange: LongRange? = null
        var lastRangeEnd = 0L
        var ended = false
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0
                line.startsWith("#EXT-X-KEY:") -> {
                    val a = attributes(line.substringAfter(':'))
                    val method = a["METHOD"] ?: "NONE"
                    key = if (method == "NONE") null else Key(
                        method = method,
                        uri = a["URI"]?.let { resolve(baseUrl, it) },
                        iv = a["IV"]?.let { hexToBytes(it) },
                    )
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val a = attributes(line.substringAfter(':'))
                    initUrl = a["URI"]?.let { resolve(baseUrl, it) }
                    initRange = a["BYTERANGE"]?.let { v ->
                        val len = v.substringBefore('@').toLong()
                        val off = v.substringAfter('@', "0").toLong()
                        off until off + len
                    }
                }
                line.startsWith("#EXTINF:") -> duration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    val v = line.substringAfter(':').trim()
                    val len = v.substringBefore('@').toLong()
                    val off = if ('@' in v) v.substringAfter('@').toLong() else lastRangeEnd
                    byteRange = off until off + len
                    lastRangeEnd = off + len
                }
                line == "#EXT-X-ENDLIST" -> ended = true
                line.isNotEmpty() && !line.startsWith("#") -> {
                    pieces += Piece(pieces.size, resolve(baseUrl, line), duration, key, sequence + pieces.size, byteRange)
                    byteRange = null
                    duration = 0.0
                }
            }
        }
        return MediaPlaylist(pieces, initUrl, pieces.sumOf { it.duration }, isLive = !ended, initRange = initRange)
    }

    /** Parses `KEY=value,KEY2="quoted, value"` attribute lists. */
    fun attributes(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < s.length) {
            val eq = s.indexOf('=', i)
            if (eq < 0) break
            val key = s.substring(i, eq).trim().trimStart(',').trim()
            var j = eq + 1
            val value: String
            if (j < s.length && s[j] == '"') {
                val close = s.indexOf('"', j + 1).let { if (it < 0) s.length else it }
                value = s.substring(j + 1, close)
                j = close + 1
            } else {
                val comma = s.indexOf(',', j).let { if (it < 0) s.length else it }
                value = s.substring(j, comma).trim()
                j = comma
            }
            out[key] = value
            i = j + 1
        }
        return out
    }

    fun resolve(base: String, ref: String): String =
        runCatching { base.toHttpUrl().resolve(ref)?.toString() }.getOrNull() ?: ref

    private fun hexToBytes(hex: String): ByteArray {
        val h = hex.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        return ByteArray(h.length / 2) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun ivForSequence(sequence: Long): ByteArray {
        val iv = ByteArray(16)
        var v = sequence
        for (i in 15 downTo 8) { iv[i] = (v and 0xFF).toByte(); v = v shr 8 }
        return iv
    }

    fun decryptAes128(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /** Output name for a stream: `video.m3u8` -> `video.ts` (or `.mp4` for fMP4 streams). */
    fun outputName(name: String, fmp4: Boolean): String {
        val base = when {
            name.endsWith(".m3u8", true) -> name.dropLast(5)
            name.endsWith(".mpd", true) -> name.dropLast(4)
            else -> name.substringBeforeLast('.', name)
        }
        return base + if (fmp4) ".mp4" else ".ts"
    }
}
