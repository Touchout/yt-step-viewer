package com.example.vbstepper

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var recent: RecentVideos
    private lateinit var urlInput: EditText
    private lateinit var recentList: LinearLayout
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val titlesInFlight = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        recent = RecentVideos(this)
        urlInput = findViewById(R.id.urlInput)
        recentList = findViewById(R.id.recentList)

        findViewById<Button>(R.id.openButton).setOnClickListener { openFromInput() }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                openFromInput()
                true
            } else {
                false
            }
        }

        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        renderRecent()
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        YouTubeId.parse(text)?.let { openPlayer(it) }
    }

    private fun openFromInput() {
        val id = YouTubeId.parse(urlInput.text.toString())
        if (id == null) {
            Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
            return
        }
        openPlayer(id)
    }

    private fun openPlayer(videoId: String) {
        recent.add(videoId)
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_VIDEO_ID, videoId)
        )
    }

    private fun renderRecent() {
        recentList.removeAllViews()
        for (video in recent.list()) {
            val button = Button(this).apply {
                text = video.title ?: video.id
                isAllCaps = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { openPlayer(video.id) }
            }
            recentList.addView(button)
            if (video.title == null) fetchTitle(video.id, button)
        }
        // 有歷史紀錄就讓焦點先落在最近一支，遙控器按 OK 就能開
        recentList.getChildAt(0)?.requestFocus()
    }

    /** 背景抓標題，拿到後直接更新那顆按鈕，不重建清單（避免遙控器焦點跳走）。 */
    private fun fetchTitle(videoId: String, button: Button) {
        if (!titlesInFlight.add(videoId)) return
        ioExecutor.execute {
            val title = VideoTitleFetcher.fetch(videoId)
            runOnUiThread {
                titlesInFlight.remove(videoId)
                if (title != null && !isDestroyed) {
                    recent.setTitle(videoId, title)
                    button.text = title
                }
            }
        }
    }
}
