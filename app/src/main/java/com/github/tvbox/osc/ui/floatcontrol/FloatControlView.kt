package com.github.tvbox.osc.ui.floatcontrol

import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.ui.page.ControlThemeSettingsContent
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.ui.theme.ViewTheme
import com.github.tvbox.osc.util.FloatControl

/**
 * 随心所欲的宿主 View：一个根 FrameLayout（自身不拦截触摸，空白处穿透），
 * 里面二选一显示悬浮球或随心所欲面板（FloatControlPanelView）。照 FloatLogView 抄。
 *
 * 悬浮球（与悬浮日志同款交互）：
 * - 球 56dp，写「主题」二字，配色跟当前主题走（主色底 + onPrimary 字，不写死固定色）；
 * - 拖动跟手（单轴 > 8px 才算拖动），拖动 1:1 顺手；
 * - 松手吸最近侧：松手时球还在半出屏状态才吸到最近的那边全藏（只露 16dp），
 *   只要已经拉到全露就停哪在哪，不再弹回去；
 * - 点击范围加大：球的触摸区用 TouchDelegate 向四周各扩 20dp；
 * - 位置保存（FloatControl.ballLeftPx / ballTopPx，半隐藏位置也保存），跨 Activity 复用；
 * - 没拖动的点按开面板；宿主尺寸变时按保存位置重停靠并钳制，免得出屏找不回。
 *
 * 面板（与悬浮日志不同处）：
 * - 几何：宽=宿主 92%、高=宿主 85%（组内容长，比日志面板高一截），默认居中；
 *   按住标题栏拖动整块面板，拖到哪停在哪，不回弹、不吸边，位置记入 FloatControl
 *   会话状态并钳制，下次打开还在上次停的位置；
 * - 面板内容就是设置页「随心所欲」组（ControlThemeSettingsContent），不另写一套；
 * - 没有全屏展开钮（悬浮日志有，任务没要，不加）。
 */
class FloatControlView(context: Context) : FrameLayout(context) {

    private val density = context.resources.displayMetrics.density

    private fun dp(v: Int): Int = (v * density).toInt()

    // 配色跟随主题（不写死固定色）。
    private val themePrimary = ViewTheme.primaryArgb(context)
    private val themeOnPrimary = ViewTheme.onPrimaryArgb(context)

    private val ballSize = dp(56)
    // 半隐藏时露出的宽度；点击范围用 TouchDelegate 在此之外再向四周各扩 20dp。
    private val ballVisiblePx = dp(16)
    private val ballTouchExpandPx = dp(20)
    private val ball = TextView(context)
    private val panelBox = FrameLayout(context)
    private val controlPanel = FloatControlPanelView(context)

    /** 球是否正在拖动（onLayout 重停靠时避让拖动中）。 */
    private var ballDragging = false

    /** 上次重停靠时的宿主宽高（尺寸变时才重停靠）。 */
    private var lastHostW = -1
    private var lastHostH = -1

