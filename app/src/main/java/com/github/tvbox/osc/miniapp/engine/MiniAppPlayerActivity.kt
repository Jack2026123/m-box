package com.github.tvbox.osc.miniapp.engine

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.player.MyVideoView
import com.github.tvbox.osc.player.PlaybackService
import org.json.JSONObject

class MiniAppPlayerActivity : BaseActivity() {

    private var videoView: MyVideoView? = null
    private var playPauseButton: ImageButton? = null
    private var seekBar: SeekBar? = null
    private var timeText: TextView? = null
    private var userSeeking = false
    private var isFullScreen = false
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            progressHandler.postDelayed(this, 500L)
        }
    }

    override fun init() {
        // Player setup happens in onCreate after super.onCreate returns.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: ""
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val headersJson = intent.getStringExtra(EXTRA_HEADERS_JSON) ?: ""
        val headers = HashMap<String, String>()
        if (headersJson.isNotEmpty()) {
            try {
                val obj = JSONObject(headersJson)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    headers[key] = obj.optString(key)
                }
            } catch (e: Throwable) {
                // ignore malformed headers
            }
        }
        val container = FrameLayout(this)
        container.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setContentView(container)
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleFullscreen()
                return true
            }
        })
        container.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullScreen) {
                    toggleFullscreen()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        if (url.isEmpty()) {
            Toast.makeText(this, "播放地址为空", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        try {
            val view = PlaybackService.engine(this).player()
            (view.parent as? ViewGroup)?.removeView(view)
            view.setVideoController(null)
            container.addView(
                view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            videoView = view
            view.setUrl(url, headers)
            view.start()
            container.addView(buildControls(title))
            progressHandler.post(progressRunnable)
        } catch (e: Throwable) {
            Toast.makeText(this, "播放器启动失败：" + e.message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun buildControls(title: String): View {
        val root = FrameLayout(this)
        root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#80000000"))
            setPadding(24, 24, 24, 24)
        }
        val titleView = TextView(this).apply {
            text = title.ifEmpty { "小程序播放" }
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        val close = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        }
        top.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(close)
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#80000000"))
            setPadding(24, 24, 24, 24)
        }
        val playPause = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_pause)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                val v = videoView ?: return@setOnClickListener
                if (v.isPlaying) v.pause() else v.resume()
                updateProgress()
            }
        }
        val seek = SeekBar(this)
        val time = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) videoView?.seekTo(progress.toLong())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                userSeeking = true
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                userSeeking = false
            }
        })
        bottom.addView(playPause)
        bottom.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(time)
        playPauseButton = playPause
        seekBar = seek
        timeText = time
        val topParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.TOP }
        val bottomParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.BOTTOM }
        root.addView(top, topParams)
        root.addView(bottom, bottomParams)
        return root
    }

    private fun updateProgress() {
        val v = videoView ?: return
        val duration = v.duration
        val position = v.currentPosition
        playPauseButton?.setImageResource(
            if (v.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
        )
        val seek = seekBar ?: return
        if (duration > 0) {
            seek.visibility = View.VISIBLE
            seek.max = duration.toInt()
            if (!userSeeking) seek.progress = position.toInt()
            timeText?.text = formatTime(position) + " / " + formatTime(duration)
        } else {
            seek.visibility = View.GONE
            timeText?.text = formatTime(position)
        }
    }

    // 双击切全屏（与原生播放页同款临时覆写）：进全屏横屏＋藏系统栏，
    // 退出回方向策略档位；Manifest 已配 configChanges，旋转不重建、不丢进度。
    private fun toggleFullscreen() {
        isFullScreen = !isFullScreen
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (isFullScreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            requestedOrientation = orientationPolicyValue()
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        progressHandler.removeCallbacks(progressRunnable)
        super.onDestroy()
        val eng = PlaybackService.peek()
        if (eng != null && !eng.isReleased()) {
            eng.releasePlayer()
        } else {
            videoView?.release()
        }
        videoView = null
    }

    companion object {
        const val EXTRA_URL = "miniapp_play_url"
        const val EXTRA_HEADERS_JSON = "miniapp_play_headers"
        const val EXTRA_TITLE = "miniapp_play_title"
    }
}
