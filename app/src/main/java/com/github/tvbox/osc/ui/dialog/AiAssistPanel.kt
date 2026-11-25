package com.github.tvbox.osc.ui.dialog

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.github.catvod.utils.Prefers
import com.github.tvbox.osc.api.LocalSource
import com.github.tvbox.osc.api.SpiderLoader
import com.github.tvbox.osc.api.ai.AiConfig
import com.github.tvbox.osc.api.ai.AiRepair
import com.github.tvbox.osc.api.ai.AiSkillPack
import com.github.tvbox.osc.api.ai.JsRender
import com.github.tvbox.osc.api.loader.WebSourceStore
import com.github.tvbox.osc.ui.activity.BrowserActivity
import com.github.tvbox.osc.ui.page.AppBootstrap
import com.github.tvbox.osc.ui.theme.ViewTheme
import com.github.tvbox.osc.util.Notify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

// AI 任务板：任务跑在应用级作用域里，关面板不停；悬浮球点回来继续看。
// statusSink 指向当前在最前面的 AI 页面的状态栏，任务日志实时推过去。
object AiTaskBoard {
    var activeTitle: String? = null
        private set
    // 任务进度（0..100）与当前阶段文字：2.24 起无弹窗读它们，只作阶段记录。
    var progress: Int = 0
        private set
    var progressLabel: String = ""
        private set
    val statusLines = ArrayList<String>()
    var reopen: ((Context) -> Unit)? = null
    var onChanged: (() -> Unit)? = null
    var statusSink: ((String) -> Unit)? = null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun log(line: String) {
        synchronized(statusLines) {
            statusLines.add(line)
            if (statusLines.size > 200) statusLines.removeAt(0)
        }
        postChanged()
        postStatus()
    }

    fun setActive(title: String?) {
        activeTitle = title
        if (title != null) {
            // 新任务开始：进度归零；任务结束时不重置，进度条停在最终值（100=完成）。
            progress = 0
            progressLabel = ""
        }
        postChanged()
    }

    fun setProgress(p: Int, label: String) {
        progress = p.coerceIn(0, 100)
        progressLabel = label
        postChanged()
    }

    fun statusText(): String = synchronized(statusLines) { statusLines.joinToString("\n") }

    private fun postChanged() {
        val cb = onChanged ?: return
        mainHandler.post {
            try {
                cb()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun postStatus() {
        val sink = statusSink ?: return
        val text = statusText()
        mainHandler.post {
            try {
                sink(text)
            } catch (ignored: Throwable) {
            }
        }
    }
}

object AiAssistPanel {
    @JvmStatic
    fun show(context: Context) {
        AiAssistMainDialog(context).show()
    }

    @JvmStatic
    fun showSourceList(context: Context) {
        AiSourceListDialog(context).show()
    }

    @JvmStatic
    fun showWorkshop(context: Context) {
        AiWorkshopDialog(context).show()
    }

    /**
     * 3.68 A：网页源管理页长按「AI 处理」入口。
     * 把该网页源的网址/名称预填进建源工坊，点「生成 py 源」即走既有 onGenerate(url, name)
     * 链路（reconSite → AvSiteScout 定性 → generateWithAgent → staticCheck → validateRun
     * 六接口 → repairWithAgent，spider 写进扫描目录）。原网页源保留不删。
     * 没配扫描目录时先引导用户去配本地源，不让流程卡在「请先填站点网址并选目标文件夹」。
     */
    @JvmStatic
    fun showWorkshopFor(context: Context, url: String, name: String) {
        if (scanDirList().isEmpty()) {
            Notify.show("先去设置 → 本地源文件夹配一个扫描目录，再回来 AI 处理")
            AiTaskBoard.log("AI 处理需要先配本地源扫描目录：请在设置 → 本地源文件夹里添加扫描目录，再长按网页源点 AI 处理")
            LocalSourceSettingsDialog.show(context) { }
            return
        }
        AiWorkshopDialog(context, url.trim(), name.trim()).show()
    }

    /** 1.07 批量加网页源：网址框一行一个（也容逗号/分号/空白分隔），多个链接时
     * 名称自动加序号往下排（影视→影视1、影视2…，已有的序号接着排）；单个链接用
     * 原名。网页源一站一条（同 host 覆盖），所以同站的链接只留第一个。 */
    @JvmStatic
    // 1.26：onAdded 在成功添加后回调，调用方（如网页源管理页）用它刷新列表
    fun showAddWebSource(context: Context, onAdded: (() -> Unit)? = null) {
        val nameInput = EditText(context).apply {
            hint = "名称（可选，默认取域名）"
            ViewTheme.styleInput(context, this)
        }
        val urlInput = EditText(context).apply {
            hint = "一行一个源：名称，网址；只贴网址也行"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 8
            ViewTheme.styleInput(context, this)
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad2 = dp(context, 12)
            setPadding(pad2, pad2, pad2, pad2)
            addView(TextView(context).apply { text = "「打开」添加后直接进第一个网页，「添加」只存成源不打开。一行一个源，写成「名称，网址」；只贴网址也行，名字自动取，没名字的多个链接按影视1、影视2排。" })
            addView(nameInput)
            addView(urlInput)
        }
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(box, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        var addDialog: Dialog? = null
        // 1.25：按钮顺序改成「取消、打开、添加」——「打开」添加后直接进第一个网页，「添加」只存成源不打开
        // LightDialog 渲染顺序是 neutral、negative、positive（从左到右），所以按这个顺序填
        fun doAdd(openFirst: Boolean) {
            val baseName = nameInput.text?.toString()?.trim().orEmpty()
            val raw = urlInput.text?.toString().orEmpty()
            // r487：通用格式，一行一个源，名字和网址分开写：
            //   名称,网址 / 名称，网址（逗号前后分开）；没逗号就整行当网址；
            //   没逗号但有 http 的，从 http 处切开，前面当名字；
            //   逗号前面本身就是网址时，按老办法逗号两边各算一个网址。
            // 归一化后按 host 去重（一站一条）。
            data class NamedUrl(val name: String, val url: String)
            fun looksLikeUrl(s: String): Boolean {
                val t = s.trim()
                return t.startsWith("http://") || t.startsWith("https://") ||
                    (t.contains('.') && !t.contains(' ') && !t.contains('，'))
            }
            val entries = mutableListOf<NamedUrl>()
            var failCount = 0
            for (line in raw.split(Regex("\\r?\\n"))) {
                val text = line.trim()
                if (text.isEmpty()) continue
                val sep = text.indexOfFirst { it == ',' || it == '，' || it == ';' || it == '；' }
                if (sep >= 0) {
                    val left = text.substring(0, sep).trim()
                    val right = text.substring(sep + 1).trim()
                    if (looksLikeUrl(left)) {
                        // 老格式：分隔符两边各是一个网址
                        for (part in listOf(left, right).filter { it.isNotEmpty() }) {
                            val u = WebSourceStore.normalize(part)
                            if (u.isNotEmpty()) entries.add(NamedUrl("", u))
                        }
                    } else {
                        // 名称在左，网址在右；右边多个网址时都加，第一个用这个名字
                        var added = false
                        for (part in right.split(Regex("[,;，；\\s]+")).filter { it.isNotEmpty() }) {
                            val u = WebSourceStore.normalize(part)
                            if (u.isNotEmpty()) {
                                entries.add(NamedUrl(if (!added) left else "", u))
                                added = true
                            }
                        }
                        if (!added) failCount++
                    }
                } else {
                    val httpAt = text.indexOf("http")
                    when {
                        httpAt == 0 -> {
                            // 整行都是网址（空格隔开多个也行）
                            var added = false
                            for (part in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                                val u = WebSourceStore.normalize(part)
                                if (u.isNotEmpty()) {
                                    entries.add(NamedUrl("", u))
                                    added = true
                                }
                            }
                            if (!added) failCount++
                        }
                        httpAt > 0 -> {
                            val u = WebSourceStore.normalize(text.substring(httpAt))
                            if (u.isNotEmpty()) entries.add(NamedUrl(text.substring(0, httpAt).trim(), u)) else failCount++
                        }
                        else -> {
                            val u = WebSourceStore.normalize(text)
                            if (u.isNotEmpty()) entries.add(NamedUrl("", u)) else failCount++
                        }
                    }
                }
            }
            val deduped = entries.distinctBy { WebSourceStore.host(it.url) }
            if (deduped.isEmpty()) {
                AiTaskBoard.log("请输入网址")
            } else {
                val multi = deduped.size > 1
                // 多链接：已有同名序号接着排（影视1、影视2 已存在时从影视3 起）。
                var next = 1
                if (multi && baseName.isNotEmpty()) {
                    val prefix = Regex("^" + Regex.escape(baseName) + "(\\d+)$")
                    next = WebSourceStore.list().map { it[0] }
                        .mapNotNull { prefix.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                        .maxOrNull()?.plus(1) ?: 1
                }
                var okCount = 0
                val names = mutableListOf<String>()
                var firstUrl = ""
                var firstName = ""
                for ((entryName, url) in deduped) {
                    val name = when {
                        entryName.isNotEmpty() -> entryName
                        !multi -> baseName
                        baseName.isNotEmpty() -> baseName + (next++)
                        else -> ""
                    }
                    if (WebSourceStore.add(url, name, "")) {
                        okCount++
                        names.add(if (name.isNotEmpty()) name else WebSourceStore.host(url))
                        if (firstUrl.isEmpty()) { firstUrl = url; firstName = names.last() }
                    } else {
                        failCount++
                    }
                }
                if (okCount > 0) {
                    AppBootstrap.onApiUrlChanged()
                    val msg = if (okCount == 1) "已添加网页源：" + names[0]
                    else "已添加 " + okCount + " 个网页源：" + names.joinToString("、")
                    AiTaskBoard.log(if (failCount > 0) "$msg（$failCount 个失败）" else msg)
                    addDialog?.dismiss()
                    onAdded?.invoke()
                    if (openFirst) (context as? Activity)?.let { BrowserActivity.start(it, firstUrl, firstName) }
                } else {
                    AiTaskBoard.log("添加失败：网址无效或已达 1000 条上限")
                }
            }
        }
        addDialog = LightDialog.create(context, "添加网页源", scroll, "添加", { doAdd(false) }, "打开", { doAdd(true) }, "取消", { addDialog?.dismiss() })
        addDialog.show()
    }
}

private data class SiteItem(val key: String, val name: String, val api: String, val json: String)

private fun siteLabel(site: SiteItem): String {
    if (site.name.isEmpty()) return site.key
    if (site.name == site.key) return site.name
    return site.name + "（" + site.key + "）"
}

private fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density + 0.5f).toInt()

private fun runAiTask(title: String, block: suspend () -> Unit) {
    if (AiTaskBoard.activeTitle != null) {
        AiTaskBoard.log("已有任务在跑：" + AiTaskBoard.activeTitle)
        return
    }
    AiTaskBoard.setActive(title)
    AiTaskBoard.log("开始：$title")
    AiTaskBoard.scope.launch {
        try {
            block()
        } catch (e: Throwable) {
            withContext(Dispatchers.Main) { AiTaskBoard.log("失败：" + (e.message ?: e.javaClass.simpleName)) }
        } finally {
            AiTaskBoard.setActive(null)
            AiTaskBoard.log("结束：$title")
        }
    }
}

private fun localConfigFile(): File? {
    val saved = try {
        Prefers.getString("local_source_file")
    } catch (ignored: Throwable) {
        ""
    }.orEmpty()
    if (saved.isNotEmpty()) {
        val f = File(saved)
        if (f.exists()) return f
    }
    val out = try {
        LocalSource.outputPath(LocalSource.loadSettings())
    } catch (ignored: Throwable) {
        ""
    }.orEmpty()
    if (out.isNotEmpty()) {
        val f = File(out)
        if (f.exists()) return f
    }
    return null
}

private fun loadSites(): Pair<List<SiteItem>, String> {
    val file = localConfigFile() ?: return Pair(emptyList(), "")
    return try {
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val arr = root.optJSONArray("sites") ?: JSONArray()
        val list = ArrayList<SiteItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(SiteItem(obj.optString("key", ""), obj.optString("name", ""), obj.optString("api", ""), obj.toString()))
        }
        Pair(list, file.absolutePath)
    } catch (ignored: Throwable) {
        Pair(emptyList(), "")
    }
}

private fun scanDirList(): List<String> {
    return try {
        val cfg = LocalSource.loadSettings()
        val enabled = LocalSource.stringList(cfg, "scan_enabled_dirs")
        if (enabled.isNotEmpty()) return enabled
        LocalSource.stringList(cfg, "scan_dirs")
    } catch (ignored: Throwable) {
        emptyList()
    }
}

private fun dirDisplayName(path: String): String {
    val name = File(path).name
    return if (name.isEmpty()) path else name
}

private fun labelView(context: Context, text: String): TextView {
    val tv = TextView(context)
    tv.text = text
    tv.textSize = 16f
    tv.setTypeface(null, android.graphics.Typeface.BOLD)
    val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    params.topMargin = dp(context, 12)
    tv.layoutParams = params
    return tv
}

private fun bodyView(context: Context, text: String): TextView {
    val tv = TextView(context)
    tv.text = text
    val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    params.topMargin = dp(context, 6)
    tv.layoutParams = params
    return tv
}

private fun linkView(context: Context, text: String, onClick: () -> Unit): TextView {
    val tv = TextView(context)
    tv.text = text
    tv.setOnClickListener { onClick() }
    val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    params.topMargin = dp(context, 6)
    tv.layoutParams = params
    return tv
}

private fun bigButton(context: Context, text: String, onClick: () -> Unit): Button {
    val button = Button(context)
    button.text = text
    ViewTheme.styleTonalButton(context, button)
    val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    params.topMargin = dp(context, 8)
    button.layoutParams = params
    button.setOnClickListener { onClick() }
    return button
}

// 主面板：只留一个 AI辅助入口，窗口里只有四个大按钮。
private class AiAssistMainDialog(private val context: Context) {
    fun show() {
        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        val pad = dp(context, 12)
        root.setPadding(pad, pad, pad, pad)

        root.addView(bigButton(context, "AI模型") {
            AiRepairSettingsDialog.show(context) { AiTaskBoard.log("模型设置已保存") }
        })
        root.addView(bigButton(context, "上传技能包") { AiSkillPackDialog(context).show() })
        root.addView(bigButton(context, "打开源列表") { AiAssistPanel.showSourceList(context) })
        root.addView(bigButton(context, "打开建源表单") { AiAssistPanel.showWorkshop(context) })

        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        var created: Dialog? = null
        created = LightDialog.create(context, "AI辅助", scroll, "关闭", { created?.dismiss() }, null, null)
        AiTaskBoard.reopen = { ctx -> AiAssistPanel.show(ctx) }
        created.show()
    }
}

// 技能包管理页：主面板“上传技能包”点进来；上传、启用/停用、删除都在这里。
private class AiSkillPackDialog(private val context: Context) {
    fun show() {
        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        val pad = dp(context, 12)
        root.setPadding(pad, pad, pad, pad)

        val skillDescView = bodyView(context, "可以上传好几个 py 写源技能包（zip / md / txt / py），写源和修源时模型会把已启用的都读一遍再写，上传多少就用多少（5M 以内的包都行）。zip 按 SKILL.md → 模板代码 → Schema → 模板文档 → 策略 → 校验器 → 归档的顺序取。")
        skillDescView.setTextColor(ViewTheme.onSurfaceArgb(context))
        root.addView(skillDescView)
        val skillSummaryView = TextView(context)
        skillSummaryView.setTextColor(ViewTheme.onSurfaceArgb(context))
        root.addView(skillSummaryView)
        val skillListBox = LinearLayout(context)
        skillListBox.orientation = LinearLayout.VERTICAL
        root.addView(skillListBox)
        fun refreshSkillPack() {
            val infos = AiSkillPack.infos(context)
            val enabledCount = infos.count { it.enabled }
            skillSummaryView.text = if (infos.isEmpty()) {
                "未上传技能包"
            } else {
                "已上传 " + infos.size + " 个，已启用 " + enabledCount + " 个"
            }
            skillListBox.removeAllViews()
            for (info in infos) {
                val row = LinearLayout(context)
                row.orientation = LinearLayout.HORIZONTAL
                row.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(context, 6); bottomMargin = dp(context, 6) }
                // 2.48：长技能包名不再塞进 CheckBox 单行（会截断），改为 CheckBox 只做开关（无文字），
                // 名字用单独 TextView 换行完整显示，下面再显示大小/字数。
                val box = CheckBox(context)
                box.isChecked = info.enabled
                box.setOnCheckedChangeListener { _, checked ->
                    AiSkillPack.setPackEnabled(info.id, checked)
                }
                box.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                row.addView(box)
                val nameCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val nameView = TextView(context).apply {
                    text = info.name
                    setTextColor(ViewTheme.onSurfaceArgb(context))
                    textSize = 15f
                    maxLines = 3
                }
                nameCol.addView(nameView)
                val metaView = TextView(context).apply {
                    text = "%.1f MB / %d 字".format(info.sizeBytes / 1048576.0, info.chars)
                    setTextColor(ViewTheme.onSurfaceVariantArgb(context))
                    textSize = 12f
                }
                nameCol.addView(metaView)
                row.addView(nameCol)
                val del = Button(context).apply { text = "删除" }
                ViewTheme.styleTonalButton(context, del)
                del.setOnClickListener {
                    AiSkillPack.removePack(context, info.id)
                    refreshSkillPack()
                }
                row.addView(del)
                skillListBox.addView(row)
            }
        }
        root.addView(bigButton(context, "上传技能包") {
            val activity = context as? Activity
            if (activity == null) {
                AiTaskBoard.log("当前界面不支持系统文件选择")
                return@bigButton
            }
            AiSkillPack.pickSkillPack(activity)
        })
        refreshSkillPack()
        AiSkillPack.onChanged = { refreshSkillPack() }

        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        var created: Dialog? = null
        created = LightDialog.create(context, "写源技能包", scroll, "关闭", { created?.dismiss() }, null, null, 40)
        created.setOnDismissListener { AiSkillPack.onChanged = null }
        created.show()
    }
}

// 源列表独立页：勾选 → 选定当前目标 → AI 优化/修复（诊断在流程里先跑，开始前可写追加要求）。
private class AiSourceListDialog(private val context: Context) {
    private var dialog: Dialog? = null
    private var pageAlive = false
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var repairResultView: TextView
    private lateinit var siteBox: LinearLayout
    private lateinit var chipRow: LinearLayout
    private lateinit var targetSpinner: Spinner
    private lateinit var overwriteButton: Button
    private val checkedKeys = HashSet<String>()
    private val expandedFolders = HashSet<String>()
    private var searchQuery = ""
    private var activeChip = "全部"

    private var sites: List<SiteItem> = emptyList()
    private var configPath: String = ""
    private var targetSite: SiteItem? = null
    private var repairedJson: String? = null

    fun show() {
        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        val pad = dp(context, 12)
        root.setPadding(pad, pad, pad, pad)

        root.addView(labelView(context, "勾选要优化的源").apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = 0
        })
        val searchInput = EditText(context).apply {
            hint = "搜索站点..."
            ViewTheme.styleInput(context, this)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    searchQuery = s?.toString().orEmpty()
                    refreshSiteList()
                }
            })
        }
        searchInput.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 8)
        }
        root.addView(searchInput)
        chipRow = LinearLayout(context)
        chipRow.orientation = LinearLayout.HORIZONTAL
        val chipScroll = HorizontalScrollView(context)
        chipScroll.isHorizontalScrollBarEnabled = false
        chipScroll.addView(chipRow)
        chipScroll.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 10)
        }
        root.addView(chipScroll)
        siteBox = LinearLayout(context)
        siteBox.orientation = LinearLayout.VERTICAL
        root.addView(siteBox)
        val loaded = loadSites()
        sites = loaded.first
        configPath = loaded.second
        refreshChips()
        refreshSiteList()

        root.addView(labelView(context, "选定当前目标"))
        targetSpinner = Spinner(context)
        targetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                val checked = checkedSites()
                targetSite = if (position in checked.indices) checked[position] else null
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                targetSite = null
            }
        }
        root.addView(targetSpinner)
        refreshTargetSpinner()

        val extraInput = EditText(context).apply { hint = "开始前可写追加要求（可选）" }
        root.addView(extraInput)

        if (!AiConfig.isConfigured()) {
            root.addView(linkView(context, "还没配模型，修复走不通。点这里去模型设置。") {
                AiRepairSettingsDialog.show(context) { AiTaskBoard.log("模型设置已保存") }
            })
        }
        root.addView(bigButton(context, "AI 优化/修复") {
            onOptimizeRepair(extraInput.text.toString().trim())
        })

        resultView = TextView(context)
        root.addView(resultView)
        repairResultView = TextView(context)
        root.addView(repairResultView)

        overwriteButton = Button(context).apply { text = "通过并覆盖原文件"; isEnabled = false }
        overwriteButton.setOnClickListener { onOverwrite() }
        root.addView(overwriteButton)

        root.addView(labelView(context, "任务状态"))
        statusView = TextView(context)
        statusView.text = AiTaskBoard.statusText()
        root.addView(statusView)

        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val created = LightDialog.create(context, "源列表", scroll, "关闭", { dialog?.dismiss() }, null, null)
        dialog = created
        created.setOnDismissListener {
            pageAlive = false
            if (AiTaskBoard.statusSink != null) AiTaskBoard.statusSink = null
        }
        pageAlive = true
        AiTaskBoard.statusSink = { text -> if (pageAlive) statusView.text = text }
        AiTaskBoard.reopen = { ctx -> AiAssistPanel.showSourceList(ctx) }
        created.show()
    }

    private fun refreshChips() {
        chipRow.removeAllViews()
        val chips = ArrayList<String>()
        chips.add("全部")
        val types = ArrayList<String>()
        val folders = ArrayList<String>()
        for (site in sites) {
            val suffix = suffixOf(site.key)
            if (suffix.isNotEmpty() && !types.contains(suffix)) types.add(suffix)
            val folder = folderOf(site.key)
            if (!folders.contains(folder)) folders.add(folder)
        }
        for (type in types.sorted()) chips.add("type:" + type)
        for (folder in folders) chips.add("folder:" + folder)
        for (chip in chips) {
            val label = when {
                chip == "全部" -> "全部"
                chip.startsWith("type:") -> "[" + chip.removePrefix("type:") + "]"
                else -> "[" + chip.removePrefix("folder:") + "]"
            }
            val button = Button(context)
            button.text = label
            button.minimumWidth = 0
            button.setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6))
            ViewTheme.styleTabButton(context, button, chip == activeChip)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.rightMargin = dp(context, 8)
            button.layoutParams = params
            button.setOnClickListener {
                activeChip = chip
                refreshChips()
                refreshSiteList()
            }
            chipRow.addView(button)
        }
    }

    private fun refreshSiteList() {
        siteBox.removeAllViews()
        if (sites.isEmpty()) {
            siteBox.addView(TextView(context).apply { text = "暂无本地源：先去扫描生成" })
            return
        }
        val query = searchQuery.trim()
        val filtering = query.isNotEmpty() || activeChip != "全部"
        if (filtering) {
            val filtered = sites.filter { site ->
                val chipOk = when {
                    activeChip == "全部" -> true
                    activeChip.startsWith("folder:") -> folderOf(site.key) == activeChip.removePrefix("folder:")
                    activeChip.startsWith("type:") -> suffixOf(site.key) == activeChip.removePrefix("type:")
                    else -> true
                }
                val queryOk = query.isEmpty() || site.key.contains(query, ignoreCase = true) || site.name.contains(query, ignoreCase = true)
                chipOk && queryOk
            }
            if (filtered.isEmpty()) {
                siteBox.addView(TextView(context).apply { text = "没有匹配的源" })
                return
            }
            for (site in filtered) {
                siteBox.addView(siteCheckBox(site))
            }
            return
        }
        val folders = folderList()
        for (folder in folders) {
            val list = sites.filter { folderOf(it.key) == folder }
            val expanded = expandedFolders.contains(folder)
            val header = labelView(context, (if (expanded) "▾ " else "▸ ") + folder + "（" + list.size + " 个源）")
            header.setOnClickListener {
                if (expanded) expandedFolders.remove(folder) else expandedFolders.add(folder)
                refreshSiteList()
            }
            siteBox.addView(header)
            if (expanded) {
                for (site in list) {
                    siteBox.addView(siteCheckBox(site))
                }
            }
        }
    }

    private fun siteCheckBox(site: SiteItem): CheckBox {
        val box = CheckBox(context)
        box.text = siteLabel(site)
        box.tag = site
        box.isChecked = checkedKeys.contains(site.key)
        box.textSize = 14f
        box.setTypeface(null, android.graphics.Typeface.BOLD)
        box.setPadding(box.paddingLeft + dp(context, 16), dp(context, 2), box.paddingRight, dp(context, 2))
        box.setOnCheckedChangeListener { _, checked ->
            if (checked) checkedKeys.add(site.key) else checkedKeys.remove(site.key)
            refreshTargetSpinner()
        }
        return box
    }

    private fun folderList(): List<String> {
        val folders = ArrayList<String>()
        for (site in sites) {
            val folder = folderOf(site.key)
            if (!folders.contains(folder)) folders.add(folder)
        }
        return folders
    }

    private fun folderOf(key: String): String {
        val match = Regex("\\[(.*?)\\]\\([^)]*\\)$").find(key)
        val folder = match?.groupValues?.getOrNull(1).orEmpty()
        return if (folder.isEmpty()) "未分类" else folder
    }

    private fun suffixOf(key: String): String {
        val match = Regex("\\(([^)]*)\\)$").find(key)
        return match?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun checkedSites(): List<SiteItem> {
        return sites.filter { checkedKeys.contains(it.key) }
    }

    private fun refreshTargetSpinner() {
        val checked = checkedSites()
        val labels = checked.map { siteLabel(it) }
        targetSpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, labels.ifEmpty { listOf("先勾选要修的源") })
        targetSite = checked.firstOrNull()
    }

    private fun onOptimizeRepair(extra: String) {
        val site = targetSite
        if (site == null) {
            AiTaskBoard.log("请先勾选要修的源并选定当前目标")
            return
        }
        if (!AiConfig.isConfigured()) {
            AiTaskBoard.log("AI 未配置：点去模型设置填地址与 Key，Base URL 与模型已有缺省")
            return
        }
        runAiTask("优化/修复：" + site.key) {
            val diagnosis = AiRepair.diagnose(site.api, site.key)
            val diagnosisText = diagnosis.summary + "\n" + diagnosis.evidence.take(10).joinToString("\n")
            withContext(Dispatchers.Main) {
                resultView.text = diagnosisText
                AiTaskBoard.log("诊断完成：" + diagnosis.category)
            }
            val requestText = if (extra.isEmpty()) diagnosisText else diagnosisText + "\n追加要求：\n" + extra
            val result = AiRepair.repairSite(site.json, requestText)
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { repaired ->
                        val keyError = repairKeyError(repaired, site.key)
                        if (keyError != null) {
                            AiTaskBoard.log("静态校验未通过：" + keyError + "，未生成可覆盖结果")
                            return@fold
                        }
                        repairedJson = repaired
                        repairResultView.text = repaired.take(800)
                        overwriteButton.isEnabled = true
                        AiTaskBoard.log("修复完成：检查无误后点通过并覆盖")
                    },
                    onFailure = { e ->
                        AiTaskBoard.log("修复失败：" + (e.message ?: e.javaClass.simpleName))
                    },
                )
            }
        }
    }

    private fun repairKeyError(repaired: String, expectKey: String): String? {
        return try {
            val parsed = JSONObject(repaired)
            val key = parsed.optString("key", "")
            if (key.isEmpty() || key != expectKey) "修复后 key 与原规则不一致，拒绝写回" else null
        } catch (e: Throwable) {
            "修复结果不是合法 JSON"
        }
    }

    private fun onOverwrite() {
        val site = targetSite
        val repaired = repairedJson
        if (site == null || repaired == null) {
            AiTaskBoard.log("没有可覆盖的修复结果")
            return
        }
        val keyError = repairKeyError(repaired, site.key)
        if (keyError != null) {
            AiTaskBoard.log("静态校验未通过：" + keyError)
            return
        }
        val message = AiRepair.applyRepairedToLocalConfig(configPath, repaired) ?: "写回返回为空"
        AiTaskBoard.log(message)
        if (message.startsWith("已写回")) {
            repairedJson = null
            overwriteButton.isEnabled = false
            val loaded = loadSites()
            sites = loaded.first
            configPath = loaded.second
            refreshSiteList()
            refreshTargetSpinner()
        }
    }
}