    init {
        isClickable = false
        isFocusable = false
        buildBall()
        // 面板容器：尺寸/位置由 layoutPanel() 按宿主宽高算并钳制
        panelBox.layoutParams = LayoutParams(0, 0).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        panelBox.addView(
            controlPanel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        // 两个入口行为不同（照悬浮日志那套）：
        // 右下角“关闭”= 最小化回悬浮球（onClose）；标题栏 ✕ = 真正关闭
        // 随心所欲悬浮球（球和面板都不见，设置里重开开关才能回来）。
        controlPanel.onClose = { applyExpanded(false, initial = false) }
        controlPanel.onCloseControl = { FloatControl.setEnabled(false) }
        attachPanelDrag(controlPanel.titleBar)
        addView(ball)
        addView(panelBox)
        applyExpanded(FloatControl.uiExpanded, initial = true)
    }

    // ---------------- 悬浮球 ----------------

    private fun buildBall() {
        ball.text = "主题"
        ball.setTextColor(themeOnPrimary)
        ball.textSize = 12f
        ball.gravity = Gravity.CENTER
        ball.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(themePrimary)
        }
        ball.layoutParams = LayoutParams(ballSize, ballSize).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        var downRawX = 0f
        var downRawY = 0f
        var startLeft = 0
        var startTop = 0
        var moved = false
        ball.setOnTouchListener { v, event ->
            val lp = v.layoutParams as LayoutParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startLeft = lp.leftMargin
                    startTop = lp.topMargin
                    moved = false
                    ballDragging = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // 单轴位移 > 8px 才算拖动，够门限才跟手
                    val fx = event.rawX - downRawX
                    val fy = event.rawY - downRawY
                    if (Math.abs(fx) > 8f || Math.abs(fy) > 8f) {
                        moved = true
                        val parentW = width
                        val parentH = height
                        // 拖动中允许半出屏（松手时再定：半出屏吸全藏、全露停哪在哪）；
                        // 上下不半隐藏，纵向全程钳制在屏内。
                        val minLeft = -(ballSize - ballVisiblePx)
                        lp.leftMargin = (startLeft + fx.toInt()).coerceIn(minLeft, Math.max(minLeft, parentW - ballVisiblePx))
                        lp.topMargin = (startTop + fy.toInt()).coerceIn(0, Math.max(0, parentH - ballSize))
                        v.layoutParams = lp
                        updateBallTouchDelegate()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ballDragging = false
                    if (!moved) {
                        // 没拖动的点按开面板
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            applyExpanded(true, initial = false)
                        }
                    } else {
                        // 松手规则（与悬浮日志同款）：松手时球还在半出屏状态才吸到
                        // 最近的那边全藏（只露 16dp）；只要已经拉到全露就停哪在哪，
                        // 不再因为离边近就弹回去。纵向恒钳制在屏内。
                        // 位置保存供其它 Activity 复用。
                        val parentW = width
                        val parentH = height
                        if (lp.leftMargin < 0) {
                            lp.leftMargin = -(ballSize - ballVisiblePx)
                        } else if (lp.leftMargin > parentW - ballSize) {
                            lp.leftMargin = parentW - ballVisiblePx
                        } else {
                            lp.leftMargin = lp.leftMargin.coerceIn(0, Math.max(0, parentW - ballSize))
                        }
                        lp.topMargin = lp.topMargin.coerceIn(0, Math.max(0, parentH - ballSize))
                        v.layoutParams = lp
                        FloatControl.ballLeftPx = lp.leftMargin
                        FloatControl.ballTopPx = lp.topMargin
                        updateBallTouchDelegate()
                    }
                    true
                }
                else -> false
            }
        }
        post { placeBallInitial() }
    }

    private fun placeBallInitial() {
        if (width == 0 || height == 0) return
        val lp = ball.layoutParams as LayoutParams
        // 恢复时保留左右半隐藏位置（半隐藏左/右边距可为负或超右界）；
        // 纵向恒全露。横向至少露 16dp，免得出屏找不回。未保存过走默认
        // （右边缘全露、纵向 55%）。
        val minLeft = -(ballSize - ballVisiblePx)
        lp.leftMargin = if (FloatControl.ballLeftPx != -1) {
            FloatControl.ballLeftPx.coerceIn(minLeft, Math.max(minLeft, width - ballVisiblePx))
        } else {
            Math.max(0, width - ballSize)
        }
        lp.topMargin = if (FloatControl.ballTopPx != -1) {
            FloatControl.ballTopPx.coerceIn(0, Math.max(0, height - ballSize))
        } else {
            (height * 0.55f).toInt()
        }
        ball.layoutParams = lp
        updateBallTouchDelegate()
    }

    /**
     * 球的触摸区向四周各扩 20dp（半隐藏时手指落在露出条子附近也能抓住球）。
     * 球移动/重停靠/显隐后都要重算，矩形取球当前位置（父坐标）；
     * 球不可见时撤掉代理，免得空白处误触。
     */
    private fun updateBallTouchDelegate() {
        if (ball.visibility != View.VISIBLE) {
            touchDelegate = null
            return
        }
        val lp = ball.layoutParams as LayoutParams
        val rect = Rect(
            lp.leftMargin - ballTouchExpandPx,
            lp.topMargin - ballTouchExpandPx,
            lp.leftMargin + ballSize + ballTouchExpandPx,
            lp.topMargin + ballSize + ballTouchExpandPx,
        )
        touchDelegate = TouchDelegate(rect, ball)
    }

    // ---------------- 面板容器 ----------------

    /** 面板拖动：按住标题栏拖动整块面板，拖到哪停在哪，不回弹、不吸边（跟球吸边不同）；移动中即保存位置（钳制在屏内）。 */
    private fun attachPanelDrag(titleBar: View) {
        var downRawX = 0f
        var downRawY = 0f
        var startLeft = 0
        var startTop = 0
        var hasDown = false
        titleBar.setOnTouchListener { _, event ->
            val lp = panelBox.layoutParams as LayoutParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startLeft = lp.leftMargin
                    startTop = lp.topMargin
                    hasDown = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (hasDown) {
                        val dx = (event.rawX - downRawX).toInt()
                        val dy = (event.rawY - downRawY).toInt()
                        lp.leftMargin = (startLeft + dx).coerceIn(0, Math.max(0, width - panelBox.width))
                        lp.topMargin = (startTop + dy).coerceIn(0, Math.max(0, height - panelBox.height))
                        panelBox.layoutParams = lp
                        FloatControl.panelLeftPx = lp.leftMargin
                        FloatControl.panelTopPx = lp.topMargin
                        true
                    } else {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startLeft = lp.leftMargin
                        startTop = lp.topMargin
                        hasDown = true
                        true
                    }
                }
                else -> {
                    hasDown = false
                    false
                }
            }
        }
    }

    /**
     * 面板几何：宽=宿主 92%、高=宿主 85%（组内容长，比日志面板 70% 高一截）；
     * 无记忆位置时居中；宿主尺寸变化时按当前宽高重算并钳制，永不溢出。
     * 只记内存会话状态（FloatControl.panelLeftPx / panelTopPx，与悬浮日志同款，
     * 不写 KV；进程死了位置回默认居中，球的开关本身仍由 KV 记住）。
     */
    private fun layoutPanel(hostW: Int, hostH: Int) {
        val lp = panelBox.layoutParams as LayoutParams
        val pw = (hostW * PANEL_WIDTH_RATIO).toInt()
        val ph = (hostH * PANEL_HEIGHT_RATIO).toInt()
        var left = if (FloatControl.panelLeftPx >= 0) FloatControl.panelLeftPx else (hostW - pw) / 2
        var top = if (FloatControl.panelTopPx >= 0) FloatControl.panelTopPx else (hostH - ph) / 2
        left = left.coerceIn(0, Math.max(0, hostW - pw))
        top = top.coerceIn(0, Math.max(0, hostH - ph))
        if (lp.width != pw || lp.height != ph || lp.leftMargin != left || lp.topMargin != top) {
            lp.width = pw
            lp.height = ph
            lp.leftMargin = left
            lp.topMargin = top
            panelBox.layoutParams = lp
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val w = r - l
        val h = b - t
        if (w > 0 && h > 0) {
            layoutPanel(w, h)
            // 宿主尺寸变了（旋转/换页）且球可见又没在拖：按保存位置重停靠并钳制，免得出屏找不回
            if (ball.visibility == View.VISIBLE && !ballDragging &&
                (w != lastHostW || h != lastHostH)
            ) {
                placeBallInitial()
            }
            lastHostW = w
            lastHostH = h
        }
    }

    // ---------------- 展开 / 收起 ----------------

    private fun applyExpanded(expanded: Boolean, initial: Boolean) {
        FloatControl.uiExpanded = expanded
        if (expanded) {
            ball.visibility = View.GONE
            touchDelegate = null
            panelBox.visibility = View.VISIBLE
        } else {
            panelBox.visibility = View.GONE
            ball.visibility = View.VISIBLE
            if (!initial) placeBallInitial()
            updateBallTouchDelegate()
        }
    }

    /** 供 FloatControl.openPanel() 从外部打开面板。 */
    fun showPanel() {
        applyExpanded(true, initial = false)
    }

    companion object {
        private const val PANEL_WIDTH_RATIO = 0.92f
        private const val PANEL_HEIGHT_RATIO = 0.85f
    }
}

