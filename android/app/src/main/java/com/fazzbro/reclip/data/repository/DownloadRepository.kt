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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.regex.Pattern

class DownloadRepository(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: DownloadRepository? = null

        fun getInstance(context: Context): DownloadRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloadRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val cookieManager = CookieManager(context)
    private val prefs: SharedPreferences = context.getSharedPreferences("reclip_history", Context.MODE_PRIVATE)

    private val _activeDownloads = MutableStateFlow<Map<String, DownloadItem>>(emptyMap())
    val activeDownloads: StateFlow<Map<String, DownloadItem>> = _activeDownloads.asStateFlow()

    private val _downloadEvents = MutableSharedFlow<DownloadItem>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val downloadEvents: SharedFlow<DownloadItem> = _downloadEvents.asSharedFlow()

    private val _downloadHistory = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloadHistory: StateFlow<List<DownloadItem>> = _downloadHistory.asStateFlow()

    private val activeJobProcesses = mutableMapOf<String, String>()

    init {
        loadHistory()
    }

    fun normalizeUrl(rawUrl: String): String {
        val url = rawUrl.trim()

        // YouTube short links
        val shortMatch = Pattern.compile("youtu\\.be/([a-zA-Z0-9_-]{11})").matcher(url)
        if (shortMatch.find()) return "https://www.youtube.com/watch?v=${shortMatch.group(1)}"

        // YouTube watch
        val watchMatch = Pattern.compile("youtube\\.com/watch\\?v=([a-zA-Z0-9_-]{11})").matcher(url)
        if (watchMatch.find()) return "https://www.youtube.com/watch?v=${watchMatch.group(1)}"

        // YouTube shorts
        val shortsMatch = Pattern.compile("youtube\\.com/shorts/([a-zA-Z0-9_-]{11})").matcher(url)
        if (shortsMatch.find()) return "https://www.youtube.com/watch?v=${shortsMatch.group(1)}"

        // Instagram: clean query parameters (e.g. ?igsh=... &utm_source=...)
        val igReelMatch = Pattern.compile("(https?://(?:www\\.)?instagram\\.com/(?:reel|reels|p|tv)/[a-zA-Z0-9_-]+)").matcher(url)
        if (igReelMatch.find()) {
            return "${igReelMatch.group(1)}/"
        }

        // TikTok: clean query parameters
        val tiktokMatch = Pattern.compile("(https?://(?:www\\.|vm\\.|vt\\.)?tiktok\\.com/(?:@[a-zA-Z0-9_.-]+/video/\\d+|[a-zA-Z0-9]+))").matcher(url)
        if (tiktokMatch.find()) {
            return tiktokMatch.group(1)
        }

        // Twitter/X: clean query parameters
        val xMatch = Pattern.compile("(https?://(?:twitter|x)\\.com/[a-zA-Z0-9_]+/status/\\d+)").matcher(url)
        if (xMatch.find()) {
            return xMatch.group(1)
        }

        return url
    }

    suspend fun fetchMediaInfo(rawUrl: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        val url = normalizeUrl(rawUrl)
        if (url.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Please enter a valid URL"))
        }

        try {
            val request = YoutubeDLRequest(url)
            request.addOption("--dump-json")
            request.addOption("--no-playlist")
            request.addOption("--geo-bypass")
            request.addOption("--no-check-certificates")
            request.addOption("--socket-timeout", "20")

            val cookiePath = cookieManager.getCookiePath()
            if (cookiePath != null) {
                request.addOption("--cookies", cookiePath)
            }

            val response = YoutubeDL.getInstance().execute(request)
            val rawOutput = response.out ?: ""

            var jsonRoot: JSONObject? = null
            for (line in rawOutput.lines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                    try {
                        jsonRoot = JSONObject(trimmed)
                        break
                    } catch (ignored: Exception) {
                    }
                }
            }
            if (jsonRoot == null) {
                jsonRoot = JSONObject(rawOutput)
            }

            val title = jsonRoot.optString("title").ifBlank { "Media_${System.currentTimeMillis()}" }
            val thumbnail = jsonRoot.optString("thumbnail", "")
            val duration = jsonRoot.optLong("duration", 0L)
            val uploader = jsonRoot.optString("uploader", "")
            val formatsArray = jsonRoot.optJSONArray("formats") ?: JSONArray()

            val bestByHeight = mutableMapOf<Int, JSONObject>()
            var bestAudioSize = 0L

            // 1. Identify default audio track size (format 140 / 128kbps AAC or best known audio stream)
            for (i in 0 until formatsArray.length()) {
                val f = formatsArray.optJSONObject(i) ?: continue
                val vcodec = f.optString("vcodec", "")
                val acodec = f.optString("acodec", "")
                val isAudioOnly = (vcodec == "none" || vcodec.isEmpty()) && (acodec != "none" && acodec.isNotEmpty())
                if (isAudioOnly) {
                    val fid = f.optString("format_id", "")
                    val lang = f.optString("language", "").lowercase()
                    var aSize = f.optLong("filesize", 0L)
                    if (aSize <= 0L) aSize = f.optLong("filesize_approx", 0L)

                    if (aSize > 0L) {
                        if (fid.contains("140") || lang == "en" || lang == "original" || bestAudioSize == 0L) {
                            bestAudioSize = aSize
                        }
                    }
                }
            }
            if (bestAudioSize <= 0L && duration > 0L) {
                // Realistic 128 kbps standard audio track size
                bestAudioSize = ((128.0 * 1000.0 / 8.0) * duration).toLong()
            }

            // 2. Select realistic video stream matching yt-dlp's actual download preference
            for (i in 0 until formatsArray.length()) {
                val f = formatsArray.optJSONObject(i) ?: continue
                val height = f.optInt("height", 0)
                val width = f.optInt("width", 0)
                val vcodec = f.optString("vcodec", "").lowercase()

                val effectiveHeight = if (width in 144..height && height > width) width else height
                if (effectiveHeight >= 144 && vcodec != "none" && vcodec.isNotEmpty()) {
                    val existing = bestByHeight[effectiveHeight]
                    if (existing == null) {
                        bestByHeight[effectiveHeight] = f
                    } else {
                        // Scoring: heavily prioritize formats with real known filesize over manifest-only null sizes
                        fun score(item: JSONObject): Long {
                            val iExt = item.optString("ext", "").lowercase()
                            val iVc = item.optString("vcodec", "").lowercase()
                            val hasSize = item.optLong("filesize", 0L) > 0L || item.optLong("filesize_approx", 0L) > 0L
                            var s = 0L
                            if (hasSize) s += 10000L
                            if (iExt == "mp4") s += 1000L
                            if (iVc.contains("av01")) s += 500L
                            else if (iVc.contains("vp9")) s += 300L
                            else if (iVc.contains("avc") || iVc.contains("h264")) s += 100L
                            return s
                        }
                        if (score(f) > score(existing)) {
                            bestByHeight[effectiveHeight] = f
                        }
                    }
                }
            }

            val formatsList = mutableListOf<FormatOption>()
            val sortedHeights = bestByHeight.keys.sortedDescending()

            for (h in sortedHeights) {
                val f = bestByHeight[h]!!
                var vSize = f.optLong("filesize", 0L)
                if (vSize <= 0L) vSize = f.optLong("filesize_approx", 0L)
                if (vSize <= 0L) {
                    var vbr = f.optDouble("vbr", 0.0)
                    if (vbr <= 0.0) vbr = f.optDouble("tbr", 0.0)
                    val maxRealisticVbr = when {
                        h >= 2160 -> 12000.0 // 4K ~12 Mbps
                        h >= 1440 -> 6000.0  // 2K ~6 Mbps
                        h >= 1080 -> 2200.0  // 1080p ~2.2 Mbps
                        h >= 720  -> 1200.0  // 720p ~1.2 Mbps
                        h >= 480  -> 600.0   // 480p ~600 kbps
                        else      -> 350.0   // 360p ~350 kbps
                    }
                    val effectiveVbr = if (vbr > 0.0) minOf(vbr, maxRealisticVbr) else maxRealisticVbr
                    if (duration > 0) {
                        vSize = ((effectiveVbr * 1000.0 / 8.0) * duration).toLong()
                    }
                }

                val totalSize = if (vSize > 0L) {
                    if (bestAudioSize > 0L) vSize + bestAudioSize else vSize
                } else 0L

                val sizeStr = if (totalSize > 0L) FormatUtils.formatBytes(totalSize) else ""

                val label = when (h) {
                    2160 -> "4K • 2160p"
                    1440 -> "2K • 1440p"
                    else -> "${h}p"
                }

                formatsList.add(
                    FormatOption(
                        id = h.toString(),
                        label = label,
                        height = h,
                        ext = "mp4",
                        filesize = totalSize,
                        filesizeFormatted = sizeStr
                    )
                )
            }

            // Fallback for single format platforms (Instagram, TikTok, Twitter) or when formats has no height
            if (formatsList.isEmpty()) {
                val rootHeight = jsonRoot.optInt("height", 0)
                val rootWidth = jsonRoot.optInt("width", 0)
                val effH = if (rootWidth in 144..rootHeight && rootHeight > rootWidth) rootWidth else rootHeight
                if (effH > 0) {
                    formatsList.add(
                        FormatOption(
                            id = effH.toString(),
                            label = "${effH}p",
                            height = effH,
                            ext = "mp4",
                            filesizeFormatted = ""
                        )
                    )
                } else {
                    val defaultHeights = listOf(1080, 720, 480, 360)
                    for (dh in defaultHeights) {
                        formatsList.add(
                            FormatOption(
                                id = dh.toString(),
                                label = "${dh}p",
                                height = dh,
                                ext = "mp4",
                                filesizeFormatted = ""
                            )
                        )
                    }
                }
            }

            // Extract multi-language audio tracks
            val audioLanguages = mutableListOf<AudioLanguage>()
            val seenLangs = mutableSetOf<String>()
            val langNames = mapOf(
                "en" to "English (Original)",
                "en-us" to "English (US)",
                "en-gb" to "English (UK)",
                "es" to "Spanish (Español)",
                "es-419" to "Spanish (Latin America)",
                "hi" to "Hindi (हिन्दी)",
                "fr" to "French (Français)",
                "de" to "German (Deutsch)",
                "ja" to "Japanese (日本語)",
                "ko" to "Korean (한국어)",
                "pt" to "Portuguese (Português)",
                "pt-br" to "Portuguese (Brasil)",
                "ru" to "Russian (Русский)",
                "ar" to "Arabic (العربية)",
                "id" to "Indonesian (Bahasa)",
                "vi" to "Vietnamese (Tiếng Việt)",
                "th" to "Thai (ไทย)",
                "tr" to "Turkish (Türkçe)",
                "it" to "Italian (Italiano)",
                "pl" to "Polish (Polski)",
                "bn" to "Bangla (বাংলা)",
                "mr" to "Marathi (मराठी)",
                "ta" to "Tamil (தமிழ்)",
                "te" to "Telugu (తెలుగు)",
                "ml" to "Malayalam (മലയാളം)",
                "pa" to "Punjabi (ਪੰਜਾਬੀ)",
                "ur" to "Urdu (اردو)"
            )

            for (i in 0 until formatsArray.length()) {
                val f = formatsArray.optJSONObject(i) ?: continue
                val vcodec = f.optString("vcodec", "")
                val isAudioOnly = (vcodec == "none" || vcodec.isEmpty())
                val rawLang = f.optString("language", "").lowercase().trim()
                val formatNote = f.optString("format_note", "").lowercase().trim()

                if (isAudioOnly && rawLang.isNotEmpty()) {
                    val baseLang = rawLang.split("-")[0]
                    if (baseLang.isNotEmpty() && !seenLangs.contains(baseLang)) {
                        seenLangs.add(baseLang)
                        val name = langNames[rawLang] ?: langNames[baseLang] ?: "${baseLang.uppercase()} ($formatNote)"
                        audioLanguages.add(AudioLanguage(code = baseLang, name = name, langId = rawLang))
                    }
                }
            }

            val approxFilesize = if (jsonRoot.optLong("filesize", 0L) > 0L) {
                jsonRoot.optLong("filesize")
            } else if (jsonRoot.optLong("filesize_approx", 0L) > 0L) {
                jsonRoot.optLong("filesize_approx")
            } else {
                formatsList.firstOrNull { it.height == 1080 }?.filesize
                    ?: formatsList.firstOrNull { it.height == 720 }?.filesize
                    ?: formatsList.firstOrNull()?.filesize ?: 0L
            }

            val mediaInfo = MediaInfo(
                url = url,
                title = title,
                thumbnail = thumbnail,
                duration = duration,
                durationFormatted = FormatUtils.formatDuration(duration),
                uploader = uploader,
                formats = formatsList,
                audioLanguages = audioLanguages,
                approxFilesizeFormatted = if (approxFilesize > 0L) FormatUtils.formatBytes(approxFilesize) else "",
                audioFilesizeFormatted = if (bestAudioSize > 0L) FormatUtils.formatBytes(bestAudioSize) else ""
            )

            Result.success(mediaInfo)
        } catch (e: Exception) {
            Log.e("DownloadRepo", "Error fetching info", e)
            val msg = e.message?.let {
                if (it.contains("Sign in to confirm", ignoreCase = true) || it.contains("bot", ignoreCase = true)) {
                    "YouTube requested bot verification. Please import a cookies.txt file in the Cookies tab to bypass."
                } else if (it.contains("login", ignoreCase = true)) {
                    "Login required by platform. Import cookies.txt in Settings to access this media."
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
        customJobId: String = "",
        onProgressUpdate: ((DownloadItem) -> Unit)? = null
    ): Result<DownloadItem> = withContext(Dispatchers.IO) {
        val normalizedUrl = normalizeUrl(url)
        val jobId = if (customJobId.isNotBlank()) customJobId else UUID.randomUUID().toString().take(8)
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
            request.addOption("--socket-timeout", "25")

            val cookiePath = cookieManager.getCookiePath()
            if (cookiePath != null) {
                request.addOption("--cookies", cookiePath)
            }

            if (formatType == MediaFormatType.AUDIO) {
                if (audioLang.isNotBlank() && audioLang != "default") {
                    request.addOption("-f", "bestaudio[language^=$audioLang][ext=m4a]/bestaudio[language^=$audioLang]/bestaudio")
                }
                request.addOption("-x")
                request.addOption("--audio-format", "mp3")
                request.addOption("--audio-quality", "0")
            } else {
                val height = qualityOption?.height ?: 1080
                if (audioLang == "all") {
                    request.addOption("--audio-multistreams")
                    request.addOption("-f", "bestvideo[height<=$height]+bestaudio/best[height<=$height]/best")
                    request.addOption("--merge-output-format", "mkv")
                } else if (audioLang.isNotBlank() && audioLang != "default") {
                    request.addOption(
                        "-f",
                        "bestvideo[height<=$height][ext=mp4]+bestaudio[language^=$audioLang][ext=m4a]/bestvideo[height<=$height]+bestaudio[language^=$audioLang]/bestvideo[height<=$height]+bestaudio/best[height<=$height]/best"
                    )
                    request.addOption("--merge-output-format", "mp4")
                } else {
                    request.addOption(
                        "-f",
                        "bestvideo[height<=$height][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=$height]+bestaudio/best[height<=$height]/best"
                    )
                    request.addOption("--merge-output-format", "mp4")
                }
            }

            activeJobProcesses[jobId] = jobId

            var lastProgressTime = 0L

            YoutubeDL.getInstance().execute(request, jobId) { progressFloat, etaSeconds, line ->
                val now = System.currentTimeMillis()
                val progressSafe = progressFloat.coerceIn(0f, 100f)

                // Throttle updates to at most once per 400ms to prevent UI freezing and notification shedding
                if (now - lastProgressTime >= 400L || progressSafe >= 99f) {
                    lastProgressTime = now
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
            _downloadEvents.tryEmit(completedItem)
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
            _downloadEvents.tryEmit(errorItem)
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
            _downloadEvents.tryEmit(cancelledItem)
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
        _downloadEvents.tryEmit(item)
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
