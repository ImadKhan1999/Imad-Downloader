package com.imad.downloader.desktop

import com.imad.downloader.core.DuplicateAction
import com.imad.downloader.core.EngineConfig
import com.imad.downloader.core.ProxyConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Where the Windows app keeps its data: %APPDATA%\iDownloader. */
object AppDirs {
    val data: File by lazy {
        // IMAD_DATA_DIR lets end-to-end tests run against a throwaway profile.
        System.getenv("IMAD_DATA_DIR")?.let { return@lazy File(it).apply { mkdirs() } }
        val base = System.getenv("APPDATA")?.let(::File) ?: File(System.getProperty("user.home"), ".config")
        val dir = File(base, "iDownloader")
        // Moves settings, the download list and tools over from the names used before the renames.
        val old = listOf("Hyper Downloader", "Imad Downloader").map { File(base, it) }.firstOrNull { it.isDirectory }
        if (!dir.exists() && old != null) runCatching { old.renameTo(dir) }
        dir.apply { mkdirs() }
    }
    val settingsFile get() = File(data, "settings.json")
    val downloadsFile get() = File(data, "downloads.json")
    val logFile get() = File(data, "idownloader.log")

    val defaultDownloads: String by lazy {
        File(System.getProperty("user.home"), "Downloads/iDownloader").path
    }
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class CompletionAction(val label: String) {
    NOTHING("Do nothing"),
    EXIT("Exit iDownloader"),
    SLEEP("Put computer to sleep"),
    SHUTDOWN("Shut down computer"),
}

@Serializable
data class DesktopSettings(
    val saveDir: String = AppDirs.defaultDownloads,
    val categorySubfolders: Boolean = true,
    val maxConcurrent: Int = 3,
    val connections: Int = 8,
    val speedLimitEnabled: Boolean = false,
    val speedLimitKBps: Long = 512,
    val maxRetries: Int = 8,
    val userAgent: String = EngineConfig.DEFAULT_USER_AGENT,
    val proxyEnabled: Boolean = false,
    val proxy: ProxyConfig? = null,
    val overwriteExisting: Boolean = false,
    val monitorClipboard: Boolean = true,
    val clipboardExtensions: String = DEFAULT_TYPES,
    val showProgressWindow: Boolean = true,
    val showCompleteDialog: Boolean = true,
    val showStartDialog: Boolean = true,
    val notifications: Boolean = true,
    val soundOnComplete: Boolean = true,
    val minimizeToTray: Boolean = true,
    val launchOnStartup: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val browserIntegration: Boolean = true,
    val autoUpdate: Boolean = true,
    /** Browser id (chrome, edge, firefox, ...) -> last time its extension talked to the app. */
    val connectedBrowsers: Map<String, Long> = emptyMap(),
    val browserSetupShown: Boolean = false,
    /** "Don't show this again" was ticked on the ImadLabs Telegram popup. */
    val telegramPromptOff: Boolean = false,
    val lastUpdateCheck: Long = 0,
    /** Browser whose cookies yt-dlp may read ("firefox", "edge", "chrome", "brave"), empty = none. */
    val videoCookiesBrowser: String = "",
    val confirmDelete: Boolean = true,
    val queueCompletionAction: CompletionAction = CompletionAction.NOTHING,
    val columnWidths: Map<String, Float> = emptyMap(),
    val windowWidth: Int = 1180,
    val windowHeight: Int = 700,
) {
    val clipboardTypeSet: Set<String>
        get() = clipboardExtensions.split(' ', ',', ';').map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }.toSet()

    fun toEngineConfig() = EngineConfig(
        defaultSaveDir = saveDir,
        categorySubfolders = categorySubfolders,
        maxConcurrentDownloads = maxConcurrent.coerceIn(1, 20),
        defaultConnections = connections.coerceIn(1, 32),
        globalSpeedLimit = if (speedLimitEnabled) speedLimitKBps.coerceAtLeast(1) * 1024 else 0,
        maxRetries = maxRetries.coerceIn(0, 100),
        userAgent = userAgent.ifBlank { EngineConfig.DEFAULT_USER_AGENT },
        duplicateAction = if (overwriteExisting) DuplicateAction.OVERWRITE else DuplicateAction.RENAME,
        proxy = if (proxyEnabled) proxy else null,
    )

    companion object {
        /** File types that trigger IDM-style capture (same defaults as IDM). */
        const val DEFAULT_TYPES =
            "3GP 7Z AAC ACE AIF APK ARJ ASF AVI BIN BZ2 EXE GZ GZIP IMG ISO LZH M4A M4V MKV MOV MP3 MP4 MPA MPE MPEG MPG " +
                "MSI MSU OGG OGV PDF PLJ PPS PPT QT R0* R1* RA RAR RM RMVB SEA SIT SITX TAR TIF TIFF WAV WEBM WMA WMV Z ZIP"
    }
}

class SettingsStore(private val file: File = AppDirs.settingsFile) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun load(): DesktopSettings =
        runCatching { json.decodeFromString(DesktopSettings.serializer(), file.readText()) }.getOrElse { DesktopSettings() }

    fun save(s: DesktopSettings) {
        file.parentFile.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(DesktopSettings.serializer(), s))
        java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}
