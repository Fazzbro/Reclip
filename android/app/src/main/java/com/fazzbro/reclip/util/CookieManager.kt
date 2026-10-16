package com.fazzbro.reclip.util

import android.content.Context
import java.io.File

class CookieManager(private val context: Context) {

    private val cookieFile: File
        get() = File(context.filesDir, "cookies.txt")

    fun hasCookies(): Boolean {
        return cookieFile.exists() && cookieFile.length() > 0
    }

    fun getCookiePath(): String? {
        return if (hasCookies()) cookieFile.absolutePath else null
    }

    fun getCookieInfo(): String {
        return if (hasCookies()) {
            "Installed (${FormatUtils.formatBytes(cookieFile.length())})"
        } else {
            "No cookies installed"
        }
    }

    fun saveCookieContent(content: String): Boolean {
        return try {
            cookieFile.writeText(content, Charsets.UTF_8)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun clearCookies(): Boolean {
        return try {
            if (cookieFile.exists()) {
                cookieFile.delete()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getCookieContent(): String {
        return if (hasCookies()) {
            try {
                cookieFile.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                ""
            }
        } else {
            ""
        }
    }
}
