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
import com.fazzbro.reclip.util.RedditResolver
import com.fazzbro.reclip.util.RedditMediaResult
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.fazzbro.reclip.ReClipApp
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
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

data class DownloadConfig(
    val url: String,
    val title: String,
    val thumbnail: String,
    val formatType: MediaFormatType,
    val qualityOption: FormatOption?,
    val audioLang: String
)

data class CachedStreamMeta(
    val timestamp: Long,
    val title: String,
    val durationSeconds: Long,
    val videoUrlsByHeight: Map<Int, String>,
    val audioUrlsByLang: Map<String, String>
)

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

    private val activeJobProcesses = ConcurrentHashMap<String, String>()
    private val activeCoroutineJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val downloadConfigs = ConcurrentHashMap<String, DownloadConfig>()
    private val pausedJobs = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val cancelledJobs = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val streamMetaCache = ConcurrentHashMap<String, CachedStreamMeta>()
    private val activeStreamableProcesses = ConcurrentHashMap<String, Process>()

    init {
        loadHistory()
    }

    fun isStreamableDownloadEnabled(): Boolean = prefs.getBoolean("streamable_download", true)

    fun setStreamableDownloadEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("streamable_download", enabled).apply()
    }

    private suspend fun ensureEnginesReady() {
        if (!ReClipApp.initDeferred.isCompleted) {
            try {
                kotlinx.coroutines.withTimeout(15000) {
                    ReClipApp.initDeferred.await()
                }
            } catch (e: Exception) {
                Log.w("DownloadRepo", "Engine readiness check timed out or failed: ${e.message}")
            }
        }
    }

    private fun applyCommonOptions(request: YoutubeDLRequest) {
        request.addOption("--geo-bypass")
        request.addOption("--no-check-certificates")
        request.addOption("--socket-timeout", "20")
        request.addOption("--retries", "3")
        request.addOption("--fragment-retries", "5")
        request.addOption("--no-cache-dir")

        val cookiePath = cookieManager.getCookiePath()
        if (cookiePath != null) {
            request.addOption("--cookies", cookiePath)
        }
    }

    fun normalizeUrl(rawUrl: String): String {
        val url = rawUrl.trim()

        // YouTube: match v parameter anywhere in query string or /v/ or embed/ or youtu.be
        val ytParamMatch = Pattern.compile("(?:youtube\\.com/(?:[^\\s/]+/.+/|(?:v|e(?:mbed)?)/|.*[?&]v=)|youtu\\.be/)([\\w-]{11})").matcher(url)
        if (ytParamMatch.find()) {
            return "https://www.youtube.com/watch?v=${ytParamMatch.group(1)}"
        }

        // YouTube shorts
        val shortsMatch = Pattern.compile("youtube\\.com/shorts/([\\w-]{11})").matcher(url)
        if (shortsMatch.find()) {
            return "https://www.youtube.com/watch?v=${shortsMatch.group(1)}"
        }

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

        // Reddit: clean query parameters (e.g. ?utm_source=... &utm_medium=...)
        val redditMatch = Pattern.compile("(https?://(?:www\\.|v\\.)?reddit\\.com/(?:r|user)/[a-zA-Z0-9_]+/(?:comments|s)/[a-zA-Z0-9_]+(?:/[^/?#]+)?)").matcher(url)
        if (redditMatch.find()) {
            return redditMatch.group(1)
        }
        val reddItMatch = Pattern.compile("(https?://redd\\.it/[a-zA-Z0-9_]+)").matcher(url)
        if (reddItMatch.find()) {
            return reddItMatch.group(1)
        }

        return url
    }

    suspend fun fetchMediaInfo(rawUrl: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        ensureEnginesReady()
        var url = normalizeUrl(rawUrl)
        if (url.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Please enter a valid URL"))
        }

        var redditMeta: RedditMediaResult? = null
        if (RedditResolver.isRedditUrl(url)) {
            redditMeta = RedditResolver.resolve(url)
            if (redditMeta != null && redditMeta.streamUrl.isNotBlank()) {
                url = redditMeta.streamUrl
            }
        }

        try {
            val request = YoutubeDLRequest(url)
            request.addOption("--dump-json")
            request.addOption("--no-playlist")
            applyCommonOptions(request)

            val response = YoutubeDL.getInstance().execute(request)
            val rawOutput = response.out ?: ""

            var jsonRoot: JSONObject? = null
            for (line in rawOutput.lines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                    try {
                        val parsed = JSONObject(trimmed)
                        if (parsed.has("title") || parsed.has("formats") || parsed.has("id")) {
                            jsonRoot = parsed
                            break
                        }
                    } catch (ignored: Exception) {
                    }
                }
            }
            if (jsonRoot == null) {
                val start = rawOutput.indexOf('{')
                val end = rawOutput.lastIndexOf('}')
                if (start != -1 && end != -1 && end > start) {
                    try {
                        jsonRoot = JSONObject(rawOutput.substring(start, end + 1))
                    } catch (ignored: Exception) {
                    }
                }
            }
            if (jsonRoot == null) {
                jsonRoot = JSONObject(rawOutput)
            }
            val rawTitle = jsonRoot.optString("title")
            val title = if (redditMeta != null && redditMeta.title.isNotBlank()) {
                redditMeta.title
            } else if (rawTitle.isBlank() || rawTitle.equals("HLSPlaylist", ignoreCase = true) || rawTitle.equals("DASHPlaylist", ignoreCase = true)) {
                redditMeta?.title ?: "Media_${System.currentTimeMillis()}"
            } else {
                rawTitle
            }

            val rawThumb = jsonRoot.optString("thumbnail", "")
            val thumbnail = if (redditMeta != null && redditMeta.thumbnail.isNotBlank()) {
                redditMeta.thumbnail
            } else {
                rawThumb
            }

            val duration = jsonRoot.optLong("duration", 0L)
            val uploader = jsonRoot.optString("uploader", "").ifBlank {
                if (redditMeta != null) "Reddit" else ""
            }
            val formatsArray = jsonRoot.optJSONArray("formats") ?: JSONArray()

            val bestByHeight = mutableMapOf<Int, JSONObject>()
            var bestAudioSize = 0L

            // 1. Identify default audio track size
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
                bestAudioSize = ((128.0 * 1000.0 / 8.0) * duration).toLong()
            }

            // 2. Select realistic video stream
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
                        fun score(item: JSONObject): Long {
                            val iExt = item.optString("ext", "").lowercase()
                            val iVc = item.optString("vcodec", "").lowercase()
                            val hasSize = item.optLong("filesize", 0L) > 0L || item.optLong("filesize_approx", 0L) > 0L
                            var s = 0L
                            if (hasSize) s += 10000L
                            if (iExt == "mp4") s += 1000L
                            if (iVc.contains("avc") || iVc.contains("h264")) s += 800L
                            else if (iVc.contains("av01")) s += 500L
                            else if (iVc.contains("vp9")) s += 300L
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
                        h >= 2160 -> 12000.0
                        h >= 1440 -> 6000.0
                        h >= 1080 -> 2200.0
                        h >= 720  -> 1200.0
                        h >= 480  -> 600.0
                        else      -> 350.0
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
                val lang = f.optString("language", "").lowercase()
                val fid = f.optString("format_id", "")
                val formatNote = f.optString("format_note", "").lowercase()
                val vcodec = f.optString("vcodec", "")
                val acodec = f.optString("acodec", "")

                val isAudioTrack = (vcodec == "none" || vcodec.isEmpty()) && (acodec != "none" && acodec.isNotEmpty())
                if (isAudioTrack && lang.isNotBlank() && !seenLangs.contains(lang)) {
                    seenLangs.add(lang)
                    val baseCode = lang.split("-")[0]
                    val displayName = langNames[lang] ?: langNames[baseCode] ?: lang.replaceFirstChar { it.uppercase() }
                    audioLanguages.add(
                        AudioLanguage(
                            code = lang,
                            name = displayName,
                            langId = fid
                        )
                    )
                }
            }

            val approxFilesize = if (jsonRoot.has("filesize_approx") && jsonRoot.optLong("filesize_approx") > 0L) {
                jsonRoot.optLong("filesize_approx")
            } else {
                formatsList.firstOrNull { it.height == 1080 }?.filesize
                    ?: formatsList.firstOrNull { it.height == 720 }?.filesize
                    ?: formatsList.firstOrNull()?.filesize ?: 0L
            }

            val videoUrls = mutableMapOf<Int, String>()
            for ((h, fObj) in bestByHeight) {
                val directUrl = fObj.optString("url", "")
                if (directUrl.isNotBlank()) {
                    videoUrls[h] = directUrl
                }
            }

            val audioUrls = mutableMapOf<String, String>()
            var bestM4aAudioUrl = ""
            var fallbackAudioUrl = ""

            for (i in 0 until formatsArray.length()) {
                val f = formatsArray.optJSONObject(i) ?: continue
                val fUrl = f.optString("url", "")
                if (fUrl.isBlank()) continue
                val vcodec = f.optString("vcodec", "")
                val acodec = f.optString("acodec", "")
                val isAudioOnly = (vcodec == "none" || vcodec.isEmpty()) && (acodec != "none" && acodec.isNotEmpty())
                if (isAudioOnly) {
                    val fid = f.optString("format_id", "")
                    val lang = f.optString("language", "").lowercase()
                    val ext = f.optString("ext", "").lowercase()
                    if (lang.isNotBlank() && (!audioUrls.containsKey(lang) || ext == "m4a")) {
                        audioUrls[lang] = fUrl
                    }
                    if (ext == "m4a" || fid.contains("140")) {
                        if (bestM4aAudioUrl.isBlank() || fid.contains("140")) {
                            bestM4aAudioUrl = fUrl
                        }
                    }
                    if (fallbackAudioUrl.isBlank()) {
                        fallbackAudioUrl = fUrl
                    }
                }
            }
            val defaultAudio = if (bestM4aAudioUrl.isNotBlank()) bestM4aAudioUrl else fallbackAudioUrl
            if (defaultAudio.isNotBlank()) {
                audioUrls["default"] = defaultAudio
            }

            val metaObj = CachedStreamMeta(
                timestamp = System.currentTimeMillis(),
                title = title,
                durationSeconds = duration,
                videoUrlsByHeight = videoUrls,
                audioUrlsByLang = audioUrls
            )
            streamMetaCache[url] = metaObj
            streamMetaCache[normalizeUrl(rawUrl)] = metaObj

            val mediaInfo = MediaInfo(
                url = if (redditMeta != null) redditMeta.streamUrl else url,
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
            val rawMsg = e.message ?: e.cause?.message ?: ""
            val userFriendlyMsg = when {
                rawMsg.contains("Sign in to confirm", ignoreCase = true) || rawMsg.contains("bot", ignoreCase = true) ->
                    "YouTube requested bot verification. Please import a cookies.txt file in Settings to bypass."
                rawMsg.contains("login", ignoreCase = true) || rawMsg.contains("private", ignoreCase = true) ->
                    "Media is private or requires login. Import cookies.txt in Settings to access."
                rawMsg.contains("timed out", ignoreCase = true) || rawMsg.contains("timeout", ignoreCase = true) ->
                    "Network connection timed out. Please check your internet connection and try again."
                rawMsg.contains("not initialized", ignoreCase = true) ->
                    "Media engine is initializing. Please wait a few seconds and try again."
                rawMsg.isNotBlank() ->
                    rawMsg.lines().firstOrNull { it.contains("ERROR:") }?.substringAfter("ERROR:")?.trim()
                        ?: rawMsg.lines().firstOrNull { it.isNotBlank() }?.take(160)
                        ?: rawMsg.take(160)
                else ->
                    "Unable to extract media from this URL. Please verify the link is public and active."
            }
            Result.failure(Exception(userFriendlyMsg, e))
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
        ensureEnginesReady()
        var normalizedUrl = normalizeUrl(url)
        if (RedditResolver.isRedditUrl(normalizedUrl) &&
            !normalizedUrl.contains("HLSPlaylist") &&
            !normalizedUrl.contains("DASHPlaylist") &&
            !normalizedUrl.endsWith(".mp4")
        ) {
            val resolved = RedditResolver.resolve(normalizedUrl)
            if (resolved != null && resolved.streamUrl.isNotBlank()) {
                normalizedUrl = resolved.streamUrl
            }
        }
        val jobId = if (customJobId.isNotBlank()) customJobId else UUID.randomUUID().toString().take(8)
        val dlDir = FileUtils.getReclipDownloadDir(context)

        coroutineContext[kotlinx.coroutines.Job]?.let { job ->
            activeCoroutineJobs[jobId] = job
        }

        // Save config for pause/resume support
        if (cancelledJobs.contains(jobId)) {
            activeCoroutineJobs.remove(jobId)
            removeActiveDownload(jobId)
            return@withContext Result.failure(Exception("Download cancelled"))
        }

        downloadConfigs[jobId] = DownloadConfig(
            url = url,
            title = title,
            thumbnail = thumbnail,
            formatType = formatType,
            qualityOption = qualityOption,
            audioLang = audioLang
        )
        pausedJobs.remove(jobId)

        val cleanTitle = FormatUtils.sanitizeFilename(title)
        val ext = if (formatType == MediaFormatType.AUDIO) "mp3" else "mp4"

        // Retain any existing progress if resuming
        val existingItem = _activeDownloads.value[jobId]
        var item = existingItem?.copy(
            status = DownloadStatus.DOWNLOADING,
            progressString = if (existingItem.progress > 0f) "Resuming download..." else "Initializing download..."
        ) ?: DownloadItem(
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
            progressString = "Initializing download..."
        )

        updateActiveDownload(item)
        onProgressUpdate?.invoke(item)

        if (cancelledJobs.contains(jobId)) {
            removeActiveDownload(jobId)
            return@withContext Result.failure(Exception("Download cancelled"))
        }
        if (pausedJobs.contains(jobId)) {
            val paused = item.copy(status = DownloadStatus.PAUSED, progressString = "Paused")
            updateActiveDownload(paused)
            _downloadEvents.tryEmit(paused)
            return@withContext Result.success(paused)
        }

        if (isStreamableDownloadEnabled() && formatType == MediaFormatType.VIDEO) {
            val streamResult = runStreamableMultiplexedDownload(
                jobId = jobId,
                url = normalizedUrl,
                title = cleanTitle,
                qualityOption = qualityOption,
                audioLang = audioLang,
                dlDir = dlDir,
                initialItem = item,
                onProgressUpdate = onProgressUpdate
            )
            if (streamResult != null) {
                return@withContext Result.success(streamResult)
            }
        }

        try {
            val request = YoutubeDLRequest(normalizedUrl)
            val outputTemplate = "${dlDir.absolutePath}/${jobId}___%(title).100B.%(ext)s"
            request.addOption("-o", outputTemplate)
            request.addOption("--newline")
            request.addOption("--continue")
            request.addOption("--no-playlist")
            applyCommonOptions(request)



            if (formatType == MediaFormatType.AUDIO) {
                if (audioLang.isNotBlank() && audioLang != "default" && audioLang != "all") {
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
            var currentStreamCount = 0
            var lastParsedPercent = item.progress

            val percentRegex = Pattern.compile("(?:\\[download\\]\\s+)?([0-9]+(?:\\.[0-9]+)?)\\s*%")
            val speedRegex = Pattern.compile("at\\s+([0-9]+(?:\\.[0-9]+)?\\s*[kKmMgG]?[iI]?[bB]/s)")
            val sizeRegex = Pattern.compile("of\\s+(~?\\s*[0-9]+(?:\\.[0-9]+)?\\s*[kKmMgG]?[iI]?[bB])")
            val etaRegex = Pattern.compile("ETA\\s+([0-9:]+)")
            val fragRegex = Pattern.compile("\\(frag\\s+([0-9]+)/([0-9]+)\\)")

            YoutubeDL.getInstance().execute(request, jobId) { progressFloat, etaSeconds, line ->
                // Check if user paused or cancelled while running
                if (cancelledJobs.contains(jobId)) {
                    try { YoutubeDL.getInstance().destroyProcessById(jobId) } catch (ignored: Exception) {}
                    return@execute
                }
                if (pausedJobs.contains(jobId)) {
                    try { YoutubeDL.getInstance().destroyProcessById(jobId) } catch (ignored: Exception) {}
                    return@execute
                }

                val now = System.currentTimeMillis()
                val lineStr = line ?: ""

                if (lineStr.contains("[download] Destination:")) {
                    currentStreamCount++
                }

                // 1. Direct percentage extraction from stdout line
                var extractedPercent: Float? = null
                val pMatch = percentRegex.matcher(lineStr)
                if (pMatch.find()) {
                    extractedPercent = pMatch.group(1)?.toFloatOrNull()
                }

                // 2. Fragment ratio extraction for HLS / DASH streams
                if (extractedPercent == null) {
                    val fMatch = fragRegex.matcher(lineStr)
                    if (fMatch.find()) {
                        val curFrag = fMatch.group(1)?.toFloatOrNull() ?: 0f
                        val totFrag = fMatch.group(2)?.toFloatOrNull() ?: 1f
                        if (totFrag > 0f) {
                            extractedPercent = (curFrag / totFrag) * 100f
                        }
                    }
                }

                // 3. Fallback to progressFloat from youtubedl-android
                if (extractedPercent == null && progressFloat > 0f) {
                    extractedPercent = progressFloat
                }

                if (extractedPercent != null) {
                    lastParsedPercent = maxOf(lastParsedPercent, extractedPercent.coerceIn(0f, 100f))
                }

                // Extract speed
                var speedStr = ""
                val sMatch = speedRegex.matcher(lineStr)
                if (sMatch.find()) {
                    speedStr = sMatch.group(1)?.trim() ?: ""
                }

                // Extract size
                var sizeStr = ""
                val szMatch = sizeRegex.matcher(lineStr)
                if (szMatch.find()) {
                    sizeStr = szMatch.group(1)?.trim() ?: ""
                }

                // Extract ETA
                var etaStr = ""
                val eMatch = etaRegex.matcher(lineStr)
                if (eMatch.find()) {
                    val candidate = eMatch.group(1)?.trim() ?: ""
                    if (!candidate.equals("Unknown", ignoreCase = true)) {
                        etaStr = candidate
                    }
                }
                if (etaStr.isBlank() && etaSeconds > 0) {
                    etaStr = FormatUtils.formatEta(etaSeconds)
                }

                // Multi-stream smoothing (e.g. Video 85% + Audio 13% + Merger 2%)
                val isSeparateStreams = formatType == MediaFormatType.VIDEO && currentStreamCount >= 2
                val finalProgress = when {
                    lineStr.contains("[Merger]") || lineStr.contains("[ExtractAudio]") || lineStr.contains("[Fixup") -> 99f
                    isSeparateStreams -> {
                        (85f + (lastParsedPercent * 0.13f)).coerceIn(85f, 98.5f)
                    }
                    formatType == MediaFormatType.VIDEO && !lineStr.contains(".m3u8") -> {
                        (lastParsedPercent * 0.85f).coerceIn(0f, 85f)
                    }
                    else -> lastParsedPercent
                }.coerceIn(0f, 100f)

                // Human-readable status readout
                val statusStr = when {
                    lineStr.contains("[Merger]") -> "Merging audio & video..."
                    lineStr.contains("[ExtractAudio]") -> "Converting to MP3..."
                    lineStr.contains("[Fixup") -> "Finalizing media..."
                    finalProgress >= 99f -> "Processing..."
                    else -> {
                        val pctStr = String.format(Locale.US, "%.1f%%", finalProgress)
                        if (speedStr.isNotBlank()) "$pctStr • $speedStr" else pctStr
                    }
                }

                // Throttle UI updates to at most once every 250ms, or upon major milestones
                if (now - lastProgressTime >= 250L || finalProgress >= 99f || (finalProgress - item.progress >= 2f)) {
                    lastProgressTime = now
                    item = item.copy(
                        status = DownloadStatus.DOWNLOADING,
                        progress = finalProgress,
                        progressString = statusStr,
                        speed = speedStr,
                        eta = etaStr,
                        filesizeFormatted = sizeStr.ifEmpty { item.filesizeFormatted }
                    )
                    updateActiveDownload(item)
                    onProgressUpdate?.invoke(item)
                }
            }

            activeJobProcesses.remove(jobId)

            // Locate downloaded file on disk
            val candidateFiles = dlDir.listFiles { file ->
                file.name.startsWith(jobId) &&
                        !file.name.endsWith(".part") &&
                        !file.name.endsWith(".ytdl") &&
                        !file.name.endsWith(".temp")
            } ?: emptyArray()

            val downloadedFile = candidateFiles.maxByOrNull { it.length() }
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

            // Trigger MediaScanner so Gallery and Players see it immediately
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

            downloadConfigs.remove(jobId)
            removeActiveDownload(jobId)
            addToHistory(completedItem)
            _downloadEvents.tryEmit(completedItem)
            onProgressUpdate?.invoke(completedItem)

            Result.success(completedItem)
        } catch (e: Exception) {
            activeJobProcesses.remove(jobId)

            // Check if process termination was intentional (PAUSE)
            if (pausedJobs.remove(jobId) || _activeDownloads.value[jobId]?.status == DownloadStatus.PAUSED) {
                val pausedItem = (_activeDownloads.value[jobId] ?: item).copy(
                    status = DownloadStatus.PAUSED,
                    progressString = "Paused",
                    speed = "",
                    eta = ""
                )
                updateActiveDownload(pausedItem)
                _downloadEvents.tryEmit(pausedItem)
                onProgressUpdate?.invoke(pausedItem)
                return@withContext Result.success(pausedItem)
            }

            // Check if process termination was intentional (CANCEL)
            if (cancelledJobs.remove(jobId)) {
                val dlDirFiles = dlDir.listFiles { file -> file.name.startsWith(jobId) }
                dlDirFiles?.forEach { f -> try { f.delete() } catch (ignored: Exception) {} }
                removeActiveDownload(jobId)
                return@withContext Result.failure(Exception("Download cancelled"))
            }

            Log.e("DownloadRepo", "Download failed for $jobId", e)
            val userMsg = when {
                e.message?.contains("Sign in to confirm", ignoreCase = true) == true ||
                e.message?.contains("bot", ignoreCase = true) == true ->
                    "YouTube requested bot verification. Please import a cookies.txt file in Settings to bypass."
                e.message?.contains("login", ignoreCase = true) == true ||
                e.message?.contains("private", ignoreCase = true) == true ->
                    "Media is private or requires login. Import cookies.txt in Settings to access."
                e.message?.contains("timed out", ignoreCase = true) == true ||
                e.message?.contains("timeout", ignoreCase = true) == true ->
                    "Network connection timed out. Please check your internet connection and try again."
                e.message?.contains("format is not available", ignoreCase = true) == true ->
                    "Selected quality format is not available. Please try selecting a different resolution."
                !e.message.isNullOrBlank() -> {
                    val raw = e.message!!
                    raw.lines().firstOrNull { it.contains("ERROR:") }?.substringAfter("ERROR:")?.trim()
                        ?: raw.lines().firstOrNull { it.isNotBlank() }?.take(160)
                        ?: raw.take(160)
                }
                else -> "Download encountered an error. Retrying or selecting another resolution may help."
            }

            val errorItem = item.copy(
                status = DownloadStatus.ERROR,
                errorMessage = userMsg,
                progressString = userMsg
            )
            downloadConfigs.remove(jobId)
            removeActiveDownload(jobId)
            addToHistory(errorItem)
            _downloadEvents.tryEmit(errorItem)
            onProgressUpdate?.invoke(errorItem)
            Result.failure(Exception(userMsg, e))
        } finally {
            activeCoroutineJobs.remove(jobId)
        }
    }

    suspend fun resumeDownload(
        jobId: String,
        onProgressUpdate: ((DownloadItem) -> Unit)? = null
    ): Result<DownloadItem> {
        val config = downloadConfigs[jobId]
            ?: return Result.failure(IllegalStateException("No saved download session found for job $jobId"))
        pausedJobs.remove(jobId)
        cancelledJobs.remove(jobId)
        return startDownload(
            url = config.url,
            title = config.title,
            thumbnail = config.thumbnail,
            formatType = config.formatType,
            qualityOption = config.qualityOption,
            audioLang = config.audioLang,
            customJobId = jobId,
            onProgressUpdate = onProgressUpdate
        )
    }

    private suspend fun runStreamableMultiplexedDownload(
        jobId: String,
        url: String,
        title: String,
        qualityOption: FormatOption?,
        audioLang: String,
        dlDir: File,
        initialItem: DownloadItem,
        onProgressUpdate: ((DownloadItem) -> Unit)?
    ): DownloadItem? = withContext(Dispatchers.IO) {
        var item = initialItem
        val cleanTitle = FormatUtils.sanitizeFilename(title)
        val targetHeight = qualityOption?.height ?: 1080
        var videoUrl: String? = null
        var audioUrl: String? = null
        var totalDuration: Long = 0L

        // 1. Check cache first
        val cached = streamMetaCache[url] ?: streamMetaCache[normalizeUrl(url)]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < 2 * 3600 * 1000L) {
            videoUrl = cached.videoUrlsByHeight[targetHeight]
                ?: cached.videoUrlsByHeight.entries.filter { it.key <= targetHeight }.maxByOrNull { it.key }?.value
                ?: cached.videoUrlsByHeight.values.firstOrNull()
            audioUrl = if (audioLang.isNotBlank() && audioLang != "default" && audioLang != "all") {
                cached.audioUrlsByLang[audioLang] ?: cached.audioUrlsByLang["default"]
            } else {
                cached.audioUrlsByLang["default"]
            }
            totalDuration = cached.durationSeconds
        }

        // 2. Fallback to direct extraction via yt-dlp -g if not cached
        if (videoUrl.isNullOrBlank()) {
            try {
                val gReq = YoutubeDLRequest(url)
                gReq.addOption("-g")
                gReq.addOption("--print", "%(title)s\t%(duration)s")
                val fmtSpec = if (targetHeight > 0) {
                    "bestvideo[height<=$targetHeight][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=$targetHeight]+bestaudio/best[height<=$targetHeight]/best"
                } else {
                    "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best"
                }
                gReq.addOption("-f", fmtSpec)
                applyCommonOptions(gReq)
                val gResp = YoutubeDL.getInstance().execute(gReq)
                val lines = gResp.out?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                val urlLines = lines.filter { it.startsWith("http://") || it.startsWith("https://") }
                val metaLine = lines.firstOrNull { it.contains("\t") }
                if (metaLine != null) {
                    val metaParts = metaLine.split("\t")
                    if (metaParts.size > 1) {
                        totalDuration = metaParts[1].toDoubleOrNull()?.toLong() ?: 0L
                    }
                }
                if (urlLines.isNotEmpty()) {
                    videoUrl = urlLines[0]
                    if (urlLines.size > 1) {
                        audioUrl = urlLines[1]
                    }
                }
            } catch (e: Exception) {
                Log.w("DownloadRepository", "Direct URL extraction with yt-dlp -g failed: ${e.message}")
            }
        }

        if (videoUrl.isNullOrBlank()) {
            Log.i("DownloadRepository", "Direct stream URLs not available, falling back to standard yt-dlp download")
            return@withContext null
        }

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val ffmpegBin = File(nativeLibDir, "libffmpeg.so")
        val packagesDir = File(context.noBackupFilesDir, "youtubedl-android/packages")
        val ffmpegLibDir = File(packagesDir, "ffmpeg/usr/lib")
        val pythonLibDir = File(packagesDir, "python/usr/lib")
        val quickjsLibDir = File(packagesDir, "quickjs/usr/lib")
        if (!ffmpegBin.exists()) {
            Log.w("DownloadRepository", "FFmpeg binary not found at ${ffmpegBin.absolutePath}")
            return@withContext null
        }

        val targetStreamFile = File(dlDir, "${jobId}___${cleanTitle}.mp4")
        if (targetStreamFile.exists()) targetStreamFile.delete()

        item = item.copy(filePath = targetStreamFile.absolutePath)
        updateActiveDownload(item)
        _downloadEvents.tryEmit(item)
        onProgressUpdate?.invoke(item)

        val ffCmd = mutableListOf<String>()
        ffCmd.add(ffmpegBin.absolutePath)
        ffCmd.add("-y")
        ffCmd.add("-loglevel")
        ffCmd.add("info")

        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // Video input
        ffCmd.add("-tls_verify")
        ffCmd.add("0")
        ffCmd.add("-headers")
        ffCmd.add("User-Agent: $ua\r\n")
        ffCmd.add("-reconnect")
        ffCmd.add("1")
        ffCmd.add("-reconnect_streamed")
        ffCmd.add("1")
        ffCmd.add("-reconnect_delay_max")
        ffCmd.add("5")
        ffCmd.add("-i")
        ffCmd.add(videoUrl)

        // Audio input: simultaneous multiplexing into fragmented MP4 container
        if (!audioUrl.isNullOrBlank() && audioUrl != videoUrl) {
            ffCmd.add("-tls_verify")
            ffCmd.add("0")
            ffCmd.add("-headers")
            ffCmd.add("User-Agent: $ua\r\n")
            ffCmd.add("-reconnect")
            ffCmd.add("1")
            ffCmd.add("-reconnect_streamed")
            ffCmd.add("1")
            ffCmd.add("-reconnect_delay_max")
            ffCmd.add("5")
            ffCmd.add("-i")
            ffCmd.add(audioUrl)

            ffCmd.add("-c:v")
            ffCmd.add("copy")
            ffCmd.add("-c:a")
            val isM4a = audioUrl.contains(".m4a") || audioUrl.contains("audio%2Fmp4") || audioUrl.contains("audio/mp4")
            if (isM4a) {
                ffCmd.add("copy")
            } else {
                ffCmd.add("aac")
            }
        } else {
            ffCmd.add("-c")
            ffCmd.add("copy")
        }

        ffCmd.add("-movflags")
        ffCmd.add("frag_keyframe+empty_moov+default_base_moof+dash")
        ffCmd.add("-progress")
        ffCmd.add("pipe:1")
        ffCmd.add(targetStreamFile.absolutePath)

        val pb = ProcessBuilder(ffCmd)
        val env = pb.environment()
        env["LD_LIBRARY_PATH"] = "${pythonLibDir.absolutePath}:${ffmpegLibDir.absolutePath}:${quickjsLibDir.absolutePath}:$nativeLibDir"
        env["PYTHONHOME"] = File(packagesDir, "python/usr").absolutePath
        env["TMPDIR"] = context.cacheDir.absolutePath
        val certFile = File(packagesDir, "python/usr/etc/tls/cert.pem")
        if (certFile.exists()) {
            env["SSL_CERT_FILE"] = certFile.absolutePath
        }
        env["HOME"] = context.filesDir.absolutePath
        pb.redirectErrorStream(true)

        val proc: Process
        try {
            proc = pb.start()
            activeStreamableProcesses[jobId] = proc
        } catch (e: Exception) {
            Log.w("DownloadRepository", "Failed to start FFmpeg process: ${e.message}")
            targetStreamFile.delete()
            return@withContext null
        }

        var lastProgressEmit = 0L
        var lastBytes = 0L
        var lastTime = System.currentTimeMillis()
        var currentSpeed = ""
        var currentEta = ""
        val errLog = StringBuilder()

        try {
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (cancelledJobs.contains(jobId) || pausedJobs.contains(jobId)) {
                        proc.destroy()
                        break
                    }
                    val trimmed = line.trim()
                    if (trimmed.startsWith("out_time_ms=") || trimmed.startsWith("out_time_us=")) {
                        val parts = trimmed.split("=")
                        if (parts.size == 2) {
                            val us = parts[1].toLongOrNull() ?: 0L
                            val curSec = if (trimmed.startsWith("out_time_ms=")) us / 1000.0 else us / 1_000_000.0
                            val pct = if (totalDuration > 0) {
                                ((curSec / totalDuration.toDouble()) * 100.0).coerceIn(0.0, 99.9).toFloat()
                            } else {
                                item.progress
                            }

                            val now = System.currentTimeMillis()
                            if (now - lastProgressEmit >= 350) {
                                val dt = (now - lastTime) / 1000.0
                                val curBytes = targetStreamFile.length()
                                if (dt > 0.3) {
                                    val dBytes = curBytes - lastBytes
                                    val bps = (dBytes / dt).toLong()
                                    if (bps > 0) {
                                        currentSpeed = FormatUtils.formatBytes(bps) + "/s"
                                        if (totalDuration > 0 && curSec > 0) {
                                            val remSec = (totalDuration - curSec).coerceAtLeast(0.0)
                                            currentEta = FormatUtils.formatDuration(remSec.toLong())
                                        }
                                    }
                                    lastBytes = curBytes
                                    lastTime = now
                                }
                                lastProgressEmit = now

                                item = item.copy(
                                    progress = pct,
                                    progressString = "${String.format(Locale.US, "%.1f", pct)}%",
                                    speed = currentSpeed,
                                    eta = currentEta,
                                    currentBytes = targetStreamFile.length(),
                                    filesizeFormatted = FormatUtils.formatBytes(targetStreamFile.length())
                                )
                                updateActiveDownload(item)
                                _downloadEvents.tryEmit(item)
                                onProgressUpdate?.invoke(item)
                            }
                        }
                    } else if (!trimmed.startsWith("progress=")) {
                        if (errLog.length < 3000) {
                            errLog.append(trimmed).append("\n")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("DownloadRepository", "Error reading FFmpeg stream progress: ${e.message}")
        }

        val exitCode = proc.waitFor()
        activeStreamableProcesses.remove(jobId)

        if (cancelledJobs.contains(jobId)) {
            targetStreamFile.delete()
            removeActiveDownload(jobId)
            throw Exception("Download cancelled")
        }
        if (pausedJobs.contains(jobId)) {
            val paused = item.copy(status = DownloadStatus.PAUSED, progressString = "Paused", speed = "", eta = "")
            updateActiveDownload(paused)
            _downloadEvents.tryEmit(paused)
            return@withContext paused
        }

        if (exitCode == 0 && targetStreamFile.exists() && targetStreamFile.length() > 250 * 1024) {
            var targetFileName = "$title.mp4"
            var finalFile = File(dlDir, targetFileName)
            var counter = 1
            while (finalFile.exists()) {
                targetFileName = "$title ($counter).mp4"
                finalFile = File(dlDir, targetFileName)
                counter++
            }

            val renamed = targetStreamFile.renameTo(finalFile)
            if (!renamed || !finalFile.exists()) {
                try {
                    targetStreamFile.inputStream().use { input ->
                        finalFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    targetStreamFile.delete()
                } catch (e: Exception) {
                    finalFile = targetStreamFile
                }
            }

            val finalSize = finalFile.length()
            FileUtils.scanMediaFile(context, finalFile)

            val completedItem = item.copy(
                status = DownloadStatus.COMPLETED,
                progress = 100f,
                progressString = "Completed",
                filePath = finalFile.absolutePath,
                fileName = finalFile.name,
                totalBytes = finalSize,
                filesizeFormatted = FormatUtils.formatBytes(finalSize),
                speed = "",
                eta = "",
                completedAt = System.currentTimeMillis()
            )

            downloadConfigs.remove(jobId)
            removeActiveDownload(jobId)
            addToHistory(completedItem)
            _downloadEvents.tryEmit(completedItem)
            onProgressUpdate?.invoke(completedItem)

            return@withContext completedItem
        } else {
            Log.w("DownloadRepository", "FFmpeg stream multiplexing exited with code $exitCode or small file (${targetStreamFile.length()} bytes). Log:\n$errLog")
            targetStreamFile.delete()
            return@withContext null
        }
    }
    fun pauseDownload(jobId: String) {
        pausedJobs.add(jobId)
        activeCoroutineJobs.remove(jobId)?.cancel()
        activeStreamableProcesses.remove(jobId)?.let { proc ->
            try { proc.destroy() } catch (ignored: Exception) {}
        }
        val current = _activeDownloads.value[jobId]
        if (current != null) {
            val pausedItem = current.copy(
                status = DownloadStatus.PAUSED,
                progressString = "Paused",
                speed = "",
                eta = ""
            )
            updateActiveDownload(pausedItem)
            _downloadEvents.tryEmit(pausedItem)
        }
        try {
            YoutubeDL.getInstance().destroyProcessById(jobId)
        } catch (e: Exception) {
            Log.w("DownloadRepo", "Could not destroy process for pause $jobId", e)
        }
    }

    fun cancelDownload(jobId: String) {
        cancelledJobs.add(jobId)
        pausedJobs.remove(jobId)
        downloadConfigs.remove(jobId)
        activeCoroutineJobs.remove(jobId)?.cancel()
        activeStreamableProcesses.remove(jobId)?.let { proc ->
            try { proc.destroy() } catch (ignored: Exception) {}
        }
        val tempStream = File(context.cacheDir, "stream_${jobId}.mp4")
        if (tempStream.exists()) tempStream.delete()
        try {
            YoutubeDL.getInstance().destroyProcessById(jobId)
        } catch (e: Exception) {
            Log.w("DownloadRepo", "Could not destroy process $jobId", e)
        }

        // Clean up partial .part or .ytdl files so they do not consume disk space
        val dlDir = FileUtils.getReclipDownloadDir(context)
        dlDir.listFiles { file -> file.name.startsWith(jobId) }?.forEach { f ->
            try { f.delete() } catch (ignored: Exception) {}
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
