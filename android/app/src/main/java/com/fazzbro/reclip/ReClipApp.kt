package com.fazzbro.reclip

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class ReClipApp : Application() {

    companion object {
        const val CHANNEL_DOWNLOADS = "reclip_downloads_channel"
        const val BUNDLED_YTDLP_VERSION = "2026.10.26"
        lateinit var instance: ReClipApp
            private set
        val initDeferred = CompletableDeferred<Boolean>()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        initEngines()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_DOWNLOADS,
                "Media Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live download progress and media completion"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun deployBundledYtDlp() {
        try {
            val ytdlpDir = File(noBackupFilesDir, "youtubedl-android/yt-dlp")
            val ytdlpFile = File(ytdlpDir, "yt-dlp")
            val ytdlpPrefs = getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE)
            val currentVersion = ytdlpPrefs.getString("dlpVersion", null)

            val needsDeploy = !ytdlpFile.exists() ||
                ytdlpFile.length() < 100_000L ||
                currentVersion == null ||
                (currentVersion != null && currentVersion < BUNDLED_YTDLP_VERSION)

            if (needsDeploy) {
                Log.i("ReClipApp", "Deploying bundled yt-dlp binary ($BUNDLED_YTDLP_VERSION, replacing $currentVersion)...")
                if (!ytdlpDir.exists()) {
                    ytdlpDir.mkdirs()
                }

                resources.openRawResource(R.raw.ytdlp).use { input ->
                    FileOutputStream(ytdlpFile).use { output ->
                        input.copyTo(output)
                    }
                }
                ytdlpFile.setExecutable(true, false)
                ytdlpFile.setReadable(true, false)

                ytdlpPrefs.edit()
                    .putString("dlpVersion", BUNDLED_YTDLP_VERSION)
                    .putString("dlpVersionName", BUNDLED_YTDLP_VERSION)
                    .apply()

                // Clear stale cache directory to avoid 403 or signature mismatches
                try {
                    File(cacheDir, "ytdlp-cache").deleteRecursively()
                } catch (ignored: Exception) {}

                Log.i("ReClipApp", "Bundled yt-dlp deployed successfully (${ytdlpFile.length()} bytes)")
            } else {
                Log.i("ReClipApp", "Existing yt-dlp is up-to-date ($currentVersion)")
            }
        } catch (e: Exception) {
            Log.e("ReClipApp", "Failed to deploy bundled yt-dlp", e)
        }
    }

    private fun initEngines() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                deployBundledYtDlp()
                YoutubeDL.getInstance().init(this@ReClipApp)
                Log.d("ReClipApp", "YoutubeDL initialized")
                FFmpeg.getInstance().init(this@ReClipApp)
                Log.d("ReClipApp", "FFmpeg initialized")
                initDeferred.complete(true)
            } catch (e: YoutubeDLException) {
                Log.e("ReClipApp", "Failed to initialize YoutubeDL / FFmpeg", e)
                initDeferred.complete(false)
            } catch (e: Exception) {
                Log.e("ReClipApp", "Error during engine initialization", e)
                initDeferred.complete(false)
            }
        }
    }
}
