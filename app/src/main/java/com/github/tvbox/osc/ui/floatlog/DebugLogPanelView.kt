package com.github.tvbox.osc.ui.floatlog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.github.tvbox.osc.R
import com.github.tvbox.osc.ui.theme.ViewTheme
import com.github.tvbox.osc.util.FloatLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 页签式调试日志面板（1.38 新建）。
 *
 * 结构按用户录像的昏黄调试日志从头做（webhtv FloatLog.java 无页签，两树皆无此面板，
 * 故不从 FloatLog 拷）：标题“调试日志”+ 停用/启用、滚动、关闭；五页签 控制台/接口/
 * 系统/Logcat/实时日志；过滤行（输关键字筛条目）；每条有时间戳、线程、来源、结构化内容，
 * 每条右侧有复制；底部有总条数和字节数，动作有复制全部、展开、文本、清空关闭、关闭。
 * 颜色跟主题（不写死昏黄蓝，和 1.37 日志口径一致），框四角直角。
 *
 * 内容仍是全套（昏黄搬入 + 编排 56 点 + 点按 26 行 + 落盘持久化，1.37 已有），
 * 按页签分流（不丢条目；实时日志不过滤）：
 * - 接口：来源 spider 且非堆栈的条目（爬虫/代理/接口消息，如 SpiderLog、代理监听）；
 * - 系统：堆栈条目（不论来源，如 Caused by / 异常 + at 帧，录像系统页即此类）；
 * - 控制台：其余全部 FloatLog 条目（app / music / player 等，兜底，不丢）；
 * - Logcat：系统 logcat 原始行（单独抓取，不走 FloatLog 缓冲）。
 * - 实时日志：全部 FloatLog 条目按时间混排不过滤（调试日志本身就是实时日志，找行不用猜页签）。
 * 底部字节数是落盘文件总字节（录像里切页签字节数不变，故不用分页面字节）。
 *
 * 底栏动作语义（本版定）：
 * - 复制全部：复制当前页签当前过滤后的全部条目；
 * - 展开：面板全屏/还原切换（宿主接 onExpandToggle）；
 * - 文本：结构化条目列与纯文本块切换（纯文本块可选中）；
 * - 清空关闭：清空全部日志（含落盘文件，各页签共享同一缓冲故全清）并关闭面板；
 * - 关闭：仅关闭面板（回悬浮球），日志保留。
 * 标题栏 停用/启用：停用即暂停自动刷新（录像 toast“已停用自动刷新”同款），
 * 启用即恢复；滚动：开时新条目到来且列表已贴底才跟随到底（非贴底不拽）。
 */
class DebugLogPanelView(context: Context) : LinearLayout(context) {

    enum class Tab { CONSOLE, API, SYSTEM, LOGCAT, REALTIME }

    /** 关闭面板（回悬浮球），由宿主 FloatLogView 接。 */
    var onClose: (() -> Unit)? = null

    /** 关闭日志（宿主关掉悬浮日志：球和面板都不见，设置里重开），由宿主 FloatLogView 接。 */
    var onCloseLog: (() -> Unit)? = null

