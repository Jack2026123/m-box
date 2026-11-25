package com.github.tvbox.osc.ui.music

import android.graphics.Color
import kotlin.math.abs

object MusicPlayerTheme {

    fun rollSeed(baseSeed: Int?, roll: Int): Int {
        val hsv = FloatArray(3)
        if (baseSeed != null) {
            Color.colorToHSV(baseSeed, hsv)
            hsv[0] = (hsv[0] + ((roll % 81) - 40).toFloat() + 360f) % 360f
            hsv[1] = hsv[1].coerceIn(0.35f, 0.85f)
            hsv[2] = hsv[2].coerceIn(0.55f, 0.9f)
            return Color.HSVToColor(hsv)
        }
        hsv[0] = (abs(roll) % 360).toFloat()
        hsv[1] = 0.6f
        hsv[2] = 0.85f
        return Color.HSVToColor(hsv)
    }

    fun shiftHue(argb: Int, degrees: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(argb, hsv)
        hsv[0] = (hsv[0] + degrees + 360f) % 360f
        return Color.HSVToColor(hsv)
    }
}
