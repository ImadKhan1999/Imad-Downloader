package com.imad.downloader.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local HTTP server for engine tests. Query flags on `/file/<name>?size=N`:
 *  - `norange=1`   ignore Range headers (always 200 with the full body)
 *  - `nolength=1`  no Content-Length, no ranges (streamed, unknown size)
 *  - `cd=<name>`   send Content-Disposition with that file name
 *  - `bps=N`       throttle each response to roughly N bytes per second
 *  - `drop=N&dropTimes=K`  cut the connection after N bytes, for the first K requests
 */
class TestServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 64)
    val port: Int get() = server.address.port
    val base: String get() = "http://127.0.0.1:$port"
    val requests = AtomicInteger(0)
    val rangeRequests = AtomicInteger(0)
    private val dropCounters = ConcurrentHashMap<String, AtomicInteger>()
    private val busyCounters = ConcurrentHashMap<String, AtomicInteger>()
    private val active = ConcurrentHashMap<String, AtomicInteger>()
    /** Extra routes: path -> bytes (served with full Range support). */
    val static = ConcurrentHashMap<String, Pair<ByteArray, String>>()

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { ex -> runCatching { handle(ex) }; ex.close() }
        server.start()
    }

    fun url(name: String, size: Int, vararg flags: Pair<String, Any>): String {
        val q = (listOf("size" to size) + flags).joinToString("&") { "${it.first}=${it.second}" }
        return "$base/file/$name?$q"
    }

    private fun handle(ex: HttpExchange) {
        requests.incrementAndGet()
        val path = ex.requestURI.path
        val query = ex.requestURI.rawQuery.orEmpty().split('&').filter { it.contains('=') }
            .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
        when {
            path.startsWith("/status/") -> {
                ex.sendResponseHeaders(path.removePrefix("/status/").toInt(), -1)
            }
            path.startsWith("/redirect") -> {
                ex.responseHeaders.add("Location", query["to"] ?: "/")
                ex.sendResponseHeaders(302, -1)
            }
            path.startsWith("/file/") -> {
                val size = query["size"]?.toInt() ?: 1024
                // busy=K: first K requests get 429; limit=N: more than N parallel requests get 429.
                val busy = query["busy"]?.toInt() ?: 0
                if (busy > 0 && busyCounters.computeIfAbsent(path) { AtomicInteger(0) }.incrementAndGet() <= busy) {
                    ex.sendResponseHeaders(429, -1); return
                }
                val running = active.computeIfAbsent(path) { AtomicInteger(0) }
                val limit = query["limit"]?.toInt()
                if (limit != null && running.get() >= limit) { ex.sendResponseHeaders(429, -1); return }
                running.incrementAndGet()
                try { serve(ex, content(size), "application/octet-stream", query, path) } finally { running.decrementAndGet() }
            }
            static.containsKey(path) -> {
                val (bytes, type) = static.getValue(path)
                serve(ex, bytes, type, query, path)
            }
            else -> ex.sendResponseHeaders(404, -1)
        }
    }

    private fun serve(ex: HttpExchange, data: ByteArray, type: String, q: Map<String, String>, path: String) {
        val noRange = q["norange"] == "1" || q["nolength"] == "1"
        ex.responseHeaders.add("Content-Type", type)
        q["cd"]?.let { ex.responseHeaders.add("Content-Disposition", "attachment; filename=\"$it\"") }
        if (!noRange) ex.responseHeaders.add("Accept-Ranges", "bytes")
        ex.responseHeaders.add("ETag", "\"v1-${data.size}\"")
        var from = 0
        var to = data.size - 1
        var code = 200
        val range = ex.requestHeaders.getFirst("Range")
        if (range != null && !noRange) {
            rangeRequests.incrementAndGet()
            val spec = range.removePrefix("bytes=")
            from = spec.substringBefore('-').toInt()
            to = spec.substringAfter('-').ifBlank { (data.size - 1).toString() }.toInt().coerceAtMost(data.size - 1)
            if (from >= data.size) {
                ex.responseHeaders.add("Content-Range", "bytes */${data.size}")
                ex.sendResponseHeaders(416, -1)
                return
            }
            code = 206
            ex.responseHeaders.add("Content-Range", "bytes $from-$to/${data.size}")
        }
        val len = to - from + 1
        if (ex.requestMethod == "HEAD") { ex.sendResponseHeaders(code, -1); return }
        ex.sendResponseHeaders(code, if (q["nolength"] == "1") 0 else len.toLong())
        val bps = q["bps"]?.toLong() ?: 0
        val dropAt = q["drop"]?.toLong()
        val dropTimes = q["dropTimes"]?.toInt() ?: 1
        val shouldDrop = dropAt != null && dropCounters.computeIfAbsent(path) { AtomicInteger(0) }.incrementAndGet() <= dropTimes
        val out = ex.responseBody
        var sent = 0L
        val chunk = 16 * 1024
        var pos = from
        val started = System.nanoTime()
        try {
            while (pos <= to) {
                val n = minOf(chunk, to - pos + 1)
                if (shouldDrop && sent + n > dropAt!!) {
                    val partial = (dropAt - sent).toInt()
                    if (partial > 0) out.write(data, pos, partial)
                    out.flush()
                    throw java.io.IOException("simulated drop")
                }
                out.write(data, pos, n)
                pos += n
                sent += n
                if (bps > 0) {
                    val expected = sent * 1_000_000_000L / bps
                    val elapsed = System.nanoTime() - started
                    if (expected > elapsed) Thread.sleep((expected - elapsed) / 1_000_000)
                }
            }
            out.flush()
        } catch (e: java.io.IOException) {
            // Client went away or we simulated a drop: kill the socket.
            runCatching { ex.httpContext.server }
            throw e
        }
    }

    override fun close() = server.stop(0)

    companion object {
        /** Deterministic, non-repeating-looking content so misplaced bytes are caught. */
        fun content(size: Int): ByteArray {
            val out = ByteArray(size)
            var x = 2463534242L
            for (i in 0 until size) {
                x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
                out[i] = (x and 0xFF).toByte()
            }
            return out
        }
    }
}
