package com.imad.downloader.desktop

import java.io.File

/** Browser families the extension supports. Chromium ones share one build. */
enum class BrowserFamily { CHROMIUM, FIREFOX }

/** A browser found on this PC. */
data class InstalledBrowser(
    val id: String,
    val name: String,
    val exe: String,
    val family: BrowserFamily,
    /** Page that lists extensions, opened for the guided install. */
    val extensionsPage: String,
    /** HKCU key where an external (store-hosted) extension can be registered. */
    val externalKey: String?,
    val storeUpdateUrl: String?,
)

/**
 * Store listings. Browsers only auto-install extensions that come from their stores, so these
 * stay empty until the extension is published; until then the app uses the guided install.
 */
object ExtensionStores {
    const val CHROME_WEB_STORE_ID = ""
    const val EDGE_ADDONS_ID = ""
    const val FIREFOX_AMO_SLUG = ""

    fun storePage(b: InstalledBrowser): String? = when {
        b.family == BrowserFamily.FIREFOX && FIREFOX_AMO_SLUG.isNotEmpty() -> "https://addons.mozilla.org/firefox/addon/$FIREFOX_AMO_SLUG/"
        b.id == "edge" && EDGE_ADDONS_ID.isNotEmpty() -> "https://microsoftedge.microsoft.com/addons/detail/$EDGE_ADDONS_ID"
        b.family == BrowserFamily.CHROMIUM && CHROME_WEB_STORE_ID.isNotEmpty() -> "https://chromewebstore.google.com/detail/$CHROME_WEB_STORE_ID"
        else -> null
    }

    fun storeId(b: InstalledBrowser): String? = when {
        b.id == "edge" && EDGE_ADDONS_ID.isNotEmpty() -> EDGE_ADDONS_ID
        b.family == BrowserFamily.CHROMIUM && CHROME_WEB_STORE_ID.isNotEmpty() -> CHROME_WEB_STORE_ID
        else -> null
    }
}

object BrowserSetup {
    private val pf = System.getenv("ProgramFiles") ?: "C:\\Program Files"
    private val pf86 = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"
    private val local = System.getenv("LOCALAPPDATA") ?: ""

    private data class Known(
        val id: String, val name: String, val appPath: String, val candidates: List<String>,
        val family: BrowserFamily, val page: String, val key: String?, val update: String?,
    )

    private const val CHROME_UPDATE = "https://clients2.google.com/service/update2/crx"
    private val known = listOf(
        Known("chrome", "Google Chrome", "chrome.exe", listOf("$pf\\Google\\Chrome\\Application\\chrome.exe", "$pf86\\Google\\Chrome\\Application\\chrome.exe", "$local\\Google\\Chrome\\Application\\chrome.exe"),
            BrowserFamily.CHROMIUM, "chrome://extensions", "Software\\Google\\Chrome\\Extensions", CHROME_UPDATE),
        Known("edge", "Microsoft Edge", "msedge.exe", listOf("$pf86\\Microsoft\\Edge\\Application\\msedge.exe", "$pf\\Microsoft\\Edge\\Application\\msedge.exe"),
            BrowserFamily.CHROMIUM, "edge://extensions", "Software\\Microsoft\\Edge\\Extensions", "https://edge.microsoft.com/extensionwebstorebase/v1/crx"),
        Known("brave", "Brave", "brave.exe", listOf("$pf\\BraveSoftware\\Brave-Browser\\Application\\brave.exe", "$local\\BraveSoftware\\Brave-Browser\\Application\\brave.exe"),
            BrowserFamily.CHROMIUM, "brave://extensions", "Software\\BraveSoftware\\Brave-Browser\\Extensions", CHROME_UPDATE),
        Known("opera", "Opera", "opera.exe", listOf("$local\\Programs\\Opera\\opera.exe", "$local\\Programs\\Opera\\launcher.exe"),
            BrowserFamily.CHROMIUM, "opera://extensions", null, null),
        Known("vivaldi", "Vivaldi", "vivaldi.exe", listOf("$local\\Vivaldi\\Application\\vivaldi.exe", "$pf\\Vivaldi\\Application\\vivaldi.exe"),
            BrowserFamily.CHROMIUM, "vivaldi://extensions", "Software\\Vivaldi\\Extensions", CHROME_UPDATE),
        Known("firefox", "Mozilla Firefox", "firefox.exe", listOf("$pf\\Mozilla Firefox\\firefox.exe", "$pf86\\Mozilla Firefox\\firefox.exe"),
            BrowserFamily.FIREFOX, "about:debugging#/runtime/this-firefox", null, null),
    )

