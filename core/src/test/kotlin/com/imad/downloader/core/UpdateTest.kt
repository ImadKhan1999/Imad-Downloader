package com.imad.downloader.core

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateTest {
    private val server = TestServer()
    private val dir = Files.createTempDirectory("upd").toFile()

    @AfterTest fun clean() { server.close(); dir.deleteRecursively() }

    @Test fun `version comparison`() {
        assertTrue(UpdateClient.isNewer("1.2.0", "1.1.0"))
        assertTrue(UpdateClient.isNewer("1.10.0", "1.9.9"))
        assertTrue(UpdateClient.isNewer("v2.0", "1.99.99"))
        assertFalse(UpdateClient.isNewer("1.1.0", "1.1.0"))
        assertFalse(UpdateClient.isNewer("1.0.9", "1.1"))
    }

    @Test fun `reads manifest and downloads a verified installer with progress`() {
        val payload = TestServer.content(700_000)
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        server.static["/rel/Imad.Downloader-9.0.0.msi"] = payload to "application/octet-stream"
        server.static["/update/version.json"] = """
            {"windows":{"version":"9.0.0","url":"${server.base}/rel/Imad.Downloader-9.0.0.msi","sha256":"$sha","size":${payload.size},"changelog":"New stuff"},
             "android":{"version":"9.0.0","versionCode":90,"url":"${server.base}/rel/x.apk","sha256":"00"},"future":"ignored"}
        """.trimIndent().toByteArray() to "application/json"
        val client = UpdateClient(manifestUrl = "${server.base}/update/version.json")
        val m = runBlocking { client.manifest() }
        assertEquals("9.0.0", m.windows!!.version)
        assertEquals(90, m.android!!.versionCode)
        val seen = mutableListOf<Long>()
        val file = runBlocking { client.download(m.windows!!, dir) { done, _ -> seen += done } }
        assertContentEquals(payload, file.readBytes())
        assertEquals("Imad.Downloader-9.0.0.msi", file.name)
        assertEquals(payload.size.toLong(), seen.last())
        // Second call reuses the verified file.
        assertEquals(file, runBlocking { client.download(m.windows!!, dir) { _, _ -> } })
    }

    @Test fun `tampered download is refused`() {
        server.static["/rel/bad.apk"] = TestServer.content(10_000) to "application/octet-stream"
        val client = UpdateClient()
        val e = assertFailsWith<java.io.IOException> {
            runBlocking { client.download(ReleaseInfo("9", url = "${server.base}/rel/bad.apk", sha256 = "ab".repeat(32)), dir) { _, _ -> } }
        }
        assertTrue(e.message!!.contains("checksum"))
        assertFalse(dir.resolve("bad.apk").exists())
    }
}
