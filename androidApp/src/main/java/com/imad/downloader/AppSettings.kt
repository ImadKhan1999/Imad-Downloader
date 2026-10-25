package com.imad.downloader

import android.os.Environment
import com.imad.downloader.core.DuplicateAction
import com.imad.downloader.core.EngineConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class SearchEngine(val label: String, val template: String, val home: String) {
    GOOGLE("Google", "https://www.google.com/search?q=%s", "https://www.google.com"),
    BING("Bing", "https://www.bing.com/search?q=%s", "https://www.bing.com"),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s", "https://duckduckgo.com"),
    YAHOO("Yahoo", "https://search.yahoo.com/search?p=%s", "https://search.yahoo.com"),
}

@Serializable
data class AppSettings(
    val saveDir: String = defaultSaveDir(),
    val categoryFolders: Boolean = true,
    val maxConcurrent: Int = 3,
    val connections: Int = 8,
    val speedLimitEnabled: Boolean = false,
    val speedLimitKBps: Long = 1024,
    val wifiOnly: Boolean = false,
    val maxRetries: Int = 10,
    val askBeforeDownload: Boolean = true,
    val overwrite: Boolean = false,
    val clipboardMonitor: Boolean = true,
    val notifyOnComplete: Boolean = true,
    val vibrateOnComplete: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val searchEngine: SearchEngine = SearchEngine.GOOGLE,
    val adBlock: Boolean = true,
    val desktopModeDefault: Boolean = false,
    val javascript: Boolean = true,
    val saveHistory: Boolean = true,
    val blockPopups: Boolean = true,
    val userAgent: String = "",
    val autoUpdate: Boolean = true,
    val lastUpdateCheck: Long = 0,
    /** "Don't show this again" was ticked on the ImadLabs Telegram popup. */
    val telegramPromptOff: Boolean = false,
) {
    fun toEngineConfig() = EngineConfig(
        defaultSaveDir = saveDir,
        categorySubfolders = categoryFolders,
        maxConcurrentDownloads = maxConcurrent.coerceIn(1, 10),
        defaultConnections = connections.coerceIn(1, 32),
        globalSpeedLimit = if (speedLimitEnabled) speedLimitKBps.coerceAtLeast(1) * 1024 else 0,
        maxRetries = maxRetries,
        userAgent = userAgent.ifBlank { MOBILE_UA },
        duplicateAction = if (overwrite) DuplicateAction.OVERWRITE else DuplicateAction.RENAME,
    )

    companion object {
        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"
        const val DESKTOP_UA = EngineConfig.DEFAULT_USER_AGENT

        fun defaultSaveDir(): String =
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "iDownloader").path
    }
}

class SettingsRepository(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    val value: AppSettings get() = _settings.value

    private fun load(): AppSettings =
        runCatching { json.decodeFromString(AppSettings.serializer(), file.readText()) }.getOrElse { AppSettings() }

    fun update(f: (AppSettings) -> AppSettings) {
        val next = f(_settings.value)
        _settings.value = next
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(AppSettings.serializer(), next))
        }
    }
}
