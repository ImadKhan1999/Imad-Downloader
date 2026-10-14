package com.imad.downloader.core

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

object Http {
    fun client(config: EngineConfig): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeoutMillis, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
        config.proxy?.let { p ->
            val type = if (p.type == ProxyType.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP
            b.proxy(Proxy(type, InetSocketAddress.createUnresolved(p.host, p.port)))
            if (!p.username.isNullOrEmpty()) {
                val cred = Credentials.basic(p.username, p.password.orEmpty())
                b.proxyAuthenticator { _, response ->
                    if (response.request.header("Proxy-Authorization") != null) null
                    else response.request.newBuilder().header("Proxy-Authorization", cred).build()
                }
            }
        }
        return b.build()
    }

    fun request(
        url: String,
        headers: Map<String, String>,
        referer: String?,
        userAgent: String,
        range: LongRange? = null,
        openEndedFrom: Long? = null,
    ): Request {
        val b = Request.Builder().url(url)
        b.header("User-Agent", userAgent)
        b.header("Accept", "*/*")
        // Compressed transfer would break byte ranges, so always ask for the raw bytes.
        b.header("Accept-Encoding", "identity")
        referer?.let { b.header("Referer", it) }
        headers.forEach { (k, v) -> if (k.isNotBlank()) b.header(k, v) }
        when {
            range != null -> b.header("Range", "bytes=${range.first}-${range.last}")
            openEndedFrom != null -> b.header("Range", "bytes=$openEndedFrom-")
        }
        return b.build()
    }
}

/** Cancellation-aware OkHttp call. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, _, _ -> response.close() }
        }
    })
}

class HttpStatusException(val code: Int, message: String) : IOException(message)

/** What the server told us about a URL before downloading. */
data class ProbeResult(
    val finalUrl: String,
    val fileName: String,
    val totalBytes: Long,
    val resumable: Boolean,
    val mimeType: String?,
    val etag: String?,
    val lastModified: String?,
    val isHls: Boolean,
) {
    val category: Category get() = Category.detect(fileName, mimeType)
}

object Prober {
    /**
     * Asks for the first byte with a Range request. A 206 proves range support (so pause/resume
     * and multiple connections work); a 200 means a single, non-resumable stream.
     */
    suspend fun probe(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        userAgent: String = EngineConfig.DEFAULT_USER_AGENT,
    ): ProbeResult {
        val req = Http.request(url, headers, referer, userAgent, range = 0L..0L)
        client.newCall(req).await().use { resp ->
            if (!resp.isSuccessful && resp.code != 416) {
                throw HttpStatusException(resp.code, "Server replied ${resp.code} ${resp.message}".trim())
            }
            val finalUrl = resp.request.url.toString()
            val mime = resp.header("Content-Type")
            val disposition = resp.header("Content-Disposition")
            var total = -1L
            var resumable = false
            if (resp.code == 206) {
                val cr = resp.header("Content-Range") // bytes 0-0/12345
                val size = cr?.substringAfter('/')?.trim()?.toLongOrNull()
                if (size != null) {
                    total = size
                    resumable = true
                }
            } else if (resp.code == 200) {
                total = resp.header("Content-Length")?.toLongOrNull() ?: -1
            } else if (resp.code == 416) {
                // Empty file on a range-capable server.
                total = resp.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: 0
                resumable = true
            }
            val name = FileNames.resolve(finalUrl, disposition, mime)
            val isHls = name.endsWith(".m3u8", ignoreCase = true) ||
                mime?.lowercase()?.let { "mpegurl" in it } == true
            return ProbeResult(
                finalUrl = finalUrl,
                fileName = name,
                totalBytes = total,
                resumable = resumable,
                mimeType = mime,
                etag = resp.header("ETag"),
                lastModified = resp.header("Last-Modified"),
                isHls = isHls,
            )
        }
    }
}
