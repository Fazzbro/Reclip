package com.fazzbro.reclip

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import com.fazzbro.reclip.data.model.FormatOption
import com.fazzbro.reclip.player.PlayerActivity
import com.fazzbro.reclip.data.model.MediaFormatType
import com.fazzbro.reclip.data.repository.DownloadRepository
import com.fazzbro.reclip.service.DownloadService
import com.yausername.youtubedl_android.YoutubeDL
import com.fazzbro.reclip.util.CookieManager
import com.fazzbro.reclip.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var repository: DownloadRepository
    private lateinit var cookieManager: CookieManager
    private var pendingSharedUrl: String? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository = DownloadRepository.getInstance(applicationContext)
        cookieManager = CookieManager(applicationContext)

        requestNecessaryPermissions()
        handleIncomingIntent(intent)
        setupWebView()
        observeActiveDownloads()
    }

    private fun requestNecessaryPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        WebView.setWebContentsDebuggingEnabled(true)

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#F7F5F0"))

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    consoleMessage?.let {
                        Log.d("ReClipWeb", "[${it.messageLevel()}] ${it.message()} -- line ${it.lineNumber()} of ${it.sourceId()}")
                    }
                    return super.onConsoleMessage(consoleMessage)
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url ?: return null
                    return assetLoader.shouldInterceptRequest(url)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    pendingSharedUrl?.let { shared ->
                        sendUrlToWeb(shared)
                        pendingSharedUrl = null
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    Log.e("ReClipWeb", "WebView error: $errorCode, $description, failingUrl: $failingUrl")
                    if (failingUrl?.startsWith("https://appassets.androidplatform.net") == true) {
                        view?.loadUrl("file:///android_asset/web/index.html")
                    }
                }
            }

            addJavascriptInterface(WebAppInterface(), "AndroidBridge")
            loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
        }

        setContentView(webView)
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val isSend = action == Intent.ACTION_SEND
        val isView = action == Intent.ACTION_VIEW

        if (!isSend && !isView) return

        var rawText: String? = null

        if (isSend) {
            rawText = intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
                ?: intent.dataString
                ?: intent.clipData?.let { clip ->
                    if (clip.itemCount > 0) clip.getItemAt(0).text?.toString() ?: clip.getItemAt(0).uri?.toString() else null
                }
        } else if (isView) {
            rawText = intent.dataString
        }

        if (rawText.isNullOrBlank()) return

        // Extract first URL using regex
        val urlPattern = Regex("https?://[^\\s]+")
        val match = urlPattern.find(rawText)
        if (match != null) {
            var extracted = match.value.trim()
            extracted = extracted.trimEnd { it in ")]}>.,;?!\"'" }
            if (extracted.isNotBlank()) {
                Log.d("ReClipShare", "Received shared URL: $extracted")
                pendingSharedUrl = extracted
                sendUrlToWeb(extracted)
            }
        }
    }

    private fun sendUrlToWeb(url: String) {
        if (!::webView.isInitialized) return
        val safeJson = JSONObject.quote(url)
        runOnUiThread {
            webView.evaluateJavascript(
                "(function() { if (typeof window.onAndroidUrlReceived === 'function') { window.onAndroidUrlReceived(" + safeJson + "); } else { window.__pendingAndroidUrl = " + safeJson + "; } })();",
                null
            )
        }
    }

    private fun observeActiveDownloads() {
        lifecycleScope.launch {
            repository.downloadEvents.collect { item ->
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("url", item.url)
                    put("title", item.title)
                    put("thumbnail", item.thumbnail)
                    put("formatType", item.formatType.name)
                    put("qualityLabel", item.qualityLabel)
                    put("status", item.status.name)
                    put("progress", item.progress)
                    put("progressString", item.progressString)
                    put("speed", item.speed)
                    put("eta", item.eta)
                    put("filesizeFormatted", item.filesizeFormatted)
                    put("filePath", item.filePath)
                    put("fileName", item.fileName)
                    put("completedAt", item.completedAt)
                }
                val jsonStr = obj.toString()
                runOnUiThread {
                    webView.evaluateJavascript("window.onAndroidDownloadProgress('$jsonStr');", null)
                }
            }
        }
    }

    inner class WebAppInterface {

        @JavascriptInterface
        fun getPendingSharedUrl(): String {
            val url = pendingSharedUrl ?: ""
            pendingSharedUrl = null
            return url
        }

        @JavascriptInterface
        fun fetchInfoAsync(url: String, callbackId: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val res = repository.fetchMediaInfo(url)
                    val jsonStr = if (res.isSuccess) {
                        val info = res.getOrThrow()
                        val obj = JSONObject().apply {
                            put("url", info.url)
                            put("title", info.title)
                            put("thumbnail", info.thumbnail)
                            put("duration", info.duration)
                            put("durationFormatted", info.durationFormatted)
                            put("uploader", info.uploader)
                            put("approxFilesizeFormatted", info.approxFilesizeFormatted)
                            put("audioFilesizeFormatted", info.audioFilesizeFormatted)

                            val formatsArr = JSONArray()
                            info.formats.forEach { f ->
                                val fObj = JSONObject().apply {
                                    put("id", f.id)
                                    put("label", f.label)
                                    put("height", f.height)
                                    put("ext", f.ext)
                                    put("filesize", f.filesize)
                                    put("filesizeFormatted", f.filesizeFormatted)
                                }
                                formatsArr.put(fObj)
                            }
                            put("formats", formatsArr)

                            val langsArr = JSONArray()
                            info.audioLanguages.forEach { l ->
                                val lObj = JSONObject().apply {
                                    put("code", l.code)
                                    put("name", l.name)
                                    put("langId", l.langId)
                                }
                                langsArr.put(lObj)
                            }
                            put("audioLanguages", langsArr)
                        }
                        obj.toString()
                    } else {
                        val ex = res.exceptionOrNull()
                        val msg = ex?.message?.ifBlank { null }
                            ?: ex?.cause?.message?.ifBlank { null }
                            ?: "Failed to extract media details. Please check the URL or your network connection."
                        val err = JSONObject().apply {
                            put("error", msg)
                        }
                        err.toString()
                    }
                    runOnUiThread {
                        val safe = JSONObject.quote(jsonStr)
                        webView.evaluateJavascript("window.onFetchInfoCallback('$callbackId', $safe);", null)
                    }
                } catch (e: Exception) {
                    val msg = e.message?.ifBlank { null }
                        ?: e.cause?.message?.ifBlank { null }
                        ?: "Failed to extract media details. Please verify the URL and try again."
                    val err = JSONObject().apply {
                        put("error", msg)
                    }
                    val safe = JSONObject.quote(err.toString())
                    runOnUiThread {
                        webView.evaluateJavascript("window.onFetchInfoCallback('$callbackId', $safe);", null)
                    }
                }
            }
        }

        @JavascriptInterface
        fun fetchInfo(url: String): String {
            return runBlocking(Dispatchers.IO) {
                val res = repository.fetchMediaInfo(url)
                if (res.isSuccess) {
                    val info = res.getOrThrow()
                    val obj = JSONObject().apply {
                        put("url", info.url)
                        put("title", info.title)
                        put("thumbnail", info.thumbnail)
                        put("duration", info.duration)
                        put("durationFormatted", info.durationFormatted)
                        put("uploader", info.uploader)
                        put("approxFilesizeFormatted", info.approxFilesizeFormatted)
                        put("audioFilesizeFormatted", info.audioFilesizeFormatted)

                        val formatsArr = JSONArray()
                        info.formats.forEach { f ->
                            val fObj = JSONObject().apply {
                                put("id", f.id)
                                put("label", f.label)
                                put("height", f.height)
                                put("ext", f.ext)
                                put("filesize", f.filesize)
                                put("filesizeFormatted", f.filesizeFormatted)
                            }
                            formatsArr.put(fObj)
                        }
                        put("formats", formatsArr)

                        val langsArr = JSONArray()
                        info.audioLanguages.forEach { l ->
                            val lObj = JSONObject().apply {
                                put("code", l.code)
                                put("name", l.name)
                                put("langId", l.langId)
                            }
                            langsArr.put(lObj)
                        }
                        put("audioLanguages", langsArr)
                    }
                    obj.toString()
                } else {
                    val ex = res.exceptionOrNull()
                    val msg = ex?.message?.ifBlank { null }
                        ?: ex?.cause?.message?.ifBlank { null }
                        ?: "Failed to extract media details. Please check the URL or your network connection."
                    val err = JSONObject().apply {
                        put("error", msg)
                    }
                    err.toString()
                }
            }
        }

        @JavascriptInterface
        fun hideKeyboard() {
            runOnUiThread {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                val token = currentFocus?.windowToken ?: webView.windowToken
                imm?.hideSoftInputFromWindow(token, 0)
                webView.clearFocus()
            }
        }

        @JavascriptInterface
        fun startDownload(requestJson: String): String {
            return try {
                val obj = JSONObject(requestJson)
                val url = obj.getString("url")
                val title = obj.getString("title")
                val thumbnail = obj.optString("thumbnail", "")
                val formatType = MediaFormatType.valueOf(obj.optString("formatType", "VIDEO"))
                val qualityLabel = obj.optString("qualityLabel", "1080p")
                val qualityId = obj.optString("qualityId", "")
                val qualityHeight = obj.optInt("qualityHeight", 1080)
                val audioLang = obj.optString("audioLang", "")
                val jobId = obj.optString("jobId", "")

                val option = FormatOption(
                    id = qualityId,
                    label = qualityLabel,
                    height = qualityHeight
                )

                DownloadService.startDownload(
                    context = this@MainActivity,
                    url = url,
                    title = title,
                    thumbnail = thumbnail,
                    formatType = formatType,
                    qualityOption = option,
                    audioLang = audioLang,
                    jobId = jobId
                )
                "started"
            } catch (e: Exception) {
                "error: ${e.message}"
            }
        }

        @JavascriptInterface
        fun pauseDownload(jobId: String) {
            repository.pauseDownload(jobId)
            DownloadService.pauseDownload(this@MainActivity, jobId)
        }

        @JavascriptInterface
        fun resumeDownload(jobId: String) {
            DownloadService.resumeDownload(this@MainActivity, jobId)
        }

        @JavascriptInterface
        fun cancelDownload(jobId: String) {
            repository.cancelDownload(jobId)
            DownloadService.cancelDownload(this@MainActivity, jobId)
        }

        @JavascriptInterface
        fun getHistory(): String {
            val list = repository.downloadHistory.value
            val array = JSONArray()
            list.forEach { item ->
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("url", item.url)
                    put("title", item.title)
                    put("thumbnail", item.thumbnail)
                    put("formatType", item.formatType.name)
                    put("qualityLabel", item.qualityLabel)
                    put("status", item.status.name)
                    put("progress", item.progress)
                    put("progressString", item.progressString)
                    put("filesizeFormatted", item.filesizeFormatted)
                    put("filePath", item.filePath)
                    put("fileName", item.fileName)
                    put("completedAt", item.completedAt)
                }
                array.put(obj)
            }
            return array.toString()
        }

        @JavascriptInterface
        fun deleteHistoryItem(jobId: String, deleteFile: Boolean) {
            val item = repository.downloadHistory.value.firstOrNull { it.id == jobId }
            if (item != null) {
                repository.deleteHistoryItem(item, deleteFile)
            }
        }

        @JavascriptInterface
        fun clearHistory() {
            repository.clearHistory()
        }

        private fun launchInAppPlayer(jobId: String, filePath: String, title: String) {
            runOnUiThread {
                val intent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_JOB_ID, jobId)
                    putExtra(PlayerActivity.EXTRA_FILE_PATH, filePath)
                    putExtra(PlayerActivity.EXTRA_TITLE, title)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
        }

        @JavascriptInterface
        fun playJob(jobId: String): String {
            try {
                // 1. Check completed history first
                val historyItem = repository.downloadHistory.value.firstOrNull { it.id == jobId }
                if (historyItem != null && historyItem.filePath.isNotBlank()) {
                    val file = File(historyItem.filePath)
                    if (file.exists()) {
                        launchInAppPlayer(jobId, file.absolutePath, historyItem.title)
                        return "OK"
                    }
                }

                // 2. Check active item streamable file directly
                val activeItem = repository.activeDownloads.value[jobId]
                if (activeItem != null && activeItem.filePath.isNotBlank()) {
                    val activeFile = File(activeItem.filePath)
                    if (activeFile.exists()) {
                        if (activeFile.length() < 250 * 1024) {
                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Buffering media stream. Please wait 3 seconds and tap Play again.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            return "Buffering"
                        }
                        launchInAppPlayer(jobId, activeFile.absolutePath, activeItem.title)
                        return "OK"
                    }
                }

                // 3. Check for any stream file candidates in dlDir or cacheDir
                val dlDir = FileUtils.getReclipDownloadDir(this@MainActivity)
                val streamCandidate = dlDir.listFiles { _, name ->
                    name.startsWith(jobId) && name.endsWith(".mp4") && !name.endsWith(".part") && !name.endsWith(".temp")
                }?.firstOrNull() ?: File(cacheDir, "stream_${jobId}.mp4")

                if (streamCandidate.exists() && streamCandidate.length() >= 250 * 1024) {
                    launchInAppPlayer(jobId, streamCandidate.absolutePath, activeItem?.title ?: "Playing Media")
                    return "OK"
                }

                // 4. Check if standard .part file exists
                val hasPartFile = dlDir.listFiles { _, name ->
                    name.startsWith(jobId) && name.endsWith(".part")
                }?.isNotEmpty() == true

                runOnUiThread {
                    if (hasPartFile) {
                        Toast.makeText(
                            this@MainActivity,
                            "Audio and video will merge upon full download completion.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Media stream is initializing. Please wait a few seconds and try again.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                return if (hasPartFile) "Incomplete part file" else "Initializing"
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Could not open media player: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                return "Error: ${e.message}"
            }
        }

        @JavascriptInterface
        fun isStreamableDownload(): Boolean = try { repository.isStreamableDownloadEnabled() } catch (e: Exception) { true }

        @JavascriptInterface
        fun setStreamableDownload(enabled: Boolean) {
            try {
                repository.setStreamableDownloadEnabled(enabled)
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun playMedia(filePath: String) {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "File does not exist on disk", Toast.LENGTH_SHORT).show()
                    }
                    return
                }
                launchInAppPlayer("", file.absolutePath, file.nameWithoutExtension)
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Could not open media: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun shareMedia(filePath: String) {
            try {
                val file = File(filePath)
                if (!file.exists()) return
                val uri = FileUtils.getContentUriForFile(this@MainActivity, file)
                val mime = FileUtils.getMimeType(file.name)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share Media"))
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error sharing media: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun openFolder() {
            try {
                val intent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Downloads saved to: /Download/ReClip", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun hasCookies(): Boolean = try { cookieManager.hasCookies() } catch (e: Exception) { false }

        @JavascriptInterface
        fun getCookieContent(): String = try { cookieManager.getCookieContent() } catch (e: Exception) { "" }

        @JavascriptInterface
        fun saveCookies(content: String): Boolean = try { cookieManager.saveCookieContent(content) } catch (e: Exception) { false }

        @JavascriptInterface
        fun clearCookies(): Boolean = try { cookieManager.clearCookies() } catch (e: Exception) { false }

        @JavascriptInterface
        fun updateYtDlp(): String {
            return runBlocking(Dispatchers.IO) {
                val res = repository.updateYtDlp()
                if (res.isSuccess) {
                    res.getOrNull() ?: "Update check completed"
                } else {
                    "Update failed: ${res.exceptionOrNull()?.message}"
                }
            }
        }

        @JavascriptInterface
        fun getYtDlpVersion(): String {
            return try {
                YoutubeDL.getInstance().versionName(this@MainActivity) ?: ReClipApp.BUNDLED_YTDLP_VERSION
            } catch (e: Exception) {
                ReClipApp.BUNDLED_YTDLP_VERSION
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
