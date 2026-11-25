package com.github.tvbox.osc.music

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.github.tvbox.osc.player.PlaybackService
import com.github.tvbox.osc.ui.activity.MusicPlayerActivity
import com.github.tvbox.osc.ui.music.LyricLine
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV

object FloatLyric {

    private const val TICK_MS = 400L
    private const val KEY_Y = "music_float_lyric_y"
    private const val LONG_PRESS_MS = 500L
    private const val MOVE_SLOP_PX = 8

    // 2.91 悬浮歌词显示模式：0=全屏，1=一行，2=两行，5=五行；默认两行。
    // 2.93：加半屏（4=半屏，悬浮窗半个屏幕高、可滚列表）。
    private const val MODE_FULLSCREEN = 0
    private const val MODE_1 = 1
    private const val MODE_2 = 2
    private const val MODE_5 = 5
    private const val MODE_HALF = 4
    private const val DOUBLE_TAP_MS = 300L
    private const val MANUAL_SCROLL_MS = 5000L

    @Volatile
    private var enabled = false

    @Volatile
    private var lines: List<LyricLine> = emptyList()

    @Volatile
    private var songTitle = ""
    private var songKey = ""

    private var windowManager: WindowManager? = null
    private var container: View? = null
    private var compactColumn: LinearLayout? = null
    private var lyricText: TextView? = null
    private var prev2Text: TextView? = null
    private var prev1Text: TextView? = null
    private var nextLyricText: TextView? = null
    private var next2Text: TextView? = null
    private var fullscreenScroll: ScrollView? = null
    private var fullscreenColumn: LinearLayout? = null
    private var fullscreenTitle: TextView? = null
    private var fullscreenLineViews: List<TextView> = emptyList()
    private var fullscreenBuiltFor: List<LyricLine>? = null
    private var menuView: LinearLayout? = null
    private var menuRows: List<TextView> = emptyList()
    private var menuVisible = false
    private var layoutParams: WindowManager.LayoutParams? = null
    private var lastLineIndex = -2
    private var lastShownTitle = ""
    private var density = 1f

    private var mode = MODE_2
    private var prevMode = MODE_2
    private var savedCompactY = 0

    // 2.93 小条手动滚歌词：上下滑翻行，5 秒内不跟回当前行；半屏/全屏列表手动滚后 5 秒内自动跟随不拽回去。
    private var manualOffset = 0
    private var manualScrollUntilMs = 0L
    private var lastListManualScrollMs = 0L

