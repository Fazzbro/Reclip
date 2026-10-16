package com.fazzbro.reclip.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.fazzbro.reclip.data.model.AudioLanguage
import com.fazzbro.reclip.data.model.DownloadItem
import com.fazzbro.reclip.data.model.DownloadStatus
import com.fazzbro.reclip.data.model.FormatOption
import com.fazzbro.reclip.data.model.MediaFormatType
import com.fazzbro.reclip.data.model.MediaInfo
import com.fazzbro.reclip.util.CookieManager
import com.fazzbro.reclip.util.FileUtils
import com.fazzbro.reclip.util.FormatUtils
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.regex.Pattern

class DownloadRepository(private val context: Context) {

    private val cookieManager = CookieManager(context)
    private val prefs: SharedPreferences = context.getSharedPreferences("reclip_history", Context.MODE_PRIVATE)

    private val _activeDownloads = MutableStateFlow<Map<String, DownloadItem>>(emptyMap())
    val activeDownloads: StateFlow<Map<String, DownloadItem>> = _activeDownloads.asStateFlow()

    private val _downloadHistory = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloadHistory: StateFlow<List<DownloadItem>> = _downloadHistory.asStateFlow()

    private val activeJobProcesses = mutableMapOf<String, String>()

    init {
        loadHistory()
    }

    fun normalizeUrl(rawUrl: String): String {
        val url = rawUrl.trim()
        val shortMatch = Pattern.compile("youtu\\.be/([a-zA-Z0-9_-]{11})").matcher(url)
        if (shortMatch.find()) return "https://www.youtube.com/watch?v=${shortMatch.group(1)}"

        val watchMatch = Pattern.compile("youtube\\.com/watch\\?v=([a-zA-Z0-9_-]{11})").matcher(url)
        if (watchMatch.find()) return "https://www.youtube.com/watch?v=${watchMatch.group(1)}"

        val shortsMatch = Pattern.compile("youtube\\.com/shorts/([a-zA-Z0-9_-]{11})").matcher(url)
        if (shortsMatch.find()) return "https://www.youtube.com/watch?v=${shortsMatch.group(1)}"

        return url
    }