// 建源表单独立页：单个“生成 py 源”钮跑全流程（侦察→写→静态校验→真跑一遍），跑通才保存。
private class AiWorkshopDialog(private val context: Context, private val presetUrl: String = "", private val presetName: String = "") {
    private var dialog: Dialog? = null
    private var pageAlive = false
    private lateinit var statusView: TextView
    private lateinit var missingView: TextView
    private lateinit var folderView: TextView
    private lateinit var generateButton: Button

    private var scanDirs: List<String> = emptyList()
    private var selectedDir: String = ""
    // 2.52 修：记当前站点网址，供 pickFolder 选完文件夹后刷新缺项与生成钮状态。
    // 原先 pickFolder 拿不到输入框里的网址，没扫描目录时选了文件夹也不会刷新，
    // 缺项还写着"还差：目标文件夹"、生成钮还灰着，卡死。
    private var currentSiteUrl: String = ""

    private var agentWorkDir: File? = null
    @Volatile
    private var agentCancelled: Boolean = false

    /** 2.38 完整工坊：取消 Agent 循环（停止按钮/关闭时调；循环每回合检查）。 */
    fun cancelAgent() {
        agentCancelled = true
    }

    /** 2.38 完整工坊工作区：filesDir/ai_workshop/<jobId>/，放 source.py、meta.json、log.txt。同一任务只建一次。 */
    private fun newWorkshopDir(siteUrl: String): File {
        agentWorkDir?.let { return it }
        val host = try {
            java.net.URI(siteUrl).host.orEmpty().removePrefix("www.")
        } catch (ignored: Throwable) {
            "site"
        }.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val jobId = System.currentTimeMillis().toString() + "_" + if (host.isEmpty()) "site" else host
        val dir = File(context.filesDir, "ai_workshop/" + jobId)
        try {
            dir.mkdirs()
            File(dir, "meta.json").writeText("{\"url\":\"" + siteUrl.replace("\"", "") + "\",\"created\":" + System.currentTimeMillis() + ",\"status\":\"Running\"}", Charsets.UTF_8)
        } catch (ignored: Throwable) {
        }
        agentWorkDir = dir
        agentCancelled = false
        // 2.47：建新沙箱时清理历史沙箱，只保留最近 3 个，避免累积撑大 App
        cleanupOldWorkshops(3)
        return dir
    }

