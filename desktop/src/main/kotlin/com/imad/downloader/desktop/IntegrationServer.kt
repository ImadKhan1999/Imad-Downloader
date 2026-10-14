package com.imad.downloader.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.Executors

/** A download handed over by the browser extension (or a second app instance). */
@Serializable
data class CapturedDownload(
    val url: String,
    val fileName: String? = null,
    val referer: String? = null,
    val cookies: String? = null,
    val userAgent: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val size: Long = -1,
    val mime: String? = null,
    /** "ask" shows the file info dialog, "silent" starts at once, "later" adds paused. */
    val mode: String = "ask",
)

@Serializable
data class CapturedLink(val url: String, val name: String? = null)

@Serializable
data class CapturedLinks(
    val links: List<CapturedLink>,
    val referer: String? = null,
    val cookies: String? = null,
    val userAgent: String? = null,
)

@Serializable
data class PingReply(val app: String, val version: String, val enabled: Boolean, val types: List<String>)

/**
 * Local HTTP API on 127.0.0.1 used by the browser extension and for single-instance hand-off.
 *
 * Web pages cannot use it: every call needs the custom `X-Imad-Client` header, which forces a CORS
 * preflight, and preflights are only answered for browser-extension origins.
 */
class IntegrationServer(
    private val onDownload: (CapturedDownload) -> Unit,
    private val onLinks: (CapturedLinks) -> Unit,
    private val onShow: () -> Unit,
    private val ping: () -> PingReply,
) : AutoCloseable {
    private var server: HttpServer? = null
    var port: Int = -1
        private set

    fun start(): Boolean {
        for (p in PORTS) {
            val s = runCatching { HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), p), 16) }.getOrNull() ?: continue
            s.executor = Executors.newFixedThreadPool(4) { r -> Thread(r, "imad-integration").apply { isDaemon = true } }
            s.createContext("/api/") { ex -> runCatching { handle(ex) }.onFailure { runCatching { reply(ex, 500, "{}") } }; ex.close() }
            s.start()
            server = s
            port = p
            return true
        }
        return false
    }

    private fun allowedOrigin(origin: String?): Boolean =
        origin == null || EXTENSION_SCHEMES.any { origin.startsWith(it) }

    private fun handle(ex: HttpExchange) {
        val origin = ex.requestHeaders.getFirst("Origin")
        if (!allowedOrigin(origin)) return reply(ex, 403, """{"error":"forbidden"}""")
        origin?.let {
            ex.responseHeaders.add("Access-Control-Allow-Origin", it)
            ex.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type, X-Imad-Client")
            ex.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        }
        if (ex.requestMethod == "OPTIONS") return reply(ex, 204, null)
        if (ex.requestHeaders.getFirst("X-Imad-Client").isNullOrBlank()) return reply(ex, 400, """{"error":"missing client header"}""")
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        when (ex.requestURI.path) {
            "/api/ping" -> reply(ex, 200, json.encodeToString(PingReply.serializer(), ping()))
            "/api/download" -> {
                val d = json.decodeFromString(CapturedDownload.serializer(), body)
                if (!d.url.startsWith("http://") && !d.url.startsWith("https://")) return reply(ex, 400, """{"error":"bad url"}""")
                onDownload(d)
                reply(ex, 200, """{"ok":true}""")
            }
            "/api/links" -> {
                val l = json.decodeFromString(CapturedLinks.serializer(), body)
                onLinks(l.copy(links = l.links.filter { it.url.startsWith("http") }.take(5000)))
                reply(ex, 200, """{"ok":true}""")
            }
            "/api/show" -> {
                onShow()
                reply(ex, 200, """{"ok":true}""")
            }
            else -> reply(ex, 404, """{"error":"not found"}""")
        }
    }

    private fun reply(ex: HttpExchange, code: Int, body: String?) {
        if (body == null) {
            ex.sendResponseHeaders(code, -1)
            return
        }
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        server?.stop(0)
        server = null
    }

    companion object {
        val PORTS = listOf(9614, 9615, 9616, 9617)
        private val EXTENSION_SCHEMES = listOf("chrome-extension://", "moz-extension://", "extension://", "edge-extension://")
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /**
         * Used by a second app instance: if one is already running, hand it our arguments
         * (a URL to download, or just "show yourself") and return true.
         */
        fun forwardToRunningInstance(args: Array<String>): Boolean {
            for (p in PORTS) {
                val ok = runCatching {
                    val ping = post(p, "/api/ping", "{}") ?: return@runCatching false
                    if (!ping.contains("Imad Downloader")) return@runCatching false
                    val url = args.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
                    if (url != null) post(p, "/api/download", json.encodeToString(CapturedDownload.serializer(), CapturedDownload(url)))
                    else if ("--tray" !in args) post(p, "/api/show", "{}")
                    true
                }.getOrDefault(false)
                if (ok) return true
            }
            return false
        }

        private fun post(port: Int, path: String, body: String): String? {
            val c = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 600
            c.readTimeout = 2000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("X-Imad-Client", "instance")
            c.outputStream.use { it.write(body.toByteArray()) }
            return if (c.responseCode == 200) c.inputStream.readBytes().toString(Charsets.UTF_8) else null
        }
    }
}
