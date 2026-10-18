package com.example.vbstepper

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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

data class RecentVideo(val id: String, val title: String?)

/**
 * 最近看過的影片，存在 SharedPreferences，最新的在前面。
 * 標題是之後非同步補上的，還沒拿到時是 null。
 */
class RecentVideos(context: Context) {
    private val prefs = context.getSharedPreferences("recent", Context.MODE_PRIVATE)

    fun list(): List<RecentVideo> {
        val json = prefs.getString(KEY_JSON, null)
            ?: return legacyIds().map { RecentVideo(it, null) }
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            RecentVideo(item.getString("id"), item.optString("title").takeIf { it.isNotEmpty() })
        }
    }

    fun add(videoId: String) {
        val current = list()
        val existing = current.find { it.id == videoId } ?: RecentVideo(videoId, null)
        save((listOf(existing) + current.filter { it.id != videoId }).take(MAX))
    }

    fun setTitle(videoId: String, title: String) {
        save(list().map { if (it.id == videoId) it.copy(title = title) else it })
    }

    private fun save(videos: List<RecentVideo>) {
        val array = JSONArray()
        for (v in videos) {
            array.put(JSONObject().put("id", v.id).apply { v.title?.let { put("title", it) } })
        }
        prefs.edit().putString(KEY_JSON, array.toString()).remove(KEY_LEGACY).apply()
    }

    /** 舊版只存了逗號分隔的 ID。 */
    private fun legacyIds(): List<String> =
        prefs.getString(KEY_LEGACY, "")!!.split(',').filter { it.isNotBlank() }

    private companion object {
        const val KEY_JSON = "videos"
        const val KEY_LEGACY = "ids"
        const val MAX = 10
    }
}

/** 用 YouTube 官方的 oEmbed 取影片標題，不需要 API key。會阻塞，請在背景執行緒呼叫。 */
object VideoTitleFetcher {
    fun fetch(videoId: String): String? = try {
        val watchUrl = URLEncoder.encode("https://www.youtube.com/watch?v=$videoId", "UTF-8")
        val conn = URL("https://www.youtube.com/oembed?format=json&url=$watchUrl")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(body).optString("title").takeIf { it.isNotBlank() }
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }
}
