package com.imad.downloader

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Tiny HTTP/1.1 server for on-device tests: static routes with byte-range support. */
class MiniServer : AutoCloseable {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val base = "http://127.0.0.1:${socket.localPort}"
    val routes = HashMap<String, Route>()

    class Route(val body: ByteArray, val type: String, val attachmentName: String? = null, val bytesPerSecond: Long = 0)

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val s = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(s) }; runCatching { s.close() } }
            }
        }
    }

    private fun serve(s: Socket) {
        val reader = BufferedReader(InputStreamReader(s.getInputStream()))
        val request = reader.readLine() ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
        }
        val path = request.split(' ')[1].substringBefore('?')
        val out = s.getOutputStream()
        val route = routes[path]
        if (route == null) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        val data = route.body
        var from = 0
        var to = data.size - 1
        val range = headers["range"]
        val status: String
        if (range != null) {
            val spec = range.removePrefix("bytes=")
            from = spec.substringBefore('-').toInt()
            to = spec.substringAfter('-').ifBlank { "${data.size - 1}" }.toInt().coerceAtMost(data.size - 1)
            status = "206 Partial Content"
        } else status = "200 OK"
        val sb = StringBuilder("HTTP/1.1 $status\r\n")
        sb.append("Content-Type: ${route.type}\r\nAccept-Ranges: bytes\r\nConnection: close\r\n")
        sb.append("Content-Length: ${to - from + 1}\r\n")
        if (range != null) sb.append("Content-Range: bytes $from-$to/${data.size}\r\n")
        route.attachmentName?.let { sb.append("Content-Disposition: attachment; filename=\"$it\"\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
        var pos = from
        val started = System.nanoTime()
        while (pos <= to) {
            val n = minOf(16 * 1024, to - pos + 1)
            out.write(data, pos, n)
            pos += n
            if (route.bytesPerSecond > 0) {
                val expected = (pos - from).toLong() * 1_000_000_000L / route.bytesPerSecond
                val elapsed = System.nanoTime() - started
                if (expected > elapsed) Thread.sleep((expected - elapsed) / 1_000_000)
            }
        }
        out.flush()
    }

    override fun close() = socket.close()

    companion object {
        fun content(size: Int) = ByteArray(size) { i -> ((i * 31 + (i shr 8) * 7) and 0xFF).toByte() }
    }
}