    suspend fun fetchMediaInfo(rawUrl: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        val url = normalizeUrl(rawUrl)
        if (url.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Please enter a valid URL"))
        }

        try {
            val request = YoutubeDLRequest(url)
            request.addOption("--no-playlist")
            request.addOption("--geo-bypass")
            request.addOption("--socket-timeout", "15")

            val cookiePath = cookieManager.getCookiePath()
            if (cookiePath != null) {
                request.addOption("--cookies", cookiePath)
            }

            val streamInfo: VideoInfo = YoutubeDL.getInstance().getInfo(request)

            val formatsList = mutableListOf<FormatOption>()
            val formats = streamInfo.formats ?: emptyList()

            // Map best video format per height
            val bestByHeight = mutableMapOf<Int, Long>()
            var bestAudioSize = 0L

            for (f in formats) {
                val height = f.height
                val size = if (f.fileSize > 0L) {
                    f.fileSize
                } else if (f.fileSizeApproximate > 0L) {
                    f.fileSizeApproximate
                } else {
                    0L
                }

                if (height >= 144) {
                    val currentBest = bestByHeight[height] ?: 0L
                    if (size > currentBest) {
                        bestByHeight[height] = size
                    }
                }
                if (f.vcodec == "none" || f.vcodec == null) {
                    if (size > bestAudioSize) {
                        bestAudioSize = size
                    }
                }
            }

            val sortedHeights = bestByHeight.keys.sortedDescending()
            for (height in sortedHeights) {
                val vSize = bestByHeight[height] ?: 0L
                val totalEst = if (vSize > 0L && bestAudioSize > 0L) vSize + bestAudioSize else vSize
                formatsList.add(
                    FormatOption(
                        id = height.toString(),
                        label = "${height}p",
                        height = height,
                        ext = "mp4",
                        filesize = totalEst,
                        filesizeFormatted = FormatUtils.formatBytes(totalEst)
                    )
                )
            }

            if (formatsList.isEmpty()) {
                val defaultHeights = listOf(1080, 720, 480, 360)
                for (h in defaultHeights) {
                    formatsList.add(
                        FormatOption(
                            id = h.toString(),
                            label = "${h}p",
                            height = h,
                            ext = "mp4",
                            filesizeFormatted = ""
                        )
                    )
                }
            }

            // Audio track options
            val audioLanguages = mutableListOf<AudioLanguage>()
            val seenLangs = mutableSetOf<String>()
            val langNames = mapOf(
                "en" to "English (Original)",
                "es" to "Spanish (Español)",
                "hi" to "Hindi (हिन्दी)",
                "fr" to "French (Français)",
                "de" to "German (Deutsch)",
                "ja" to "Japanese (日本語)",
                "ko" to "Korean (한국어)",
                "pt" to "Portuguese (Português)",
                "ar" to "Arabic (العربية)",
                "ru" to "Russian (Русский)"
            )

            for (f in formats) {
                val note = (f.formatNote ?: "").lowercase()
                val formatStr = (f.format ?: "").lowercase()
                for ((code, name) in langNames) {
                    if ((note.contains(code) || formatStr.contains(code)) && !seenLangs.contains(code)) {
                        seenLangs.add(code)
                        audioLanguages.add(AudioLanguage(code = code, name = name, langId = code))
                    }
                }
            }

            val approxFilesize = if (streamInfo.fileSize > 0L) {
                streamInfo.fileSize
            } else if (streamInfo.fileSizeApproximate > 0L) {
                streamInfo.fileSizeApproximate
            } else {
                bestByHeight[1080] ?: bestByHeight[720] ?: 0L
            }

            val mediaInfo = MediaInfo(
                url = url,
                title = streamInfo.title ?: "Media_${System.currentTimeMillis()}",
                thumbnail = streamInfo.thumbnail ?: "",
                duration = streamInfo.duration.toLong(),
                durationFormatted = FormatUtils.formatDuration(streamInfo.duration.toLong()),
                uploader = streamInfo.uploader ?: "",
                formats = formatsList,
                audioLanguages = audioLanguages,
                approxFilesizeFormatted = FormatUtils.formatBytes(approxFilesize),
                audioFilesizeFormatted = FormatUtils.formatBytes(bestAudioSize)
            )

            Result.success(mediaInfo)
        } catch (e: Exception) {
            Log.e("DownloadRepo", "Error fetching info", e)
            val msg = e.message?.let {
                if (it.contains("Sign in to confirm", ignoreCase = true) || it.contains("bot", ignoreCase = true)) {
                    "YouTube requested bot verification. Please import a cookies.txt file in the Cookies tab to bypass."
                } else {
                    it
                }
            } ?: "Failed to fetch video details"
            Result.failure(Exception(msg, e))
        }
    }

