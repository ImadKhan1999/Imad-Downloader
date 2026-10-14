package com.imad.downloader.core

import org.w3c.dom.Element
import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.ceil

/** MPEG-DASH (.mpd) manifests: the first period of on-demand presentations. */
object Dash {
    enum class Type { VIDEO, AUDIO, OTHER }

    data class Representation(
        val id: String,
        val type: Type,
        val bandwidth: Long,
        val width: Int,
        val height: Int,
        val codecs: String?,
        val mimeType: String?,
        val initUrl: String?,
        val initRange: LongRange?,
        /** Media pieces in order. A single piece without range = the whole file. */
        val pieces: List<Pair<String, LongRange?>>,
    ) {
        val isWebm: Boolean get() = mimeType?.contains("webm") == true
    }

    data class Manifest(val representations: List<Representation>, val durationSeconds: Double) {
        val videos get() = representations.filter { it.type == Type.VIDEO }.sortedByDescending { it.bandwidth }
        val audios get() = representations.filter { it.type == Type.AUDIO }.sortedByDescending { it.bandwidth }
    }

    fun parse(xml: String, manifestUrl: String): Manifest {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // No external entities: manifests come from the network.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
        val mpd = doc.documentElement
        if (mpd.getAttribute("type") == "dynamic") throw IOException("Live streams are not supported")
        val period = mpd.children("Period").firstOrNull() ?: throw IOException("Manifest has no period")
        val duration = parseDuration(period.getAttribute("duration").ifEmpty { mpd.getAttribute("mediaPresentationDuration") })
        var base = resolveBase(manifestUrl, mpd)
        base = resolveBase(base, period)
        val reps = mutableListOf<Representation>()
        for (set in period.children("AdaptationSet")) {
            val setBase = resolveBase(base, set)
            val setTemplate = set.children("SegmentTemplate").firstOrNull()
            val setList = set.children("SegmentList").firstOrNull()
            for (rep in set.children("Representation")) {
                val repBase = resolveBase(setBase, rep)
                val mime = rep.attr("mimeType") ?: set.attr("mimeType")
                val contentType = set.attr("contentType")
                val codecs = rep.attr("codecs") ?: set.attr("codecs")
                val type = when {
                    mime?.startsWith("video") == true || contentType == "video" -> Type.VIDEO
                    mime?.startsWith("audio") == true || contentType == "audio" -> Type.AUDIO
                    else -> Type.OTHER
                }
                val id = rep.attr("id") ?: reps.size.toString()
                val bandwidth = rep.attr("bandwidth")?.toLongOrNull() ?: 0
                val template = rep.children("SegmentTemplate").firstOrNull() ?: setTemplate
                val list = rep.children("SegmentList").firstOrNull() ?: setList
                var initUrl: String? = null
                var initRange: LongRange? = null
                val pieces = mutableListOf<Pair<String, LongRange?>>()
                when {
                    template != null -> {
                        val vars = mapOf("RepresentationID" to id, "Bandwidth" to bandwidth.toString())
                        val tTemplate = merge(template, setTemplate)
                        tTemplate("initialization")?.let { initUrl = Hls.resolve(repBase, fill(it, vars, null, null)) }
                        val media = tTemplate("media") ?: throw IOException("SegmentTemplate without media")
                        val start = tTemplate("startNumber")?.toLongOrNull() ?: 1
                        val timescale = tTemplate("timescale")?.toLongOrNull() ?: 1
                        val timeline = (template.children("SegmentTimeline").firstOrNull() ?: setTemplate?.children("SegmentTimeline")?.firstOrNull())
                        if (timeline != null) {
                            var t = 0L
                            var n = start
                            for (s in timeline.children("S")) {
                                s.attr("t")?.toLongOrNull()?.let { t = it }
                                val d = s.attr("d")?.toLongOrNull() ?: throw IOException("SegmentTimeline entry without d")
                                var r = s.attr("r")?.toLongOrNull() ?: 0
                                if (r < 0) r = ((duration * timescale - t) / d).toLong().coerceAtLeast(0) // repeat to period end
                                repeat((r + 1).toInt()) {
                                    pieces += Hls.resolve(repBase, fill(media, vars, n, t)) to null
                                    t += d; n++
                                }
                            }
                        } else {
                            val d = tTemplate("duration")?.toLongOrNull() ?: throw IOException("SegmentTemplate without duration")
                            if (duration <= 0) throw IOException("Manifest has no duration")
                            val count = ceil(duration * timescale / d).toLong()
                            for (i in 0 until count) {
                                pieces += Hls.resolve(repBase, fill(media, vars, start + i, i * d)) to null
                            }
                        }
                    }
                    list != null -> {
                        list.children("Initialization").firstOrNull()?.let { init ->
                            initUrl = Hls.resolve(repBase, init.attr("sourceURL") ?: "")
                            initRange = init.attr("range")?.let(::parseRange)
                        }
                        for (seg in list.children("SegmentURL")) {
                            val url = seg.attr("media")?.let { Hls.resolve(repBase, it) } ?: repBase
                            pieces += url to seg.attr("mediaRange")?.let(::parseRange)
                        }
                    }
                    else -> pieces += repBase to null // SegmentBase / plain BaseURL: one complete file
                }
                reps += Representation(
                    id, type, bandwidth,
                    rep.attr("width")?.toIntOrNull() ?: set.attr("width")?.toIntOrNull() ?: 0,
                    rep.attr("height")?.toIntOrNull() ?: set.attr("height")?.toIntOrNull() ?: 0,
                    codecs, mime, initUrl, initRange, pieces,
                )
            }
        }
        if (reps.isEmpty()) throw IOException("Manifest has no streams")
        return Manifest(reps, duration)
    }

