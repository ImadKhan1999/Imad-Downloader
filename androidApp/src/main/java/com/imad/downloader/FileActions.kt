package com.imad.downloader

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.imad.downloader.core.DownloadItem
import java.io.File

/** Opening, sharing and copying for finished downloads. */
object FileActions {
    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun mimeFor(item: DownloadItem): String =
        item.mimeType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() && it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(item.fileName.substringAfterLast('.', "").lowercase())
            ?: "*/*"

    fun openIntent(context: Context, item: DownloadItem): Intent {
        val file = File(item.filePath)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, file), mimeFor(item))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun open(context: Context, item: DownloadItem) {
        if (!File(item.filePath).exists()) {
            Toast.makeText(context, "The file was moved or deleted", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            context.startActivity(Intent.createChooser(openIntent(context, item), "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: IllegalArgumentException) {
            Toast.makeText(context, "This folder cannot be shared with other apps", Toast.LENGTH_SHORT).show()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app can open this file type", Toast.LENGTH_SHORT).show()
        }
    }

    fun share(context: Context, item: DownloadItem) {
        val file = File(item.filePath)
        val intent = if (file.exists()) {
            Intent(Intent.ACTION_SEND).setType(mimeFor(item)).putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, item.url)
        }
        context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun copy(context: Context, label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }

    fun clipboardText(context: Context): String? = runCatching {
        context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
    }.getOrNull()
}
