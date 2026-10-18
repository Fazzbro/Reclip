package com.fazzbro.reclip.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.fazzbro.reclip.MainActivity
import com.fazzbro.reclip.R
import com.fazzbro.reclip.ReClipApp
import com.fazzbro.reclip.data.model.FormatOption
import com.fazzbro.reclip.data.model.MediaFormatType
import com.fazzbro.reclip.data.repository.DownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var repository: DownloadRepository

    companion object {
        private const val NOTIFICATION_ID = 4040
        const val ACTION_START_DOWNLOAD = "com.fazzbro.reclip.START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.fazzbro.reclip.CANCEL_DOWNLOAD"

        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_THUMBNAIL = "extra_thumbnail"
        const val EXTRA_FORMAT_TYPE = "extra_format_type"
        const val EXTRA_QUALITY_LABEL = "extra_quality_label"
        const val EXTRA_QUALITY_ID = "extra_quality_id"
        const val EXTRA_QUALITY_HEIGHT = "extra_quality_height"
        const val EXTRA_AUDIO_LANG = "extra_audio_lang"
        const val EXTRA_JOB_ID = "extra_job_id"

        fun startDownload(
            context: Context,
            url: String,
            title: String,
            thumbnail: String,
            formatType: MediaFormatType,
            qualityOption: FormatOption?,
            audioLang: String = "",
            jobId: String = ""
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_THUMBNAIL, thumbnail)
                putExtra(EXTRA_FORMAT_TYPE, formatType.name)
                putExtra(EXTRA_QUALITY_LABEL, qualityOption?.label ?: "1080p")
                putExtra(EXTRA_QUALITY_ID, qualityOption?.id ?: "")
                putExtra(EXTRA_QUALITY_HEIGHT, qualityOption?.height ?: 1080)
                putExtra(EXTRA_AUDIO_LANG, audioLang)
                if (jobId.isNotBlank()) {
                    putExtra(EXTRA_JOB_ID, jobId)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelDownload(context: Context, jobId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_JOB_ID, jobId)
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = DownloadRepository(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Media Download"
                val thumbnail = intent.getStringExtra(EXTRA_THUMBNAIL) ?: ""
                val formatTypeName = intent.getStringExtra(EXTRA_FORMAT_TYPE) ?: "VIDEO"
                val formatType = MediaFormatType.valueOf(formatTypeName)
                val qualityLabel = intent.getStringExtra(EXTRA_QUALITY_LABEL) ?: "1080p"
                val qualityId = intent.getStringExtra(EXTRA_QUALITY_ID) ?: ""
                val qualityHeight = intent.getIntExtra(EXTRA_QUALITY_HEIGHT, 1080)
                val audioLang = intent.getStringExtra(EXTRA_AUDIO_LANG) ?: ""
                val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: ""

                val option = FormatOption(
                    id = qualityId,
                    label = qualityLabel,
                    height = qualityHeight
                )

                startForegroundServiceWithNotification(title, "Starting download...", 0)

                serviceScope.launch {
                    repository.startDownload(
                        url = url,
                        title = title,
                        thumbnail = thumbnail,
                        formatType = formatType,
                        qualityOption = option,
                        audioLang = audioLang,
                        customJobId = jobId,
                        onProgressUpdate = { item ->
                            updateNotification(
                                title = item.title,
                                text = "${item.progressString} • ${item.eta}",
                                progress = item.progress.toInt()
                            )
                        }
                    )
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_CANCEL_DOWNLOAD -> {
                val jobId = intent.getStringExtra(EXTRA_JOB_ID)
                if (jobId != null) {
                    repository.cancelDownload(jobId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundServiceWithNotification(title: String, text: String, progress: Int) {
        val notification = buildNotification(title, text, progress)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(title: String, text: String, progress: Int) {
        val notification = buildNotification(title, text, progress)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, text: String, progress: Int): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, ReClipApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, progress <= 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
