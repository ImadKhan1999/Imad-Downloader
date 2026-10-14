package com.imad.downloader

import com.imad.downloader.browser.AdBlocker
import com.imad.downloader.browser.DetectedMedia
import com.imad.downloader.browser.MediaDetector
import com.imad.downloader.browser.UrlInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UrlInputTest {
    private val google = SearchEngine.GOOGLE.template

    @Test fun `addresses become urls and words become searches`() {
        assertEquals("https://example.com", UrlInput.toUrl("example.com", google))
        assertEquals("https://sub.example.co.uk/path?q=1", UrlInput.toUrl("sub.example.co.uk/path?q=1", google))
        assertEquals("http://192.168.1.10:8080", UrlInput.toUrl("192.168.1.10:8080", google))
        assertEquals("http://localhost:3000", UrlInput.toUrl("localhost:3000", google))
        assertEquals("https://x.com/a", UrlInput.toUrl("  https://x.com/a ", google))
        assertEquals("https://www.google.com/search?q=how+to+download", UrlInput.toUrl("how to download", google))
        assertEquals("https://www.google.com/search?q=kotlin", UrlInput.toUrl("kotlin", google))
    }

    @Test fun `host strips www`() {
        assertEquals("example.com", UrlInput.host("https://www.example.com/a/b"))
    }
}

class MediaDetectorTest {
    @Test fun `classifies media and skips stream pieces`() {
        assertEquals(DetectedMedia.Kind.VIDEO, MediaDetector.classify("https://cdn.x.com/v/movie.mp4?token=1"))
        assertEquals(DetectedMedia.Kind.STREAM, MediaDetector.classify("https://cdn.x.com/live/master.m3u8"))
        assertEquals(DetectedMedia.Kind.AUDIO, MediaDetector.classify("https://cdn.x.com/a/song.mp3"))
        assertNull(MediaDetector.classify("https://cdn.x.com/live/seg001.ts"))
        assertNull(MediaDetector.classify("https://cdn.x.com/page.html"))
        assertNull(MediaDetector.classify("blob:https://x.com/123"))
    }

    @Test fun `merge removes duplicates that differ only by query`() {
        val a = DetectedMedia("https://x.com/v.mp4?t=1", "p", "t", DetectedMedia.Kind.VIDEO)
        val b = DetectedMedia("https://x.com/v.mp4?t=2", "p", "t", DetectedMedia.Kind.VIDEO)
        val c = DetectedMedia("https://x.com/w.mp4", "p", "t", DetectedMedia.Kind.VIDEO)
        val list = MediaDetector.merge(MediaDetector.merge(listOf(a), b), c)
        assertEquals(listOf(a, c), list)
    }
}

class AdBlockerTest {
    @Test fun `blocks ad hosts and their subdomains only`() {
        assertTrue(AdBlocker.isAd("doubleclick.net"))
        assertTrue(AdBlocker.isAd("stats.g.doubleclick.net"))
        assertTrue(AdBlocker.isAd("pagead2.googlesyndication.com"))
        assertFalse(AdBlocker.isAd("example.com"))
        assertFalse(AdBlocker.isAd("notdoubleclick.net"))
        assertFalse(AdBlocker.isAd(null))
    }
}

class AppSettingsTest {
    @Test fun `engine config follows settings`() {
        val s = AppSettings(saveDir = "/sdcard/Download/x", connections = 16, maxConcurrent = 2, speedLimitEnabled = true, speedLimitKBps = 256)
        val c = s.toEngineConfig()
        assertEquals("/sdcard/Download/x", c.defaultSaveDir)
        assertEquals(16, c.defaultConnections)
        assertEquals(2, c.maxConcurrentDownloads)
        assertEquals(256 * 1024L, c.globalSpeedLimit)
        assertEquals(AppSettings.MOBILE_UA, c.userAgent)
    }
}
