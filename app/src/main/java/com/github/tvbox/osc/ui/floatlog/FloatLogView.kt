package com.github.tvbox.osc.ui.floatlog

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.github.tvbox.osc.ui.dialog.AiTaskBoard
import com.github.tvbox.osc.ui.theme.ViewTheme
import com.github.tvbox.osc.util.FloatLog

/**
 * 悬浮日志的宿主 View：一个根 FrameLayout（自身不拦截触摸，空白处穿透），
 * 里面二选一显示悬浮球或页签式调试日志面板（DebugLogPanelView）。
 *
 * 悬浮球（1.50 定：靠边隐藏保留，但拉出来停住、推进去藏好）：
 * - 拖动跟手（单轴 > 8px 才算拖动，与 webhtv 原版门限一致），拖动 1:1 顺手；
 * - 靠边隐藏：平时拖到左右边上松手就贴边半隐藏（球大部份藏到屏外只露 16dp），
 *   但拉三回才拉出来的毛病要修——松手规则改成：松手时球还在半出屏状态才吸全藏，
 *   只要已经拉到全露就停哪在哪，不再弹回去；
 * - 点击范围加大：球的触摸区用 TouchDelegate 向屏内方向外扩 20dp，
 *   半隐藏时手指落在露出的条子附近也能抓住球；
 * - 位置保存（FloatLog.ballLeftPx / ballTopPx，半隐藏位置也保存），跨 Activity 复用；
 * - 没拖动的点按开面板；宿主尺寸变时按保存位置重停靠并钳制，免得出屏找不回。
 *
 * 面板（1.38 新建页签式调试日志，取代 1.37 浮动运行日志面板）：
 * - 几何：宽=宿主 92%、高=宿主 70%，默认居中；按住标题栏拖动整块面板，
 *   位置记入 FloatLog 会话状态并钳制；展开钮切全屏/还原；
 * - 面板内容（标题/页签/过滤/条目/计数/底栏）全在 DebugLogPanelView 里，
 *   本类只管球、面板容器、拖动与显隐。
 */
class FloatLogView(context: Context) : FrameLayout(context) {

    private val density = context.resources.displayMetrics.density

    private fun dp(v: Int): Int = (v * density).toInt()

    // 1.37：配色跟随主题（1.36 写死昏黄蓝/白/黑作废，用户纠正）。
    private val themePrimary = ViewTheme.primaryArgb(context)
    private val themeOnPrimary = ViewTheme.onPrimaryArgb(context)

    private val ballSize = dp(56)
    // 1.43 靠边隐藏：半隐藏时露出的宽度（可抓的一小部分）；1.50 点击范围
    // 用 TouchDelegate 在此之外再向屏内扩 20dp，半隐藏的球也好抓。
    private val ballVisiblePx = dp(16)
    private val ballTouchExpandPx = dp(20)
    private val ball = TextView(context)
    private val panelBox = FrameLayout(context)
    private val debugPanel = DebugLogPanelView(context)

    /** 球是否正在拖动（onLayout 重停靠时避让拖动中）。 */
    private var ballDragging = false

    /** 上次重停靠时的宿主宽高（尺寸变时才重停靠）。 */
    private var lastHostW = -1
    private var lastHostH = -1

    /** 面板是否全屏（展开钮切换）。 */
    private var panelFullscreen = false

