package com.imad.downloader.core

import kotlinx.serialization.Serializable

/** File categories, matching IDM's left-hand tree plus 1DM's Images tab. */
@Serializable
enum class Category(val label: String, val folder: String, val extensions: Set<String>) {
    COMPRESSED(
        "Compressed", "Compressed",
        setOf("zip", "rar", "7z", "gz", "tgz", "tar", "bz2", "xz", "lz", "lzma", "zst", "cab", "iso", "img", "arj", "z"),
    ),
    DOCUMENTS(
        "Documents", "Documents",
        setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "txt", "rtf", "csv", "epub", "mobi", "djvu", "md"),
    ),
    MUSIC(
        "Music", "Music",
        setOf("mp3", "wav", "flac", "aac", "m4a", "ogg", "oga", "opus", "wma", "aif", "aiff", "mid", "midi", "amr"),
    ),
    PROGRAMS(
        "Programs", "Programs",
        setOf("exe", "msi", "msix", "appx", "apk", "xapk", "aab", "dmg", "pkg", "deb", "rpm", "appimage", "bat", "jar", "run"),
    ),
    VIDEO(
        "Video", "Video",
        setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "mpg", "mpeg", "3gp", "ts", "m3u8", "vob", "ogv", "rm", "rmvb"),
    ),
    IMAGES(
        "Images", "Images",
        setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "tif", "tiff", "ico", "heic", "avif", "psd"),
    ),
    OTHER("Other", "General", emptySet()),
    ;

    companion object {
        fun fromFileName(name: String): Category {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) return OTHER
            return entries.firstOrNull { ext in it.extensions } ?: OTHER
        }

        fun fromMime(mime: String?): Category? {
            val m = mime?.substringBefore(';')?.trim()?.lowercase() ?: return null
            return when {
                m.startsWith("video/") || m == "application/vnd.apple.mpegurl" || m == "application/x-mpegurl" -> VIDEO
                m.startsWith("audio/") -> MUSIC
                m.startsWith("image/") -> IMAGES
                m == "application/pdf" || m.contains("officedocument") || m == "application/msword" || m == "application/epub+zip" -> DOCUMENTS
                m == "application/zip" || m.contains("compressed") || m.contains("rar") || m.contains("7z") || m == "application/gzip" -> COMPRESSED
                m == "application/vnd.android.package-archive" || m.contains("msdownload") || m == "application/x-msi" -> PROGRAMS
                else -> null
            }
        }

        /** Category for a file, preferring the extension and falling back to the MIME type. */
        fun detect(fileName: String, mime: String? = null): Category {
            val byName = fromFileName(fileName)
            return if (byName != OTHER) byName else fromMime(mime) ?: OTHER
        }
    }
}
