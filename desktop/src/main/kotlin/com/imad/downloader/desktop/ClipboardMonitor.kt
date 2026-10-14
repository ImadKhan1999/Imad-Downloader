package com.imad.downloader.desktop

import com.imad.downloader.core.BatchUrls
import com.imad.downloader.core.FileNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** IDM-style clipboard capture: a copied link to a known file type opens the add dialog. */
class ClipboardMonitor(
    private val enabled: () -> Boolean,
    private val types: () -> Set<String>,
    private val read: () -> String? = Platform::clipboardText,
    private val onLink: (String) -> Unit,
) {
    private var last: String? = null

    /** Primes with the current clipboard so whatever was already copied is not captured. */
    fun prime() { last = read() }

    fun check() {
        val text = read() ?: return
        if (text == last) return
        last = text
        if (!enabled()) return
        val link = findLink(text, types()) ?: return
        onLink(link)
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        prime()
        while (isActive) {
            runCatching { check() }
            delay(1_000)
        }
    }

    companion object {
        /** First link in [text] whose file extension matches [types] (wildcards like `r0*` allowed). */
        fun findLink(text: String, types: Set<String>): String? {
            if (text.length > 20_000) return null
            return BatchUrls.extractLinks(text).firstOrNull { url ->
                val ext = FileNames.fromUrl(url)?.substringAfterLast('.', "")?.lowercase().orEmpty()
                ext.isNotEmpty() && types.any { t -> if (t.endsWith("*")) ext.startsWith(t.dropLast(1)) else ext == t }
            }
        }
    }
}