    /**
     * 2.47：沙箱清理。建源完成后清理临时文件（log.txt/meta.json 会越积越大），
     * 只保留 source.py 备查；如果保留的 source.py 已被用户拿走（或已保存到目标
     * 文件夹），整个沙箱目录可删。
     */
    private fun cleanupWorkshop(dir: File?) {
        if (dir == null || !dir.exists()) return
        try {
            // log.txt 是 Agent 工具日志，会越积越大，直接删
            File(dir, "log.txt").delete()
            // meta.json 是任务元数据，任务结束无用，删
            File(dir, "meta.json").delete()
            // source.py 保留（用户可能要看/复制），但通常已保存到目标文件夹
            // 如果目标文件夹已有同名源码，沙箱里这份可删以省空间
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 2.47：清理历史沙箱，只保留最近 KEEP 个。每次建源建一个 ai_workshop/<jobId>/，
     * 多次累积会撑大 App（每个含 source.py/log.txt/meta.json）。建新源时清理旧的。
     */
    private fun cleanupOldWorkshops(keep: Int = 3) {
        try {
            val root = File(context.filesDir, "ai_workshop")
            if (!root.exists()) return
            val dirs = root.listFiles()?.filter { it.isDirectory }
                ?.sortedByDescending { it.lastModified() } ?: return
            for ((idx, d) in dirs.withIndex()) {
                if (idx >= keep) {
                    d.deleteRecursively()
                    AiTaskBoard.log("清理历史沙箱：" + d.name)
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    /** 2.38 Agent 沙箱根：有工作区时只限工作区，否则回落到目标文件夹。 */
    private fun sandboxBase(): File {
        return agentWorkDir ?: File(selectedDir)
    }

    /** 2.38 工作区日志：追加到 log.txt（AiTaskBoard 仍照常显示）。 */
    private fun workshopLog(msg: String) {
        try {
            val dir = agentWorkDir ?: return
            File(dir, "log.txt").appendText(msg + "\n", Charsets.UTF_8)
        } catch (ignored: Throwable) {
        }
    }

    /** 2.38 错误指纹：静态错误 + 真跑报告前 200 字，同一指纹连续 3 次即熔断。 */
    private fun errorFingerprint(staticErr: String?, runReport: String): String {
        return (staticErr.orEmpty() + "|" + runReport.take(200)).take(300)
    }

    fun show() {
        scanDirs = scanDirList()
        selectedDir = scanDirs.firstOrNull().orEmpty()

        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        val pad = dp(context, 12)
        root.setPadding(pad, pad, pad, pad)

        // 右边给悬浮球留 72dp：球 56dp 宽，贴右边缘时不盖住这两行说明文字。
        val introView = bodyView(context, "给一个网址：先侦察站点结构，再生成 py 源，最后真跑一遍验证。")
        (introView.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(context, 72)
        root.addView(introView)
        if (!AiConfig.isConfigured()) {
            val warn = linkView(context, "没配 AI 模型 —— 点这里填地址 / Key / 模型；不配就只能用模板生成。") {
                AiRepairSettingsDialog.show(context) { AiTaskBoard.log("模型设置已保存") }
            }
            warn.setTextColor(Color.parseColor("#E53935"))
            root.addView(warn)
        }

        val skillInfos = AiSkillPack.infos(context)
        val enabledSkills = skillInfos.filter { it.enabled }
        val skillText = if (enabledSkills.isNotEmpty()) {
            "技能包已加载 " + enabledSkills.size + " 个（共 " + enabledSkills.sumOf { it.chars } + " 字），写源时会按它们执行。"
        } else if (skillInfos.isNotEmpty()) {
            "技能包已上传 " + skillInfos.size + " 个但都未启用，去 AI辅助里打开启用开关。"
        } else {
            "未上传技能包：模型按内置写法写源；去 AI辅助里上传 py 写源技能包可让它按你的规矩写。"
        }
        val skillView = bodyView(context, skillText)
        (skillView.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(context, 72)
        root.addView(skillView)

        val siteUrlInput = EditText(context).apply {
            hint = "https://example.com"
            if (presetUrl.isNotEmpty()) setText(presetUrl)
        }
        val siteNameInput = EditText(context).apply {
            hint = "例如：我的影视源.py（留空自动命名）"
            if (presetName.isNotEmpty()) setText(presetName)
        }
        root.addView(bodyView(context, "站点地址"))
        root.addView(siteUrlInput)
        root.addView(bodyView(context, "源名称"))
        root.addView(siteNameInput)

        root.addView(labelView(context, "目标文件夹"))
        val changeButton = Button(context).apply { text = "更改" }
        changeButton.setOnClickListener { pickFolder() }
        root.addView(changeButton)
        folderView = TextView(context)
        folderView.text = if (selectedDir.isEmpty()) "无扫描目录" else dirDisplayName(selectedDir)
        root.addView(folderView)
        root.addView(bodyView(context, "源会保存到这个文件夹里。"))

        missingView = TextView(context)
        root.addView(missingView)

        generateButton = Button(context).apply { text = "生成 py 源" }
        ViewTheme.styleTonalButton(context, generateButton)
        generateButton.setOnClickListener {
            val url = siteUrlInput.text.toString().trim()
            val name = siteNameInput.text.toString().trim()
            onGenerate(url, name)
        }
        root.addView(generateButton)

        root.addView(bodyView(context, "可以关掉面板，点右下角悬浮球再回来。"))
        root.addView(labelView(context, "任务状态"))
        statusView = TextView(context)
        statusView.text = AiTaskBoard.statusText()
        root.addView(statusView)

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentSiteUrl = siteUrlInput.text.toString().trim()
                refreshMissing(currentSiteUrl)
            }
        }
        siteUrlInput.addTextChangedListener(watcher)
        currentSiteUrl = siteUrlInput.text.toString().trim()
        refreshMissing(currentSiteUrl)

        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val created = LightDialog.create(context, "建源工坊", scroll, "取消", { dialog?.dismiss() }, null, null)
        dialog = created
        created.setOnDismissListener {
            pageAlive = false
            if (AiTaskBoard.statusSink != null) AiTaskBoard.statusSink = null
        }
        pageAlive = true
        AiTaskBoard.statusSink = { text -> if (pageAlive) statusView.text = text }
        AiTaskBoard.reopen = { ctx -> AiAssistPanel.showWorkshop(ctx) }
        created.show()
    }

    private fun refreshMissing(siteUrl: String) {
        val missing = ArrayList<String>()
        if (siteUrl.isEmpty()) missing.add("站点网址")
        if (selectedDir.isEmpty()) missing.add("目标文件夹")
        missingView.text = if (missing.isEmpty()) "已齐全，可以生成" else "还差：" + missing.joinToString("、")
        if (::generateButton.isInitialized) generateButton.isEnabled = missing.isEmpty()
    }

    private fun pickFolder() {
        // 2.50：真正的文件夹浏览器。可进入子文件夹、返回上级、新建文件夹。
        val startDir = if (selectedDir.isNotEmpty()) selectedDir
                       else if (scanDirs.isNotEmpty()) scanDirs.first()
                       else "/storage/emulated/0/Download"
        var currentDir = startDir
        var browserDialog: Dialog? = null
        var listContainer: LinearLayout? = null
        var pathView: TextView? = null
        fun refresh() {
            val dir = File(currentDir)
            pathView?.text = dir.absolutePath
            listContainer?.removeAllViews()
            val parent = dir.parentFile
            if (parent != null && parent.exists()) {
                val upRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(context, 4) }
                }
                val upBtn = Button(context).apply { text = "↩ 上级目录"; ViewTheme.styleTonalButton(context, this) }
                upBtn.setOnClickListener { currentDir = parent.absolutePath; refresh() }
                upRow.addView(upBtn, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                listContainer?.addView(upRow)
            }
            val subs = dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }.orEmpty()
            if (subs.isEmpty()) {
                listContainer?.addView(bodyView(context, "（空文件夹）"))
            } else {
                for (sub in subs) {
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(context, 6), 0, dp(context, 6))
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                    val nameBtn = Button(context).apply {
                        text = "📁 " + sub.name
                        ViewTheme.styleTonalButton(context, this)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    nameBtn.setOnClickListener { currentDir = sub.absolutePath; refresh() }
                    row.addView(nameBtn)
                    val selectBtn = Button(context).apply { text = "选此"; ViewTheme.styleTonalButton(context, this) }
                    selectBtn.setOnClickListener {
                        selectedDir = sub.absolutePath
                        folderView.text = dirDisplayName(selectedDir)
                        // 2.51 修：选好的文件夹记进扫描目录，下次打开直接可选，不然每次都得重新进。
                        if (!scanDirs.contains(selectedDir)) {
                            scanDirs = scanDirs + selectedDir
                        }
                        // 2.53 修：只改内存不够——show() 每次打开都从设置重读 scanDirs，
                        // 不写回设置下次打开就丢。记进 scan_dirs 和 scan_enabled_dirs 并保存。
                        rememberScanDir(selectedDir)
                        // 2.52 修：选完刷新缺项与生成钮——没扫描目录时不刷，缺项还写着"还差：目标文件夹"、生成钮还灰着。
                        refreshMissing(currentSiteUrl)
                        browserDialog?.dismiss()
                    }
                    row.addView(selectBtn)
                    listContainer?.addView(row)
                }
            }
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(context, 12)
            setPadding(pad, pad, pad, pad)
        }
        pathView = TextView(context).apply {
            text = currentDir
            setTextColor(ViewTheme.onSurfaceArgb(context))
            textSize = 12f
            maxLines = 2
        }
        root.addView(pathView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val listScroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        listScroll.addView(listContainer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // 2.51 修：权重在 ScrollView 里分不到空间——弹窗高随内容走、外层又是 ScrollView，
        // listScroll 高 0 + 权重 1f 最后还是 0，真机上文件夹列表看不见。改固定高 320dp，在固定区里滚。
        root.addView(listScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 320)))
        val newBtn = Button(context).apply { text = "新建文件夹"; ViewTheme.styleTonalButton(context, this) }
        newBtn.setOnClickListener {
            val input = EditText(context).apply { hint = "新文件夹名称" }
            val newBox = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad2 = dp(context, 12)
                setPadding(pad2, pad2, pad2, pad2)
                addView(TextView(context).apply { text = "在 " + currentDir + " 下新建文件夹：" })
                addView(input)
            }
            val newScroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
            newScroll.addView(newBox, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            var newDialog: Dialog? = null
            newDialog = LightDialog.create(context, "新建文件夹", newScroll, "创建", {
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    val newDir = File(currentDir, name)
                    if (newDir.mkdirs() || newDir.exists()) {
                        currentDir = newDir.absolutePath
                        refresh()
                    } else {
                        AiTaskBoard.log("创建失败：" + newDir.absolutePath)
                    }
                }
                newDialog?.dismiss()
            }, "取消", { newDialog?.dismiss() })
            newDialog.show()
        }
        root.addView(newBtn, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        browserDialog = LightDialog.create(context, "选择目标文件夹", scroll, "确定", {
            selectedDir = currentDir
            folderView.text = dirDisplayName(selectedDir)
            // 2.51 修：选好的文件夹记进扫描目录，下次打开直接可选。
            if (!scanDirs.contains(selectedDir)) {
                scanDirs = scanDirs + selectedDir
            }
            // 2.53 修：同"选此"，写回设置才持久，不然下次打开工坊就丢。
            rememberScanDir(selectedDir)
            // 2.52 修：选完刷新缺项与生成钮（同"选此"）。
            refreshMissing(currentSiteUrl)
            browserDialog?.dismiss()
        }, "取消", { browserDialog?.dismiss() }, 45)
        // 2.52 修：补丁原样就缺这一行——refresh 只在上级/进子文件夹/新建完才调，
        // 打开那一下没调，列表容器是空的，一个文件夹、一个"选此"都没有，当然选不了。
        // show 之前先填一次，打开就能看到子文件夹。
        refresh()
        browserDialog?.show()
    }

    // 2.53 修：选好的文件夹写回 LocalSource 设置，下次打开工坊还在。
    // 按 LocalSourceSettingsDialog 添加文件夹同款写法：loadSettings / saveSettings，
    // 不用 updateSetting()（它在本地源启用时触发后台自动回写）。
    private fun rememberScanDir(path: String) {
        try {
            val cfg = LocalSource.loadSettings()
            val dirs = LocalSource.stringList(cfg, "scan_dirs")
            if (!dirs.contains(path)) dirs.add(path)
            val enabled = LocalSource.stringList(cfg, "scan_enabled_dirs")
            if (!enabled.contains(path)) enabled.add(path)
            cfg.add("scan_dirs", LocalSource.toJsonArray(dirs))
            cfg.add("scan_enabled_dirs", LocalSource.toJsonArray(enabled))
            LocalSource.saveSettings(cfg)
        } catch (ignored: Throwable) { }
    }

    private fun normalizeSiteUrl(raw: String): String? {
        val text = raw.trim().replace(" ", "")
        if (text.isEmpty()) return null
        val withScheme = if (text.startsWith("http://") || text.startsWith("https://")) text else "https://$text"
        return try {
            val uri = java.net.URI(withScheme)
            if (uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) uri.normalize().toString().trimEnd('/') else null
        } catch (ignored: Throwable) { null }
    }

    private fun onGenerate(rawSiteUrl: String, siteName: String) {
        if (rawSiteUrl.isBlank() || selectedDir.isEmpty()) {
            AiTaskBoard.log("请先填站点网址并选目标文件夹")
            return
        }
        val siteUrl = normalizeSiteUrl(rawSiteUrl)
        if (siteUrl == null) {
            AiTaskBoard.log("网址格式不正确，请输入完整域名，例如 https://example.com（不带 http:// 时会自动补 https://）")
            return
        }
        if (!AiConfig.isConfigured()) {
            AiTaskBoard.log("请先在「模型设置」填写 Base URL、Key 和模型。完整 AI 写源需要模型分析真实网页；未配置时不再输出不可用的起点骨架。")
            AiTaskBoard.setProgress(100, "等待配置模型")
            return
        }
        // 2.24 起不再弹进度对话框：进度只在底下“任务状态”日志里看。
        runAiTask("建源：" + siteUrl) {
            AiTaskBoard.setProgress(5, "开始生成…")
            AiTaskBoard.log("开始侦察：" + siteUrl)
            AiTaskBoard.setProgress(10, "正在侦察站点…")
            val recon = reconSite(siteUrl)
            AiTaskBoard.log("侦察完成")
            AiTaskBoard.log(recon.take(800))
            // 2.50：站点定性（照昏黄影视 SiteScout）。先定性 CMS_API/HTML/SPA/UNKNOWN，
            // 再按定性走对应路线。定性结果写进摘要，供 AI 写源参考。
            val scoutReport = com.github.tvbox.osc.api.ai.AvSiteScout.scout(
                siteUrl,
                fetch = { url ->
                    try {
                        val pr = runCatching {
                            okhttp3.OkHttpClient.Builder()
                                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                                .newCall(okhttp3.Request.Builder().url(url)
                                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
                                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                                    .header("Referer", siteUrl.trimEnd('/') + "/")
                                    .build())
                                .execute()
                        }.getOrNull()
                        if (pr != null) {
                            pr.use { resp ->
                                val body = resp.body?.string().orEmpty()
                                Pair(resp.code, body)
                            }
                        } else Pair(-1, "")
                    } catch (e: Throwable) {
                        Pair(-1, e.message ?: "")
                    }
                },
                progress = { msg -> AiTaskBoard.log(msg) }
            )
            AiTaskBoard.log("站点定性：" + scoutReport.headline())
            if (scoutReport.kind == com.github.tvbox.osc.api.ai.AvSiteScout.KIND_CMS_API) {
                // CMS_API 站：用采集接口模板（字段标准，成功率最高）
                AiTaskBoard.log("命中标准采集接口：" + scoutReport.cms + "，用采集接口模板生成")
                if (scoutReport.classes.isEmpty()) {
                    AiTaskBoard.log("采集接口未拿到分类表，转 AI 驱动")
                }
            }
            if (reconLooksEmpty(recon)) {
                AiTaskBoard.log("侦察未拿到页面正文（常见原因：手机网络访问不了该站、TLS/代理、或站点拦了 App 请求）。请浏览器打开同一网址确认，或换网络/开代理后再建源。已中止，避免空跑三轮。")
                return@runAiTask
            }
            // 2.37 补丁：OneNav 导航目录站分诊。此类站点首页聚合外链，不是本站影视内容，
            // 若继续生成 Spider 会因首页/推荐 0 条进入无效的选择器修复循环。
            if (isWordPressOneNavDirectory(recon)) {
                val candidates = extractExternalHostCandidates(siteUrl, recon)
                AiTaskBoard.log("识别为 WordPress + OneNav 导航目录站：本站未发现可验证的影视分类/详情/播放内容，停止生成，避免输出空源。")
                if (candidates.isNotEmpty()) {
                    AiTaskBoard.log("导航中发现的外部目标候选域名（请选一个真正承载影视内容的网站，复制其完整网址重新建源）：")
                    candidates.forEach { AiTaskBoard.log("候选：" + it) }
                } else {
                    AiTaskBoard.log("未能从侦察文本提取外部域名；请在浏览器打开导航站，手动复制一个真正的影视站网址重新建源。")
                }
                AiTaskBoard.setProgress(100, "分诊完成：请指定外部影视目标站")
                return@runAiTask
            }
            // 2.35 昏黄模板优先：侦察命中 MacCMS/myui → 不用技能包、不用模型写源，直接按侦察拼模板；其余站走原技能包+模型流程。
            var profile = ""
            var profileJson = ""
            // 2.42：主题仍分诊（供日志/参考画像），但不再据此套模板
            val isMac = isMacCmsSite(recon)
            val theme = if (isMac) detectTheme(recon) else "unknown"
            val skillPrefix = AiSkillPack.systemPrefixFor(context, siteUrl, recon)
            // 2.46：一律 AI 驱动。配了模型就让 Agent 看真实 HTML 写源，
            // 不按主题套模板（模板是猜的结构，对不上就 0 条，且主题无穷尽追不上）。
            var code: String
            if (AiConfig.isConfigured()) {
                AiTaskBoard.log(
                    "AI 写源：Agent 看本站真实 HTML 片段（首页/分类/详情/播放抽样）写源，" +
                        "写完自动真跑六接口验证，失败带证据自修"
                )
                profileJson = profileFromRecon(siteUrl, recon)
                profile = profileJson
                val skillPrefix = AiSkillPack.systemPrefixFor(context, siteUrl, recon)
                val seed = seedPy(siteUrl, siteName, recon)
                code = generateWithAgent(seed, profileJson, profile, recon, skillPrefix, siteUrl, siteName)
            } else {
                AiTaskBoard.log("未配 AI 模型：无法 AI 写源，生成起点骨架（配模型后可让 AI 按真实网页写源）")
                profileJson = profileFromRecon(siteUrl, recon)
                profile = profileJson
                code = seedPy(siteUrl, siteName, recon)
            }
            newWorkshopDir(siteUrl)
            AiTaskBoard.setProgress(60, "模型已生成，静态校验…")
            var repairRound = 0
            val maxRepair = AiConfig.getRounds().coerceIn(1, 3)
            var runReport = ""
            var lastStaticOk = if (staticCheck(code) == null) code else ""
            while (true) {
                val checkError = staticCheck(code)
                val lastError: String
                if (checkError == null) {
                    lastStaticOk = code
                    AiTaskBoard.log("静态校验通过，开始真跑六接口")
                    AiTaskBoard.setProgress(70, "静态校验通过，真跑验证…")
                    val runResult = validateRun(code)
                    runReport = runResult.second
                    if (runResult.first) break
                    // 3.9：引擎问题不是源的问题，别让 AI 去改选择器（越改越错）。
                    // 直接把原因摆出来，保存一份源码就收工。
                    if (runReport.startsWith("__ENGINE__")) {
                        val reason = runReport.removePrefix("__ENGINE__")
                        AiTaskBoard.log("无法真跑验证：" + reason)
                        AiTaskBoard.log("源码已生成（未真跑验证），保存到目标文件夹")
                        AiTaskBoard.setProgress(100, "已生成（未验证）")
                        val engineName = autoName(siteUrl).replace(Regex("[/\\\\:*?<>|]"), "_")
                        val engineFile = File(selectedDir, engineName + "_未验证.py")
                        try {
                            AiRepair.writeAtomic(engineFile.absolutePath, code)
                            AiTaskBoard.log("可运行源码已存：" + engineFile.absolutePath)
                            workshopLog("引擎不可用，源码已存：" + engineFile.absolutePath)
                        } catch (ignored: Throwable) {
                        }
                        cleanupWorkshop(agentWorkDir)
                        return@runAiTask
                    }
                    lastError = "真跑未通过（" + runReport + "）"
                } else {
                    lastError = "静态校验未通过：" + checkError
                }
                AiTaskBoard.log(lastError)
                if (!AiConfig.isConfigured() || repairRound >= maxRepair) {
                    // 2.47：熔断也保存最后一版源码（标注未通过），方便用户复制内容
                    // 给 AI 或人工诊断，而不是只留一句日志什么都看不到。
                    AiTaskBoard.log("已修 " + repairRound + " 轮仍未通过，仍保存最后一版源码到目标文件夹供诊断")
                    // 存到用户选的目标文件夹（如"海豚湾"），不是沙箱，方便用户打开复制内容反馈
                    val diagName = autoName(siteUrl).replace(Regex("[/\\\\:*?<>|]"), "_")
                    val diagFile = File(selectedDir, diagName + "_未通过_第" + repairRound + "轮.py")
                    val diagCode = if (code.contains("class Spider")) code else lastStaticOk.ifEmpty { code }
                    try {
                        AiRepair.writeAtomic(diagFile.absolutePath, diagCode)
                        AiTaskBoard.log("未通过源码已存：" + diagFile.absolutePath + "（打开复制内容发反馈可诊断）")
                        workshopLog("未通过源码已存目标文件夹：" + diagFile.absolutePath)
                    } catch (ignored: Throwable) {
                    }
                    // 清理本次沙箱临时文件（log.txt 会越积越大），只留 source.py 备查
                    cleanupWorkshop(agentWorkDir)
                    return@runAiTask
                }
                repairRound++
                AiTaskBoard.setProgress(65, "第 " + repairRound + " 轮修复中…")
                val baseline = when {
                    lastStaticOk.contains("class Spider") -> lastStaticOk
                    code.contains("class Spider") -> code
                    else -> seedPy(siteUrl, siteName, recon)
                }
                val zeroList = lastError.contains("0 条") || lastError.contains("0条") || lastError.contains("真跑")
                val fixSystem = "你是影视源爬虫修复助手。只输出修复后的完整 Python，不要解释、不要 markdown。" +
                    "硬性：必须保留 class Spider(Spider) 与全部接口，禁止删除类。" +
                    "路由与选择器只用侦察摘要/画像里的真实 href，禁止套用任何模板路由；画像里写'未知'的先 fetch_url 查真实地址再改。" +
                    (if (zeroList) "真跑无数据：只改列表/分类 URL 与选择器，让 list 非空；不要输出空骨架。" else "只改出错处。")
                val fixUser = "站点地址：$siteUrl\n站点画像：\n" + (if (profileJson.isNotEmpty()) profileJson else profile) +
                    "\n侦察摘要：\n$recon\n技能包路由提示：\n" + skillRouteHints(recon) +
                    "\n当前基线代码：\n" + baseline + "\n问题：" + lastError + "\n请输出修复后的完整代码（含 class Spider）。"
                val repaired = repairWithAgent(baseline, lastError, siteUrl, siteName, skillPrefix, fixSystem, fixUser)
                if (repaired == baseline) {
                    // 2.45：修复没改动基线，再校验同一份代码必定再失败，直接停，不白烧剩下几轮。
                    AiTaskBoard.log("第 " + repairRound + " 轮修复未改动基线，停止修复，保留基线")
                    return@runAiTask
                }
                code = repaired
                AiTaskBoard.log("第 " + repairRound + " 轮修复已生成，重新校验…")
            }
            AiTaskBoard.log("真跑通过：" + runReport)
            AiTaskBoard.setProgress(90, "真跑通过，正在保存…")
            val baseName = if (siteName.isEmpty()) autoName(siteUrl) else siteName
            val safeName = baseName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            val fileName = if (safeName.endsWith(".py", ignoreCase = true)) safeName else safeName + ".py"
            val file = File(selectedDir, fileName)
            val ok = AiRepair.writeAtomic(file.absolutePath, code)
            if (ok) {
                AiTaskBoard.setProgress(100, "已保存")
                AiTaskBoard.log("已保存到 " + file.absolutePath + "，去本地源扫一次即可生效（不用另行注册）")
                if (profileJson.isNotEmpty()) {
                    val profileName = if (safeName.endsWith(".py", ignoreCase = true)) safeName.substring(0, safeName.length - 3) else safeName
                    val profileFile = File(selectedDir, profileName + ".profile.json")
                    if (AiRepair.writeAtomic(profileFile.absolutePath, profileJson)) {
                        AiTaskBoard.log("站点画像已存为 " + profileFile.absolutePath)
                    }
                }
            } else {
                AiTaskBoard.log("保存失败：写文件未成功")
            }
        }
    }

    private fun autoName(siteUrl: String): String {
        return try {
            val host = java.net.URI(siteUrl).host.orEmpty()
            val bare = host.removePrefix("www.")
            val first = bare.substringBefore(".")
            val safe = first.replace(Regex("[^A-Za-z0-9_\\u4e00-\\u9fa5]"), "")
            if (safe.isEmpty()) "新建源" else safe
        } catch (ignored: Throwable) {
            "新建源"
        }
    }

    /**
     * 2.23 六接口回归（SKILL.md 第 1 节）：依次真跑首页、推荐、分类、详情、搜索、播放；
     * 门是首页 + 详情 + 播放直链，分类与搜索只记结果、不拦保存（个别站这两个接口本身就是坏的）。
     */
    private fun validateRun(py: String): Pair<Boolean, String> {
        val tmp = File(context.cacheDir, "ai_validate_" + System.currentTimeMillis() + ".py")
        tmp.writeText(py, Charsets.UTF_8)
        try {
            // 3.9 修：先确认 Python 引擎真的能跑。
            // 原来直接 pySpider()，Python 不可用时拿到 SpiderNull（六接口全返回 ""），
            // 于是一个完全正确的源也被判成"首页 0 条"，AI 收到"0 条"就去瞎改选择器，
            // 越改越错。这是"AI 写源老跑不起来"的头号原因。
            val engineError = pythonEngineProblem(py)
            if (engineError != null) {
                return Pair(false, "__ENGINE__" + engineError)
            }

            val spider = SpiderLoader().pySpider("ai_v_" + System.currentTimeMillis(), tmp.absolutePath, "")
            // 再确认一次没拿到空壳
            if (spider is com.github.catvod.crawler.SpiderNull) {
                val detail = com.undcover.freedom.pyramid.PythonLoader.getInstance().lastLoadError
                    .ifBlank { "加载器未返回具体异常" }
                val sourceError = Regex("SyntaxError|IndentationError|ImportError|ModuleNotFoundError|NameError|AttributeError|TypeError|invalid syntax|No module named", RegexOption.IGNORE_CASE)
                if (sourceError.containsMatchIn(detail)) return Pair(false, "Python 源码加载失败：" + detail)
                return Pair(false, "__ENGINE__Python 加载器返回空实现（SpiderNull）：" + detail)
            }
            fun safeCall(block: () -> String?): String {
                return try {
                    block().orEmpty()
                } catch (e: Throwable) {
                    // 2.47：异常不再吞掉，回传给 AI 诊断（如 ImportError=缺依赖、
                    // 网络错=被墙/反爬）。AI 看到真实异常才能改对，否则只看到 0 条瞎改。
                    "__EXC__" + (e.javaClass.simpleName + ": " + (e.message ?: "")).take(300)
                }
            }
            fun listSize(json: String): Int {
                return try {
                    JSONObject(json).optJSONArray("list")?.length() ?: 0
                } catch (ignored: Throwable) {
                    0
                }
            }
            fun firstVod(json: String): JSONObject? {
                return try {
                    JSONObject(json).optJSONArray("list")?.optJSONObject(0)
                } catch (ignored: Throwable) {
                    null
                }
            }
            val report = StringBuilder()
            val home = safeCall { spider.homeContent(true) }
            var videoJson = ""
            val homeCount = listSize(home)
            var videoCount = 0
            var sampleJson = home
            if (homeCount <= 0) {
                val video = safeCall { spider.homeVideoContent() }
                videoJson = video
                videoCount = listSize(video)
                if (videoCount > 0) sampleJson = video
            }
            report.append("首页 ").append(homeCount).append(" 条，推荐 ").append(videoCount).append(" 条")
            if (homeCount <= 0 && videoCount <= 0) {
                fun debugOf(json: String): String {
                    return try {
                        JSONObject(json).optString("_debug").orEmpty()
                    } catch (ignored: Throwable) {
                        ""
                    }
                }
                val dbg = listOf(debugOf(home), debugOf(videoJson)).filter { it.isNotEmpty() }.distinct()
                if (dbg.isNotEmpty()) report.append("（").append(dbg.joinToString("；")).append("）")
                // 3.9：把异常原文带出来。原来异常只进了 safeCall 的字符串，
                // 却被 listSize 吃掉变成"0 条"，AI 完全看不到 ImportError / 超时 / SSL。
                val exc = listOf(home, videoJson).firstOrNull { it.startsWith("__EXC__") }
                if (exc != null) {
                    report.append("｜接口异常：").append(exc.removePrefix("__EXC__"))
                } else {
                    report.append("｜接口正常返回但列表为空：选择器没匹配到节点，或站点要求 Referer/Cookie")
                }
                return Pair(false, report.toString())
            }
            var firstTid = ""
            try {
                firstTid = JSONObject(home).optJSONArray("class")?.optJSONObject(0)?.optString("type_id").orEmpty()
            } catch (ignored: Throwable) {
            }
            if (firstTid.isNotEmpty()) {
                val categoryCount = listSize(safeCall { spider.categoryContent(firstTid, "1", false, null) })
                report.append("，分类 ").append(categoryCount).append(" 条")
            } else {
                report.append("，分类未取到")
            }
            val first = firstVod(sampleJson) ?: return Pair(false, report.append("，详情无样本").toString())
            val firstId = first.optString("vod_id").orEmpty()
            val firstName = first.optString("vod_name").orEmpty()
            if (firstId.isEmpty()) return Pair(false, report.append("，详情无 vod_id").toString())
            val detail = safeCall { spider.detailContent(listOf(firstId)) }
            val detailVod = firstVod(detail) ?: return Pair(false, report.append("，详情 0 条").toString())
            report.append("，详情 OK")
            if (firstName.isNotEmpty()) {
                val searchCount = listSize(safeCall { spider.searchContent(firstName, false) })
                report.append("，搜索 ").append(searchCount).append(" 条")
            }
            val playFrom = detailVod.optString("vod_play_from").orEmpty()
            val playUrl = detailVod.optString("vod_play_url").orEmpty()
            if (playFrom.isEmpty() || playUrl.isEmpty()) return Pair(false, report.append("，播放无线路").toString())
            val flag = playFrom.split("$$$")[0]
            val firstLine = playUrl.split("$$$")[0]
            val firstEp = firstLine.split("#")[0]
            val playId = firstEp.substringAfter("$", "")
            if (playId.isEmpty()) return Pair(false, report.append("，播放无剧集 ID").toString())
            val played = safeCall { spider.playerContent(flag, playId, null) }
            val directUrl = try {
                JSONObject(played).optString("url").orEmpty()
            } catch (ignored: Throwable) {
                ""
            }
            if (directUrl.isEmpty()) return Pair(false, report.append("，播放未返回地址").toString())
            if (!isDirectMediaUrl(directUrl)) return Pair(false, report.append("，播放仍是播放页，未解析出直链（").append(directUrl.take(80)).append("）").toString())
            report.append("，播放直链 OK")
            return Pair(true, report.toString())
        } finally {
            try {
                tmp.delete()
            } catch (ignored: Throwable) {
            }
        }
    }

    /**
     * Python 引擎预检：在真跑之前先确认"能不能跑"，而不是跑完看 0 条。
     *
     * 返回 null 表示引擎可用；返回值是给用户/AI 看的具体原因。
     *
     * 三层检查：
     *  1. 系统层面：Android 16+ 的 32 位进程跑不了 Python（pyLoader 里写死的限制）
     *  2. 源码层面：import 了本环境没有的模块（cloudscraper / lxml 等），
     *     这类错误在真跑时会变成 0 条，完全看不出原因
     *  3. 硬禁用：lxml 会产生静默失败的解析结果，提示词里本来就禁了
     */
    private fun pythonEngineProblem(py: String): String? {
        // 1. 系统限制：对齐 pyLoader.isPythonSupported()
        if (android.os.Build.VERSION.SDK_INT >= 36 && !android.os.Process.is64Bit()) {
            return "Android 16+ 的 32 位进程不支持 Python 引擎，无法真跑验证。" +
                "源码已生成，请换 64 位设备或自行在电脑上验。"
        }

        // 2. 硬禁用：lxml 在本环境会静默失败导致 0 条
        if (Regex("""(^|\n)\s*(from\s+lxml|import\s+lxml)""").containsMatchIn(py)) {
            return "源码 import 了 lxml，本环境 lxml 不稳定会静默失败。请改用 " +
                "from bs4 import BeautifulSoup 重新解析。"
        }

        // 3. 缺模块：只查常见且环境里确实没有的，避免误判
        val risky = mapOf(
            "cloudscraper" to "cloudscraper（Cloudflare 绕过），请改用 requests + 真实 UA",
            "selenium" to "selenium（浏览器自动化），本环境不支持",
            "playwright" to "playwright（浏览器自动化），本环境不支持",
            "pyquery" to "pyquery，请改用 BeautifulSoup",
            "scrapy" to "scrapy 框架，本环境只支持单文件 Spider",
            "pandas" to "pandas，本环境未内置",
            "numpy" to "numpy，本环境未内置",
        )
        for ((mod, why) in risky) {
            val hit = Regex("""(^|\n)\s*(from\s+""" + Regex.escape(mod) + """|import\s+""" + Regex.escape(mod) + """)\b""")
                .containsMatchIn(py)
            if (hit) return "源码依赖 $why。环境里没有这个模块，真跑必然失败。"
        }
        return null
    }

    /** 播放直链判定：看起来像 m3u8/mp4 等媒体地址才算直链，站内播放页不算。 */
    private fun isDirectMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return false
        val media = arrayOf(".m3u8", ".mp4", ".flv", ".mkv", ".mov", ".ts", ".m4v", ".webm")
        if (media.any { lower.contains(it) }) return true
        if (lower.contains("m3u8") || lower.contains("mime=video")) return true
        return false
    }

    /**
     * 2.38 Agent 修源：提示词协议工具调用（不依赖模型原生 function calling，任何聊天模型都能用）。
     * 模型在回复里用 ```tool_call JSON 块调工具，或用 ```python 代码块交最终答案。
     * 工具在工坊沙箱内自动放行（用户定）：文件读写只限选定的目标文件夹，fetch 仅 http/https。
     * 工作文件是目标文件夹里的 _agent_work.py（草稿，跑完删除）；最终答案仍以代码块提交，外层循环照常校验保存。
     */
    private fun agentToolDefinitions(): String {
        return "可用工具（一次只调一个，用 ```tool_call 代码块输出 JSON，格式：{\"name\": \"工具名\", \"arguments\": {...}}；调完等工具结果再继续；修好后直接输出完整 Python 代码块交答案，不要再调工具）：\n" +
            "1. fetch_url(url)：拉取一个 http/https 页面，返回 HTTP 状态、长度与正文前 12000 字。\n" +
            "2. read_skill(moduleKey)：按需读一个技能模块（如 maccms_mytheme_code、maccms_example、onenav_directory、agent_workflow），返回该模块文本（前 8000 字）；命中 mytheme 时先读 maccms_mytheme 再写源。\n" +
            "3. write_source(content)：把完整 Spider 源码写入工作区 source.py（唯一主文件）。\n" +
            "4. static_check(path)：对工作区里的一个 Python 文件做静态校验，返回通过或错误。\n" +
            "5. run_six_api()：真跑工作区 source.py 的六接口，返回各接口条数与报告。\n" +
            "6. read_file(path)：读工作区里的一个文件，返回内容（前 8000 字）。\n" +
            "7. write_file(path, content)：把完整内容写入工作区里的一个文件。\n" +
            "8. edit_file(path, old, new)：把文件里第一次出现的 old 替换成 new。\n" +
            "9. run_spider(path)：真跑工作区里的一个 Spider 文件的六接口，返回各接口条数与报告。\n" +
            "10. list_files()：列出工作区里的文件。\n" +
            "硬规则：只改导致 0 条的那一项；必须保留 class Spider(Spider) 与全部接口；不要为凑数伪造数据；工具结果是唯一依据，不要凭猜测下结论；完成前必须自己调 static_check 与 run_six_api 验过。"
    }

    /** 从模型回复里抠 ```tool_call JSON 块；没有返回 null。返回 (name, argumentsJson)。 */
    private fun parseToolCall(content: String): Pair<String, String>? {
        val startMarker = "```tool_call"
        val start = content.indexOf(startMarker)
        if (start < 0) return null
        val jsonStart = content.indexOf('{', start + startMarker.length)
        if (jsonStart < 0) return null
        val end = content.indexOf("```", jsonStart)
        if (end < 0) return null
        val jsonText = content.substring(jsonStart, end).trim()
        return try {
            val obj = JSONObject(jsonText)
            val name = obj.optString("name", "")
            if (name.isEmpty()) null else name to (obj.optJSONObject("arguments")?.toString().orEmpty())
        } catch (ignored: Throwable) {
            null
        }
    }

    /** 2.38 沙箱：只接受目标文件夹内的相对路径；绝对路径、.. 越界一律拒绝。 */
    private fun sandboxFile(path: String): File? {
        return try {
            val clean = path.trim().replace('\\', '/').trimStart('/')
            if (clean.isEmpty() || clean.contains("..")) return null
            val base = sandboxBase().canonicalFile
            val f = File(base, clean).canonicalFile
            if (!f.path.startsWith(base.path)) null else f
        } catch (ignored: Throwable) {
            null
        }
    }

    /** 2.38 执行一个 Agent 工具，返回给模型的工具结果文本（已截断）。 */
    private fun runAgentTool(name: String, argsJson: String, siteUrl: String): String {
        return try {
            val args = if (argsJson.isEmpty()) JSONObject() else JSONObject(argsJson)
            when (name) {
                "fetch_url" -> {
                    val url = args.optString("url", "")
                    if (!url.startsWith("http://") && !url.startsWith("https://")) return "工具错误：fetch_url 只接受 http/https 链接"
                    val client = OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .followRedirects(true)
                        .build()
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                        .header("Referer", siteUrl.trim().trimEnd('/') + "/")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        val text = resp.body?.string().orEmpty()
                        "HTTP " + resp.code + " len=" + text.length + "\n" + text.take(12000)
                    }
                }
                "read_file" -> {
                    val f = sandboxFile(args.optString("path", ""))
                        ?: return "工具错误：read_file 只限目标文件夹内的文件"
                    if (!f.exists()) return "工具错误：文件不存在：" + f.name
                    f.readText(Charsets.UTF_8).take(8000)
                }
                "write_file" -> {
                    val f = sandboxFile(args.optString("path", ""))
                        ?: return "工具错误：write_file 只限目标文件夹内的文件"
                    val content = args.optString("content", "")
                    f.parentFile?.mkdirs()
                    f.writeText(content, Charsets.UTF_8)
                    "已写入 " + f.name + "（" + content.length + " 字）"
                }
                "edit_file" -> {
                    val f = sandboxFile(args.optString("path", ""))
                        ?: return "工具错误：edit_file 只限目标文件夹内的文件"
                    if (!f.exists()) return "工具错误：文件不存在：" + f.name
                    val old = args.optString("old", "")
                    val new = args.optString("new", "")
                    val text = f.readText(Charsets.UTF_8)
                    if (old.isEmpty() || !text.contains(old)) return "工具错误：文件里找不到要替换的 old 文本"
                    f.writeText(text.replaceFirst(old, new), Charsets.UTF_8)
                    "已替换 " + f.name + " 里第一次出现的 old 文本"
                }
                "static_check" -> {
                    val f = sandboxFile(args.optString("path", ""))
                        ?: return "工具错误：static_check 只限目标文件夹内的文件"
                    if (!f.exists()) return "工具错误：文件不存在：" + f.name
                    staticCheck(f.readText(Charsets.UTF_8)) ?: "静态校验通过"
                }
                "run_spider" -> {
                    val f = sandboxFile(args.optString("path", ""))
                        ?: return "工具错误：run_spider 只限目标文件夹内的文件"
                    if (!f.exists()) return "工具错误：文件不存在：" + f.name
                    validateRun(f.readText(Charsets.UTF_8)).second.take(2000)
                }
                "list_files" -> {
                    val files = sandboxBase().listFiles()?.map { it.name }?.sorted().orEmpty()
                    if (files.isEmpty()) "目标文件夹为空" else files.joinToString("\n")
                }
                "read_skill" -> {
                    val q = args.optString("moduleKey", "")
                    val query = if (q.isEmpty()) args.optString("path", "") else q
                    if (query.isEmpty()) return "工具错误：read_skill 需要 moduleKey 或 path"
                    val text = AiSkillPack.readModule(context, query)
                    if (text.isEmpty()) "工具错误：技能包里没有这个模块：" + query else text.take(8000)
                }
                "write_source" -> {
                    val content = args.optString("content", "")
                    val f = File(sandboxBase(), "source.py")
                    f.parentFile?.mkdirs()
                    f.writeText(content, Charsets.UTF_8)
                    "已写入 source.py（" + content.length + " 字）"
                }
                "run_six_api" -> {
                    val f = File(sandboxBase(), "source.py")
                    if (!f.exists()) return "工具错误：工作区还没有 source.py，先用 write_source 写一个"
                    validateRun(f.readText(Charsets.UTF_8)).second.take(2000)
                }
                else -> "工具错误：未知工具 " + name
            }
        } catch (e: Throwable) {
            "工具错误：" + (e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 2.38 一轮 Agent 修源：模型在 8 个工具回合内自己查、自己改、自己跑，最后交完整代码。
     * 工作文件是工作区 source.py（不删除，留作产物）；同一错误指纹连续 3 次即熔断。
     * 返回合并后的代码（mergeSpiderCode 兜底：模型交不出完整类时保留基线）。
     */
    private fun repairWithAgent(baseline: String, lastError: String, siteUrl: String, siteName: String, skillPrefix: String, fixSystem: String, fixUser: String): String {
        val workFile = "source.py"
        try {
            sandboxFile(workFile)?.writeText(baseline, Charsets.UTF_8)
        } catch (ignored: Throwable) {
        }
        // 2.45：修复轮主路径是直接交完整代码，不再强制"先读再改再验再交"——
        // 两头摇摆就是"未交完整代码"的根：模型既不调工具也不交代码，只回空话。
        val system = fixSystem + "\n" + agentToolDefinitions() +
            "\n工作文件是 " + workFile + "（已写入当前基线代码）。主路径：直接输出修复后的完整 Python 代码块（从 # coding=utf-8 到文件末尾，含 class Spider 与六接口），不要解释、不要分段交；工具只用于查真实地址（fetch_url），不要用 edit_file/write_source 代替交代码。"
        val history = ArrayList<Pair<String, String>>()
        history.add("user" to fixUser)
        var consecutiveFail = 0
        var lastChanceUsed = false
        var turn = 0
        val maxTurns = maxOf(AiConfig.getRounds(), 8)
        var lastFingerprint = ""
        var sameErrorCount = 0
        while (turn < maxTurns) {
            if (agentCancelled) {
                AiTaskBoard.log("Agent 已取消，保留基线")
                workshopLog("Agent 已取消，保留基线")
                break
            }
            turn++
            val reply = try {
                callChatHistory(system, history, skillPrefix)
            } catch (e: Throwable) {
                AiTaskBoard.log("Agent 第 " + turn + " 回合模型调用失败：" + (e.message ?: e.javaClass.simpleName))
                consecutiveFail++
                if (consecutiveFail >= 3) break
                history.add("user" to "模型调用失败，请继续修源（可调工具或直接交代码）。")
                continue
            }
            val toolCall = parseToolCall(reply)
            if (toolCall == null && reply.contains("```tool_call")) {
                AiTaskBoard.log("Agent 第 " + turn + " 回合工具调用解析失败（JSON 损坏），按无代码处理")
            }
            if (toolCall != null) {
                val toolName = toolCall.first
                val toolArgs = toolCall.second
                AiTaskBoard.log("Agent 工具：" + toolName)
                workshopLog("工具：" + toolName)
                val result = runAgentTool(toolName, toolArgs, siteUrl)
                AiTaskBoard.log("工具 " + toolName + " 返回：" + result.take(120).replace("\n", " "))
                val isFail = result.startsWith("工具错误") ||
                    (toolName == "static_check" && result != "静态校验通过") ||
                    ((toolName == "run_spider" || toolName == "run_six_api") && (result.contains("0 条") || result.contains("未通过") || result.contains("失败")))
                if (isFail) {
                    val fp = errorFingerprint(if (toolName == "static_check") result else null, if (toolName == "run_spider" || toolName == "run_six_api") result else "")
                    if (fp == lastFingerprint) sameErrorCount++ else { lastFingerprint = fp; sameErrorCount = 1 }
                    if (sameErrorCount >= 2) {
                        AiTaskBoard.log("同一错误连续 2 次，停止本轮")
                        workshopLog("同一错误连续 2 次，停止本轮")
                        break
                    }
                } else {
                    lastFingerprint = ""
                    sameErrorCount = 0
                }
                history.add("assistant" to reply)
                history.add("user" to "工具 " + toolName + " 返回：\n" + result + "\n继续修源：可再调工具，或修好后直接输出完整 Python 代码块。")
                consecutiveFail = 0
                continue
            }
            val code = extractCode(reply)
            if (code.contains("class Spider")) {
                AiTaskBoard.log("Agent 第 " + turn + " 回合交出代码，合并基线…")
                workshopLog("第 " + turn + " 回合交出代码")
                return mergeSpiderCode(code, baseline, siteUrl, siteName)
            }
            consecutiveFail++
            AiTaskBoard.log("Agent 第 " + turn + " 回合未交完整代码（无 class Spider），继续…")
            if (consecutiveFail >= 3 && !lastChanceUsed) {
                // 2.45：最后一搏。3 次空回合后不再直接断轮，改发一次短促的只交代码追问，
                // 再空才断——长上下文里模型常在分析性废话里耗尽输出，短追问能把它拉回正轨。
                lastChanceUsed = true
                consecutiveFail = 0
                AiTaskBoard.log("Agent 连续 3 回合未交代码，最后追问一次…")
                history.add("assistant" to reply)
                history.add("user" to "不要解释、不要调工具、不要分析，直接输出修复后的完整 Python 代码块（从 # coding=utf-8 到文件末尾，含 class Spider 与六接口）。只交代码。")
                continue
            }
            if (consecutiveFail >= 3) break
            history.add("assistant" to reply)
            history.add("user" to "你没有交出含 class Spider 的完整代码。请调工具查明原因，或直接输出完整 Python 代码块。")
        }
        // 2.45：交不出代码块时先回读工作区 source.py——模型可能用 edit_file/write_source
        // 改过文件，丢掉等于白改；再退基线（与生成轮一致）。
        val workCode = try {
            sandboxFile(workFile)?.readText(Charsets.UTF_8).orEmpty()
        } catch (ignored: Throwable) {
            ""
        }
        if (workCode.contains("class Spider") && workCode != baseline) {
            AiTaskBoard.log("Agent 本轮未修出代码块，改用工作区 source.py")
            return mergeSpiderCode(workCode, baseline, siteUrl, siteName)
        }
        AiTaskBoard.log("Agent 本轮未修出可用代码，保留基线")
        return baseline
    }

    /**
     * 2.38 完整工坊：Agent 写源（生成）。模型用工具从起点骨架建出完整 Spider：
     * 先 fetch_url 拉页面、read_skill 读匹配模块（mytheme 站先读 maccms_mytheme），
     * 再 write_source 写工作区 source.py，static_check 与 run_six_api 自验，验通过再交完整代码块。
     * 8 个工具回合；同一错误指纹连续 3 次即熔断；交不出完整类时返回工作区里已有的可用代码，否则返回起点骨架。
     */
    private fun generateWithAgent(seed: String, profileJson: String, profile: String, recon: String, skillPrefix: String, siteUrl: String, siteName: String): String {
        newWorkshopDir(siteUrl)
        try {
            sandboxFile("source.py")?.writeText(seed, Charsets.UTF_8)
        } catch (ignored: Throwable) {
        }
        val genSystem = "你是影视源爬虫编写助手。核心原则：**以侦察摘要里本站真实 HTML 片段为准写选择器，禁止套用任何固定模板的 class 名**。" +
            "不同站的列表 class、详情路由、播放结构都不同（可能是 stui-vodlist__box、module-poster-item、module-card-item、myui-vodlist__thumb，或完全自定义的 class）——" +
            "侦察摘要的『列表 class』『详情路由』『分类路由』『XX页片段』就是本站真实结构，照它写；片段里没有的 class 不要凭空编。" +
            "\n【最重要的一条】侦察摘要末尾有『结构核对 x/7』，这是写源前必须补齐的清单：" +
            "①首页列表 ②分类路由 ③筛选选项 ④详情字段 ⑤播放线路 ⑥播放集数 ⑦播放直链。" +
            "凡标 [缺] 的项，必须先用 fetch_url 抓对应页面把它找出来，再写源；" +
            "七项全部落实后一次性写完整 Spider，不要写一版跑一次。" +
            "标 [缺] 的项禁止在代码里写「未知」、禁止凭猜测填选择器、禁止留 TODO。" +
            "\n数据格式硬规定（写错必 0 条）：" +
            "homeContent 返回 {\"class\":[{\"type_id\":\"1\",\"type_name\":\"电影\"}],\"list\":[{\"vod_id\":\"/voddetail/123.html\",\"vod_name\":\"片名\",\"vod_pic\":\"封面\"}]}；" +
            "categoryContent 返回 {\"list\":[...],\"page\":pg,\"pagecount\":9999,\"limit\":20,\"total\":9999}；" +
            "detailContent 返回 {\"list\":[{\"vod_id\":\"id\",\"vod_name\":\"名\",\"vod_pic\":\"图\",\"vod_content\":\"简介\"," +
            "\"vod_play_from\":\"线路1${'$'}${'$'}${'$'}线路2\",\"vod_play_url\":\"第1集${'$'}地址1#第2集${'$'}地址2${'$'}${'$'}${'$'}线路2第1集${'$'}地址\"}]}；" +
            "playerContent 返回 {\"parse\":0,\"playUrl\":\"\",\"url\":\"m3u8地址\",\"header\":{}}；" +
            "解析器页拿不到直链时返回 {\"parse\":1,\"playUrl\":\"\",\"url\":\"播放页地址\"}。" +
            "分隔符只有两个：${'$'}${'$'}${'$'} 分线路、# 分集、${'$'} 分集名与地址，别搞混。" +
            "解析库只用 requests + BeautifulSoup（from bs4 import BeautifulSoup），**不要用 lxml/etree**（本环境 lxml 不稳定会静默失败导致 0 条）。" +
            "抓取必须带完整 headers：User-Agent（真实浏览器 UA）+ Referer=首页 + Accept-Language。" +
            "规范：from base.spider import Spider；class Spider(Spider)；六接口齐全（homeContent/homeVideoContent/categoryContent/detailContent/searchContent/playerContent）；requests.Session。" +
            "路由只能用侦察摘要和画像里真实出现的 href 原样拼（详情/分类/播放三路），禁止套用任何模板路由（?cates-、?vodss-、/index.php/vod/ 等）；画像里写'未知'的项，先用 fetch_url 到站上查到真实 href 再写。" +
            "起点骨架的路由、列表 class、分类、筛选已按侦察真实地址生成，直接用，不要重猜；翻页路由来自分类页真实的翻页链接，没有就不要猜；筛选按侦察给出的真实选项链接拼。" +
            "交付源码不要 __main__ 测试块、TODO、空壳接口和无关注释；六接口必须直接可供 App 加载。"
        val genUser = "站点地址：" + siteUrl + "\n源名称：" + siteName +
            "\n站点画像：\n" + (if (profileJson.isNotEmpty()) profileJson else profile) +
            "\n侦察摘要：\n" + recon +
            "\n技能包路由提示：\n" + skillRouteHints(recon) +
            "\n起点骨架已写入工作区 source.py（请改成能抓到数据，不要删 class）：\n" + seed +
            "\n\n【执行顺序，必须遵守】" +
            "\n1. 先看侦察摘要末尾的『结构核对 x/7』，列出所有标 [缺] 的项；" +
            "\n2. 对每个 [缺] 的项，用 fetch_url 抓对应页面把它找出来" +
            "（缺首页列表→抓首页；缺分类路由→抓首页导航；缺筛选→抓分类页；" +
            "缺详情字段→抓一个详情页；缺线路/集数→抓详情页播放区；缺直链→抓播放页）；" +
            "\n3. 七项都拿到真实值后，一次性写完整 Spider（write_source），" +
            "把每一项的真实选择器/路由直接写进代码，不要留任何 TODO 或「未知」；" +
            "\n4. 最后 static_check + run_six_api 自验，六接口全非空才交答案。" +
            "\n不要跳过第 1、2 步直接写代码——那正是以前反复 0 条的原因。"
        val system = genSystem + "\n" + agentToolDefinitions() +
            "\n工作文件是 source.py（已写入起点骨架）：先读它，再用 edit_file / write_source 改，最后用 static_check 与 run_six_api 验，验通过再交完整代码块。"
        val history = ArrayList<Pair<String, String>>()
        history.add("user" to genUser)
        var consecutiveFail = 0
        var turn = 0
        val maxTurns = maxOf(AiConfig.getRounds(), 8)
        var lastFingerprint = ""
        var sameErrorCount = 0
        AiTaskBoard.log("Agent 写源开始（工具循环，最多 8 回合）")
        workshopLog("Agent 写源开始")
        while (turn < maxTurns) {
            if (agentCancelled) {
                AiTaskBoard.log("Agent 已取消，返回起点骨架")
                break
            }
            turn++
            val reply = try {
                callChatHistory(system, history, skillPrefix)
            } catch (e: Throwable) {
                AiTaskBoard.log("Agent 写源第 " + turn + " 回合模型调用失败：" + (e.message ?: e.javaClass.simpleName))
                consecutiveFail++
                if (consecutiveFail >= 3) break
                history.add("user" to "模型调用失败，请继续写源（可调工具或直接交代码）。")
                continue
            }
            val toolCall = parseToolCall(reply)
            if (toolCall == null && reply.contains("```tool_call")) {
                AiTaskBoard.log("Agent 写源第 " + turn + " 回合工具调用解析失败（JSON 损坏），按无代码处理")
            }
            if (toolCall != null) {
                val toolName = toolCall.first
                val toolArgs = toolCall.second
                AiTaskBoard.log("Agent 工具：" + toolName)
                workshopLog("工具：" + toolName)
                val result = runAgentTool(toolName, toolArgs, siteUrl)
                AiTaskBoard.log("工具 " + toolName + " 返回：" + result.take(120).replace("\n", " "))
                val isFail = result.startsWith("工具错误") ||
                    (toolName == "static_check" && result != "静态校验通过") ||
                    ((toolName == "run_spider" || toolName == "run_six_api") && (result.contains("0 条") || result.contains("未通过") || result.contains("失败")))
                if (isFail) {
                    val fp = errorFingerprint(if (toolName == "static_check") result else null, if (toolName == "run_spider" || toolName == "run_six_api") result else "")
                    if (fp == lastFingerprint) sameErrorCount++ else { lastFingerprint = fp; sameErrorCount = 1 }
                    if (sameErrorCount >= 2) {
                        AiTaskBoard.log("同一错误连续 2 次，停止写源")
                        workshopLog("同一错误连续 2 次，停止写源")
                        break
                    }
                } else {
                    lastFingerprint = ""
                    sameErrorCount = 0
                }
                history.add("assistant" to reply)
                history.add("user" to "工具 " + toolName + " 返回：\n" + result + "\n继续写源：可再调工具，或写好后直接输出完整 Python 代码块。")
                consecutiveFail = 0
                continue
            }
            val code = extractCode(reply)
            if (code.contains("class Spider")) {
                AiTaskBoard.log("Agent 写源第 " + turn + " 回合交出代码，合并基线…")
                workshopLog("第 " + turn + " 回合交出代码")
                return mergeSpiderCode(code, seed, siteUrl, siteName)
            }
            consecutiveFail++
            AiTaskBoard.log("Agent 写源第 " + turn + " 回合未交完整代码（无 class Spider），继续…")
            if (consecutiveFail >= 3) break
            history.add("assistant" to reply)
            history.add("user" to "你没有交出含 class Spider 的完整代码。请调工具查明原因，或直接输出完整 Python 代码块。")
        }
        val workCode = try {
            sandboxFile("source.py")?.readText(Charsets.UTF_8).orEmpty()
        } catch (ignored: Throwable) {
            ""
        }
        if (workCode.contains("class Spider")) {
            AiTaskBoard.log("Agent 写源未交代码块，改用工作区 source.py")
            return workCode
        }
        AiTaskBoard.log("Agent 写源未产出可用代码，返回起点骨架")
        return seed
    }
}

/**
 * 深度侦察：首页 + 相对链接归纳 + 抽样分类/详情/播放页。
 * 旧版只抓首页 title 与 20 条绝对外链，模型只能填「未知」，真跑必 0 条。
 */
    // 2.45：路由占位按位置换：第一个数字是 tid/id，后面的是页码/序号。
    // 全换成 {tid} 会把 /index.php/vod/show/id/1/page/2.html 的页码也写成 tid。
    private fun generalizeRoute(href: String?, first: String, rest: String): String {
        if (href.isNullOrEmpty()) return ""
        val scheme = href.indexOf("://")
        val pathIdx = if (scheme >= 0) href.indexOf("/", scheme + 3).takeIf { it >= 0 } ?: href.length else 0
        val head = href.substring(0, pathIdx)
        val path = href.substring(pathIdx)
        var i = 0
        return head + path.replace(Regex("\\d+")) { if (i++ == 0) first else rest }
    }

    // 2.45：筛选组。pos 是短横段位（tid 后第几段），label 是组名，options 是 名 或 名@值，hrefs 是选项原链接。
    // 2.45：取 href 的路径部分（去协议+域名、去查询与锚点），相对链接补一个前导 /，只比路径形状。
    private fun hrefPath(href: String): String {
        val scheme = href.indexOf("://")
        val idx = if (scheme >= 0) href.indexOf("/", scheme + 3) else 0
        val p0 = if (idx < 0) href else href.substring(idx)
        val p = p0.substringBefore("?").substringBefore("#")
        return if (p.isEmpty() || p.startsWith("/")) p else "/" + p
    }

    // 2.45：找出 href 相对 base 改动的字符区间（左闭右开）与替换值；没改动返回 null。
    // 不假设任何分隔符与段数，只比两个真实链接的公共前后缀。
    private fun filterRange(base: String, href: String): Triple<Int, Int, String>? {
        var n = 0
        while (n < base.length && n < href.length && base[n] == href[n]) n++
        var m = 0
        while (m < base.length - n && m < href.length - n && base[base.length - 1 - m] == href[href.length - 1 - m]) m++
        val start = n
        val end = base.length - m
        val value = href.substring(n, href.length - m)
        if (start == end && value.isEmpty()) return null
        return Triple(start, end, value)
    }

    // 2.45：筛选组。idx 是组序号（按改动区间起点排），label 是组名，options 是（链接文字，真实选项链接）。
    private data class FilterGroup(val idx: Int, val label: String, val options: List<Pair<String, String>>)

    // 2.45：从分类页提筛选组。筛选链接是真实 href；每组选项相对当前分类 URL 改动的是同一字符区间，
    // 区间即维度——不假设短横、斜杠或参数，真实网站是什么形状就是什么形状。
    private fun extractFilters(catUrl: String, cbody: String): List<FilterGroup> {
        val basePath = hrefPath(catUrl)
        if (basePath.isEmpty()) return emptyList()
        val dirPrefix = basePath.substringBeforeLast("/", "")
        val byRange = LinkedHashMap<Pair<Int, Int>, LinkedHashMap<String, String>>()
        val labels = LinkedHashMap<Pair<Int, Int>, String>()
        val linkRe = Regex("""<a[^>]+href=["']([^"']+)["'][^>]*>([^<]{1,20})</a>""", RegexOption.IGNORE_CASE)
        for (m in linkRe.findAll(cbody)) {
            val href = m.groupValues[1]
            val text = m.groupValues[2].trim()
            if (text.isEmpty() || href.startsWith("javascript:") || href == "#") continue
            val p = hrefPath(href)
            if (p.isEmpty() || p == basePath) continue
            if (dirPrefix.isNotEmpty() && !p.startsWith(dirPrefix)) continue
            val r = filterRange(basePath, p) ?: continue
            val key = r.first to r.second
            val opts = byRange.getOrPut(key) { LinkedHashMap() }
            if (!opts.containsKey(text)) opts[text] = p
            if (!labels.containsKey(key)) {
                val before = cbody.substring(0, m.range.first).takeLast(300)
                val lab = Regex(""">([^<>]{1,8})[：:]<""").findAll(before).lastOrNull()?.groupValues?.getOrNull(1)?.trim().orEmpty()
                labels[key] = lab
            }
        }
        return byRange.entries.sortedBy { it.key.first }.take(6).mapIndexed { i, (range, opts) ->
            val label = labels[range].orEmpty().ifEmpty { "筛选" + (i + 1) }
            FilterGroup(i + 1, label, opts.entries.take(8).map { (t, h) -> t to h })
        }
    }

/** 3.68：去 private 供 B/C 共用（侦察只此一份，不许在别处复制）。 */
suspend fun reconSite(siteUrl: String): String {
    return try {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        val base = siteUrl.trim().trimEnd('/')
        fun fetch(url: String, desktop: Boolean = false): Triple<Int, String, String> {
            return try {
                val ua = if (desktop) {
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                } else {
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                }
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", ua)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Referer", base + "/")
                    .build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val finalUrl = resp.request.url.toString()
                    Triple(resp.code, text, finalUrl)
                }
            } catch (e: Throwable) {
                Triple(-1, "", e.message ?: e.javaClass.simpleName)
            }
        }
        fun absUrl(href: String): String {
            val h = href.trim()
            if (h.startsWith("http://") || h.startsWith("https://")) return h
            if (h.startsWith("//")) return "https:" + h
            if (h.startsWith("?")) return base + "/" + h
            if (h.startsWith("/")) return base + h
            return base + "/" + h
        }
        var home = fetch(base, desktop = false)
        // 手机 UA 若正文为空，再试桌面 UA（部分站对 App/WebView 指纹返回空壳）
        if (home.second.length < 200) {
            val retry = fetch(base, desktop = true)
            if (retry.second.length > home.second.length) home = retry
        }
        if (home.first == -1) {
            return "侦察失败：网络错误 " + home.third + "（请确认手机浏览器能打开该站）"
        }
        if (home.first !in 200..399) {
            return "站点 HTTP " + home.first + " final=" + home.third + " bodyLen=" + home.second.length
        }
        var body = home.second
        // 2.41：SPA/JS 渲染站兜底。OkHttp 抓到空壳 <div id="app"></div> 时，
        // 用离屏 WebView 真正执行 JS、等数据渲染后再取 outerHTML。
        // 这样 Vue/React/Nuxt 站也能看到真实列表，避免选择器全写「未知」。
        var renderedBy = "okhttp"
        if (JsRender.looksLikeSpa(body) || body.length < 400) {
            AiTaskBoard.log("首页疑似 SPA/空壳（len=" + body.length + "），改用无头 WebView 渲染…")
            val rendered = JsRender.render(base, 25000L)
            if (rendered.length > body.length) {
                body = rendered
                renderedBy = "webview"
                AiTaskBoard.log("渲染成功：len=" + body.length + "（已执行 JS）")
            } else {
                AiTaskBoard.log("渲染未获得更多内容，沿用 okhttp 结果（len=" + body.length + "）")
            }
        }
        if (body.length < 200) {
            return "侦察失败：HTTP " + home.first + " 但正文过短 bodyLen=" + body.length + " final=" + home.third + "。手机侧可能被墙/需代理，或站点拒绝非浏览器请求。"
        }
        // 后续正文引用 body，标记渲染来源供日志排查
        val bodySource = renderedBy
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(body)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        // 相对 + 绝对 href，影视站伪静态多在 ?cates- / ?vodss- / /index.php/vod/
        val hrefs = Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(body).map { it.groupValues[1] }.filter { it.isNotBlank() && !it.startsWith("javascript:") && it != "#" }.distinct().toList()
        val catHrefs = hrefs.filter { h ->
            val l = h.lowercase()
            l.contains("cate") || l.contains("type") || l.contains("/vod/show") || l.contains("/vodshow/") ||
                l.contains("/frim/") || l.contains("channel") || Regex("""cates?-\d+""").containsMatchIn(l)
        }.take(12)
        val vodHrefs = hrefs.filter { h ->
            val l = h.lowercase()
            l.contains("vodss") || l.contains("/vod/detail") || l.contains("/movie/") || l.contains("detail") ||
                Regex("""/vod/\d+\.html""").containsMatchIn(l) || Regex("""/voddetail/\d+""").containsMatchIn(l)
        }.take(12)
        val marks = buildList {
            if (body.contains("myui", ignoreCase = true)) add("myui")
            if (body.contains("mytheme", ignoreCase = true)) add("mytheme")
            if (body.contains("MacPlayer", ignoreCase = true)) add("MacPlayer")
            if (body.contains("player_data", ignoreCase = true)) add("player_data")
            if (body.contains("/template/mytheme/", ignoreCase = true)) add("template/mytheme")
            if (body.contains("苹果CMS") || body.contains("maccms", ignoreCase = true)) add("maccms")
            // 2.39：module-* 主题特征（wangfei.tv 类），供模板分支选择与路由推断使用。
            if (body.contains("module-poster-item", ignoreCase = true)) add("module-poster-item")
            if (Regex("/vodtype/\\d", RegexOption.IGNORE_CASE).containsMatchIn(body)) add("/vodtype/")
            if (Regex("/voddetail/\\d", RegexOption.IGNORE_CASE).containsMatchIn(body)) add("/voddetail/")
            if (body.contains("module-play-list-link", ignoreCase = true)) add("module-play-list-link")
            // 2.45：stui 特征进摘要，否则画像认不出该站，路由全写"未知"。
            if (body.contains("stui-vodlist", ignoreCase = true)) add("stui-vodlist")
            if (body.contains("/vodshow/", ignoreCase = true)) add("/vodshow/")
        }
        // 2.39：自动提炼"列表 class + 详情路由形状"实测证据。
        // 未知主题站靠这段把真实选择器交给模型，避免选择器全写"未知"→真跑 0 条。
        val listClass = Regex("""class="([^"']*(?:poster-item|card-item|vodlist__thumb|module-item)[^"']*)"""", RegexOption.IGNORE_CASE)
            .findAll(body).map { it.groupValues[1] }.map { it.split(" ")[0] }
            .groupBy { it }.entries.sortedByDescending { it.value.size }.firstOrNull()?.key.orEmpty()
        // 2.45：路由模板从真实 href 概括出来（把数字 id 换成占位，短横/路径原样保留），
        // 不再按主题名硬编码：/vodshow/1-----------.html → /vodshow/{tid}-----------.html。
        // 占位按位置换：分类第一个数字是 tid，后面是页码 {page}；详情第一个是 id。
        // 没提到的真实 href 写"无"，画像如实写"未知"，不许编。
        val detailRoute = generalizeRoute(vodHrefs.firstOrNull(), "{id}", "{n}")
        val categoryRoute = generalizeRoute(catHrefs.firstOrNull(), "{tid}", "{page}")
        // 2.45：分类名。首页分类链接的文字就是分类名（/vodshow/1-----------.html → 电影），
        // 骨架的分类直接用真实数据，不再写死电影/连续剧/综艺/动漫。
        val catNames = LinkedHashMap<String, String>()
        Regex("""<a[^>]+href=["']([^"']+)["'][^>]*>([^<]{1,20})</a>""", RegexOption.IGNORE_CASE)
            .findAll(body).forEach { m ->
                val h = m.groupValues[1]
                val t = m.groupValues[2].trim()
                if (t.isEmpty()) return@forEach
                val l = h.lowercase()
                if (!(l.contains("/vodshow/") || l.contains("/vod/show") || l.contains("/vodtype/") || Regex("""cates?-\d+""").containsMatchIn(l))) return@forEach
                val tid = Regex("""(?:/vodshow/|/vod/show/id/|/vodtype/|cates?-)(\d+)""").find(h)?.groupValues?.getOrNull(1) ?: return@forEach
                if (!catNames.containsKey(tid)) catNames[tid] = t
            }
        val sb = StringBuilder()
        sb.append("标题：").append(if (title.isEmpty()) "（无）" else title).append("\n")
        sb.append("特征：").append(if (marks.isEmpty()) "无" else marks.joinToString(",")).append("\n")
        // 2.39：把自动提炼的实测结构写进摘要，模型必须据此填选择器，禁止对这些写"未知"
        sb.append("实测列表class：").append(if (listClass.isEmpty()) "（无）" else listClass).append("\n")
        sb.append("实测详情路由：").append(if (detailRoute.isEmpty()) "（无）" else detailRoute).append("\n")
        sb.append("实测分类路由：").append(if (categoryRoute.isEmpty()) "（无）" else categoryRoute).append("\n")
        sb.append("分类：").append(if (catNames.isEmpty()) "（无）" else catNames.entries.take(12).joinToString(", ") { it.key + "=" + it.value }).append("\n")
        sb.append("分类候选链接：\n").append(catHrefs.joinToString("\n").ifEmpty { "（无）" }).append("\n")
        sb.append("详情候选链接：\n").append(vodHrefs.joinToString("\n").ifEmpty { "（无）" }).append("\n")

        // 抽样打开一个分类页
        val catUrl = catHrefs.firstOrNull()?.let { absUrl(it) }
        if (catUrl != null) {
            val cat = fetch(catUrl)
            val cbody = cat.second
            val listIds = Regex("""(?:vodss-|/vod/detail/id/|/movie/index|/vod/)(\d+)""", RegexOption.IGNORE_CASE)
                .findAll(cbody).map { it.groupValues[1] }.distinct().take(8).toList()
            val thumbs = Regex("""data-original=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(cbody).map { it.groupValues[1] }.take(3).toList()
            sb.append("\n[分类页抽样] ").append(catUrl).append(" HTTP ").append(cat.first).append("\n")
            sb.append("列表 id 样例：").append(listIds.joinToString(",")).append("\n")
            sb.append("封面样例：").append(thumbs.joinToString("\n")).append("\n")
            sb.append("分类页片段：\n").append(cbody.take(2500)).append("\n")
            // 2.45：翻页。分类页的翻页链接是真实 href（文字 "2" 或 "下一页"），从它概括出翻页路由；
            // 没有翻页链接就不猜，画像写"未知"，骨架第 2 页起返回空等 Agent 修。
            val pageHref = Regex("""<a[^>]+href=["']([^"']+)["'][^>]*>([^<]{1,12})</a>""", RegexOption.IGNORE_CASE)
                .findAll(cbody).map { it.groupValues[1] to it.groupValues[2].trim() }
                .firstOrNull { (h, t) -> (t == "2" || t == "下一页" || t == "下页") && h.isNotBlank() }?.first
            val pageRoute = pageHref?.let { generalizeRoute(hrefPath(it), "{tid}", "{page}") }.orEmpty()
            sb.append("实测翻页路由：").append(if (pageRoute.isEmpty()) "（无）" else pageRoute).append("\n")
            // 2.45：筛选。筛选栏就在分类页上，抽样分类页时一并提出来：每组选项是真实链接，
            // 组按各链接相对当前分类 URL 改动的字符区间分，不假设短横/斜杠/参数。
            val filterGroups = extractFilters(catUrl, cbody)
            sb.append("实测筛选：").append(if (filterGroups.isEmpty()) "（无）" else filterGroups.joinToString("|") { g -> "f" + g.idx + ":" + g.label + "=" + g.options.joinToString(",") { (t, h) -> t + "@" + h } }).append("\n")

            // 抽样详情
            val detailHref = vodHrefs.firstOrNull()
                ?: listIds.firstOrNull()?.let { id ->
                    when {
                        cbody.contains("?vodss-") -> "?vodss-$id.html"
                        cbody.contains("/vod/detail") -> "/index.php/vod/detail/id/$id.html"
                        cbody.contains("/vod/") -> "/vod/$id.html"
                        else -> null
                    }
                }
            if (detailHref != null) {
                val dUrl = absUrl(detailHref)
                val det = fetch(dUrl)
                val dbody = det.second
                // 2.45：stui 播放链是 /p/{vid}-{sid}-{nid}.html，含 play 字样的过滤会漏光。
                val playHrefs = Regex("""href=["']([^"']*(?:play|plays|player|/p/\d+-\d+-\d+)[^"']*)["']""", RegexOption.IGNORE_CASE)
                    .findAll(dbody).map { it.groupValues[1] }.distinct().take(10).toList()
                sb.append("\n[详情页抽样] ").append(dUrl).append(" HTTP ").append(det.first).append("\n")
                sb.append("播放链候选：\n").append(playHrefs.joinToString("\n").ifEmpty { "（无）" }).append("\n")
                // 2.45：播放路由也从真实 href 概括（数字依次换成 {id}/{sid}/{nid}），画像不再猜 /index.php/vod/play/。
                val playRoute = playHrefs.firstOrNull()?.let { href ->
                    var i = 0
                    val names = listOf("{id}", "{sid}", "{nid}")
                    href.replace(Regex("\\d+")) { if (i < names.size) names[i++] else "{n}" }
                }.orEmpty()
                sb.append("实测播放路由：").append(if (playRoute.isEmpty()) "（无）" else playRoute).append("\n")
                sb.append("详情页片段：\n").append(dbody.take(2500)).append("\n")
                // 3.10：七要素核对。在把 HTML 片段丢给模型之前，
                // 先逐项确认「首页/分类/筛选/详情/线路/集数/直链」到底找没找到，
                // 找到的直接给出真实值，没找到的明确说缺什么、怎么补。
                // 模型不用再从几千字 HTML 里猜，猜错一项就 0 条的问题从根上解决。
                var playPageHtml = ""
                val playUrlForAudit = playHrefs.firstOrNull()?.let { absUrl(it) }
                if (playUrlForAudit != null) {
                    playPageHtml = fetch(playUrlForAudit).second
                }
                val auditText = com.github.tvbox.osc.api.ai.SiteStructureAudit.audit(
                    homeHtml = body,
                    listClass = listClass,
                    listClassHits = Regex("class=\"[^\"]*" + Regex.escape(listClass) + "[^\"]*\"")
                        .findAll(body).count(),
                    categoryRoute = categoryRoute,
                    pageRoute = pageRoute,
                    filterGroups = filterGroups.map { g ->
                        g.label to g.options.map { it.first }
                    },
                    detailHtml = dbody,
                    playRoute = playRoute,
                    playPageHtml = playPageHtml,
                )
                sb.append("\n").append(auditText)
                AiTaskBoard.log("结构核对：" + auditText.lineSequence().firstOrNull().orEmpty())

                // 抽样播放页，抠 iframe / m3u8
                val playUrl = playHrefs.firstOrNull()?.let { absUrl(it) }
                if (playUrl != null) {
                    val play = fetch(playUrl)
                    val pbody = play.second
                    val iframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .find(pbody)?.groupValues?.getOrNull(1).orEmpty()
                    val m3u8 = Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""", RegexOption.IGNORE_CASE)
                        .findAll(pbody).map { it.value }.distinct().take(3).toList()
                    sb.append("\n[播放页抽样] ").append(playUrl).append(" HTTP ").append(play.first).append("\n")
                    sb.append("iframe：").append(iframe.ifEmpty { "（无）" }).append("\n")
                    sb.append("m3u8：").append(m3u8.joinToString("\n").ifEmpty { "（无）" }).append("\n")
                    sb.append("播放页片段：\n").append(pbody.take(2000)).append("\n")
                }
            }
        } else {
            sb.append("\n未找到分类候选，仅首页。\n")
            sb.append("首页片段：\n").append(body.take(12000))
        }
        sb.toString()
    } catch (e: Throwable) {
        "侦察失败：" + (e.message ?: e.javaClass.simpleName)
    }
}

/** 2.38 多轮对话：Agent 循环用；单轮 callChat 转调这里，请求形态不变。 */
/** 3.68：去 private 供 B/C 共用（模型调用只此一份，不许在别处复制）。 */
fun callChatHistory(systemPrompt: String, history: List<Pair<String, String>>, skillPrefix: String = ""): String {
    val body = JSONObject()
    body.put("model", AiConfig.getModel())
    body.put("temperature", 0)
    body.put("stream", false)
    val messages = JSONArray()
    val system = JSONObject()
    system.put("role", "system")
    system.put("content", (if (skillPrefix.isEmpty()) AiSkillPack.systemPrefix() else skillPrefix) + systemPrompt)
    messages.put(system)
    for ((role, content) in history) {
        val m = JSONObject()
        m.put("role", role)
        m.put("content", content)
        messages.put(m)
    }
    body.put("messages", messages)
    val request = Request.Builder()
        .url(AiConfig.chatUrl())
        .addHeader("Authorization", "Bearer " + AiConfig.getApiKey())
        .addHeader("Content-Type", "application/json")
        .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()
    val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    val stopHeartbeat = java.util.concurrent.atomic.AtomicBoolean(false)
    val heartbeat = Thread {
        var seconds = 0
        while (!stopHeartbeat.get()) {
            try {
                Thread.sleep(1000)
            } catch (ignored: InterruptedException) {
                break
            }
            if (stopHeartbeat.get()) break
            seconds++
            AiTaskBoard.log("等待模型响应… " + seconds + "s")
        }
    }
    heartbeat.isDaemon = true
    heartbeat.start()
    try {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: ""
            if (!response.isSuccessful) throw IllegalStateException("模型接口 HTTP " + response.code + "：" + text.take(200))
            val json = JSONObject(text)
            val choices = json.optJSONArray("choices") ?: throw IllegalStateException("模型返回里没有 choices")
            if (choices.length() == 0) throw IllegalStateException("模型返回 choices 为空")
            val content = choices.getJSONObject(0).optJSONObject("message")?.optString("content", "") ?: ""
            if (content.isBlank()) throw IllegalStateException("模型返回内容为空")
            return content
        }
    } finally {
        stopHeartbeat.set(true)
        heartbeat.interrupt()
    }
}

private fun callChat(systemPrompt: String, userPrompt: String, skillPrefix: String = ""): String {
    return callChatHistory(systemPrompt, listOf("user" to userPrompt), skillPrefix)
}


/** 从模型回复里抠出第一个完整的 JSON 对象；抠不到返回空串（调用方用原文兜底）。 */
private fun extractJsonObject(content: String): String {
    val start = content.indexOf('{')
    val end = content.lastIndexOf('}')
    if (start < 0 || end <= start) return ""
    val candidate = content.substring(start, end + 1)
    return try {
        JSONObject(candidate)
        candidate
    } catch (ignored: Throwable) {
        ""
    }
}

private fun extractCode(content: String): String {
    var text = content.trim()
    text = text.replace(Regex("^```(?:python|py)?\\s*", RegexOption.IGNORE_CASE), "")
    text = text.replace(Regex("\\s*```\\s*$"), "")
    val lines = text.lines().filter { !it.trim().startsWith("```") }
    val start = lines.indexOfFirst {
        val s = it.trim()
        s.startsWith("from ") || s.startsWith("import ") || s.startsWith("class ") ||
            s.startsWith("# coding") || s.startsWith("# -*-") || s.startsWith("#!/")
    }
    val code = if (start >= 0) lines.subList(start, lines.size).joinToString("\n") else lines.joinToString("\n")
    return code.trim()
}

private fun templatePy(siteUrl: String, siteName: String): String {
    return "# coding=utf-8\n" +
        "# " + siteName + " " + siteUrl + "（AI辅助模板生成，未配模型时仅作起点）\n" +
        "import sys\n" +
        "sys.path.append('..')\n" +
        "from base.spider import Spider\n" +
        "import json\n" +
        "import urllib.request\n" +
        "\n" +
        "\n" +
        "class Spider(Spider):\n" +
        "\n" +
        "    def getName(self):\n" +
        "        return '" + siteName.replace("'", "") + "'\n" +
        "\n" +
        "    def init(self, extend=''):\n" +
        "        self.host = '" + siteUrl.trimEnd('/') + "'\n" +
        "        self.headers = {'User-Agent': 'Mozilla/5.0'}\n" +
        "\n" +
        "    def homeContent(self, filter):\n" +
        "        return json.dumps({'class': [], 'list': []})\n" +
        "\n" +
        "    def homeVideoContent(self):\n" +
        "        return json.dumps({'list': []})\n" +
        "\n" +
        "    def categoryContent(self, tid, pg, filter, extend):\n" +
        "        return json.dumps({'page': pg, 'pagecount': 0, 'limit': 0, 'total': 0, 'list': []})\n" +
        "\n" +
        "    def detailContent(self, array):\n" +
        "        return json.dumps({'list': []})\n" +
        "\n" +
        "    def searchContent(self, key, quick):\n" +
        "        return json.dumps({'list': []})\n" +
        "\n" +
        "    def playerContent(self, flag, id, vipFlags):\n" +
        "        return json.dumps({'parse': 0, 'playUrl': '', 'url': id})\n" +
        "\n" +
        "    def isVideoFormat(self, url):\n" +
        "        return False\n" +
        "\n" +
        "    def manualVideoCheck(self):\n" +
        "        return False\n"
}

/**
 * 判断是否是 WordPress OneNav 导航目录而非内容影视站。
 * 采用保守条件：需有 OneNav 主题签名，且侦察没有分类/详情候选和抽样页面。
 */
private fun isWordPressOneNavDirectory(recon: String): Boolean {
    val low = recon.lowercase()
    val hasOneNav = low.contains("wp-content/themes/onenav") ||
        (low.contains("onenav") && (low.contains("wordpress") || low.contains("wp-content")))
    if (!hasOneNav) return false
    val hasMediaSiteSignals = listOf(
        "myui", "mytheme", "macplayer", "player_data", "maccms", "vodss-", "cates-", "plays-",
        "/vod/show", "/vod/detail", "播放页抽样", "详情页抽样", "分类页抽样"
    ).any { low.contains(it) }
    val noCategoryCandidates = Regex("""分类候选链接：\s*（无）""").containsMatchIn(recon)
    val noDetailCandidates = Regex("""详情候选链接：\s*（无）""").containsMatchIn(recon)
    return !hasMediaSiteSignals && noCategoryCandidates && noDetailCandidates
}

/** 从侦察摘要提取与当前站不同的外部域名，供用户选择目标站；不自动生成或访问这些站点。 */
private fun extractExternalHostCandidates(siteUrl: String, recon: String): List<String> {
    val ownHost = try { java.net.URI(siteUrl).host.orEmpty().lowercase().removePrefix("www.") } catch (ignored: Throwable) { "" }
    return Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
        .findAll(recon)
        .mapNotNull { match ->
            val raw = match.value.trimEnd('.', ',', ';', ')', ']', '}', '"')
            try {
                val host = java.net.URI(raw).host?.lowercase()?.removePrefix("www.") ?: return@mapNotNull null
                if (host.isBlank() || host == ownHost || (ownHost.isNotBlank() && host.endsWith("." + ownHost))) null
                else "https://" + host
            } catch (ignored: Throwable) {
                null
            }
        }
        .distinct()
        .take(12)
        .toList()
}

/** 侦察摘要是否实质为空（抓不到 HTML）。 */
private fun reconLooksEmpty(recon: String): Boolean {
    if (recon.isBlank()) return true
    if (recon.startsWith("侦察失败")) return true
    if (recon.startsWith("站点 HTTP") && !recon.contains("bodyLen=")) return true
    if (recon.contains("正文过短")) return true
    if (recon.contains("无标题") && recon.contains("首页片段为空")) return true
    // 深度侦察成功至少应有标题或分类/详情候选之一
    val hasTitle = recon.contains("标题：") && !recon.contains("标题：（无）")
    val hasCat = recon.contains("分类候选链接：") && !recon.contains("分类候选链接：\n（无）")
    val hasSample = recon.contains("[分类页抽样]") || recon.contains("[详情页抽样]")
    val hasChunk = Regex("首页片段：\\s*\\S").containsMatchIn(recon) || recon.contains("分类页片段：")
    return !hasTitle && !hasCat && !hasSample && !hasChunk
}

/** 画像是否仍停留在「未知」（关键选择器/播放未实测）。 */
private fun profileLooksIncomplete(profileJson: String, profile: String): Boolean {
    val t = (if (profileJson.isNotEmpty()) profileJson else profile).ifBlank { return true }
    if (!t.contains("未知")) return false
    val keys = listOf("列表项", "播放地址", "详情线路", "category", "detail", "play")
    return keys.any { k -> Regex(k + """["']?\s*[:：]\s*["']?未知""").containsMatchIn(t) } ||
        (t.contains("未知") && t.length < 80)
}

/**
 * 合并模型输出与基线：模型丢掉 class 或过短时保留基线，避免修复轮被空骨架盖掉。
 */
private fun mergeSpiderCode(modelOut: String, baseline: String, siteUrl: String, siteName: String): String {
    val out = modelOut.trim()
    val base = baseline.trim().ifEmpty { seedPy(siteUrl, siteName, "") }
    if (out.contains("class Spider") && out.length >= 80) return out
    if (base.contains("class Spider")) {
        AiTaskBoard.log("模型输出缺少完整 Spider 类，已保留上一版基线代码")
        return base
    }
    AiTaskBoard.log("输出与基线均无完整类，已套技能种子骨架")
    return seedPy(siteUrl, siteName, "")
}

/** MacCMS/myui 种子骨架（可解析列表）；其它站回落 templatePy。 */
/** 2.35：侦察命中 MacCMS/myui 特征 → 走模板优先（不用技能包/模型写源）。 */
/**
 * 2.39：MacCMS 主题自动分诊。按"列表 class 指纹 + 详情路由形状"两个维度识别，
 * 不同主题的 URL 和选择器完全不通用，硬套必 0 条，所以先分诊再选模板。
 * 返回 "myui" / "module"；都不是则 "unknown"（交给模型按实测选择器写）。
 */
private fun detectTheme(recon: String): String {
    val low = recon.lowercase()
    // myui 主题指纹：myui-vodlist__box/thumb，详情路由 ?vodss-{id}.html
    val myuiSignals = listOf("myui-vodlist__box", "myui-vodlist__thumb", "myui-vodlist", "vodss-", "cates-")
    // module 主题指纹：module-poster-item，详情路由 /voddetail/{id}.html
    val moduleSignals = listOf("module-poster-item", "module-play-list-link", "/vodtype/", "/voddetail/")
    // stui 主题指纹（MacCMS 第三套，如 cnsq114.com）：stui-vodlist__box/thumb，
    // 路由 /vod/{id}.html 详情、/p/{id}-{sid}-{nid}.html 播放、/vodshow/{tid} 筛选。
    val stuiSignals = listOf("stui-vodlist__box", "stui-vodlist__thumb", "stui-vodlist", "/vodshow/")
    val myui = myuiSignals.count { low.contains(it) }
    val module = moduleSignals.count { low.contains(it) }
    val stui = stuiSignals.count { low.contains(it) }
    if (module >= myui && module >= stui && module > 0) return "module"
    if (stui >= myui && stui >= module && stui > 0) return "stui"
    if (myui > 0) return "myui"
    if (low.contains("/voddetail/") || low.contains("/vodtype/")) return "module"
    if (low.contains("/vod/") && low.contains("/vodshow/")) return "stui"
    if (low.contains("vodss-") || low.contains("cates-")) return "myui"
    return "unknown"
}

private fun isMacCmsSite(recon: String): Boolean {
    val low = recon.lowercase()
    if (listOf("myui", "mytheme", "macplayer", "player_data", "vodss-", "cates-", "plays-", "maccms", "template/mytheme").any { low.contains(it) }) return true
    // 2.39：MacCMS 还有 module-* 主题（wangfei.tv 类），路由 /vodtype//voddetail/、列表 module-poster-item。
    if (listOf("module-poster-item", "/vodtype/", "/voddetail/", "module-play-list-link").any { low.contains(it) }) return true
    // stui 主题（MacCMS 第三套）：stui-vodlist__box / /vodshow/
    if (listOf("stui-vodlist__box", "stui-vodlist__thumb", "/vodshow/").any { low.contains(it) }) return true
    return false
}

/** 2.35：画像由侦察摘要直接拼出（不用模型/技能包），仅用于 MacCMS/myui 模板路径。 */
private fun profileFromRecon(siteUrl: String, recon: String): String {
    val low = recon.lowercase()
    fun lineAfter(prefix: String): String {
        val idx = recon.indexOf(prefix)
        if (idx < 0) return ""
        return recon.substring(idx + prefix.length).lineSequence().firstOrNull().orEmpty().trim()
    }
    val title = lineAfter("标题：")
    val marks = lineAfter("特征：")
    // 2.45：画像只转述侦察实测。路由从侦察摘要的"实测…路由"行原样取，取不到写"未知"，
    // 禁止再编 /index.php/vod/... 的假路由——cnsq114 就是被这套假路由带偏到 0 条的。
    fun routeAfter(prefix: String): String {
        val v = lineAfter(prefix)
        return if (v.isEmpty() || v == "（无）") "未知" else v
    }
    val routes = JSONObject()
    routes.put("home", siteUrl.trim().trimEnd('/'))
    routes.put("category", routeAfter("实测分类路由："))
    routes.put("detail", routeAfter("实测详情路由："))
    routes.put("play", routeAfter("实测播放路由："))
    routes.put("search", "未知")
    val selectors = JSONObject()
    val listClass = lineAfter("实测列表class：")
    if (listClass.isNotEmpty() && listClass != "（无）") {
        selectors.put("列表项", "a." + listClass)
        selectors.put("标题", "按列表片段里的 title/文本")
        selectors.put("封面", "按列表片段里的 data-original/img/@src/style")
    } else {
        selectors.put("列表项", "未知")
        selectors.put("标题", "未知")
        selectors.put("封面", "未知")
    }
    selectors.put("详情线路", "按详情页片段")
    selectors.put("播放地址", "按播放页片段：iframe[src] url= / 页面 m3u8")
    val obj = JSONObject()
    obj.put("site", siteUrl.trim().trimEnd('/'))
    obj.put("type", "MacCMS")
    obj.put("routes", routes)
    obj.put("selectors", selectors)
    obj.put("categories", lineAfter("分类："))
    obj.put("filters", lineAfter("实测筛选："))
    obj.put("player", "page, iframe url= 或页面 m3u8, Referer " + siteUrl.trim().trimEnd('/') + "/, proxy false")
    obj.put("notes", "2.45：画像只转述侦察实测，路由/选择器以侦察摘要里的真实 href 为准；写'未知'的项禁止猜，必须先 fetch_url 查真实地址。标题：" + title + "；特征：" + marks + "；分类：" + lineAfter("分类：") + "。")
    return obj.toString(2)
}

    // 2.45：把真实路由模板转成取 id 的正则：/vod/{id}.html → /vod/(\d+)\.html；
    // /p/{id}-{sid}-{nid}.html → /p/(\d+)-(\d+)-(\d+)\.html。
    private fun patternOf(route: String, vararg holders: String): String {
        // 不使用 Kotlin Regex.escape：它的 \Q...\E 不是合法 Python re。
        var marked = route
        holders.forEachIndexed { i, h -> marked = marked.replace(h, "\u0001$i\u0002") }
        val escaped = buildString {
            for (ch in marked) {
                if (ch.isLetterOrDigit() || ch == '_' || ch == '\u0001' || ch == '\u0002') append(ch)
                else { append('\\'); append(ch) }
            }
        }
        var out = escaped
        holders.indices.forEach { out = out.replace("\u0001$it\u0002", "(\\d+)") }
        return out
    }

    // 2.45：侦察的"分类："行（1=电影, 2=电视剧）转成 Python 分类列表；没有就回落 4 个通用分类。
    private fun catsPyOf(catsLine: String): String {
        val items = catsLine.split(",").mapNotNull { part ->
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2 && kv[0].trim().isNotEmpty() && kv[1].trim().isNotEmpty()) {
                "{'type_id': '" + kv[0].trim() + "', 'type_name': '" + kv[1].trim().replace("'", "") + "'}"
            } else null
        }.take(12)
        if (items.isEmpty()) {
            return "[{'type_id': '1', 'type_name': '电影'}, {'type_id': '2', 'type_name': '连续剧'}, {'type_id': '3', 'type_name': '综艺'}, {'type_id': '4', 'type_name': '动漫'}]"
        }
        return "[" + items.joinToString(", ") + "]"
    }

    // 2.45：侦察的"实测筛选："行（f1:类型=动作,喜剧|f2:地区=大陆,香港）转成 Python filters 字典，
    // 按分类 id 逐个挂；没有筛选就返回 {}。选项写成 名 或 名@值（值与名不同时）。
    private fun filtersPyOf(catsLine: String, filterLine: String): String {
        if (filterLine.isEmpty() || filterLine == "（无）") return "{}"
        val tids = catsLine.split(",").mapNotNull { part ->
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2 && kv[0].trim().isNotEmpty()) kv[0].trim() else null
        }.take(12).ifEmpty { listOf("1", "2", "3", "4") }
        val groups = filterLine.split("|").mapNotNull { g ->
            val m = Regex("""f(\d+):([^=]+)=(.+)""").find(g.trim()) ?: return@mapNotNull null
            val key = "f" + m.groupValues[1]
            val gname = m.groupValues[2].trim().ifEmpty { "筛选" + m.groupValues[1] }
            val opts = m.groupValues[3].split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(12).map { opt ->
                val nv = opt.split("@", limit = 2)
                val n = nv[0].replace("'", "")
                val v = if (nv.size == 2) nv[1].replace("'", "\\'") else n
                "{'n': '" + n + "', 'v': '" + v + "'}"
            }
            if (opts.isEmpty()) null else "{'key': '" + key + "', 'name': '" + gname.replace("'", "") + "', 'value': [{'n': '全部', 'v': ''}, " + opts.joinToString(", ") + "]}"
        }
        if (groups.isEmpty()) return "{}"
        val perTid = tids.joinToString(", ") { tid -> "'" + tid + "': [" + groups.joinToString(", ") + "]" }
        return "{" + perTid + "}"
    }

    // 2.45：起点骨架按侦察真实地址生成（不再分 myui/空骨架两套）：路由、列表 class、
    // 取 id/播放正则、分类、筛选全部从侦察摘要取；侦察没给出的才回落 myui 缺省。
    // 侦察空白时回落空骨架 templatePy。
    private fun seedPy(siteUrl: String, siteName: String, recon: String): String {
        val host = siteUrl.trim().trimEnd('/')
        val name = siteName.replace("'", "").ifBlank { "site" }
        if (recon.isBlank()) return templatePy(siteUrl, siteName)
        fun lineAfter(prefix: String): String {
            val idx = recon.indexOf(prefix)
            if (idx < 0) return ""
            return recon.substring(idx + prefix.length).lineSequence().firstOrNull().orEmpty().trim()
        }
        fun realOr(prefix: String, fallback: String): String {
            val v = lineAfter(prefix)
            return if (v.isEmpty() || v == "（无）") fallback else v
        }
        val catRoute = realOr("实测分类路由：", "/?cates-{tid}.html").replace("{pg}", "{page}")
        val detRoute = realOr("实测详情路由：", "/?vodss-{id}.html")
        val playRoute = realOr("实测播放路由：", "/?plays-{id}-{sid}-{nid}.html")
        val listClass = realOr("实测列表class：", "myui-vodlist__thumb")
        val catPageRoute = realOr("实测翻页路由：", "")
        val vidPat = patternOf(detRoute, "{id}")
        val playPat = patternOf(playRoute, "{id}", "{sid}", "{nid}")
        val catsLine = lineAfter("分类：")
        val catsPy = catsPyOf(catsLine)
        val filtersPy = filtersPyOf(catsLine, lineAfter("实测筛选："))
        val body = """
            # coding=utf-8
            import re
            from urllib.parse import urljoin, quote, unquote
            import requests
            from base.spider import Spider

            class Spider(Spider):
                def getName(self):
                    return '__NAME__'

                def init(self, extend=''):
                    self.host = '__HOST__'
                    self.headers = {
                        'User-Agent': 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36',
                        'Referer': self.host + '/',
                    }
                    self.session = requests.Session()
                    self.session.headers.update(self.headers)
                    self.categories = ${catsPy}
                    self.filters = ${filtersPy}
                    self.route = {
                        'category': '${catRoute}',
                        'category_page': '${catPageRoute}',
                        'detail': '${detRoute}',
                        'play': '${playRoute}',
                    }
                    self.list_class = '${listClass}'
                    self.vid_pat = r'${vidPat}'
                    self.play_pat = r'${playPat}'

                def _get(self, url):
                    try:
                        r = self.session.get(url, headers=self.headers, timeout=15, verify=False)
                        r.encoding = r.apparent_encoding or 'utf-8'
                        return r.text
                    except Exception:
                        return ''

                def _abs(self, u):
                    if not u:
                        return ''
                    return urljoin(self.host + '/', u)

                def _list_from_html(self, html):
                    items = []
                    if not html:
                        return items
                    pat = re.compile(r'<a[^>]+class="[^"]*' + re.escape(self.list_class) + r'[^"]*"[^>]+>', re.I)
                    for m in pat.finditer(html):
                        tag = m.group(0)
                        href = re.search(r'href="([^"]+)"', tag, re.I)
                        title = re.search(r'title="([^"]+)"', tag, re.I)
                        pic = re.search(r'data-original="([^"]+)"', tag, re.I)
                        if not href:
                            continue
                        h = href.group(1)
                        mm = re.search(self.vid_pat, h)
                        if not mm:
                            continue
                        vid = mm.group(1)
                        items.append({
                            'vod_id': vid,
                            'vod_name': (title.group(1) if title else vid),
                            'vod_pic': self._abs(pic.group(1) if pic else ''),
                            'vod_remarks': '',
                        })
                    return items

                def _filter_range(self, base, href):
                    # 找出 href 相对 base 改动的字符区间（左闭右开）与替换值；没改动返回 None。
                    # 不假设任何分隔符与段数，只比两个真实链接的公共前后缀。
                    n = 0
                    while n < len(base) and n < len(href) and base[n] == href[n]:
                        n += 1
                    m = 0
                    while m < len(base) - n and m < len(href) - n and base[len(base) - 1 - m] == href[len(href) - 1 - m]:
                        m += 1
                    start, end = n, len(base) - m
                    value = href[n:len(href) - m]
                    if start == end and not value:
                        return None
                    return (start, end, value)

                def _apply_filters(self, base, target, filter):
                    # filter: {key: 真实选项链接}。每组取其相对 base 的改动区间，从右往左合并到 target。
                    # 区间重叠的组只留第一组（不拼假链接），其余忽略；target 与 base 的前缀须一致（页码在筛选段之后）。
                    if not filter:
                        return target
                    reps = []
                    for href in (filter or {}).values():
                        if not href or not isinstance(href, str):
                            continue
                        r = self._filter_range(base, href)
                        if r is not None:
                            reps.append(r)
                    reps.sort(key=lambda x: x[0], reverse=True)
                    out = target
                    used = []
                    for start, end, value in reps:
                        if any(start <= u_end and u_start <= end for u_start, u_end in used):
                            continue
                        out = out[:start] + value + out[end:]
                        used.append((start, end))
                    return out

                def homeContent(self, filter):
                    return {'class': self.categories, 'filters': self.filters, 'list': []}

                def homeVideoContent(self):
                    html = self._get(self.host + '/')
                    return {'list': self._list_from_html(html)[:30]}

                def categoryContent(self, tid, pg, filter, extend):
                    page = int(pg or 1)
                    base = self.route['category'].replace('{tid}', str(tid))
                    if page > 1:
                        tmpl = self.route['category_page']
                        if not tmpl:
                            return {'list': [], 'page': page, 'pagecount': page, 'limit': 0, 'total': 0, '_debug': 'no real page route; agent must fetch the real page link'}
                        target = tmpl.replace('{tid}', str(tid)).replace('{page}', str(page))
                    else:
                        target = base
                    path = self._apply_filters(base, target, filter)
                    html = self._get(self.host + path)
                    items = self._list_from_html(html)
                    return {'page': page, 'pagecount': page + (1 if items else 0), 'limit': len(items), 'total': 0, 'list': items}

                def detailContent(self, ids):
                    vid = str(ids[0]) if ids else ''
                    html = self._get(self.host + self.route['detail'].replace('{id}', vid))
                    if not html:
                        html = self._get(self.host + '/index.php/vod/detail/id/' + vid + '.html')
                    name = ''
                    m = re.search(r'<h1[^>]*>(.*?)</h1>', html, re.I | re.S)
                    if m:
                        name = re.sub(r'<[^>]+>', '', m.group(1)).strip()
                    plays = []
                    for pm in re.finditer(r'<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>', html, re.I | re.S):
                        if not re.search(self.play_pat, pm.group(1)):
                            continue
                        title = re.sub(r'<[^>]+>', '', pm.group(2)).strip() or '播放'
                        plays.append(title + '${'$'}' + pm.group(1))
                    if not plays:
                        for pm in re.finditer(r'href="([^"]*(?:plays?|play)-[^"]+)"[^>]*>(.*?)</a>', html, re.I | re.S):
                            title = re.sub(r'<[^>]+>', '', pm.group(2)).strip() or '播放'
                            plays.append(title + '${'$'}' + pm.group(1))
                    if not plays:
                        plays.append('播放${'$'}plays-1-1-1')
                    return {'list': [{
                        'vod_id': vid,
                        'vod_name': name or vid,
                        'vod_play_from': '默认',
                        'vod_play_url': '#'.join(plays),
                    }]}

                def searchContent(self, key, quick, pg='1'):
                    html = self._get(self.host + '/index.php/vod/search/page/1/wd/' + quote(key) + '.html')
                    if not html:
                        html = self._get(self.host + '/?search.html&keyword=' + quote(key))
                    return {'list': self._list_from_html(html), 'page': 1}

                def playerContent(self, flag, id, vipFlags):
                    url = id
                    if not str(url).startswith('http'):
                        url = self._abs(str(url))
                    html = self._get(url)
                    m3u8 = ''
                    im = re.search(r'<iframe[^>]+src="([^"]+)"', html, re.I)
                    if im:
                        src = im.group(1).replace('&amp;', '&')
                        um = re.search(r'[?&]url=([^&]+)', src)
                        if um:
                            m3u8 = unquote(um.group(1))
                        elif '.m3u8' in src:
                            m3u8 = src
                    if not m3u8:
                        mm = re.search(r'https?://[^\s"<>]+\.m3u8[^\s"<>]*', html, re.I)
                        if mm:
                            m3u8 = mm.group(0)
                    if m3u8 and m3u8.startswith('http'):
                        return {'parse': 0, 'url': m3u8, 'header': self.headers}
                    return {'parse': 1, 'url': url, 'header': self.headers}
            """.trimIndent()
        return body.replace("__NAME__", name).replace("__HOST__", host)
    }

private fun ensureSpiderClass(code: String, siteUrl: String, siteName: String): String {
    return mergeSpiderCode(code, "", siteUrl, siteName)
}


/** 按侦察摘要生成 maccms 路由/选择器/iframe 提示（v4 补丁调用、原包漏定义，2.34 补写；v5 又删了，此处补回）。 */
private fun skillRouteHints(recon: String): String {
    val r = recon.lowercase()
    val hasMyui = r.contains("myui") || r.contains("mytheme") || r.contains("vodss-") ||
        r.contains("cates-") || r.contains("plays-") || r.contains("macplayer") || r.contains("maccms")
    if (!hasMyui && !r.contains("iframe") && !r.contains("m3u8")) {
        return "侦察未见 MacCMS/myui 特征，按画像与侦察摘要写。"
    }
    val sb = StringBuilder()
    if (r.contains("cates-")) sb.append("分类路由：?cates-{id}.html。")
    if (r.contains("vodss-")) sb.append("详情路由：?vodss-{id}.html。")
    if (r.contains("plays-")) sb.append("集数路由：?plays-{id}-{sid}-{nid}.html。")
    if (r.contains("myui-vodlist") || r.contains("myui")) sb.append("列表选 a.myui-vodlist__thumb，标题取 a 的 title 属性，封面取 a 的 style background url 或 data-original。")
    if (r.contains("iframe") || r.contains("m3u8") || r.contains("player_data")) sb.append("播放：播放页 iframe[src] 的 url= 参数或页面内 m3u8 直链，禁止把播放页 URL 当直链。")
    if (sb.isEmpty()) sb.append("侦察见 MacCMS 特征，按画像与侦察摘要写。")
    return sb.toString()
}

private fun staticCheck(py: String): String? {
    if (py.isBlank()) return "生成内容为空"
    if (!Regex("""class\s+Spider\s*\(\s*Spider\s*\)""").containsMatchIn(py)) return "缺少 class Spider(Spider) 完整类，禁止用骨架代替"
    for (method in listOf("homeContent", "homeVideoContent", "categoryContent", "detailContent", "searchContent", "playerContent")) {
        if (!Regex("""def\s+$method\s*\(""").containsMatchIn(py)) return "缺少 $method 入口"
    }
    if (py.contains("\\Q") || py.contains("\\E")) return "含 Python 不支持的 \\Q/\\E 正则，请用 re.escape 等价写法"
    if (!py.contains("homeContent")) return "缺少 homeContent 入口"

    // 3.9：lxml 单独给准确提示。原来它会被下面的「空壳」规则误判成
    // "homeContent 把分类写死成空数组"，AI 收到这个提示只会去改 class 数组，
    // 完全想不到是 lxml 的问题，于是原地打转。
    if (Regex("""(^|\n)\s*(from\s+lxml|import\s+lxml)""").containsMatchIn(py)) {
        return "使用了 lxml：本环境 lxml 不稳定会静默失败导致 0 条。" +
            "必须改成 from bs4 import BeautifulSoup，用 soup.select / find_all 解析"
    }

    // 3.9：常见缺依赖，提前点破，别等真跑变 0 条
    val riskyModules = listOf(
        "cloudscraper", "selenium", "playwright", "pyquery", "scrapy", "pandas", "numpy",
    )
    for (m in riskyModules) {
        val re = Regex("""(^|\n)\s*(from\s+""" + Regex.escape(m) + """|import\s+""" + Regex.escape(m) + """)\b""")
        if (re.containsMatchIn(py)) {
            return "依赖了本环境没有的模块 $m，真跑必然失败。请改用 requests + BeautifulSoup"
        }
    }

    val compact = py.replace(Regex("\\s+"), "")
    val hasNet = compact.contains("requests.") || compact.contains("urllib") ||
        compact.contains("HttpRequest") || compact.contains("session.get") ||
        compact.contains("session.post") || compact.contains("self._get(") ||
        compact.contains("self._post(") || compact.contains("self._api(")
    if (!hasNet && (compact.contains("\"class\":[]") || compact.contains("'class':[]"))) {
        return "空壳：homeContent 把分类写死成空数组（没真发请求）。必须 requests 真请求站点的可调用接口，从返回结果取真实分类"
    }
    var depth = 0
    var inSingle = false
    var inDouble = false
    var escaped = false
    for (ch in py) {
        if (escaped) {
            escaped = false
            continue
        }
        if (ch == '\\') {
            if (inSingle || inDouble) escaped = true
            continue
        }
        if (ch == '\'' && !inDouble) {
            inSingle = !inSingle
            continue
        }
        if (ch == '"' && !inSingle) {
            inDouble = !inDouble
            continue
        }
        if (inSingle || inDouble) continue
        when (ch) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> {
                depth--
                if (depth < 0) return "括号不匹配"
            }
        }
    }
    if (depth != 0) return "括号不匹配，可能被截断"
    return null
}