    private fun Element.children(name: String): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && (n.tagName == name || n.tagName.substringAfter(':') == name)) out += n
        }
        return out
    }

    private fun Element.attr(name: String): String? = getAttribute(name).takeIf { it.isNotEmpty() }

    /** Template attributes fall back from Representation to AdaptationSet level. */
    private fun merge(primary: Element, fallback: Element?): (String) -> String? = { n -> primary.attr(n) ?: fallback?.attr(n) }

    private fun resolveBase(base: String, el: Element): String {
        val b = el.children("BaseURL").firstOrNull()?.textContent?.trim()
        return if (b.isNullOrEmpty()) base else Hls.resolve(base, b)
    }

    private val token = Regex("""\$(RepresentationID|Number|Time|Bandwidth)(%0(\d+)d)?\$""")

    fun fill(template: String, vars: Map<String, String>, number: Long?, time: Long?): String =
        template.replace("$$", "\u0000").replace(token) { m ->
            val value = when (m.groupValues[1]) {
                "Number" -> number?.toString() ?: ""
                "Time" -> time?.toString() ?: ""
                else -> vars[m.groupValues[1]] ?: ""
            }
            val width = m.groupValues[3].toIntOrNull()
            if (width != null) value.padStart(width, '0') else value
        }.replace("\u0000", "$")

    private fun parseRange(s: String): LongRange {
        val a = s.substringBefore('-').trim().toLong()
        val b = s.substringAfter('-').trim().toLong()
        return a..b
    }

    /** ISO 8601 durations like PT1H2M3.5S. */
    fun parseDuration(s: String?): Double {
        if (s.isNullOrBlank()) return 0.0
        val m = Regex("""P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?""").find(s) ?: return 0.0
        val (d, h, mi, sec) = m.destructured
        return (d.toDoubleOrNull() ?: 0.0) * 86400 + (h.toDoubleOrNull() ?: 0.0) * 3600 +
            (mi.toDoubleOrNull() ?: 0.0) * 60 + (sec.toDoubleOrNull() ?: 0.0)
    }
}