    suspend fun startDownload(
        url: String,
        title: String,
        thumbnail: String,
        formatType: MediaFormatType,
        qualityOption: FormatOption?,
        audioLang: String = "",
        onProgressUpdate: ((DownloadItem) -> Unit)? = null
    ): Result<DownloadItem> = withContext(Dispatchers.IO) {
        val normalizedUrl = normalizeUrl(url)
        val jobId = UUID.randomUUID().toString().take(8)
        val dlDir = FileUtils.getReclipDownloadDir(context)

        val cleanTitle = FormatUtils.sanitizeFilename(title)
        val ext = if (formatType == MediaFormatType.AUDIO) "mp3" else "mp4"

        var item = DownloadItem(
            id = jobId,
            url = normalizedUrl,
            title = cleanTitle,
            thumbnail = thumbnail,
            formatType = formatType,
            qualityLabel = qualityOption?.label ?: if (formatType == MediaFormatType.AUDIO) "MP3 Audio" else "1080p",
            formatId = qualityOption?.id ?: "",
            audioLang = audioLang,
            status = DownloadStatus.DOWNLOADING,
            progress = 0f,
            progressString = "Starting download..."
        )

        updateActiveDownload(item)
        onProgressUpdate?.invoke(item)

        try {
            val request = YoutubeDLRequest(normalizedUrl)
            val outputTemplate = "${dlDir.absolutePath}/${jobId}___%(title).100B.%(ext)s"
            request.addOption("-o", outputTemplate)
            request.addOption("--newline")
            request.addOption("--no-playlist")
            request.addOption("--geo-bypass")
            request.addOption("--no-check-certificates")
            request.addOption("--socket-timeout", "20")

            val cookiePath = cookieManager.getCookiePath()
            if (cookiePath != null) {
                request.addOption("--cookies", cookiePath)
            }

            if (formatType == MediaFormatType.AUDIO) {
                request.addOption("-x")
                request.addOption("--audio-format", "mp3")
                request.addOption("--audio-quality", "0")
            } else {
                val height = qualityOption?.height ?: 1080
                if (audioLang.isNotBlank() && audioLang != "default") {
                    request.addOption(
                        "-f",
                        "bestvideo[height<=$height]+bestaudio[language^=$audioLang]/bestvideo[height<=$height]+bestaudio/best[height<=$height]/best"
                    )
                } else {
                    request.addOption(
                        "-f",
                        "bestvideo[height<=$height]+bestaudio/best[height<=$height]/best"
                    )
                }
                request.addOption("--merge-output-format", "mp4")
            }

            activeJobProcesses[jobId] = jobId

            YoutubeDL.getInstance().execute(request, jobId) { progressFloat, etaSeconds, line ->
                val lineStr = line ?: ""
                var speedStr = ""
                var sizeStr = ""

                // Extract speed and size if present in line
                val speedRegex = Pattern.compile("at\\s+([\\w\\.\\/\\s]+)\\s+ETA").matcher(lineStr)
                if (speedRegex.find()) {
                    speedStr = speedRegex.group(1)?.trim() ?: ""
                }
                val sizeRegex = Pattern.compile("of\\s+([~\\w\\.\\s\\+]+)\\s+at").matcher(lineStr)
                if (sizeRegex.find()) {
                    sizeStr = sizeRegex.group(1)?.trim() ?: ""
                }

                val progressSafe = progressFloat.coerceIn(0f, 100f)
                val statusStr = if (progressSafe >= 99f && (lineStr.contains("ExtractAudio") || lineStr.contains("Merger"))) {
                    "Processing & Converting..."
                } else {
                    String.format("%.1f%%", progressSafe) + if (speedStr.isNotBlank()) " • $speedStr" else ""
                }

                item = item.copy(
                    progress = progressSafe,
                    progressString = statusStr,
                    speed = speedStr,
                    eta = FormatUtils.formatEta(etaSeconds),
                    filesizeFormatted = sizeStr.ifEmpty { item.filesizeFormatted }
                )
                updateActiveDownload(item)
                onProgressUpdate?.invoke(item)
            }

            activeJobProcesses.remove(jobId)

            // Locate downloaded file
            val candidateFiles = dlDir.listFiles { file ->
                file.name.startsWith(jobId) &&
                        !file.name.endsWith(".part") &&
                        !file.name.endsWith(".ytdl") &&
                        !file.name.endsWith(".temp")
            } ?: emptyArray()

            val downloadedFile = candidateFiles.firstOrNull()
                ?: throw IllegalStateException("Download finished but media file could not be located on storage")

            val fileExt = downloadedFile.extension.ifBlank { ext }
            var targetFileName = "$cleanTitle.$fileExt"
            var targetFile = File(dlDir, targetFileName)

            var counter = 1
            while (targetFile.exists() && targetFile.absolutePath != downloadedFile.absolutePath) {
                targetFileName = "$cleanTitle ($counter).$fileExt"
                targetFile = File(dlDir, targetFileName)
                counter++
            }

            if (downloadedFile.absolutePath != targetFile.absolutePath) {
                downloadedFile.renameTo(targetFile)
            }

            val finalFile = if (targetFile.exists()) targetFile else downloadedFile
            val finalSize = finalFile.length()

            // Trigger MediaScanner so Gallery / Music players see it
            FileUtils.scanMediaFile(context, finalFile)

            val completedItem = item.copy(
                status = DownloadStatus.COMPLETED,
                progress = 100f,
                progressString = "Completed",
                filePath = finalFile.absolutePath,
                fileName = finalFile.name,
                totalBytes = finalSize,
                filesizeFormatted = FormatUtils.formatBytes(finalSize),
                completedAt = System.currentTimeMillis()
            )

            removeActiveDownload(jobId)
            addToHistory(completedItem)
            onProgressUpdate?.invoke(completedItem)

            Result.success(completedItem)
        } catch (e: Exception) {
            activeJobProcesses.remove(jobId)
            Log.e("DownloadRepo", "Download failed for $jobId", e)
            val errorItem = item.copy(
                status = DownloadStatus.ERROR,
                errorMessage = e.message ?: "Download failed",
                progressString = "Failed"
            )
            removeActiveDownload(jobId)
            addToHistory(errorItem)
            onProgressUpdate?.invoke(errorItem)
            Result.failure(e)
        }
    }

