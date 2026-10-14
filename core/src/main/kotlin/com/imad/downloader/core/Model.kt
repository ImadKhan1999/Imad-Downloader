package com.imad.downloader.core

import kotlinx.serialization.Serializable

/** Lifecycle of a download. Mirrors the states IDM / 1DM show in their lists. */
@Serializable
enum class DownloadStatus {
    /** Wants to run; waiting for a free slot in the engine. */
    QUEUED,
    /** Probing the server / opening connections. */
    CONNECTING,
    DOWNLOADING,
    /** Joining HLS pieces or moving the finished file into place. */
    ASSEMBLING,
    PAUSED,
    COMPLETED,
    FAILED,
    ;

    val isActive: Boolean get() = this == CONNECTING || this == DOWNLOADING || this == ASSEMBLING
    val canStart: Boolean get() = this == PAUSED || this == FAILED || this == QUEUED
}

@Serializable
enum class DownloadKind { HTTP, HLS, DASH;
    val isStream: Boolean get() = this != HTTP
}

/** One byte range of a multi-connection download. [end] is inclusive; -1 means "until EOF". */
@Serializable
data class SegmentState(
    val start: Long,
    val end: Long,
    val downloaded: Long,
) {
    val length: Long get() = if (end < 0) -1 else end - start + 1
    val isDone: Boolean get() = end >= 0 && downloaded >= length
}

/** Immutable snapshot of a download, safe to hand to any UI. */
@Serializable
data class DownloadItem(
    val id: String,
    val url: String,
    val fileName: String,
    val saveDir: String,
    val category: Category = Category.OTHER,
    val kind: DownloadKind = DownloadKind.HTTP,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val resumable: Boolean = false,
    val segments: List<SegmentState> = emptyList(),
    val maxConnections: Int = 8,
    /** Bytes per second, 0 = unlimited. */
    val speedLimit: Long = 0,
    val headers: Map<String, String> = emptyMap(),
    val referer: String? = null,
    val description: String = "",
    val queueId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastTryAt: Long = 0,
    val completedAt: Long = 0,
    val error: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val mimeType: String? = null,
    val expectedChecksum: String? = null,
    /** True when the user picked the name, so the server's Content-Disposition must not replace it. */
    val nameLocked: Boolean = false,
    /** Streams: wanted video height (0 = best). */
    val streamHeight: Int = 0,
    /** HLS only: pieces finished / total pieces. */
    val piecesDone: Int = 0,
    val piecesTotal: Int = 0,
    // Live values: not meaningful after a restart, but harmless to persist.
    val speedBps: Long = 0,
    val etaSeconds: Long = -1,
    val activeConnections: Int = 0,
) {
    val filePath: String get() = java.io.File(saveDir, fileName).path
    val progress: Float
        get() = when {
            status == DownloadStatus.COMPLETED -> 1f
            totalBytes > 0 -> (downloadedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
            piecesTotal > 0 -> piecesDone.toFloat() / piecesTotal
            else -> 0f
        }
}

/** What a caller (UI, browser extension, clipboard) asks the engine to fetch. */
@Serializable
data class DownloadRequest(
    val url: String,
    val fileName: String? = null,
    val saveDir: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val referer: String? = null,
    val cookies: String? = null,
    val userAgent: String? = null,
    val description: String = "",
    val queueId: String? = null,
    val maxConnections: Int? = null,
    val speedLimit: Long = 0,
    val expectedChecksum: String? = null,
    val kind: DownloadKind? = null,
    /** Streams: wanted video height (0 = best). */
    val streamHeight: Int = 0,
    /** Known size from a previous probe, skips nothing but helps the UI. */
    val sizeHint: Long = -1,
)

/** A named download queue with an optional daily schedule (IDM "Scheduler"). */
@Serializable
data class DownloadQueue(
    val id: String,
    val name: String,
    val maxConcurrent: Int = 1,
    val schedule: QueueSchedule? = null,
    val running: Boolean = false,
)

@Serializable
data class QueueSchedule(
    /** Minutes after midnight, local time. */
    val startMinute: Int,
    /** Minutes after midnight; null = run until the queue is empty. */
    val stopMinute: Int? = null,
    /** ISO days 1 (Mon) .. 7 (Sun); empty = every day. */
    val days: Set<Int> = emptySet(),
    val enabled: Boolean = true,
)

/** Everything the engine persists between runs. */
@Serializable
data class EngineState(
    val downloads: List<DownloadItem> = emptyList(),
    val queues: List<DownloadQueue> = listOf(DownloadQueue(MAIN_QUEUE_ID, "Main queue", maxConcurrent = 3)),
) {
    companion object {
        const val MAIN_QUEUE_ID = "main"
    }
}

/** Runtime knobs. UIs build this from their settings screens. */
data class EngineConfig(
    val defaultSaveDir: String,
    /** Put files into per-category sub folders (Video, Music, ...) like IDM does. */
    val categorySubfolders: Boolean = true,
    val maxConcurrentDownloads: Int = 3,
    val defaultConnections: Int = 8,
    /** Global speed limit in bytes per second, 0 = unlimited. */
    val globalSpeedLimit: Long = 0,
    val maxRetries: Int = 8,
    val retryDelayMillis: Long = 1_000,
    /** Segments smaller than this are never split further. */
    val minSplitBytes: Long = 256 * 1024,
    val userAgent: String = DEFAULT_USER_AGENT,
    val bufferSize: Int = 64 * 1024,
    val progressIntervalMillis: Long = 500,
    val saveIntervalMillis: Long = 2_000,
    val duplicateAction: DuplicateAction = DuplicateAction.RENAME,
    val proxy: ProxyConfig? = null,
    val connectTimeoutMillis: Long = 20_000,
    val readTimeoutMillis: Long = 30_000,
) {
    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/141.0.0.0 Safari/537.36"
    }
}

@Serializable
data class ProxyConfig(
    val type: ProxyType = ProxyType.HTTP,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
)

@Serializable
enum class ProxyType { HTTP, SOCKS }

enum class DuplicateAction { RENAME, OVERWRITE }

sealed interface EngineEvent {
    val item: DownloadItem
    data class Added(override val item: DownloadItem) : EngineEvent
    data class Started(override val item: DownloadItem) : EngineEvent
    data class Completed(override val item: DownloadItem) : EngineEvent
    data class Failed(override val item: DownloadItem) : EngineEvent
    data class Removed(override val item: DownloadItem) : EngineEvent
}