/**
 * 随心所欲面板（与 FloatControlView 同文件；DebugLogPanelView 是单文件，
 * 这里按任务只新建两个文件，面板放同文件）。
 *
 * 结构：标题栏（「随心所欲」+ ✕）+ Compose 内容区 + 底部「关闭」。
 * 背景是原生对话框同款（表面色 + 28dp 圆角，ViewTheme.dialogBackground，
 * 与悬浮日志 1.50 同款）；内容区是 ComposeView，里面直接放设置页抽出来的
 * ControlThemeSettingsContent()，不另写一套。
 */
class FloatControlPanelView(context: Context) : LinearLayout(context) {

    /** 右下角“关闭”：仅关闭面板（回悬浮球），由宿主 FloatControlView 接。 */
    var onClose: (() -> Unit)? = null

    /** 标题栏 ✕：真正关闭随心所欲悬浮球（球和面板都不见，设置里重开），由宿主接。 */
    var onCloseControl: (() -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    private val themePrimary = ViewTheme.primaryArgb(context)
    private val themeOnPrimary = ViewTheme.onPrimaryArgb(context)

    /** 标题栏（宿主拿它做面板拖动手柄）。 */
    val titleBar: LinearLayout = LinearLayout(context)

    private val composeHolder = FrameLayout(context)
    private val composeView = ComposeView(context)

    init {
        orientation = VERTICAL
        // 原生对话框同款表面色 + 28dp 圆角；clipToOutline 让内容不探出圆角。
        background = ViewTheme.dialogBackground(context)
        clipToOutline = true
        elevation = dp(8).toFloat()
        buildTitleBar()
        // 内容区：ComposeView 承载抽出来的组内容（与设置页共用同一份）；
        // AVBoxTheme 必须包一层——这是独立的 composition 根，不包就拿不到
        // App 主题色（会掉回 Material 默认紫）；manageStatusBarIcons 关掉，
        // 浮窗不碰 Activity 的状态栏。
        composeHolder.addView(
            composeView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        addView(
            composeHolder,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        composeView.setContent {
            AVBoxTheme(manageStatusBarIcons = false) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ControlThemeSettingsContent(isFloatingPanel = true)
                }
            }
        }
        buildBottomBar()
    }

    private fun buildTitleBar() {
        titleBar.orientation = HORIZONTAL
        titleBar.gravity = Gravity.CENTER_VERTICAL
        titleBar.setBackgroundColor(themePrimary)
        titleBar.setPadding(dp(12), dp(8), dp(12), dp(8))
        val title = TextView(context).apply {
            text = "随心所欲"
            setTextColor(themeOnPrimary)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleBar.addView(title)
        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(themeOnPrimary)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(6), dp(8), dp(6))
            isClickable = true
            setOnClickListener { onCloseControl?.invoke() }
        }
        titleBar.addView(closeBtn)
        addView(titleBar)
    }

    private fun buildBottomBar() {
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        val close = TextView(context).apply {
            setText(R.string.float_log_close)
            setTextColor(themePrimary)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
            setOnClickListener { onClose?.invoke() }
        }
        bar.addView(close)
        addView(bar)
    }
}
