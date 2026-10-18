package com.example.vbstepper

import android.content.Context
import android.net.Uri

object YouTubeId {
    private val ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")

    /** 從網址或純 ID 取出 11 碼影片 ID；看不懂就回傳 null。 */
    fun parse(input: String): String? {
        val text = input.trim()
        if (ID_REGEX.matches(text)) return text

        // 分享文字可能夾帶其他字，先抓出第一個網址
        val url = Regex("https?://\\S+").find(text)?.value ?: return null
        val uri = Uri.parse(url)
        val host = uri.host?.removePrefix("www.")?.removePrefix("m.") ?: return null
        val segments = uri.pathSegments

        val candidate = when {
            host == "youtu.be" -> segments.firstOrNull()
            host.endsWith("youtube.com") -> when (segments.firstOrNull()) {
                "watch" -> uri.getQueryParameter("v")
                "shorts", "live", "embed", "v" -> segments.getOrNull(1)
                else -> uri.getQueryParameter("v")
            }
            else -> null
        }
        return candidate?.takeIf { ID_REGEX.matches(it) }
    }
}

/** 最近看過的影片，存在 SharedPreferences，最新的在前面。 */
class RecentVideos(context: Context) {
    private val prefs = context.getSharedPreferences("recent", Context.MODE_PRIVATE)

    fun list(): List<String> =
        prefs.getString(KEY, "")!!.split(',').filter { it.isNotBlank() }

    fun add(videoId: String) {
        val updated = (listOf(videoId) + list().filter { it != videoId }).take(MAX)
        prefs.edit().putString(KEY, updated.joinToString(",")).apply()
    }

    private companion object {
        const val KEY = "ids"
        const val MAX = 10
    }
}
