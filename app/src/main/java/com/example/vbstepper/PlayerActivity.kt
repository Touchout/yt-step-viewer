package com.example.vbstepper

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.SeekBar
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
 *
 * 觸控裝置（手機、平板）會在資訊列多一排按鈕，做同樣的事。
 * 進度條可以拖動，拖動時播放器會跟著跳，當作即時預覽。
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

        /** 觸控按住步進鍵多久後開始連續步進。 */
        private const val TOUCH_REPEAT_DELAY_MS = 400L

        /**
         * 拖動進度條時送 seek 的最小間隔。太密會讓播放器排隊、反而更慢；
         * 間隔內的移動會合併，只送最後的位置。
         */
        private const val SCRUB_SEEK_INTERVAL_MS = 100L
    }

    private lateinit var playerView: YouTubePlayerView
    private lateinit var statusText: TextView
    private lateinit var playButton: Button
    private lateinit var stepButton: Button
    private lateinit var rateButton: Button
    private lateinit var seekBar: SeekBar
    private val handler = Handler(Looper.getMainLooper())

    private var player: YouTubePlayer? = null
    private var videoId = ""

    // getCurrentTime() 是非同步的，快速連按會讀到舊值，所以自己維護目前時間。
    private var cursor = 0f
    private var duration = 0f
    private var playing = false
    private var lastSeekAt = 0L

    // 進度條拖動狀態
    private var scrubbing = false
    private var resumeAfterScrub = false
    private var lastScrubSeekAt = 0L
    private val pendingScrubSeek = Runnable { scrubSeekNow() }

    private var stepIndex = DEFAULT_STEP_INDEX
    private var rateIndex = DEFAULT_RATE_INDEX
    private var centerLongPressed = false
    private var errorMessage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.playerView)
        statusText = findViewById(R.id.statusText)
        playButton = findViewById(R.id.playButton)
        stepButton = findViewById(R.id.stepButton)
        rateButton = findViewById(R.id.rateButton)
        seekBar = findViewById(R.id.seekBar)
        setupSeekBar()
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
                if (playing && !scrubbing && SystemClock.uptimeMillis() - lastSeekAt > SEEK_SETTLE_MS) {
                    cursor = second
                    updateStatus()
                }
            }

            override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) {
                this@PlayerActivity.duration = duration
                seekBar.max = (duration * 1000).toInt()
                updateStatus()
            }

            override fun onVideoLoadedFraction(youTubePlayer: YouTubePlayer, loadedFraction: Float) {
                // 已緩衝的範圍，拖到這段內預覽會特別快
                seekBar.secondaryProgress = (loadedFraction * seekBar.max).toInt()
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

        if (isTouchDevice()) setupTouchControls()
        updateStatus()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun setupSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) {
                scrubbing = true
                resumeAfterScrub = playing
                // 暫停後 seek 才會停在那一格給你看
                if (playing) {
                    player?.pause()
                    playing = false
                }
            }

            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                cursor = progress / 1000f
                updateStatus()
                scheduleScrubSeek()
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                scrubbing = false
                handler.removeCallbacks(pendingScrubSeek)
                scrubSeekNow()
                if (resumeAfterScrub) player?.play()
            }
        })
    }

    /** 節流：距離上次 seek 夠久就馬上送，否則排到間隔結束時送最新位置。 */
    private fun scheduleScrubSeek() {
        handler.removeCallbacks(pendingScrubSeek)
        val wait = lastScrubSeekAt + SCRUB_SEEK_INTERVAL_MS - SystemClock.uptimeMillis()
        if (wait <= 0) scrubSeekNow() else handler.postDelayed(pendingScrubSeek, wait)
    }

    private fun scrubSeekNow() {
        val p = player ?: return
        val now = SystemClock.uptimeMillis()
        lastScrubSeekAt = now
        lastSeekAt = now
        p.seekTo(cursor)
    }

    private fun isTouchDevice(): Boolean {
        val uiMode = getSystemService(UiModeManager::class.java).currentModeType
        return uiMode != Configuration.UI_MODE_TYPE_TELEVISION &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    private fun setupTouchControls() {
        findViewById<View>(R.id.touchControls).visibility = View.VISIBLE
        findViewById<TextView>(R.id.helpText).setText(R.string.help_touch)

        bindHoldToStep(findViewById(R.id.backButton), -1)
        bindHoldToStep(findViewById(R.id.forwardButton), +1)
        playButton.setOnClickListener { togglePlay() }
        stepButton.setOnClickListener { changeStep(+1, wrap = true) }
        rateButton.setOnClickListener { changeRate(+1, wrap = true) }
    }

    /** 點一下步進一次；按住超過 [TOUCH_REPEAT_DELAY_MS] 就連續步進，和遙控器按住一樣。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindHoldToStep(button: Button, direction: Int) {
        val repeat = object : Runnable {
            override fun run() {
                step(direction, isRepeat = true)
                handler.postDelayed(this, REPEAT_SEEK_INTERVAL_MS)
            }
        }
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    step(direction, isRepeat = false)
                    handler.postDelayed(repeat, TOUCH_REPEAT_DELAY_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(repeat)
                }
            }
            true
        }
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
        if (scrubbing) return
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

    private fun changeStep(delta: Int, wrap: Boolean = false) {
        stepIndex = if (wrap) {
            (stepIndex + delta).mod(STEPS.size)
        } else {
            (stepIndex + delta).coerceIn(0, STEPS.lastIndex)
        }
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

        if (!scrubbing) seekBar.progress = (cursor * 1000).toInt()

        playButton.text = if (playing) "❚❚ 暫停" else "▶ 播放"
        stepButton.text = "步距 $stepLabel"
        rateButton.text = "速度 ${formatRate(rate)}x"
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