    /** Path registered under "App Paths" (where Windows itself looks programs up). */
    private fun appPath(exe: String): String? {
        for (hive in listOf("HKCU", "HKLM")) {
            val out = runCatching {
                val p = ProcessBuilder("reg", "query", "$hive\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\$exe", "/ve").redirectErrorStream(true).start()
                p.inputStream.bufferedReader().readText().also { p.waitFor() }
            }.getOrNull() ?: continue
            val line = out.lines().firstOrNull { it.contains("REG_SZ") } ?: continue
            val path = line.substringAfter("REG_SZ").trim().trim('"')
            if (File(path).isFile) return path
        }
        return null
    }

    fun detect(): List<InstalledBrowser> = known.mapNotNull { k ->
        val exe = (if (Platform.isWindows) appPath(k.appPath) else null) ?: k.candidates.firstOrNull { File(it).isFile } ?: return@mapNotNull null
        InstalledBrowser(k.id, k.name, exe, k.family, k.page, k.key, k.update)
    }

    /** Extension builds that ship with the app (or sit in the repo when running from Gradle). */
    fun packagedExtension(family: BrowserFamily): File? {
        val sub = if (family == BrowserFamily.FIREFOX) "firefox" else "chromium"
        val roots = buildList {
            System.getProperty("compose.application.resources.dir")?.let { add(File(it, "extension")) }
            System.getProperty("jpackage.app-path")?.let { add(File(it).parentFile.resolve("app/resources/extension")) }
            add(File("extension/dist")); add(File("../extension/dist"))
        }
        return roots.map { File(it, sub) }.firstOrNull { File(it, "manifest.json").isFile }
    }

    /**
     * Copies the extension to a fixed place in the user's profile, so it keeps working when the app
     * is updated or moved (Chromium remembers unpacked extensions by folder).
     */
    fun stableCopy(family: BrowserFamily): File? {
        val src = packagedExtension(family) ?: return null
        val dst = File(AppDirs.data, "browser-extension/" + if (family == BrowserFamily.FIREFOX) "firefox" else "chromium")
        dst.deleteRecursively()
        src.copyRecursively(dst, overwrite = true)
        return dst
    }

    /** Store-hosted install: registers the extension so the browser offers to turn it on. */
    fun registerFromStore(b: InstalledBrowser): Boolean {
        val id = ExtensionStores.storeId(b) ?: return false
        val key = b.externalKey ?: return false
        val url = b.storeUpdateUrl ?: return false
        return runCatching {
            ProcessBuilder("reg", "add", "HKCU\\$key\\$id", "/v", "update_url", "/t", "REG_SZ", "/d", url, "/f").start().waitFor() == 0
        }.getOrDefault(false)
    }

    fun open(b: InstalledBrowser, url: String) {
        runCatching { ProcessBuilder(b.exe, url).start() }
    }

    /** Which browser a request came from, by its User-Agent. */
    fun familyOf(userAgent: String?, origin: String?): String? = when {
        origin?.startsWith("moz-extension://") == true || userAgent?.contains("Firefox/") == true -> "firefox"
        userAgent == null -> null
        userAgent.contains("Edg/") -> "edge"
        userAgent.contains("OPR/") -> "opera"
        userAgent.contains("Vivaldi") -> "vivaldi"
        userAgent.contains("Chrome/") -> "chrome" // Chrome and Brave send the same
        else -> null
    }
}
