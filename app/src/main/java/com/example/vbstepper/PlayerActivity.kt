package com.example.vbstepper

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.toFloat
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import java.util.Locale

/**
 * 遙控器操作：
 *  ←/→        後退／前進一個步距（播放中會先暫停）
 *  ↑/↓        切換步距
 *  OK         播放／暫停
 *  長按 OK     循環切換速度 0.25x → 0.5x → 1x
 *  ⏪/⏩       降低／提高速度（遙控器有這兩鍵時）
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_ID = "video_id"

        /** 1/30 秒約等於 30fps 影片的一格。 */
        private val STEPS = floatArrayOf(1f / 30f, 0.05f, 0.1f, 0.2f, 0.5f, 1f)
        private const val DEFAULT_STEP_INDEX = 2 // 0.1 秒

        private val RATES = listOf(
            PlayerConstants.PlaybackRate.RATE_0_25,
            PlayerConstants.PlaybackRate.RATE_0_5,
            PlayerConstants.PlaybackRate.RATE_1,
        )
        private const val DEFAULT_RATE_INDEX = 2 // 1x

        /** seek 之後這段時間內忽略播放器回報的時間，避免舊值蓋掉自己維護的游標。 */
        private const val SEEK_SETTLE_MS = 600L

        /** 按住左右鍵時，限制 seek 的頻率，避免塞爆播放器。 */
        private const val REPEAT_SEEK_INTERVAL_MS = 120L
    }

    private lateinit var playerView: YouTubePlayerView
    private lateinit var statusText: TextView

    private var player: YouTubePlayer? = null
    private var videoId = ""

    // getCurrentTime() 是非同步的，快速連按會讀到舊值，所以自己維護目前時間。
    private var cursor = 0f
    private var duration = 0f
    private var playing = false
    private var lastSeekAt = 0L

    private var stepIndex = DEFAULT_STEP_INDEX
    private var rateIndex = DEFAULT_RATE_INDEX
    private var centerLongPressed = false
    private var errorMessage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.playerView)
        statusText = findViewById(R.id.statusText)
        videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: run { finish(); return }

        lifecycle.addObserver(playerView)

        val options = IFramePlayerOptions.Builder(this)
            .controls(0)      // 隱藏 YouTube 自己的控制列，全部交給遙控器
            .rel(0)
            .ivLoadPolicy(3)  // 不顯示資訊卡
            .fullscreen(0)
            .build()

        playerView.initialize(object : AbstractYouTubePlayerListener() {
            override fun onReady(youTubePlayer: YouTubePlayer) {
                player = youTubePlayer
                youTubePlayer.loadVideo(videoId, 0f)
            }

            override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                playing = state == PlayerConstants.PlayerState.PLAYING
                updateStatus()
            }

            override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                if (playing && SystemClock.uptimeMillis() - lastSeekAt > SEEK_SETTLE_MS) {
                    cursor = second
                    updateStatus()
                }
            }

            override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) {
                this@PlayerActivity.duration = duration
                updateStatus()
            }

            override fun onPlaybackRateChange(youTubePlayer: YouTubePlayer, playbackRate: PlayerConstants.PlaybackRate) {
                RATES.indexOf(playbackRate).takeIf { it >= 0 }?.let { rateIndex = it }
                updateStatus()
            }

            override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                errorMessage = when (error) {
                    PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER -> "這支影片不允許嵌入播放"
                    PlayerConstants.PlayerError.VIDEO_NOT_FOUND -> "找不到影片（可能是私人或已刪除）"
                    else -> "播放錯誤：$error"
                }
                updateStatus()
            }
        }, true, options)

        updateStatus()
    }

    // 在 Activity 層攔截按鍵，不讓 WebView 吃掉 DPAD 事件。
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0
        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) step(-1, event.repeatCount > 0); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) step(+1, event.repeatCount > 0); true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (first) changeStep(+1); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (first) changeStep(-1); true }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> { handleCenter(event); true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (first) togglePlay(); true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (first && !playing) togglePlay(); true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (first && playing) togglePlay(); true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (first) changeRate(+1, wrap = false); true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (first) changeRate(-1, wrap = false); true }
            else -> false
        }
        return handled || super.dispatchKeyEvent(event)
    }

    /** 短按 OK 播放／暫停；按住超過系統 repeat 延遲就當長按，切換速度。 */
    private fun handleCenter(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    centerLongPressed = false
                } else if (!centerLongPressed) {
                    centerLongPressed = true
                    changeRate(+1, wrap = true)
                }
            }
            KeyEvent.ACTION_UP -> {
                if (!centerLongPressed) togglePlay()
                centerLongPressed = false
            }
        }
    }

    private fun step(direction: Int, isRepeat: Boolean) {
        val p = player ?: return
        val now = SystemClock.uptimeMillis()
        if (isRepeat && now - lastSeekAt < REPEAT_SEEK_INTERVAL_MS) return

        if (playing) {
            p.pause()
            playing = false
        }
        val max = if (duration > 0f) duration else Float.MAX_VALUE
        cursor = (cursor + direction * STEPS[stepIndex]).coerceIn(0f, max)
        lastSeekAt = now
        p.seekTo(cursor)
        updateStatus()
    }

    private fun togglePlay() {
        val p = player ?: return
        if (playing) {
            p.pause()
        } else {
            lastSeekAt = SystemClock.uptimeMillis()
            p.play()
        }
    }

    private fun changeStep(delta: Int) {
        stepIndex = (stepIndex + delta).coerceIn(0, STEPS.lastIndex)
        updateStatus()
    }

    private fun changeRate(delta: Int, wrap: Boolean) {
        rateIndex = if (wrap) {
            (rateIndex + delta).mod(RATES.size)
        } else {
            (rateIndex + delta).coerceIn(0, RATES.lastIndex)
        }
        player?.setPlaybackRate(RATES[rateIndex])
        updateStatus()
    }

    private fun updateStatus() {
        val state = if (playing) "▶" else "❚❚"
        val step = STEPS[stepIndex]
        val stepLabel = if (stepIndex == 0) "1格(30fps)" else String.format(Locale.US, "%.2fs", step)
        val rate = RATES[rateIndex].toFloat()
        val base = String.format(
            Locale.US,
            "%s  %s / %s    步距 %s    速度 %sx",
            state, formatTime(cursor), formatTime(duration), stepLabel, formatRate(rate),
        )
        statusText.text = errorMessage?.let { "$base    ⚠ $it" } ?: base
    }

    private fun formatTime(seconds: Float): String {
        val totalCentis = (seconds * 100).toInt()
        val minutes = totalCentis / 6000
        val secs = (totalCentis % 6000) / 100
        val centis = totalCentis % 100
        return String.format(Locale.US, "%d:%02d.%02d", minutes, secs, centis)
    }

    private fun formatRate(rate: Float): String =
        if (rate == rate.toInt().toFloat()) rate.toInt().toString() else rate.toString()
}
