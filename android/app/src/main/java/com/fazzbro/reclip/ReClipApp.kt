package com.fazzbro.reclip

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReClipApp : Application() {

    companion object {
        const val CHANNEL_DOWNLOADS = "reclip_downloads_channel"
        lateinit var instance: ReClipApp
            private set
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

    private fun initEngines() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                YoutubeDL.getInstance().init(this@ReClipApp)
                Log.d("ReClipApp", "YoutubeDL initialized")
                FFmpeg.getInstance().init(this@ReClipApp)
                Log.d("ReClipApp", "FFmpeg initialized")
            } catch (e: YoutubeDLException) {
                Log.e("ReClipApp", "Failed to initialize YoutubeDL / FFmpeg", e)
            } catch (e: Exception) {
                Log.e("ReClipApp", "Error during engine initialization", e)
            }
        }
    }
}
