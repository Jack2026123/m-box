package com.github.tvbox.osc.music

import android.media.audiofx.Visualizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MusicVisualizer {

    var level by mutableStateOf(0f)
        private set

    var echoLevel by mutableStateOf(0f)
        private set

    var available by mutableStateOf(false)
        private set

    private var visualizer: Visualizer? = null

    fun start() {
        if (visualizer != null) return
        try {
            val viz = Visualizer(0)
            viz.setCaptureSize(Visualizer.getCaptureSizeRange()[0])
            viz.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED)
            viz.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        viz: Visualizer,
                        waveform: ByteArray,
                        samplingRate: Int,
                    ) {
                        updateLevel(waveform)
                    }

                    override fun onFftDataCapture(
                        viz: Visualizer,
                        fft: ByteArray,
                        samplingRate: Int,
                    ) {
                    }
                },
                Visualizer.getMaxCaptureRate(),
                true,
                false,
            )
            viz.enabled = true
            visualizer = viz
            available = true
        } catch (th: Throwable) {
            available = false
            stop()
        }
    }

    fun stop() {
        runCatching { visualizer?.enabled = false }
        runCatching { visualizer?.release() }
        visualizer = null
        level = 0f
        echoLevel = 0f
    }

    private fun updateLevel(waveform: ByteArray) {
        if (waveform.isEmpty()) return
        var sum = 0.0
        for (b in waveform) {
            val v = (b.toInt() and 0xFF) - 128
            sum += v.toDouble() * v.toDouble()
        }
        val rms = kotlin.math.sqrt(sum / waveform.size) / 128.0
        val gated = if (rms < 0.02) 0.0 else rms
        val target = gated.toFloat().coerceIn(0f, 1f)
        level = if (target > level) target else level + (target - level) * 0.08f
        echoLevel = if (target > echoLevel) {
            echoLevel + (target - echoLevel) * 0.25f
        } else {
            echoLevel + (target - echoLevel) * 0.03f
        }
    }
}
