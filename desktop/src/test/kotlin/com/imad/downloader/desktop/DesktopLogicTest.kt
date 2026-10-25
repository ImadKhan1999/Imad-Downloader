package com.imad.downloader.desktop

import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClipboardMonitorTest {
    private val types = DesktopSettings().clipboardTypeSet

    @Test fun `captures links to known file types only`() {
        assertEquals("https://x.com/a/setup.exe", ClipboardMonitor.findLink("get it at https://x.com/a/setup.exe now", types))
        assertEquals("https://x.com/v.mp4?sig=1", ClipboardMonitor.findLink("https://x.com/v.mp4?sig=1", types))
        assertEquals("https://x.com/part.r01", ClipboardMonitor.findLink("https://x.com/part.r01", types), "wildcard R0*")
        assertNull(ClipboardMonitor.findLink("https://x.com/page.html", types))
        assertNull(ClipboardMonitor.findLink("just text", types))
    }

    @Test fun `fires once per new clipboard content`() {
        var clip = "https://x.com/old.zip"
        val got = mutableListOf<String>()
        val m = ClipboardMonitor({ true }, { types }, { clip }) { got += it }
        m.prime()
        m.check()
        assertTrue(got.isEmpty(), "already copied before start")
        clip = "https://x.com/new.zip"
        m.check(); m.check()
        assertEquals(listOf("https://x.com/new.zip"), got)
    }
}

class SchedulerTimeTest {
    @Test fun `parses and formats times`() {
        assertEquals(23 * 60 + 30, parseMinutes("23:30"))
        assertEquals(0, parseMinutes("0:00"))
        assertNull(parseMinutes("24:00"))
        assertNull(parseMinutes("abc"))
        assertEquals("07:05", minutesText(7 * 60 + 5))
    }
}

class SettingsTest {
    @Test fun `engine config follows settings`() {
        val s = DesktopSettings(speedLimitEnabled = true, speedLimitKBps = 300, connections = 16, maxConcurrent = 4, overwriteExisting = true)
        val c = s.toEngineConfig()
        assertEquals(300 * 1024L, c.globalSpeedLimit)
        assertEquals(16, c.defaultConnections)
        assertEquals(4, c.maxConcurrentDownloads)
        assertEquals(0, s.copy(speedLimitEnabled = false).toEngineConfig().globalSpeedLimit)
    }

    @Test fun `settings round trip`() {
        val f = java.io.File.createTempFile("settings", ".json")
        val store = SettingsStore(f)
        store.save(DesktopSettings(theme = ThemeMode.DARK, connections = 12))
        val back = store.load()
        assertEquals(ThemeMode.DARK, back.theme)
        assertEquals(12, back.connections)
        f.delete()
    }
}

class IntegrationServerTest {
    init {
        // Lets the test send an Origin header like a browser would.
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true")
    }

    private val downloads = CopyOnWriteArrayList<CapturedDownload>()
    private val server = IntegrationServer(
        onDownload = { downloads += it },
        onLinks = {},
        onShow = {},
        ping = { PingReply("iDownloader", "test", true, listOf("zip")) },
    )

    @AfterTest fun stop() = server.close()

    private fun call(path: String, body: String, origin: String?, clientHeader: Boolean = true): Pair<Int, String> {
        val c = URI("http://127.0.0.1:${server.port}$path").toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        if (clientHeader) c.setRequestProperty("X-Imad-Client", "test")
        origin?.let { c.setRequestProperty("Origin", it) }
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
        return code to text
    }

    @Test fun `extension can ping and send a download`() {
        assertTrue(server.start())
        val (code, text) = call("/api/ping", "{}", "chrome-extension://abcdef")
        assertEquals(200, code)
        assertTrue(text.contains("iDownloader"))
        val (c2, _) = call("/api/download", """{"url":"https://x.com/a.zip","referer":"https://x.com/","mode":"silent"}""", "chrome-extension://abcdef")
        assertEquals(200, c2)
        assertEquals("https://x.com/a.zip", downloads.single().url)
        assertEquals("silent", downloads.single().mode)
    }

    @Test fun `web pages and header-less calls are rejected`() {
        assertTrue(server.start())
        assertEquals(403, call("/api/download", """{"url":"https://evil/x.zip"}""", "https://evil.example.com").first)
        assertEquals(400, call("/api/download", """{"url":"https://x/x.zip"}""", null, clientHeader = false).first)
        assertEquals(400, call("/api/download", """{"url":"file:///c:/x"}""", null).first)
        assertTrue(downloads.isEmpty())
    }
}

class BrowserSetupTest {
    @Test fun `tells browsers apart by user agent`() {
        val chrome = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
        assertEquals("chrome", BrowserSetup.familyOf(chrome, "chrome-extension://abc"))
        assertEquals("edge", BrowserSetup.familyOf("$chrome Edg/141.0.0.0", null))
        assertEquals("opera", BrowserSetup.familyOf("$chrome OPR/120.0.0.0", null))
        assertEquals("firefox", BrowserSetup.familyOf("Mozilla/5.0 (Windows NT 10.0; rv:144.0) Gecko/20100101 Firefox/144.0", null))
        assertEquals("firefox", BrowserSetup.familyOf(null, "moz-extension://1234"))
        assertNull(BrowserSetup.familyOf(null, null))
    }

    @Test fun `no store listing means guided install`() {
        val b = InstalledBrowser("chrome", "Google Chrome", "chrome.exe", BrowserFamily.CHROMIUM, "chrome://extensions", "k", "u")
        assertNull(ExtensionStores.storePage(b))
        assertEquals(false, BrowserSetup.registerFromStore(b))
    }

    @Test fun `both extension builds are found`() {
        assertTrue(BrowserSetup.packagedExtension(BrowserFamily.CHROMIUM) != null, "run node extension/build.mjs")
        assertTrue(BrowserSetup.packagedExtension(BrowserFamily.FIREFOX) != null)
    }
}
