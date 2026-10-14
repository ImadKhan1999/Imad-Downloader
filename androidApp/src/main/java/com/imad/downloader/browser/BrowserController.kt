package com.imad.downloader.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.imad.downloader.AppSettings
import com.imad.downloader.SettingsRepository
import com.imad.downloader.core.DownloadRequest
import com.imad.downloader.core.FileNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream

/** Link long-pressed in a page. */
data class LinkMenu(val url: String, val imageUrl: String?, val title: String?)

/** One browser tab: its WebView plus the state the UI shows. */
class BrowserTab(val id: Int, val incognito: Boolean) {
    var webView: WebView? = null
    var url by mutableStateOf("")
    var title by mutableStateOf("New tab")
    var progress by mutableIntStateOf(100)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var desktopMode by mutableStateOf(false)
    var favicon by mutableStateOf<Bitmap?>(null)
    val media = mutableStateListOf<DetectedMedia>()
    val isStartPage: Boolean get() = url.isEmpty() || url == "about:blank"
}

/**
 * Owns the tabs and wires every WebView to the download manager: downloads the page starts,
 * media the page plays, long-pressed links, ad blocking and history.
 */
class BrowserController(
    private val context: Context,
    private val settings: SettingsRepository,
    private val data: BrowserData,
) {
    val tabs = mutableStateListOf<BrowserTab>()
    var current by mutableStateOf<BrowserTab?>(null)
        private set
    /** A download the page asked for; the UI shows the add-download sheet for it. */
    var pendingDownload by mutableStateOf<DownloadRequest?>(null)
    var linkMenu by mutableStateOf<LinkMenu?>(null)
    var fullscreenView by mutableStateOf<View?>(null)
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    var blockedAds by mutableIntStateOf(0)
        private set
    private var nextId = 1

    init {
        newTab()
    }

    fun newTab(url: String? = null, incognito: Boolean = false, select: Boolean = true): BrowserTab {
        val tab = BrowserTab(nextId++, incognito)
        tabs += tab
        if (select) current = tab
        if (url != null) load(tab, url)
        return tab
    }

    fun select(tab: BrowserTab) { current = tab }

    fun close(tab: BrowserTab) {
        val idx = tabs.indexOf(tab)
        tabs.remove(tab)
        tab.webView?.let { it.stopLoading(); it.destroy() }
        tab.webView = null
        if (tabs.isEmpty()) newTab()
        else if (current == tab) current = tabs[(idx - 1).coerceAtLeast(0).coerceAtMost(tabs.lastIndex)]
    }

    fun closeAll(incognitoOnly: Boolean = false) {
        tabs.filter { !incognitoOnly || it.incognito }.forEach(::close)
    }

    fun navigate(input: String) {
        val tab = current ?: newTab()
        load(tab, UrlInput.toUrl(input, settings.value.searchEngine.template))
    }

    fun goHome() {
        val tab = current ?: return
        tab.webView?.loadUrl("about:blank")
        tab.url = ""
        tab.title = "New tab"
        tab.media.clear()
    }

    fun load(tab: BrowserTab, url: String) {
        tab.url = url
        tab.media.clear()
        if (url == "about:blank") return
        val wv = tab.webView
        if (wv != null) wv.loadUrl(url) else pendingLoads[tab.id] = url
    }

    private val pendingLoads = HashMap<Int, String>()

    fun back(): Boolean {
        val wv = current?.webView ?: return false
        if (wv.canGoBack()) { wv.goBack(); return true }
        if (current?.isStartPage == false) { goHome(); return true }
        return false
    }

    fun forward() = current?.webView?.goForward()
    fun reload() = current?.webView?.reload()
    fun stop() = current?.webView?.stopLoading()

    fun toggleDesktopMode() {
        val tab = current ?: return
        tab.desktopMode = !tab.desktopMode
        tab.webView?.let { applyMode(it, tab) ; it.reload() }
    }

    fun exitFullscreen() {
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        fullscreenView = null
    }

    fun find(text: String) = current?.webView?.findAllAsync(text)
    fun findNext(forward: Boolean) = current?.webView?.findNext(forward)
    fun clearFind() = current?.webView?.clearMatches()

    fun requestFor(url: String, fileName: String? = null, mime: String? = null, size: Long = -1): DownloadRequest {
        val tab = current
        val ua = tab?.webView?.settings?.userAgentString ?: AppSettings.MOBILE_UA
        return DownloadRequest(
            url = url,
            fileName = fileName,
            referer = tab?.url?.takeIf { it.startsWith("http") },
            cookies = CookieManager.getInstance().getCookie(url),
            userAgent = ua,
            sizeHint = size,
            description = tab?.title?.takeIf { it.isNotBlank() && it != "New tab" }.orEmpty(),
        )
    }

    private fun applyMode(wv: WebView, tab: BrowserTab) {
        val s = settings.value
        wv.settings.userAgentString = when {
            tab.desktopMode -> AppSettings.DESKTOP_UA
            s.userAgent.isNotBlank() -> s.userAgent
            else -> null // WebView default (mobile)
        }
        wv.settings.useWideViewPort = true
        wv.settings.loadWithOverviewMode = tab.desktopMode
    }

    /** Creates (once) the WebView for a tab. Called from the Compose AndroidView factory. */
    @SuppressLint("SetJavaScriptEnabled")
    fun webViewFor(tab: BrowserTab, viewContext: Context): WebView {
        tab.webView?.let { return it }
        val s = settings.value
        val wv = WebView(viewContext)
        tab.desktopMode = tab.desktopMode || s.desktopModeDefault
        wv.settings.apply {
            javaScriptEnabled = s.javascript
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = !s.blockPopups
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = if (tab.incognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        }
        applyMode(wv, tab)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, !tab.incognito)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:")) return false
                // intent:, market:, tel:, mailto: ... hand to the system.
                runCatching {
                    val intent = if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                if (settings.value.adBlock && !request.isForMainFrame && AdBlocker.isAd(u.host)) {
                    blockedAds++
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                val url = u.toString()
                MediaDetector.classify(url)?.let { kind ->
                    view.post { addMedia(tab, DetectedMedia(url, tab.url, tab.title, kind)) }
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (url != "about:blank") tab.url = url
                tab.favicon = favicon
                tab.media.clear()
                updateNav(tab, view)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url != "about:blank") tab.url = url
                updateNav(tab, view)
                if (!tab.incognito && settings.value.saveHistory && url.startsWith("http")) data.addHistory(url, view.title.orEmpty())
                view.evaluateJavascript(MediaDetector.SCAN_SCRIPT) { result -> scanResult(tab, result) }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                if (url != "about:blank") tab.url = url
                updateNav(tab, view)
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { tab.progress = newProgress }
            override fun onReceivedTitle(view: WebView, title: String?) { if (!title.isNullOrBlank()) tab.title = title }
            override fun onReceivedIcon(view: WebView, icon: Bitmap?) { tab.favicon = icon }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (settings.value.blockPopups && !isUserGesture) return false
                val newTab = newTab(incognito = tab.incognito)
                val child = webViewFor(newTab, viewContext)
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = child
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                fullscreenCallback = callback
                fullscreenView = view
            }

            override fun onHideCustomView() {
                fullscreenCallback = null
                fullscreenView = null
            }
        }

        wv.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            val name = FileNames.fromContentDisposition(contentDisposition)
                ?: URLUtil.guessFileName(url, contentDisposition, mimetype)
            pendingDownload = requestFor(url, name, mimetype, contentLength).copy(userAgent = userAgent)
        }

        wv.setOnLongClickListener {
            val hit = wv.hitTestResult
            when (hit.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> { linkMenu = LinkMenu(hit.extra ?: return@setOnLongClickListener false, null, null); true }
                WebView.HitTestResult.IMAGE_TYPE -> { linkMenu = LinkMenu(hit.extra ?: return@setOnLongClickListener false, hit.extra, null); true }
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    val msg = wv.handler.obtainMessage()
                    wv.requestFocusNodeHref(msg)
                    val href = msg.data.getString("url")
                    linkMenu = LinkMenu(href ?: hit.extra ?: return@setOnLongClickListener false, hit.extra, msg.data.getString("title"))
                    true
                }
                else -> false
            }
        }

        tab.webView = wv
        pendingLoads.remove(tab.id)?.let { wv.loadUrl(it) }
        return wv
    }

    private fun updateNav(tab: BrowserTab, view: WebView) {
        tab.canGoBack = view.canGoBack()
        tab.canGoForward = view.canGoForward()
    }

    private fun addMedia(tab: BrowserTab, m: DetectedMedia) {
        val merged = MediaDetector.merge(tab.media.toList(), m)
        if (merged.size != tab.media.size) {
            tab.media.clear()
            tab.media.addAll(merged)
        }
    }

    private fun scanResult(tab: BrowserTab, raw: String?) {
        if (raw.isNullOrBlank() || raw == "null") return
        runCatching {
            // evaluateJavascript returns a JSON string literal holding our JSON array.
            val inner = Json.parseToJsonElement(raw).jsonPrimitive.content
            Json.parseToJsonElement(inner).jsonArray.map { it.jsonPrimitive.content }.forEach { src ->
                val kind = MediaDetector.classify(src) ?: DetectedMedia.Kind.VIDEO
                addMedia(tab, DetectedMedia(src, tab.url, tab.title, kind))
            }
        }
    }

    fun onPause() = tabs.forEach { it.webView?.onPause() }
    fun onResume() = tabs.forEach { it.webView?.onResume() }
    fun destroy() = tabs.forEach { it.webView?.destroy() }
}
