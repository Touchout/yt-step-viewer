package com.example.vbstepper

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.toFloat
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import java.util.Locale
import kotlin.math.abs

/**
 * 遙控器操作：
 *  ←/→        後退／前進一個小步距（播放中會先暫停）
 *  ↓/↑        後退／前進一個大步距
 *  OK         播放／暫停
 *  長按 OK     設定選單（小步距、大步距、速度）
 *  ⏪/⏩       降低／提高速度（遙控器有這兩鍵時）
 *
 * 觸控裝置（手機、平板）會在資訊列多一排按鈕，做同樣的事。
 * 進度條可以拖動，手指停住時播放器會跳到該位置當作預覽。
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_ID = "video_id"

        private val RATES = listOf(
            PlayerConstants.PlaybackRate.RATE_0_25,
            PlayerConstants.PlaybackRate.RATE_0_5,
            PlayerConstants.PlaybackRate.RATE_1,
        )
        private const val DEFAULT_RATE_INDEX = 2 // 1x

        /** seek 之後這段時間內忽略播放器回報的時間，避免舊值蓋掉自己維護的游標。 */
        private const val SEEK_SETTLE_MS = 600L

        /** 按住步進鍵時，限制 seek 的頻率，避免塞爆播放器。 */
        private const val REPEAT_SEEK_INTERVAL_MS = 120L

        /** 觸控按住步進鍵多久後開始連續步進。 */
        private const val TOUCH_REPEAT_DELAY_MS = 400L

        /**
         * 拖動進度條時，手指停在原地多久才送 seek。
         * 移動中不送：每次 seek 都會打斷上一次的載入，連續送反而永遠看不到畫面。
         */
        private const val SCRUB_DWELL_MS = 150L

        /** 手指移動小於這個距離視為「停住」，忽略觸控抖動。 */
        private const val SCRUB_JITTER_DP = 6f
    }

    private lateinit var playerView: YouTubePlayerView
    private lateinit var statusText: TextView
    private lateinit var playButton: Button
    private lateinit var smallStepButton: Button
    private lateinit var largeStepButton: Button
    private lateinit var rateButton: Button
    private lateinit var seekBar: SeekBar
    private lateinit var stepSettings: StepSettings
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
    /** 拖動中最後一次真正送出 seek 的進度值（毫秒）；-1 代表這次拖動還沒送過。 */
    private var lastScrubTarget = -1
    /** 目前等待停留計時的進度值，用來判斷手指是否還在同一個地方。 */
    private var dwellAnchor = -1
    private val dwellSeek = Runnable { scrubSeekTo(seekBar.progress) }

    private var rateIndex = DEFAULT_RATE_INDEX
    private var centerLongPressed = false
    private var errorMessage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.playerView)
        statusText = findViewById(R.id.statusText)
        playButton = findViewById(R.id.playButton)
        smallStepButton = findViewById(R.id.smallStepButton)
        largeStepButton = findViewById(R.id.largeStepButton)
        rateButton = findViewById(R.id.rateButton)
        seekBar = findViewById(R.id.seekBar)
        stepSettings = StepSettings(this)
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

    // ---- 進度條 ----

    private fun setupSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) {
                scrubbing = true
                resumeAfterScrub = playing
                lastScrubTarget = -1
                dwellAnchor = -1
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
                if (!scrubbing) {
                    // 點一下進度條（沒有拖動）就直接跳
                    scrubSeekTo(progress)
                    return
                }
                // 抖動範圍內的移動不重設計時，否則手指永遠「沒停住」
                if (dwellAnchor >= 0 && abs(progress - dwellAnchor) <= jitterTolerance()) return
                dwellAnchor = progress
                handler.removeCallbacks(dwellSeek)
                handler.postDelayed(dwellSeek, SCRUB_DWELL_MS)
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                scrubbing = false
                handler.removeCallbacks(dwellSeek)
                val target = bar.progress
                if (lastScrubTarget >= 0 && abs(target - lastScrubTarget) <= jitterTolerance()) {
                    // 放開的位置就是剛剛預覽的位置，不再 seek，免得打斷正在載入的畫面
                    cursor = lastScrubTarget / 1000f
                    updateStatus()
                } else {
                    scrubSeekTo(target)
                }
                if (resumeAfterScrub) player?.play()
            }
        })
    }

    /** 手指抖動容許值，換算成進度條的單位（毫秒）。 */
    private fun jitterTolerance(): Int {
        val trackPx = (seekBar.width - seekBar.paddingLeft - seekBar.paddingRight).coerceAtLeast(1)
        val jitterPx = SCRUB_JITTER_DP * resources.displayMetrics.density
        return (seekBar.max * jitterPx / trackPx).toInt()
    }

    private fun scrubSeekTo(progress: Int) {
        val p = player ?: return
        lastScrubTarget = progress
        cursor = progress / 1000f
        lastSeekAt = SystemClock.uptimeMillis()
        p.seekTo(cursor)
        updateStatus()
    }

    // ---- 觸控按鈕 ----

    private fun isTouchDevice(): Boolean {
        val uiMode = getSystemService(UiModeManager::class.java).currentModeType
        return uiMode != Configuration.UI_MODE_TYPE_TELEVISION &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    private fun setupTouchControls() {
        findViewById<View>(R.id.touchControls).visibility = View.VISIBLE
        findViewById<TextView>(R.id.helpText).setText(R.string.help_touch)

        bindHoldToStep(findViewById(R.id.largeBackButton), StepKind.LARGE, -1)
        bindHoldToStep(findViewById(R.id.smallBackButton), StepKind.SMALL, -1)
        bindHoldToStep(findViewById(R.id.smallForwardButton), StepKind.SMALL, +1)
        bindHoldToStep(findViewById(R.id.largeForwardButton), StepKind.LARGE, +1)
        playButton.setOnClickListener { togglePlay() }
        smallStepButton.setOnClickListener { showStepPicker(StepKind.SMALL) }
        largeStepButton.setOnClickListener { showStepPicker(StepKind.LARGE) }
        rateButton.setOnClickListener { changeRate(+1, wrap = true) }
    }

    /** 點一下步進一次；按住超過 [TOUCH_REPEAT_DELAY_MS] 就連續步進，和遙控器按住一樣。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindHoldToStep(button: Button, kind: StepKind, direction: Int) {
        val repeat = object : Runnable {
            override fun run() {
                step(kind, direction, isRepeat = true)
                handler.postDelayed(this, REPEAT_SEEK_INTERVAL_MS)
            }
        }
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    step(kind, direction, isRepeat = false)
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

    // ---- 設定選單 ----

    /** 遙控器長按 OK 開啟的總選單。 */
    private fun showSettingsMenu() {
        val items = arrayOf(
            "${StepKind.SMALL.title}：${StepSettings.format(stepSettings.get(StepKind.SMALL))}",
            "${StepKind.LARGE.title}：${StepSettings.format(stepSettings.get(StepKind.LARGE))}",
            "速度：${formatRate(RATES[rateIndex].toFloat())}x",
        )
        AlertDialog.Builder(this)
            .setTitle("設定")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showStepPicker(StepKind.SMALL)
                    1 -> showStepPicker(StepKind.LARGE)
                    2 -> showRatePicker()
                }
            }
            .show()
    }

    private fun showStepPicker(kind: StepKind) {
        val current = stepSettings.get(kind)
        val labels = kind.presets.map { preset ->
            val label = if (preset == ONE_FRAME_30FPS) "1格（1/30 秒）" else StepSettings.format(preset)
            if (abs(preset - current) < 1e-4f) "$label  ✓" else label
        } + "自訂…"
        AlertDialog.Builder(this)
            .setTitle(kind.title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < kind.presets.size) {
                    setStep(kind, kind.presets[which])
                } else {
                    showCustomStepInput(kind)
                }
            }
            .show()
    }

    private fun showCustomStepInput(kind: StepKind) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "秒，例如 0.03"
            setText(StepSettings.format(stepSettings.get(kind)).removeSuffix("s").takeIf { it != "1格" })
            setSelectAllOnFocus(true)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("自訂${kind.title}（秒）")
            .setView(input)
            .setPositiveButton("確定") { _, _ ->
                val seconds = input.text.toString().trim().toFloatOrNull()
                if (seconds == null || seconds < StepSettings.MIN_SECONDS || seconds > StepSettings.MAX_SECONDS) {
                    Toast.makeText(
                        this,
                        "請輸入 ${StepSettings.MIN_SECONDS} 到 ${StepSettings.MAX_SECONDS.toInt()} 秒之間的數字",
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    setStep(kind, seconds)
                }
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private fun showRatePicker() {
        val labels = RATES.mapIndexed { i, rate ->
            val label = "${formatRate(rate.toFloat())}x"
            if (i == rateIndex) "$label  ✓" else label
        }
        AlertDialog.Builder(this)
            .setTitle("速度")
            .setItems(labels.toTypedArray()) { _, which -> changeRate(which - rateIndex, wrap = false) }
            .show()
    }

    private fun setStep(kind: StepKind, seconds: Float) {
        stepSettings.set(kind, seconds)
        updateStatus()
    }

    // ---- 遙控器 ----

    // 在 Activity 層攔截按鍵，不讓 WebView 吃掉 DPAD 事件。
    // 對話框開著時按鍵會送到對話框自己的視窗，不會經過這裡。
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0
        val repeat = event.repeatCount > 0
        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) step(StepKind.SMALL, -1, repeat); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) step(StepKind.SMALL, +1, repeat); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) step(StepKind.LARGE, -1, repeat); true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (down) step(StepKind.LARGE, +1, repeat); true }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> { handleCenter(event); true }
            KeyEvent.KEYCODE_MENU -> { if (first) showSettingsMenu(); true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (first) togglePlay(); true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (first && !playing) togglePlay(); true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (first && playing) togglePlay(); true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (first) changeRate(+1, wrap = false); true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (first) changeRate(-1, wrap = false); true }
            else -> false
        }
        return handled || super.dispatchKeyEvent(event)
    }

    /** 短按 OK 播放／暫停；按住超過系統 repeat 延遲就當長按，開設定選單。 */
    private fun handleCenter(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    centerLongPressed = false
                } else if (!centerLongPressed) {
                    centerLongPressed = true
                    showSettingsMenu()
                }
            }
            KeyEvent.ACTION_UP -> {
                if (!centerLongPressed) togglePlay()
                centerLongPressed = false
            }
        }
    }

    // ---- 播放控制 ----

    private fun step(kind: StepKind, direction: Int, isRepeat: Boolean) {
        val p = player ?: return
        if (scrubbing) return
        val now = SystemClock.uptimeMillis()
        if (isRepeat && now - lastSeekAt < REPEAT_SEEK_INTERVAL_MS) return

        if (playing) {
            p.pause()
            playing = false
        }
        val max = if (duration > 0f) duration else Float.MAX_VALUE
        cursor = (cursor + direction * stepSettings.get(kind)).coerceIn(0f, max)
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
        val small = StepSettings.format(stepSettings.get(StepKind.SMALL))
        val large = StepSettings.format(stepSettings.get(StepKind.LARGE))
        val rate = formatRate(RATES[rateIndex].toFloat())
        val base = String.format(
            Locale.US,
            "%s  %s / %s    小步 %s  大步 %s    速度 %sx",
            state, formatTime(cursor), formatTime(duration), small, large, rate,
        )
        statusText.text = errorMessage?.let { "$base    ⚠ $it" } ?: base

        if (!scrubbing) seekBar.progress = (cursor * 1000).toInt()

        playButton.text = if (playing) "❚❚" else "▶"
        smallStepButton.text = "小 $small"
        largeStepButton.text = "大 $large"
        rateButton.text = "${rate}x"
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