    private val main = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            updateLine()
            main.postDelayed(this, TICK_MS)
        }
    }

    // 2.93 长按抓取移动：手指按住 500ms 不动才抓起，抓起后拖动才移动窗口；
    // 没抓起就拖不动（防误触）；菜单改双击开，单击回播放页（延时 300ms 等双击）。
    private var longPressFired = false
    private var grabbed = false
    private var lastTapUpMs = 0L
    private val longPressRunnable = Runnable {
        longPressFired = true
        grabbed = true
        setGrabbedVisual(true)
    }
    private val pendingTapRunnable = Runnable {
        val ctx = container?.context
        if (ctx != null) openPlayer(ctx)
    }

    fun init(context: Context) {
        enabled = KV.get(HawkConfig.MUSIC_FLOAT_LYRIC, false)
        if (enabled && hasPermission(context)) show(context.applicationContext)
    }

    fun isEnabled(): Boolean = enabled

    fun hasPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun requestPermissionIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:" + context.packageName),
        )
    }

    fun setEnabled(context: Context, value: Boolean): Boolean {
        if (value && !hasPermission(context)) return false
        enabled = value
        KV.put(HawkConfig.MUSIC_FLOAT_LYRIC, value)
        val app = context.applicationContext
        if (value) show(app) else hide()
        return true
    }

    fun setLyrics(newLines: List<LyricLine>, title: String, key: String = "") {
        lines = newLines
        songTitle = title
        songKey = key
        lastLineIndex = -2
        lastShownTitle = ""
        manualOffset = 0
        manualScrollUntilMs = 0L
        lastListManualScrollMs = 0L
        if (enabled) {
            val ctx = container?.context
            if (ctx != null) updateLine()
        }
    }

    fun clearLyrics() {
        lines = emptyList()
        songTitle = ""
        songKey = ""
        lastLineIndex = -2
        lastShownTitle = ""
        manualOffset = 0
        manualScrollUntilMs = 0L
        lastListManualScrollMs = 0L
    }

    private fun normalizeMode(value: Int): Int {
        return when (value) {
            MODE_FULLSCREEN, MODE_1, MODE_2, MODE_5, MODE_HALF -> value
            else -> MODE_2
        }
    }

    // 2.93 圆角 + 拖动时变透明：平时圆角黑底，抓起拖动时更透明 + 淡白边；
    // 全屏满屏时直角（圆角会露出底下的 App）。
    private fun lyricBackgroundDrawable(grabbedVisual: Boolean): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.RECTANGLE
        drawable.cornerRadius = if (mode == MODE_FULLSCREEN) 0f else 16f * density
        if (grabbedVisual) {
            drawable.setColor(0x66000000.toInt())
            drawable.setStroke((1 * density).toInt(), 0x66FFFFFF.toInt())
        } else {
            drawable.setColor(0x99000000.toInt())
        }
        return drawable
    }

    private fun setGrabbedVisual(grabbedVisual: Boolean) {
        val root = container ?: return
        root.background = lyricBackgroundDrawable(grabbedVisual)
    }

    private fun openPlayer(context: Context) {
        try {
            val intent = Intent(context, MusicPlayerActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(intent)
        } catch (ignored: Throwable) {
        }
    }

    private fun showModeMenu() {
        val menu = menuView ?: return
        if (menuVisible) return
        menuVisible = true
        val labels = arrayOf("一行", "两行", "五行", "半屏", "全屏")
        val values = intArrayOf(MODE_1, MODE_2, MODE_5, MODE_HALF, MODE_FULLSCREEN)
        for (i in menuRows.indices) {
            menuRows[i].text = if (mode == values[i]) "✓ " + labels[i] else labels[i]
        }
        menu.visibility = View.VISIBLE
    }

    private fun hideModeMenu() {
        menuVisible = false
        menuView?.visibility = View.GONE
    }

    // 切模式：存 KV、立即生效。全屏/半屏进出时改窗口尺寸，来回切不丢之前行数模式。
    private fun applyMode(newMode: Int) {
        val target = normalizeMode(newMode)
        if (target != MODE_FULLSCREEN && target != MODE_HALF) prevMode = target
        mode = target
        if (target == MODE_FULLSCREEN || target == MODE_HALF) {
            manualOffset = 0
            manualScrollUntilMs = 0L
        }
        KV.put(HawkConfig.MUSIC_FLOAT_LYRIC_MODE, target)
        lastLineIndex = -2
        val root = container
        val wm = windowManager
        val params = layoutParams
        // 下一行在两行模式是 14sp 小字暗色，在五行模式是 16sp 近邻行，跟上一行对称。
        val nextTv = nextLyricText
        if (nextTv != null) {
            val pad = (12 * density).toInt()
            if (target == MODE_5) {
                nextTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                nextTv.setTextColor(0xB3FFFFFF.toInt())
                nextTv.setPadding(pad, (2 * density).toInt(), pad, (2 * density).toInt())
            } else {
                nextTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                nextTv.setTextColor(0x99FFFFFF.toInt())
                nextTv.setPadding(pad, (2 * density).toInt(), pad, (8 * density).toInt())
            }
        }
        if (root != null && wm != null && params != null) {
            if (target == MODE_FULLSCREEN) {
                savedCompactY = params.y
                params.height = WindowManager.LayoutParams.MATCH_PARENT
                params.y = 0
                try {
                    wm.updateViewLayout(root, params)
                } catch (ignored: Throwable) {
                }
                compactColumn?.visibility = View.GONE
                fullscreenScroll?.visibility = View.VISIBLE
            } else if (target == MODE_HALF) {
                savedCompactY = params.y
                val displayHeight = root.context.resources.displayMetrics.heightPixels
                params.height = displayHeight / 2
                params.y = (displayHeight - params.height) / 2
                try {
                    wm.updateViewLayout(root, params)
                } catch (ignored: Throwable) {
                }
                compactColumn?.visibility = View.GONE
                fullscreenScroll?.visibility = View.VISIBLE
            } else {
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                params.y = savedCompactY
                try {
                    wm.updateViewLayout(root, params)
                } catch (ignored: Throwable) {
                }
                fullscreenScroll?.visibility = View.GONE
                compactColumn?.visibility = View.VISIBLE
            }
        }
        setGrabbedVisual(false)
        updateLine()
    }

    private fun show(context: Context) {
        if (container != null) {
            main.removeCallbacks(tick)
            main.post(tick)
            return
        }
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm
            val density = context.resources.displayMetrics.density
            this.density = density
            val pad = (12 * density).toInt()
            val textSize = 20f
            mode = normalizeMode(KV.get(HawkConfig.MUSIC_FLOAT_LYRIC_MODE, MODE_2))

            val root = FrameLayout(context)
            root.background = lyricBackgroundDrawable(false)
            val column = LinearLayout(context)
            column.orientation = LinearLayout.VERTICAL
            val columnLp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            root.addView(column, columnLp)

            // 5 行模式用：当前行前面两行，越远越小越暗。
            val prev2Tv = TextView(context)
            prev2Tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            prev2Tv.setTextColor(0x66FFFFFF.toInt())
            prev2Tv.gravity = Gravity.CENTER
            prev2Tv.setPadding(pad, (2 * density).toInt(), pad, (2 * density).toInt())
            prev2Tv.text = ""
            prev2Tv.visibility = View.GONE
            column.addView(
                prev2Tv,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )

            val prev1Tv = TextView(context)
            prev1Tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            prev1Tv.setTextColor(0xB3FFFFFF.toInt())
            prev1Tv.gravity = Gravity.CENTER
            prev1Tv.setPadding(pad, (2 * density).toInt(), pad, (2 * density).toInt())
            prev1Tv.text = ""
            prev1Tv.visibility = View.GONE
            column.addView(
                prev1Tv,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )

            val tv = TextView(context)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
            tv.setTextColor(0xFFFFFFFF.toInt())
            tv.gravity = Gravity.CENTER
            tv.setPadding(pad, (8 * density).toInt(), pad, (2 * density).toInt())
            tv.text = songTitle.ifBlank { "♪" }
            column.addView(
                tv,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )

            val nextTv = TextView(context)
            nextTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            nextTv.setTextColor(0x99FFFFFF.toInt())
            nextTv.gravity = Gravity.CENTER
            nextTv.setPadding(pad, (2 * density).toInt(), pad, (8 * density).toInt())
            nextTv.text = ""
            nextTv.visibility = View.GONE
            column.addView(
                nextTv,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )

            val next2Tv = TextView(context)
            next2Tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            next2Tv.setTextColor(0x66FFFFFF.toInt())
            next2Tv.gravity = Gravity.CENTER
            next2Tv.setPadding(pad, (2 * density).toInt(), pad, (2 * density).toInt())
            next2Tv.text = ""
            next2Tv.visibility = View.GONE
            column.addView(
                next2Tv,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )

            // 全屏模式：可滚动的全歌词列表，当前行高亮跟随；标题单独放首行。
            val fsScroll = ScrollView(context)
            fsScroll.visibility = View.GONE
            val fsColumn = LinearLayout(context)
            fsColumn.orientation = LinearLayout.VERTICAL
            fsScroll.addView(
                fsColumn,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            val fsTitle = TextView(context)
            fsTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            fsTitle.setTextColor(0xFFFFFFFF.toInt())
            fsTitle.gravity = Gravity.CENTER
            fsTitle.setPadding(pad, (16 * density).toInt(), pad, (8 * density).toInt())
            fsTitle.text = songTitle.ifBlank { "♪" }
            fsColumn.addView(
                fsTitle,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            root.addView(
                fsScroll,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            // 2.93 全屏/半屏里的手势：半屏时长按抓起再拖动窗口（拖动时拦截滚动），
            // 全屏时长按仍出菜单（窗口满屏没法移）；点一下回播放页（延时 300ms 等双击），双击出菜单。
            var fsDownX = 0f
            var fsDownY = 0f
            var fsStartX = 0
            var fsStartY = 0
            var fsMoved = false
            var fsGrabbed = false
            var fsLongFired = false
            val fsLongRunnable = Runnable {
                fsLongFired = true
                if (mode == MODE_HALF) {
                    fsGrabbed = true
                    setGrabbedVisual(true)
                } else {
                    showModeMenu()
                }
            }
            fsScroll.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        fsDownX = event.rawX
                        fsDownY = event.rawY
                        val p = layoutParams
                        fsStartX = p?.x ?: 0
                        fsStartY = p?.y ?: 0
                        fsMoved = false
                        fsGrabbed = false
                        fsLongFired = false
                        main.postDelayed(fsLongRunnable, LONG_PRESS_MS)
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (fsGrabbed) {
                            val p = layoutParams
                            if (p != null) {
                                p.x = fsStartX + (event.rawX - fsDownX).toInt()
                                p.y = fsStartY + (event.rawY - fsDownY).toInt()
                                try {
                                    wm.updateViewLayout(root, p)
                                } catch (ignored: Throwable) {
                                }
                            }
                            true
                        } else {
                            if (Math.abs(event.rawX - fsDownX) > MOVE_SLOP_PX ||
                                Math.abs(event.rawY - fsDownY) > MOVE_SLOP_PX
                            ) {
                                if (!fsMoved) {
                                    fsMoved = true
                                    main.removeCallbacks(fsLongRunnable)
                                }
                                // 2.93 列表手动滚：竖拖就记时间，5 秒内自动跟随不拽回去。
                                if (Math.abs(event.rawY - fsDownY) > MOVE_SLOP_PX) {
                                    lastListManualScrollMs = android.os.SystemClock.uptimeMillis()
                                }
                            }
                            false
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        main.removeCallbacks(fsLongRunnable)
                        if (fsGrabbed) {
                            fsGrabbed = false
                            fsLongFired = false
                            setGrabbedVisual(false)
                            true
                        } else if (fsLongFired) {
                            fsLongFired = false
                            true
                        } else if (menuVisible) {
                            hideModeMenu()
                            lastTapUpMs = 0L
                            true
                        } else {
                            if (!fsMoved) {
                                val now = android.os.SystemClock.uptimeMillis()
                                if (now - lastTapUpMs < DOUBLE_TAP_MS) {
                                    main.removeCallbacks(pendingTapRunnable)
                                    lastTapUpMs = 0L
                                    showModeMenu()
                                } else {
                                    lastTapUpMs = now
                                    main.postDelayed(pendingTapRunnable, DOUBLE_TAP_MS)
                                }
                            }
                            false
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        main.removeCallbacks(fsLongRunnable)
                        fsLongFired = false
                        fsGrabbed = false
                        setGrabbedVisual(false)
                        false
                    }
                    else -> false
                }
            }

            val close = TextView(context)
            close.text = "✕"
            close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            close.setTextColor(0xAAFFFFFF.toInt())
            close.setPadding(pad, (4 * density).toInt(), pad, (4 * density).toInt())
            val closeLp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            closeLp.gravity = Gravity.END or Gravity.TOP
            root.addView(close, closeLp)
            // 全屏/半屏里点 ✕ 只退回上一个行数模式，不关悬浮窗；平时点 ✕ 才是关闭。
            close.setOnClickListener {
                if (mode == MODE_FULLSCREEN || mode == MODE_HALF) {
                    applyMode(prevMode)
                } else {
                    setEnabled(context, false)
                }
            }

            // 长按菜单：悬浮窗内的小弹窗，点外面或点取消就收起，不挡歌词。
            val menu = LinearLayout(context)
            menu.orientation = LinearLayout.VERTICAL
            menu.setBackgroundColor(0xEE1A1A1A.toInt())
            menu.minimumWidth = (160 * density).toInt()
            menu.visibility = View.GONE
            val menuLp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            menuLp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            menuLp.topMargin = (8 * density).toInt()
            root.addView(menu, menuLp)
            val modeLabels = arrayOf("一行", "两行", "五行", "半屏", "全屏")
            val modeValues = intArrayOf(MODE_1, MODE_2, MODE_5, MODE_HALF, MODE_FULLSCREEN)
            val rows = ArrayList<TextView>()
            for (i in modeLabels.indices) {
                val row = TextView(context)
                row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                row.setTextColor(0xFFFFFFFF.toInt())
                row.gravity = Gravity.CENTER
                row.setPadding(pad, (10 * density).toInt(), pad, (10 * density).toInt())
                row.text = modeLabels[i]
                val value = modeValues[i]
                row.setOnClickListener {
                    hideModeMenu()
                    applyMode(value)
                }
                menu.addView(
                    row,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                rows.add(row)
            }
            val cancelRow = TextView(context)
            cancelRow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            cancelRow.setTextColor(0xAAFFFFFF.toInt())
            cancelRow.gravity = Gravity.CENTER
            cancelRow.setPadding(pad, (10 * density).toInt(), pad, (10 * density).toInt())
            cancelRow.text = "取消"
            cancelRow.setOnClickListener { hideModeMenu() }
            menu.addView(
                cancelRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )

            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var moved = false
            var lastMoveY = 0f
            var scrollAccumDy = 0f
            root.setOnTouchListener { _, event ->
                val params = layoutParams ?: return@setOnTouchListener false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = params.x
                        startY = params.y
                        moved = false
                        longPressFired = false
                        grabbed = false
                        lastMoveY = event.rawY
                        scrollAccumDy = 0f
                        main.postDelayed(longPressRunnable, LONG_PRESS_MS)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downX).toInt()
                        val dy = (event.rawY - downY).toInt()
                        if (Math.abs(dx) > MOVE_SLOP_PX || Math.abs(dy) > MOVE_SLOP_PX) {
                            if (!moved) {
                                moved = true
                                main.removeCallbacks(longPressRunnable)
                                if (menuVisible) hideModeMenu()
                            }
                        }
                        if (grabbed) {
                            params.x = startX + dx
                            params.y = startY + dy
                            try {
                                wm.updateViewLayout(root, params)
                            } catch (ignored: Throwable) {
                            }
                        } else if (!menuVisible) {
                            // 2.93 小条手动滚歌词：竖滑 24dp 翻一行，上滑看后面、下滑看前面，5 秒内不跟回当前行。
                            val stepDy = event.rawY - lastMoveY
                            lastMoveY = event.rawY
                            scrollAccumDy += stepDy
                            val stepPx = 24f * density
                            val steps = (scrollAccumDy / stepPx).toInt()
                            if (steps != 0) {
                                scrollAccumDy -= steps * stepPx
                                if (lines.isNotEmpty()) {
                                    manualOffset = (manualOffset - steps).coerceIn(-1000, 1000)
                                    manualScrollUntilMs = android.os.SystemClock.uptimeMillis() + MANUAL_SCROLL_MS
                                    updateLine()
                                }
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        main.removeCallbacks(longPressRunnable)
                        if (grabbed) {
                            grabbed = false
                            longPressFired = false
                            setGrabbedVisual(false)
                            if (mode != MODE_FULLSCREEN && mode != MODE_HALF) {
                                KV.put(KEY_Y, params.y)
                                savedCompactY = params.y
                            }
                        } else if (menuVisible) {
                            hideModeMenu()
                            lastTapUpMs = 0L
                        } else if (!moved) {
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastTapUpMs < DOUBLE_TAP_MS) {
                                main.removeCallbacks(pendingTapRunnable)
                                lastTapUpMs = 0L
                                showModeMenu()
                            } else {
                                lastTapUpMs = now
                                main.postDelayed(pendingTapRunnable, DOUBLE_TAP_MS)
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        main.removeCallbacks(longPressRunnable)
                        longPressFired = false
                        grabbed = false
                        setGrabbedVisual(false)
                        true
                    }
                    else -> false
                }
            }

            val y = KV.get(KEY_Y, (100 * density).toInt())
            savedCompactY = y
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 0
            params.y = y
            layoutParams = params
            container = root
            compactColumn = column
            lyricText = tv
            prev2Text = prev2Tv
            prev1Text = prev1Tv
            nextLyricText = nextTv
            next2Text = next2Tv
            fullscreenScroll = fsScroll
            fullscreenColumn = fsColumn
            fullscreenTitle = fsTitle
            menuView = menu
            menuRows = rows
            wm.addView(root, params)
            // 存过全屏就按全屏打开，否则按行数模式打开。
            applyMode(mode)
            main.removeCallbacks(tick)
            main.post(tick)
        } catch (ignored: Throwable) {
            container = null
            compactColumn = null
            lyricText = null
            prev2Text = null
            prev1Text = null
            nextLyricText = null
            next2Text = null
            fullscreenScroll = null
            fullscreenColumn = null
            fullscreenTitle = null
            fullscreenLineViews = emptyList()
            fullscreenBuiltFor = null
            menuView = null
            menuRows = emptyList()
            menuVisible = false
            layoutParams = null
        }
    }

    fun hide() {
        main.removeCallbacks(tick)
        main.removeCallbacks(longPressRunnable)
        main.removeCallbacks(pendingTapRunnable)
        longPressFired = false
        grabbed = false
        lastTapUpMs = 0L
        menuVisible = false
        try {
            val wm = windowManager
            val view = container
            if (wm != null && view != null) wm.removeView(view)
        } catch (ignored: Throwable) {
        }
        container = null
        compactColumn = null
        lyricText = null
        prev2Text = null
        prev1Text = null
        nextLyricText = null
        next2Text = null
        fullscreenScroll = null
        fullscreenColumn = null
        fullscreenTitle = null
        fullscreenLineViews = emptyList()
        fullscreenBuiltFor = null
        menuView = null
        menuRows = emptyList()
        layoutParams = null
        windowManager = null
    }

    private fun updateLine() {
        val tv = lyricText ?: return
        val ctx = container?.context ?: return
        val currentLines = lines
        if (currentLines.isEmpty()) {
            // 没歌词时任何模式都只显示歌名。
            val title = songTitle
            if (title.isNotBlank() && title != lastShownTitle) {
                lastShownTitle = title
                tv.text = title
                fullscreenTitle?.text = title
            }
            fullscreenTitle?.visibility = View.VISIBLE
            for (lineView in fullscreenLineViews) {
                lineView.visibility = View.GONE
            }
            val nextTv = nextLyricText
            if (nextTv != null && nextTv.visibility != View.GONE) {
                nextTv.visibility = View.GONE
            }
            val prev1Tv = prev1Text
            if (prev1Tv != null && prev1Tv.visibility != View.GONE) {
                prev1Tv.visibility = View.GONE
            }
            val prev2Tv = prev2Text
            if (prev2Tv != null && prev2Tv.visibility != View.GONE) {
                prev2Tv.visibility = View.GONE
            }
            val next2Tv = next2Text
            if (next2Tv != null && next2Tv.visibility != View.GONE) {
                next2Tv.visibility = View.GONE
            }
            return
        }
        val positionMs = try {
            val engine = PlaybackService.engine(ctx)
            engine.player().currentPosition
        } catch (ignored: Throwable) {
            return
        }
        // 2.92 歌词按歌校准：与主界面共用同一套按歌映射
        val off = MusicLyricOffset.get(songKey)
        val index = MusicLyricOffset.lyricIndex(currentLines, positionMs, off)
        // 2.93 小条手动滚：5 秒内显示 index + 手动偏移，过期回零；半屏/全屏不走手动偏移。
        val now = android.os.SystemClock.uptimeMillis()
        if (now >= manualScrollUntilMs && manualOffset != 0) manualOffset = 0
        val displayIndex = if (now < manualScrollUntilMs) {
            (index + manualOffset).coerceIn(0, currentLines.lastIndex)
        } else {
            index
        }
        if (displayIndex == lastLineIndex) return
        lastLineIndex = displayIndex
        val text = lineText(currentLines, displayIndex)
        when (mode) {
            MODE_FULLSCREEN, MODE_HALF -> updateFullscreen(currentLines, index)
            MODE_1 -> {
                if (text != lastShownTitle) {
                    lastShownTitle = text
                    setLineTextAnimated(tv, text)
                }
                nextLyricText?.visibility = View.GONE
                prev1Text?.visibility = View.GONE
                prev2Text?.visibility = View.GONE
                next2Text?.visibility = View.GONE
            }
            MODE_5 -> {
                if (text != lastShownTitle) {
                    lastShownTitle = text
                    setLineTextAnimated(tv, text)
                }
                setLineTextAnimated(prev2Text, lineText(currentLines, displayIndex - 2))
                setLineTextAnimated(prev1Text, lineText(currentLines, displayIndex - 1))
                setLineTextAnimated(nextLyricText, lineText(currentLines, displayIndex + 1))
                setLineTextAnimated(next2Text, lineText(currentLines, displayIndex + 2))
            }
            else -> {
                // MODE_2：当前行大字 + 下一行小字暗色。
                if (text != lastShownTitle) {
                    lastShownTitle = text
                    setLineTextAnimated(tv, text)
                }
                setLineTextAnimated(nextLyricText, lineText(currentLines, displayIndex + 1))
                prev1Text?.visibility = View.GONE
                prev2Text?.visibility = View.GONE
                next2Text?.visibility = View.GONE
            }
        }
    }

    private fun lineText(currentLines: List<LyricLine>, index: Int): String {
        if (index < 0) return songTitle.ifBlank { "♪" }
        return currentLines.getOrNull(index)?.text ?: ""
    }

    // 2.93 换行平滑过渡：字变了才淡入 + 上滑 8dp，180ms。
    private fun setLineTextAnimated(view: TextView?, text: String) {
        if (view == null) return
        if (view.visibility != View.VISIBLE) {
            view.visibility = View.VISIBLE
        }
        if (view.text.toString() == text) return
        view.text = text
        view.alpha = 0f
        view.translationY = 8f * density
        view.animate().alpha(1f).translationY(0f).setDuration(180).start()
    }

    private fun updateFullscreen(currentLines: List<LyricLine>, index: Int) {
        val column = fullscreenColumn ?: return
        val scroll = fullscreenScroll ?: return
        val pad = (12 * density).toInt()
        if (fullscreenBuiltFor !== currentLines) {
            fullscreenBuiltFor = currentLines
            val count = column.childCount
            if (count > 1) column.removeViews(1, count - 1)
            val views = ArrayList<TextView>()
            for (line in currentLines) {
                val lineView = TextView(column.context)
                lineView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                lineView.setTextColor(0xAAFFFFFF.toInt())
                lineView.gravity = Gravity.CENTER
                lineView.setPadding(pad, (6 * density).toInt(), pad, (6 * density).toInt())
                lineView.text = line.text
                column.addView(
                    lineView,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                views.add(lineView)
            }
            fullscreenLineViews = views
        }
        for (i in fullscreenLineViews.indices) {
            val lineView = fullscreenLineViews[i]
            lineView.visibility = View.VISIBLE
            if (i == index) {
                lineView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                lineView.setTextColor(0xFFFFFFFF.toInt())
                lineView.setTypeface(Typeface.DEFAULT_BOLD)
            } else {
                lineView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                lineView.setTextColor(0xAAFFFFFF.toInt())
                lineView.setTypeface(Typeface.DEFAULT)
            }
        }
        // 2.93 列表手动滚后 5 秒内不自动跟随（高亮照常换，只是不拽滚动位置）。
        if (android.os.SystemClock.uptimeMillis() - lastListManualScrollMs >= MANUAL_SCROLL_MS) {
            val current = fullscreenLineViews.getOrNull(index)
            scroll.post {
                if (current != null) {
                    val y = (current.top - scroll.height / 2 + current.height / 2).coerceAtLeast(0)
                    scroll.smoothScrollTo(0, y)
                } else {
                    scroll.scrollTo(0, 0)
                }
            }
        }
    }

}
