package com.imad.downloader.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.net.URLEncoder

@Serializable
data class Bookmark(val url: String, val title: String, val addedAt: Long = System.currentTimeMillis())

@Serializable
data class HistoryEntry(val url: String, val title: String, val visitedAt: Long = System.currentTimeMillis())

@Serializable
data class BrowserStore(
    val bookmarks: List<Bookmark> = DEFAULT_SPEED_DIAL,
    val history: List<HistoryEntry> = emptyList(),
) {
    companion object {
        val DEFAULT_SPEED_DIAL = listOf(
            Bookmark("https://www.google.com", "Google"),
            Bookmark("https://www.youtube.com", "YouTube"),
            Bookmark("https://www.facebook.com", "Facebook"),
            Bookmark("https://www.instagram.com", "Instagram"),
            Bookmark("https://x.com", "X"),
            Bookmark("https://www.wikipedia.org", "Wikipedia"),
            Bookmark("https://www.reddit.com", "Reddit"),
            Bookmark("https://github.com", "GitHub"),
        )
    }
}

/** Bookmarks (also the speed dial on the start page) and history, saved as JSON. */
class BrowserData(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<BrowserStore> = _state.asStateFlow()

    private fun load(): BrowserStore =
        runCatching { json.decodeFromString(BrowserStore.serializer(), file.readText()) }.getOrElse { BrowserStore() }

    private fun save() = runCatching { file.writeText(json.encodeToString(BrowserStore.serializer(), _state.value)) }

    fun isBookmarked(url: String) = _state.value.bookmarks.any { it.url == url }

    fun toggleBookmark(url: String, title: String) {
        _state.update { s ->
            if (s.bookmarks.any { it.url == url }) s.copy(bookmarks = s.bookmarks.filterNot { it.url == url })
            else s.copy(bookmarks = s.bookmarks + Bookmark(url, title.ifBlank { UrlInput.host(url) }))
        }
        save()
    }

    fun removeBookmark(url: String) {
        _state.update { s -> s.copy(bookmarks = s.bookmarks.filterNot { it.url == url }) }
        save()
    }

    fun addHistory(url: String, title: String) {
        if (url.startsWith("about:") || url.startsWith("data:")) return
        _state.update { s ->
            val rest = s.history.filterNot { it.url == url }
            s.copy(history = (listOf(HistoryEntry(url, title)) + rest).take(MAX_HISTORY))
        }
        save()
    }

    fun clearHistory() {
        _state.update { it.copy(history = emptyList()) }
        save()
    }

    companion object {
        const val MAX_HISTORY = 500
    }
}

/** Turns what the user typed in the address bar into a URL. */
object UrlInput {
    private val hostLike = Regex("""^([a-z0-9-]+\.)+[a-z]{2,}(:\d+)?(/.*)?$""", RegexOption.IGNORE_CASE)
    private val ipLike = Regex("""^(\d{1,3}\.){3}\d{1,3}(:\d+)?(/.*)?$""")

    fun toUrl(input: String, searchTemplate: String): String {
        val t = input.trim()
        if (t.isEmpty()) return "about:blank"
        if (t.startsWith("http://", true) || t.startsWith("https://", true) || t.startsWith("about:") || t.startsWith("file:")) return t
        if (!t.contains(' ') && (hostLike.matches(t) || ipLike.matches(t) || t.startsWith("localhost"))) {
            return if (ipLike.matches(t) || t.startsWith("localhost")) "http://$t" else "https://$t"
        }
        return searchTemplate.replace("%s", URLEncoder.encode(t, "UTF-8"))
    }

    fun host(url: String): String = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull() ?: url

    /** What the address bar shows when not editing: the host, or the search words. */
    fun display(url: String): String = host(url)
}
