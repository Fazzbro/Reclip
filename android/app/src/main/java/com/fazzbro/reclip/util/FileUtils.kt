package com.fazzbro.reclip.util

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File

object FileUtils {

    fun getReclipDownloadDir(context: Context): File {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val reclipDir = File(publicDownloads, "ReClip")
        if (!reclipDir.exists()) {
            reclipDir.mkdirs()
        }
        return if (reclipDir.exists() && reclipDir.canWrite()) {
            reclipDir
        } else {
            // Fallback to app external files dir
            val appDl = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val fallback = File(appDl, "ReClip")
            fallback.mkdirs()
            fallback
        }
    }

    fun scanMediaFile(context: Context, file: File, mimeType: String? = null, onScanCompleted: ((Uri?) -> Unit)? = null) {
        if (!file.exists()) return
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            if (mimeType != null) arrayOf(mimeType) else null
        ) { _, uri ->
            onScanCompleted?.invoke(uri)
        }
    }

    fun getContentUriForFile(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    fun getMimeType(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".m4a") -> "audio/mp4"
            lower.endsWith(".opus") -> "audio/opus"
            lower.endsWith(".ogg") -> "audio/ogg"
            lower.endsWith(".wav") -> "audio/wav"
            else -> "*/*"
        }
    }
}