    fun cancelDownload(jobId: String) {
        try {
            YoutubeDL.getInstance().destroyProcessById(jobId)
        } catch (e: Exception) {
            Log.w("DownloadRepo", "Could not destroy process $jobId", e)
        }
        val current = _activeDownloads.value[jobId]
        if (current != null) {
            val cancelledItem = current.copy(
                status = DownloadStatus.CANCELLED,
                progressString = "Cancelled"
            )
            removeActiveDownload(jobId)
            addToHistory(cancelledItem)
        }
    }

    suspend fun updateYtDlp(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE)
            Result.success(status?.name ?: "Updated successfully")
        } catch (e: Exception) {
            Log.e("DownloadRepo", "Failed to update yt-dlp", e)
            Result.failure(e)
        }
    }

    private fun updateActiveDownload(item: DownloadItem) {
        val map = _activeDownloads.value.toMutableMap()
        map[item.id] = item
        _activeDownloads.value = map
    }

    private fun removeActiveDownload(id: String) {
        val map = _activeDownloads.value.toMutableMap()
        map.remove(id)
        _activeDownloads.value = map
    }

    private fun addToHistory(item: DownloadItem) {
        val current = _downloadHistory.value.toMutableList()
        current.removeAll { it.id == item.id }
        current.add(0, item)
        _downloadHistory.value = current
        saveHistory(current)
    }

    fun clearHistory() {
        _downloadHistory.value = emptyList()
        prefs.edit().remove("history_json").apply()
    }

    fun deleteHistoryItem(item: DownloadItem, deleteFile: Boolean = false) {
        if (deleteFile && item.filePath.isNotBlank()) {
            try {
                val f = File(item.filePath)
                if (f.exists()) f.delete()
            } catch (e: Exception) {
                Log.w("DownloadRepo", "Failed to delete file ${item.filePath}", e)
            }
        }
        val current = _downloadHistory.value.toMutableList()
        current.removeAll { it.id == item.id }
        _downloadHistory.value = current
        saveHistory(current)
    }

    private fun saveHistory(items: List<DownloadItem>) {
        try {
            val array = JSONArray()
            items.take(100).forEach { item ->
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("url", item.url)
                    put("title", item.title)
                    put("thumbnail", item.thumbnail)
                    put("formatType", item.formatType.name)
                    put("qualityLabel", item.qualityLabel)
                    put("status", item.status.name)
                    put("filesizeFormatted", item.filesizeFormatted)
                    put("filePath", item.filePath)
                    put("fileName", item.fileName)
                    put("completedAt", item.completedAt)
                    put("totalBytes", item.totalBytes)
                }
                array.put(obj)
            }
            prefs.edit().putString("history_json", array.toString()).apply()
        } catch (e: Exception) {
            Log.e("DownloadRepo", "Error saving history", e)
        }
    }

    private fun loadHistory() {
        try {
            val jsonStr = prefs.getString("history_json", null) ?: return
            val array = JSONArray(jsonStr)
            val list = mutableListOf<DownloadItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    DownloadItem(
                        id = obj.optString("id"),
                        url = obj.optString("url"),
                        title = obj.optString("title"),
                        thumbnail = obj.optString("thumbnail"),
                        formatType = MediaFormatType.valueOf(obj.optString("formatType", "VIDEO")),
                        qualityLabel = obj.optString("qualityLabel", "1080p"),
                        status = DownloadStatus.valueOf(obj.optString("status", "COMPLETED")),
                        filesizeFormatted = obj.optString("filesizeFormatted"),
                        filePath = obj.optString("filePath"),
                        fileName = obj.optString("fileName"),
                        completedAt = obj.optLong("completedAt", 0L),
                        totalBytes = obj.optLong("totalBytes", 0L),
                        progress = 100f,
                        progressString = "Completed"
                    )
                )
            }
            _downloadHistory.value = list
        } catch (e: Exception) {
            Log.e("DownloadRepo", "Error loading history", e)
        }
    }
}
