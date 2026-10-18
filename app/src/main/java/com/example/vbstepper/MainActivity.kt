package com.example.vbstepper

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var recent: RecentVideos
    private lateinit var urlInput: EditText
    private lateinit var recentList: LinearLayout

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
        for (id in recent.list()) {
            val button = Button(this).apply {
                text = id
                isAllCaps = false
                setOnClickListener { openPlayer(id) }
            }
            recentList.addView(button)
        }
        // 有歷史紀錄就讓焦點先落在最近一支，遙控器按 OK 就能開
        recentList.getChildAt(0)?.requestFocus()
    }
}
