package com.example.vbstepper

import android.content.Context
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/** 1/30 秒約等於 30fps 影片的一格。 */
const val ONE_FRAME_30FPS = 1f / 30f

enum class StepKind(
    val title: String,
    val prefKey: String,
    val defaultSeconds: Float,
    val presets: List<Float>,
) {
    SMALL("小步距", "small_step", 0.05f, listOf(ONE_FRAME_30FPS, 0.05f, 0.1f, 0.2f, 0.5f)),
    LARGE("大步距", "large_step", 1f, listOf(0.5f, 1f, 2f, 5f, 10f, 30f)),
}

/** 小步距、大步距存在 SharedPreferences，下次開啟沿用。 */
class StepSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun get(kind: StepKind): Float = prefs.getFloat(kind.prefKey, kind.defaultSeconds)

    fun set(kind: StepKind, seconds: Float) {
        prefs.edit().putFloat(kind.prefKey, seconds).apply()
    }

    companion object {
        const val MIN_SECONDS = 0.01f
        const val MAX_SECONDS = 600f

        /** 0.05 → "0.05s"，1 → "1s"，1/30 → "1格" */
        fun format(seconds: Float): String {
            if (abs(seconds - ONE_FRAME_30FPS) < 1e-4f) return "1格"
            val text = BigDecimal(seconds.toDouble())
                .setScale(3, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
            return "${text}s"
        }
    }
}