    /** 展开钮：面板全屏/还原切换，由宿主 FloatLogView 接。 */
    var onExpandToggle: (() -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    private val themePrimary = ViewTheme.primaryArgb(context)
    private val themeOnPrimary = ViewTheme.onPrimaryArgb(context)
    private val themeSurface = ViewTheme.surfaceArgb(context)
    private val themeSurfaceContainer = ViewTheme.surfaceContainerArgb(context)
    private val themeOnSurface = ViewTheme.onSurfaceArgb(context)
    private val themeOnSurfaceVariant = ViewTheme.scheme(context).onSurfaceVariant.toArgb()
    private val themeError = ViewTheme.scheme(context).error.toArgb()

    /** 标题栏（宿主拿它做面板拖动手柄）。 */
    val titleBar: LinearLayout = LinearLayout(context)
    private val disableBtn: TextView = TextView(context)
    private val scrollBtn: TextView = TextView(context)
    private val tabViews = ArrayList<TextView>()
    private val filterInput: EditText = EditText(context)
    private val entryList: ListView = ListView(context)
    private val emptyText: TextView = TextView(context)
    private val plainScroll: ScrollView = ScrollView(context)
    private val plainText: TextView = TextView(context)
    private val countText: TextView = TextView(context)

    private val adapter = EntryAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    private var currentTab: Tab = Tab.entries[FloatLog.debugLogTab.coerceIn(0, Tab.entries.size - 1)]
    private var autoRefresh = true
    private var autoScroll = true
    private var textMode = false
    private var filterKeyword = ""
    private var shown = false
    private var logcatLines: List<String> = emptyList()
    private var logcatLastLoadMs = 0L

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (autoRefresh) {
                if (currentTab == Tab.LOGCAT &&
                    System.currentTimeMillis() - logcatLastLoadMs > LOGCAT_THROTTLE_MS
                ) {
                    loadLogcat()
                }
                refreshList()
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    init {
        orientation = VERTICAL
        // 1.50：日志窗口四个角变圆弧，跟随主题（原生对话框同款：表面色 + 28dp 圆角）；
        // clipToOutline 让列表内容不探出圆角。
        background = ViewTheme.dialogBackground(context)
        clipToOutline = true
        elevation = dp(8).toFloat()
        buildTitleBar()
        buildTabRow()
        buildFilterRow()
        buildListArea()
        buildBottom()
        entryList.adapter = adapter
        entryList.emptyView = emptyText
        updateTabUI()
        updateTitleButtons()
    }

    // ---------------- 搭建 ----------------

    private fun buildTitleBar() {
        titleBar.orientation = HORIZONTAL
        titleBar.gravity = Gravity.CENTER_VERTICAL
        titleBar.setBackgroundColor(themePrimary)
        titleBar.setPadding(dp(12), dp(8), dp(12), dp(8))
        val title = TextView(context).apply {
            text = context.getString(R.string.debug_log_title)
            setTextColor(themeOnPrimary)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleBar.addView(title)
        fun titleBtn(tv: TextView, onClick: () -> Unit) {
            tv.setTextColor(themeOnPrimary)
            tv.textSize = 12f
            tv.gravity = Gravity.CENTER
            tv.setPadding(dp(8), dp(6), dp(8), dp(6))
            tv.isClickable = true
            tv.setOnClickListener { onClick() }
            titleBar.addView(tv)
        }
        titleBtn(disableBtn) {
            autoRefresh = !autoRefresh
            updateTitleButtons()
            if (autoRefresh) {
                refreshList()
            } else {
                Toast.makeText(context, R.string.debug_log_auto_refresh_disabled, Toast.LENGTH_SHORT).show()
            }
        }
        titleBtn(scrollBtn) {
            autoScroll = !autoScroll
            updateTitleButtons()
            if (autoScroll) scrollToBottomNow()
        }
        val closeBtn = TextView(context)
        titleBtn(closeBtn) { onCloseLog?.invoke() }
        closeBtn.text = "✕"
        closeBtn.textSize = 14f
        closeBtn.setPadding(dp(20), dp(6), dp(8), dp(6))
        addView(titleBar)
    }

    private fun buildTabRow() {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(themeSurfaceContainer)
        }
        val labels = listOf(
            R.string.debug_log_tab_console,
            R.string.debug_log_tab_api,
            R.string.debug_log_tab_system,
            R.string.debug_log_tab_logcat,
            R.string.debug_log_tab_realtime,
        )
        val tabs = Tab.entries
        for (i in labels.indices) {
            val tv = TextView(context).apply {
                text = context.getString(labels[i])
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
                isClickable = true
                layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                val tab = tabs[i]
                setOnClickListener { setTab(tab) }
            }
            tabViews.add(tv)
            row.addView(tv)
        }
        addView(row)
    }

    private fun buildFilterRow() {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val label = TextView(context).apply {
            text = context.getString(R.string.debug_log_filter)
            setTextColor(themeOnSurfaceVariant)
            textSize = 14f
        }
        row.addView(label)
        filterInput.apply {
            hint = context.getString(R.string.debug_log_filter_hint)
            setTextColor(themeOnSurface)
            setHintTextColor(themeOnSurfaceVariant)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            background = GradientDrawable().apply {
                cornerRadius = 0f
                setColor(themeSurface)
            }
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(8)
                rightMargin = dp(4)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    filterKeyword = s?.toString()?.lowercase().orEmpty()
                    refreshList()
                }
            })
        }
        row.addView(filterInput)
        val clearFilter = TextView(context).apply {
            text = context.getString(R.string.float_log_clear_search)
            setTextColor(themeOnSurfaceVariant)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            isClickable = true
            setOnClickListener { filterInput.setText("") }
        }
        row.addView(clearFilter)
        val clearAll = TextView(context).apply {
            text = context.getString(R.string.debug_log_clear)
            setTextColor(themeError)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(4), dp(4))
            isClickable = true
            setOnClickListener { clearAllLogs() }
        }
        row.addView(clearAll)
        addView(row)
    }

    private fun buildListArea() {
        entryList.apply {
            divider = null
            dividerHeight = 0
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        addView(entryList)
        emptyText.apply {
            text = context.getString(R.string.debug_log_empty)
            setTextColor(themeOnSurfaceVariant)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(24), dp(12), dp(24))
            visibility = View.GONE
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        addView(emptyText)
        plainText.apply {
            setTextColor(themeOnSurface)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextIsSelectable(true)
        }
        plainScroll.apply {
            isFillViewport = true
            visibility = View.GONE
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            addView(
                plainText,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        addView(plainScroll)
    }

    private fun buildBottom() {
        countText.apply {
            setTextColor(themeOnSurfaceVariant)
            textSize = 13f
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        addView(countText)
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        fun action(textRes: Int, color: Int, onClick: () -> Unit) {
            val tv = TextView(context).apply {
                setText(textRes)
                setTextColor(color)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(8), dp(10), dp(8))
                isClickable = true
                layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { onClick() }
            }
            bar.addView(tv)
        }
        action(R.string.debug_log_copy_all, themePrimary) { copyAll() }
        action(R.string.debug_log_expand, themePrimary) { onExpandToggle?.invoke() }
        action(R.string.debug_log_text, themePrimary) {
            textMode = !textMode
            updateModeVisibility()
            refreshList()
        }
        action(R.string.debug_log_clear_close, themeError) {
            clearAllLogs()
            onClose?.invoke()
        }
        action(R.string.float_log_close, themePrimary) { onClose?.invoke() }
        addView(bar)
    }

    // ---------------- 显隐与轮询 ----------------

    fun onShow() {
        if (shown) {
            refreshList()
            return
        }
        shown = true
        updateTitleButtons()
        updateTabUI()
        if (currentTab == Tab.LOGCAT) loadLogcat()
        refreshList()
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, POLL_MS)
    }

    fun onHide() {
        shown = false
        handler.removeCallbacks(pollRunnable)
    }

    // ---------------- 页签与分流 ----------------

    private fun setTab(tab: Tab) {
        if (currentTab == tab) return
        currentTab = tab
        FloatLog.debugLogTab = tab.ordinal
        updateTabUI()
        if (tab == Tab.LOGCAT) loadLogcat()
        refreshList()
    }

    private fun updateTabUI() {
        for (i in tabViews.indices) {
            val tv = tabViews[i]
            val selected = Tab.entries[i] == currentTab
            if (selected) {
                tv.setBackgroundColor(themeSurface)
                tv.setTextColor(themePrimary)
                tv.typeface = Typeface.DEFAULT_BOLD
            } else {
                tv.setBackgroundColor(themeSurfaceContainer)
                tv.setTextColor(themeOnSurfaceVariant)
                tv.typeface = Typeface.DEFAULT
            }
        }
    }

    private fun updateTitleButtons() {
        disableBtn.setText(
            if (autoRefresh) R.string.debug_log_disable else R.string.debug_log_enable,
        )
        scrollBtn.setText(R.string.debug_log_scroll)
        if (autoScroll) {
            scrollBtn.setBackgroundColor(0x40FFFFFF)
            scrollBtn.setTextColor(themeOnPrimary)
        } else {
            scrollBtn.background = null
            scrollBtn.setTextColor(themeOnPrimary)
            scrollBtn.alpha = 0.7f
        }
        if (autoScroll) scrollBtn.alpha = 1f
    }

    private fun updateModeVisibility() {
        if (textMode) {
            entryList.visibility = View.GONE
            emptyText.visibility = View.GONE
            plainScroll.visibility = View.VISIBLE
        } else {
            plainScroll.visibility = View.GONE
            entryList.visibility = View.VISIBLE
        }
    }

    /** 堆栈条目判定：Caused by，或含 Exception 且带 at 帧行（录像系统页即此类）。 */
    private fun isStackTrace(text: String): Boolean {
        if (text.contains("Caused by:")) return true
        if (!text.contains("Exception")) return false
        return text.indexOf("\nat ") >= 0 || text.indexOf("\n\tat ") >= 0
    }

    private fun tabFor(entry: FloatLog.Entry): Tab {
        if (isStackTrace(entry.text)) return Tab.SYSTEM
        if (entry.source == "spider") return Tab.API
        return Tab.CONSOLE
    }

    /** 实时日志页签：不过滤来源，全部 FloatLog 条目按时间混排（调试日志本身就是实时日志，找行不用猜页签）。 */
    private fun visibleEntries(): List<FloatLog.Entry> {
        val all = FloatLog.snapshot()
        if (currentTab == Tab.REALTIME) return all
        return all.filter { tabFor(it) == currentTab }
    }

    /** 条目显示行：时间戳 [线程] 来源/标签: 全文（录像接口页同款结构，线程为 append 时抓取）。 */
    private fun displayText(entry: FloatLog.Entry): String {
        val label = if (!entry.tag.isNullOrEmpty()) entry.tag else entry.source
        return dateFormat.format(Date(entry.timeMillis)) +
            " [" + entry.thread + "] " + label + ": " + entry.text
    }

    // ---------------- 刷新 ----------------

    private fun refreshList() {
        val keyword = filterKeyword
        val display: List<String>
        if (currentTab == Tab.LOGCAT) {
            display = if (keyword.isEmpty()) {
                logcatLines
            } else {
                logcatLines.filter { it.lowercase().contains(keyword) }
            }
        } else {
            val entries = visibleEntries()
            val kept = if (keyword.isEmpty()) {
                entries
            } else {
                entries.filter { it.matches(keyword) }
            }
            display = kept.map { displayText(it) }
        }
        val bytes = FloatLog.persistBytes().takeIf { it > 0 } ?: FloatLog.memoryBytes()
        countText.text = context.getString(R.string.debug_log_count, display.size, bytes)
        if (textMode) {
            plainText.text = if (display.isEmpty()) {
                context.getString(R.string.debug_log_empty)
            } else {
                display.joinToString("\n")
            }
            if (autoScroll) plainScroll.post { plainScroll.fullScroll(View.FOCUS_DOWN) }
            return
        }
        val wasAtBottom = isListAtBottom()
        val firstPos = entryList.firstVisiblePosition
        val firstTop = entryList.getChildAt(0)?.top ?: 0
        adapter.setData(display)
        if (display.isEmpty()) return
        if (autoScroll && wasAtBottom) {
            entryList.setSelection(adapter.count - 1)
        } else {
            entryList.setSelectionFromTop(firstPos, firstTop)
        }
    }

    private fun isListAtBottom(): Boolean {
        if (adapter.count == 0) return true
        return entryList.lastVisiblePosition >= adapter.count - 1
    }

    private fun scrollToBottomNow() {
        if (textMode) {
            plainScroll.post { plainScroll.fullScroll(View.FOCUS_DOWN) }
        } else if (adapter.count > 0) {
            entryList.setSelection(adapter.count - 1)
        }
    }

    // ---------------- logcat 抓取 ----------------

    private fun loadLogcat() {
        val now = System.currentTimeMillis()
        if (now - logcatLastLoadMs < LOGCAT_THROTTLE_MS && logcatLines.isNotEmpty()) return
        logcatLastLoadMs = now
        Thread {
            val lines = try {
                val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "500"))
                val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
                try {
                    proc.waitFor()
                } catch (ignored: Throwable) {
                }
                val pid = android.os.Process.myPid().toString()
                val all = out.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList()
                val mine = all.filter { it.contains("($pid)") }
                if (mine.isNotEmpty()) mine else all
            } catch (ignored: Throwable) {
                emptyList()
            }
            handler.post {
                if (!shown) return@post
                logcatLines = lines
                if (currentTab == Tab.LOGCAT) refreshList()
            }
        }.start()
    }

    // ---------------- 动作 ----------------

    private fun currentDisplayLines(): List<String> {
        val keyword = filterKeyword
        if (currentTab == Tab.LOGCAT) {
            return if (keyword.isEmpty()) {
                logcatLines
            } else {
                logcatLines.filter { it.lowercase().contains(keyword) }
            }
        }
        val entries = visibleEntries()
        val kept = if (keyword.isEmpty()) entries else entries.filter { it.matches(keyword) }
        return kept.map { displayText(it) }
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("AVBoxLog", text))
            Toast.makeText(context, R.string.float_log_copied, Toast.LENGTH_SHORT).show()
        } catch (ignored: Throwable) {
        }
    }

    private fun copyAll() {
        val lines = currentDisplayLines()
        if (lines.isEmpty()) {
            Toast.makeText(context, R.string.debug_log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        copyToClipboard(lines.joinToString("\n"))
    }

    private fun clearAllLogs() {
        FloatLog.clear()
        logcatLines = emptyList()
        refreshList()
    }

    // ---------------- 条目列 ----------------

    private inner class EntryAdapter : BaseAdapter() {
        private var data: List<String> = emptyList()

        fun setData(next: List<String>) {
            data = next
            notifyDataSetChanged()
        }

        override fun getCount(): Int = data.size
        override fun getItem(position: Int): Any = data[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }
            val textView: TextView
            val copyView: TextView
            if (convertView == null) {
                textView = TextView(context).apply {
                    setTextColor(themeOnSurface)
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                copyView = TextView(context).apply {
                    text = context.getString(R.string.float_log_copy)
                    setTextColor(themePrimary)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    background = GradientDrawable().apply {
                        cornerRadius = 0f
                        setColor(themeSurface)
                    }
                    isClickable = true
                    setOnClickListener { v ->
                        val t = v.tag as? String ?: return@setOnClickListener
                        copyToClipboard(t)
                    }
                }
                row.addView(textView)
                row.addView(copyView)
                row.tag = Pair(textView, copyView)
            } else {
                val pair = row.tag as Pair<TextView, TextView>
                textView = pair.first
                copyView = pair.second
            }
            val line = data[position]
            textView.text = line
            copyView.tag = line
            return row
        }
    }

    companion object {
        private const val POLL_MS = 1000L
        private const val LOGCAT_THROTTLE_MS = 5000L
    }
}
