package com.imad.downloader.core

import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** File hashing for the "verify checksum" feature. */
object Checksums {
    /** Algorithm is chosen by digest length unless prefixed, e.g. "sha256:abcd...". */
    fun algorithmFor(expected: String): Pair<String, String> {
        val trimmed = expected.trim()
        val prefix = trimmed.substringBefore(':', "").lowercase()
        val hex = trimmed.substringAfter(':').lowercase()
        val algo = when (prefix) {
            "md5" -> "MD5"
            "sha1", "sha-1" -> "SHA-1"
            "sha256", "sha-256" -> "SHA-256"
            "sha512", "sha-512" -> "SHA-512"
            else -> when (hex.length) {
                32 -> "MD5"
                40 -> "SHA-1"
                128 -> "SHA-512"
                else -> "SHA-256"
            }
        }
        return algo to hex
    }

    fun hash(file: File, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun matches(file: File, expected: String): Boolean {
        val (algo, hex) = algorithmFor(expected)
        return hash(file, algo).equals(hex, ignoreCase = true)
    }
}

/**
 * IDM "Add batch download": expands `http://x/img[001-120].jpg` or `file[a-z].zip`
 * into every matching URL.
 */
object BatchUrls {
    private val pattern = Regex("""\[(\d+)-(\d+)]|\[([a-zA-Z])-([a-zA-Z])]""")
    const val MAX_URLS = 10_000

    fun expand(template: String): List<String> {
        val m = pattern.find(template) ?: return listOf(template)
        val prefix = template.substring(0, m.range.first)
        val suffix = template.substring(m.range.last + 1)
        val values: List<String> = if (m.groupValues[1].isNotEmpty()) {
            val a = m.groupValues[1]
            val b = m.groupValues[2]
            val from = a.toLong()
            val to = b.toLong()
            val width = if (a.length > 1 && a.startsWith('0')) a.length else 0
            val range = if (from <= to) from..to else from downTo to
            range.take(MAX_URLS).map { if (width > 0) it.toString().padStart(width, '0') else it.toString() }
        } else {
            val a = m.groupValues[3][0]
            val b = m.groupValues[4][0]
            val range = if (a <= b) a..b else a downTo b
            range.map { it.toString() }
        }
        return values.flatMap { expand(prefix + it + suffix) }.take(MAX_URLS)
    }

    /** Pulls every http(s) link out of free text (clipboard, pasted lists). */
    fun extractLinks(text: String): List<String> =
        Regex("""https?://[^\s"'<>]+""").findAll(text).map { it.value.trimEnd('.', ',', ')', ';') }.distinct().toList()
}

/** Where the engine keeps its state. */
interface DownloadStore {
    fun load(): EngineState
    fun save(state: EngineState)
}

class InMemoryStore(private var state: EngineState = EngineState()) : DownloadStore {
    override fun load() = state
    override fun save(state: EngineState) { this.state = state }
}

/** JSON file store with atomic replace, so a crash never leaves a half-written list. */
class JsonFileStore(private val file: File) : DownloadStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }

    override fun load(): EngineState {
        for (candidate in listOf(file, File(file.path + ".bak"))) {
            if (!candidate.exists()) continue
            val parsed = runCatching { json.decodeFromString(EngineState.serializer(), candidate.readText()) }.getOrNull()
            if (parsed != null) return parsed
        }
        return EngineState()
    }

    @Synchronized
    override fun save(state: EngineState) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(EngineState.serializer(), state))
        if (file.exists()) file.copyTo(File(file.path + ".bak"), overwrite = true)
        try {
            java.nio.file.Files.move(
                tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
