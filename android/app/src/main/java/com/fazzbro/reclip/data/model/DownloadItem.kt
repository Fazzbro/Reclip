package com.fazzbro.reclip.data.model

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    ERROR,
    CANCELLED
}

enum class MediaFormatType {
    VIDEO,
    AUDIO
}

data class DownloadItem(
    val id: String,
    val url: String,
    val title: String,
    val thumbnail: String,
    val formatType: MediaFormatType = MediaFormatType.VIDEO,
    val qualityLabel: String = "1080p",
    val formatId: String = "",
    val audioLang: String = "",
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Float = 0f,
    val progressString: String = "Waiting...",
    val speed: String = "",
    val eta: String = "",
    val currentBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val filesizeFormatted: String = "",
    val filePath: String = "",
    val fileName: String = "",
    val errorMessage: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0L
)
