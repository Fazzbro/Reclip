package com.fazzbro.reclip.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

data class RedditMediaResult(
    val streamUrl: String,
    val title: String,
    val thumbnail: String,
    val canonicalUrl: String
)

object RedditResolver {
    private const val TAG = "RedditResolver"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    fun isRedditUrl(rawUrl: String): Boolean {
        val lower = rawUrl.lowercase()
        return lower.contains("reddit.com") || lower.contains("redd.it") || lower.contains("v.redd.it")
    }

    suspend fun resolve(rawUrl: String): RedditMediaResult? = withContext(Dispatchers.IO) {
        val trimmed = rawUrl.trim()
        if (!isRedditUrl(trimmed)) return@withContext null

        try {
            // Direct v.redd.it check
            val vMatch = Pattern.compile("https?://v\\.redd\\.it/([a-zA-Z0-9_]+)").matcher(trimmed)
            if (vMatch.find()) {
                val vid = vMatch.group(1) ?: ""
                return@withContext RedditMediaResult(
                    streamUrl = "https://v.redd.it/$vid/HLSPlaylist.m3u8",
                    title = "Reddit Video $vid",
                    thumbnail = "",
                    canonicalUrl = "https://v.redd.it/$vid"
                )
            }

            // Create an isolated CookieManager for this resolution request
            val cookieManager = CookieManager()
            cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL)
            val oldCookieHandler = CookieHandler.getDefault()
            CookieHandler.setDefault(cookieManager)

            try {
                // 1. Follow redirects (e.g. for /s/ shortlinks or redd.it/ shortcodes)
                val cleanUrl = trimmed.split("?")[0]
                var canonicalUrl = cleanUrl
                var html = ""

                val conn1 = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 12000
                    readTimeout = 12000
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                }

                canonicalUrl = conn1.url.toString().split("?")[0]
                html = conn1.inputStream.bufferedReader().use { it.readText() }
                conn1.disconnect()

                // 2. Check if Reddit served a Proof-of-Work JS Challenge
                val tokenMatcher = Pattern.compile("name=[\"']jsc_token[\"']\\s+value=[\"']([^\"']+)[\"']").matcher(html)
                val argMatcher = Pattern.compile("await\\(async\\s+e=>e\\+e\\)\\([\"']([^\"']+)[\"']\\)").matcher(html)

                if (tokenMatcher.find() && argMatcher.find()) {
                    val token = tokenMatcher.group(1) ?: ""
                    val arg = argMatcher.group(1) ?: ""
                    val solution = arg + arg

                    val query = "solution=${URLEncoder.encode(solution, "UTF-8")}&js_challenge=1&jsc_token=${URLEncoder.encode(token, "UTF-8")}&jsc_orig_r="
                    val baseUrl = canonicalUrl.split("?")[0]
                    val challengeUrl = "$baseUrl?$query"

                    val conn2 = (URL(challengeUrl).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = true
                        connectTimeout = 12000
                        readTimeout = 12000
                        setRequestProperty("User-Agent", USER_AGENT)
                        setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                    }
                    html = conn2.inputStream.bufferedReader().use { it.readText() }
                    conn2.disconnect()
                }

                // 3. Extract metadata from HTML
                var title = ""
                var thumbnail = ""
                var streamUrl = ""

                // Extract Title
                val titleMatcher = Pattern.compile("<shreddit-title[^>]*\\stitle=[\"']([^\"']+)[\"']").matcher(html)
                if (titleMatcher.find()) {
                    val rawTitle = titleMatcher.group(1) ?: ""
                    title = rawTitle.replace(Regex("\\s*:\\s*r/\\w+.*$"), "").trim()
                }
                if (title.isBlank()) {
                    val slugMatcher = Pattern.compile("/comments/[a-zA-Z0-9]+/([^/?#]+)").matcher(canonicalUrl)
                    if (slugMatcher.find()) {
                        title = slugMatcher.group(1)?.replace("_", " ")?.replace("-", " ")
                            ?.split(" ")?.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } } ?: ""
                    }
                }

                // Extract Thumbnail
                val posterMatcher = Pattern.compile("poster=[\"']([^\"']+)[\"']").matcher(html)
                if (posterMatcher.find()) {
                    thumbnail = posterMatcher.group(1)?.replace("&amp;", "&") ?: ""
                }
                if (thumbnail.isBlank()) {
                    val ogImgMatcher = Pattern.compile("property=[\"']og:image[\"']\\s+content=[\"']([^\"']+)[\"']").matcher(html)
                    if (ogImgMatcher.find()) {
                        thumbnail = ogImgMatcher.group(1)?.replace("&amp;", "&") ?: ""
                    }
                }

                // Extract Stream URL
                val shredditSrcMatcher = Pattern.compile("<shreddit-player[^>]*\\ssrc=[\"']([^\"']+)[\"']").matcher(html)
                if (shredditSrcMatcher.find()) {
                    streamUrl = shredditSrcMatcher.group(1)?.replace("&amp;", "&") ?: ""
                } else {
                    val vRedditMatcher = Pattern.compile("https?://v\\.redd\\.it/([a-zA-Z0-9_]+)").matcher(html)
                    if (vRedditMatcher.find()) {
                        val vid = vRedditMatcher.group(1) ?: ""
                        streamUrl = "https://v.redd.it/$vid/HLSPlaylist.m3u8"
                    }
                }

                // 4. Fallback to RapidSave if streamUrl not found
                if (streamUrl.isBlank()) {
                    try {
                        val rsUrl = "https://rapidsave.com/info?url=" + URLEncoder.encode(canonicalUrl, "UTF-8")
                        val rsConn = (URL(rsUrl).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 8000
                            readTimeout = 8000
                            setRequestProperty("User-Agent", USER_AGENT)
                        }
                        val rsHtml = rsConn.inputStream.bufferedReader().use { it.readText() }
                        rsConn.disconnect()

                        val rsVMatch = Pattern.compile("https?://v\\.redd\\.it/([a-zA-Z0-9_]+)").matcher(rsHtml)
                        if (rsVMatch.find()) {
                            val vid = rsVMatch.group(1) ?: ""
                            streamUrl = "https://v.redd.it/$vid/HLSPlaylist.m3u8"
                        } else {
                            val rsDlMatch = Pattern.compile("href=[\"'](https?://sd\\.rapidsave\\.com/download\\.php\\?[^\"']+)[\"']").matcher(rsHtml)
                            if (rsDlMatch.find()) {
                                streamUrl = rsDlMatch.group(1)?.replace("&amp;", "&") ?: ""
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "RapidSave fallback failed", e)
                    }
                }

                if (streamUrl.isNotBlank()) {
                    return@withContext RedditMediaResult(
                        streamUrl = streamUrl,
                        title = title.ifBlank { "Reddit Video" },
                        thumbnail = thumbnail,
                        canonicalUrl = canonicalUrl
                    )
                }

            } finally {
                CookieHandler.setDefault(oldCookieHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve Reddit URL: $rawUrl", e)
        }

        null
    }
}