    init {
        isClickable = false
        isFocusable = false
        buildBall()
        // 面板容器：尺寸/位置由 layoutPanel() 按宿主宽高算并钳制
        panelBox.layoutParams = LayoutParams(0, 0).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        panelBox.addView(
            debugPanel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        // 1.50：只改一个 ✕——标题栏 ✕ 是真正关闭日志（关掉悬浮日志：球和面板
        // 都不见，设置里重开）；右下角关闭不动，仍是回悬浮球的最小化（onClose）。
        debugPanel.onClose = { applyExpanded(false, initial = false) }
        debugPanel.onCloseLog = { FloatLog.setEnabled(false) }
        debugPanel.onExpandToggle = { togglePanelFullscreen() }
        attachPanelDrag(debugPanel.titleBar)
        addView(ball)
        addView(panelBox)
        applyExpanded(FloatLog.uiExpanded, initial = true)
    }

    // ---------------- 悬浮球（1.43 两者合一） ----------------

    private fun buildBall() {
        ball.text = if (AiTaskBoard.activeTitle != null) "AI" else "日志"
        AiTaskBoard.onChanged = {
            ball.text = if (AiTaskBoard.activeTitle != null) "AI" else "日志"
        }
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
                    // 原版 Touch 门限：单轴位移 > 8px 才算拖动，够门限才跟手
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
                        // 没拖动的点按：AI 任务在跑时点回 AI 面板，否则开日志面板
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            val aiReopen = AiTaskBoard.reopen
                            if (AiTaskBoard.activeTitle != null && aiReopen != null) {
                                aiReopen(context)
                            } else {
                                applyExpanded(true, initial = false)
                            }
                        }
                    } else {
                        // 1.50 松手规则（修拉三回）：松手时球还在半出屏状态才吸到
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
                        FloatLog.ballLeftPx = lp.leftMargin
                        FloatLog.ballTopPx = lp.topMargin
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
        lp.leftMargin = if (FloatLog.ballLeftPx != -1) {
            FloatLog.ballLeftPx.coerceIn(minLeft, Math.max(minLeft, width - ballVisiblePx))
        } else {
            Math.max(0, width - ballSize)
        }
        lp.topMargin = if (FloatLog.ballTopPx != -1) {
            FloatLog.ballTopPx.coerceIn(0, Math.max(0, height - ballSize))
        } else {
            (height * 0.55f).toInt()
        }
        ball.layoutParams = lp
        updateBallTouchDelegate()
    }

    /**
     * 1.50 点击范围加大：球的触摸区向四周各扩 20dp（半隐藏时手指落在露出
     * 条子附近也能抓住球）。球移动/重停靠/显隐后都要重算，矩形取球当前
     * 位置（父坐标）；球不可见时撤掉代理，免得空白处误触。
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

    /** 面板拖动：按住标题栏拖动整块面板，移动中即保存位置（AVBox 钳制，原版允许拖出屏幕）。 */
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
                        FloatLog.panelLeftPx = lp.leftMargin
                        FloatLog.panelTopPx = lp.topMargin
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
     * 面板几何：宽=宿主 92%、高=宿主 70%；无记忆位置时居中；
     * 全屏时铺满宿主。宿主尺寸变化时按当前宽高重算并钳制，永不溢出。
     */
    private fun layoutPanel(hostW: Int, hostH: Int) {
        val lp = panelBox.layoutParams as LayoutParams
        if (panelFullscreen) {
            if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT ||
                lp.height != ViewGroup.LayoutParams.MATCH_PARENT ||
                lp.leftMargin != 0 || lp.topMargin != 0
            ) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                lp.leftMargin = 0
                lp.topMargin = 0
                panelBox.layoutParams = lp
            }
            return
        }
        val pw = (hostW * PANEL_WIDTH_RATIO).toInt()
        val ph = (hostH * PANEL_HEIGHT_RATIO).toInt()
        var left = if (FloatLog.panelLeftPx >= 0) FloatLog.panelLeftPx else (hostW - pw) / 2
        var top = if (FloatLog.panelTopPx >= 0) FloatLog.panelTopPx else (hostH - ph) / 2
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

    /** 展开钮：全屏/还原切换；还原时按 92%×70% 重算（保留拖动位置）。 */
    private fun togglePanelFullscreen() {
        panelFullscreen = !panelFullscreen
        if (panelFullscreen) {
            val lp = panelBox.layoutParams as LayoutParams
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.leftMargin = 0
            lp.topMargin = 0
            panelBox.layoutParams = lp
        } else {
            val w = lastHostW
            val h = lastHostH
            if (w > 0 && h > 0) {
                layoutPanel(w, h)
            } else {
                requestLayout()
            }
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
        FloatLog.uiExpanded = expanded
        if (expanded) {
            ball.visibility = View.GONE
            touchDelegate = null
            panelBox.visibility = View.VISIBLE
            debugPanel.onShow()
        } else {
            debugPanel.onHide()
            panelBox.visibility = View.GONE
            ball.visibility = View.VISIBLE
            if (!initial) placeBallInitial()
            updateBallTouchDelegate()
        }
    }

    /** 供 FloatLog.openPanel() 从外部（设置入口）打开面板。 */
    fun showPanel() {
        applyExpanded(true, initial = false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (FloatLog.uiExpanded) debugPanel.onShow()
    }

    override fun onDetachedFromWindow() {
        debugPanel.onHide()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val PANEL_WIDTH_RATIO = 0.92f
        private const val PANEL_HEIGHT_RATIO = 0.70f
    }
}
