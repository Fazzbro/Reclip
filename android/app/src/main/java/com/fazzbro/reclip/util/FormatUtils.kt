package com.fazzbro.reclip.util

import java.util.Locale
import kotlin.math.abs

object FormatUtils {

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return ""
        val absBytes = abs(bytes).toDouble()
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = absBytes
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.size - 1) {
            value /= 1024.0
            unitIndex++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }

    fun formatDuration(seconds: Long): String {
        if (seconds <= 0L) return ""
        val hrs = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hrs > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }

    fun formatEta(seconds: Long): String {
        if (seconds <= 0L) return "--:--"
        val mins = seconds / 60
        val secs = seconds % 60
        return if (mins >= 60) {
            val hrs = mins / 60
            val remMins = mins % 60
            String.format(Locale.US, "%02d:%02d:%02d", hrs, remMins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }

    fun sanitizeFilename(name: String): String {
        if (name.isBlank()) return "ReClip_Media"
        val clean = name.replace(Regex("[\\\\/*?:\"<>|]"), "").trim()
        val singleSpaced = clean.replace(Regex("\\s+"), " ")
        return if (singleSpaced.length > 120) singleSpaced.substring(0, 120).trim() else singleSpaced
    }
}
