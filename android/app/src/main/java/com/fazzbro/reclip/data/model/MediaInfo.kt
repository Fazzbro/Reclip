package com.fazzbro.reclip.data.model

data class FormatOption(
    val id: String,
    val label: String,
    val height: Int = 0,
    val ext: String = "mp4",
    val filesize: Long = 0L,
    val filesizeFormatted: String = "",
    val isAudioOnly: Boolean = false,
    val formatNote: String = ""
)

data class AudioLanguage(
    val code: String,
    val name: String,
    val langId: String
)

data class MediaInfo(
    val url: String,
    val title: String,
    val thumbnail: String,
    val duration: Long,
    val durationFormatted: String,
    val uploader: String,
    val formats: List<FormatOption>,
    val audioLanguages: List<AudioLanguage>,
    val approxFilesizeFormatted: String,
    val audioFilesizeFormatted: String
)
