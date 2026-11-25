package com.github.tvbox.osc.miniapp

import android.content.Context
import android.os.SystemClock
import com.github.tvbox.osc.miniapp.engine.MiniAppEngine
import com.github.tvbox.osc.miniapp.engine.MiniAppHost
import com.github.tvbox.osc.miniapp.engine.MiniAppListItem
import com.github.tvbox.osc.miniapp.engine.MiniAppRule
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

enum class DiagStatus {
    PENDING,
    RUNNING,
    PASS,
    FAIL,
    SKIP,
    CANCELLED,
}

data class DiagStepResult(
    val name: String,
    val status: DiagStatus,
    val detail: String,
    val millis: Long,
)

data class DiagReport(
    val itemName: String,
    val itemSource: String,
    val steps: List<DiagStepResult>,
    val cancelled: Boolean,
    val startedAt: Long,
    val endedAt: Long,
) {
    val passCount: Int get() = steps.count { it.status == DiagStatus.PASS }
    val failCount: Int get() = steps.count { it.status == DiagStatus.FAIL }
    val skipCount: Int get() = steps.count { it.status == DiagStatus.SKIP }

    fun toText(): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        sb.append("小程序运行检测报告\n")
        sb.append("小程序：").append(itemName).append("\n")
        sb.append("来源：").append(itemSource).append("\n")
        sb.append("时间：").append(format.format(Date(startedAt))).append("\n")
        if (cancelled) {
            sb.append("结果：已取消，仅完成 ").append(steps.count { it.status == DiagStatus.PASS || it.status == DiagStatus.FAIL || it.status == DiagStatus.SKIP }).append(" 步\n")
        } else {
            sb.append("结果：通过 ").append(passCount).append(" / 失败 ").append(failCount)
                .append(" / 跳过 ").append(skipCount).append("\n")
        }
        sb.append("说明：检测只解地址不播放、不改规则、不写缓存\n")
        sb.append("---\n")
        for ((index, step) in steps.withIndex()) {
            val mark = when (step.status) {
                DiagStatus.PASS -> "通过"
                DiagStatus.FAIL -> "失败"
                DiagStatus.SKIP -> "跳过"
                DiagStatus.RUNNING -> "进行中"
                DiagStatus.PENDING -> "未开始"
                DiagStatus.CANCELLED -> "已取消"
            }
            sb.append(index + 1).append(". ").append(step.name)
                .append(" [").append(mark).append("]")
                .append("（").append(String.format(Locale.getDefault(), "%.1f", step.millis / 1000.0)).append("秒）")
                .append("：").append(step.detail).append("\n")
        }
        return sb.toString()
    }
}

object MiniAppDiagnoser {

    const val STEP_TIMEOUT_MS = 60_000L

    private const val STEP_STATIC = "静态检验"
    private const val STEP_HOME = "首页加载"
    private const val STEP_CATEGORY_PAGE = "分类与翻页"
    private const val STEP_DETAIL = "详情"
    private const val STEP_SEARCH = "搜索"
    private const val STEP_PLAY = "播放解析"

    val STEP_NAMES = listOf(STEP_STATIC, STEP_HOME, STEP_CATEGORY_PAGE, STEP_DETAIL, STEP_SEARCH, STEP_PLAY)

    suspend fun runDiagnose(
        context: Context,
        item: MiniAppItem,
        onStepsChanged: (List<DiagStepResult>) -> Unit,
    ): DiagReport {
        val startedAt = System.currentTimeMillis()
        val steps = ArrayList<DiagStepResult>()
        val names = listOf(STEP_STATIC, STEP_HOME, STEP_CATEGORY_PAGE, STEP_DETAIL, STEP_SEARCH, STEP_PLAY)
        for (name in names) {
            steps.add(DiagStepResult(name, DiagStatus.PENDING, "", 0L))
        }
        fun publish() {
            onStepsChanged(ArrayList(steps))
        }

        val varSnapshot = synchronized(MiniAppHost.varMap) { HashMap(MiniAppHost.varMap) }
        val engine = MiniAppEngine(context.applicationContext)
        var cancelled = false
        var homeItems: List<MiniAppListItem> = emptyList()
        var homeRule: MiniAppRule? = null
        var playRule: MiniAppRule? = null
        var playCandidates: List<MiniAppListItem> = emptyList()
        var directPlay: Pair<String, Boolean>? = null

        fun setStep(index: Int, status: DiagStatus, detail: String, millis: Long) {
            steps[index] = DiagStepResult(names[index], status, detail, millis)
            publish()
        }

        fun markRunning(index: Int) {
            steps[index] = DiagStepResult(names[index], DiagStatus.RUNNING, "进行中…", 0L)
            publish()
        }

        suspend fun timedStep(index: Int, block: suspend () -> Pair<DiagStatus, String>): DiagStepResult {
            markRunning(index)
            val start = SystemClock.elapsedRealtime()
            val result = try {
                val timed = withTimeoutOrNull(STEP_TIMEOUT_MS) { block() }
                if (timed == null) {
                    DiagStepResult(names[index], DiagStatus.FAIL, "步骤超时（60 秒）", SystemClock.elapsedRealtime() - start)
                } else {
                    DiagStepResult(names[index], timed.first, timed.second, SystemClock.elapsedRealtime() - start)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                DiagStepResult(names[index], DiagStatus.FAIL, e.message ?: e.toString(), SystemClock.elapsedRealtime() - start)
            }
            steps[index] = result
            publish()
            return result
        }

        try {
            val staticReport = MiniAppRuleValidator.validate(item.ruleJson)
            val staticDetail = staticReport.summary + "；" + staticReport.details.joinToString("；")
            timedStep(0) {
                if (staticReport.ok) DiagStatus.PASS to staticDetail else DiagStatus.FAIL to staticDetail
            }

            val rule = MiniAppRule.fromJson(item.ruleJson)
            if (rule == null) {
                for (i in 1..5) {
                    setStep(i, DiagStatus.SKIP, "规则解析失败，跳过", 0L)
                }
                return DiagReport(item.name, item.source, ArrayList(steps), false, startedAt, System.currentTimeMillis())
            }
            homeRule = rule

            val homeResult = timedStep(1) {
                when (val r = engine.loadHome(rule, 1)) {
                    is MiniAppEngine.PageResult.ListPage -> {
                        homeItems = r.items
                        if (r.items.isEmpty()) {
                            DiagStatus.FAIL to "首页结果为空（字段：find_rule）"
                        } else {
                            val first = r.items.firstOrNull { it.type != "header" } ?: r.items[0]
                            DiagStatus.PASS to "首页 " + r.items.size + " 条，首项：" + first.title
                        }
                    }
                    is MiniAppEngine.PageResult.Error -> DiagStatus.FAIL to r.message + "（字段：find_rule）"
                }
            }
            if (homeResult.status == DiagStatus.FAIL || homeItems.isEmpty()) {
                for (i in 2..5) {
                    setStep(i, DiagStatus.SKIP, "首页未成功，跳过", 0L)
                }
                return DiagReport(item.name, item.source, ArrayList(steps), false, startedAt, System.currentTimeMillis())
            }

            timedStep(2) {
                val pageNotes = ArrayList<String>()
                var pageOk = true
                when (val r = engine.loadHome(rule, 2)) {
                    is MiniAppEngine.PageResult.ListPage -> {
                        val same = r.items.map { it.title } == homeItems.map { it.title }
                        pageNotes.add(
                            "第 2 页 " + r.items.size + " 条" +
                                if (same) "，与第 1 页相同（源可能不认页码，字段：MY_PAGE）" else "",
                        )
                    }
                    is MiniAppEngine.PageResult.Error -> {
                        pageOk = false
                        pageNotes.add("翻页失败：" + r.message + "（字段：MY_PAGE）")
                    }
                }
                var catOk = true
                val header = homeItems.firstOrNull { engine.isHeaderFilter(it) }
                if (header == null) {
                    pageNotes.add("无分类筛选项，分类跳过")
                } else {
                    val filtered = engine.applyHeaderFilter(rule, header, 0)
                    if (filtered == null) {
                        catOk = false
                        pageNotes.add("分类应用失败：" + header.desc)
                    } else {
                        when (val r = engine.loadHome(filtered, 1)) {
                            is MiniAppEngine.PageResult.ListPage -> {
                                pageNotes.add("分类「" + header.title + "」" + r.items.size + " 条")
                            }
                            is MiniAppEngine.PageResult.Error -> {
                                catOk = false
                                pageNotes.add("分类加载失败：" + r.message)
                            }
                        }
                    }
                }
                if (pageOk && catOk) DiagStatus.PASS to pageNotes.joinToString("；")
                else DiagStatus.FAIL to pageNotes.joinToString("；")
            }

            timedStep(3) {
                val target = homeItems.firstOrNull { it.type != "header" && it.url.isNotBlank() }
                if (target == null) {
                    DiagStatus.FAIL to "首页无可点条目"
                } else {
                    when (val action = engine.actionForItem(rule, target)) {
                        is MiniAppEngine.ItemAction.OpenList -> {
                            playRule = action.rule
                            when (val r = engine.loadHome(action.rule, 1)) {
                                is MiniAppEngine.PageResult.ListPage -> {
                                    playCandidates = r.items
                                    if (r.items.isEmpty()) {
                                        DiagStatus.FAIL to "详情列表为空"
                                    } else {
                                        DiagStatus.PASS to "详情列表 " + r.items.size + " 条，首项：" + target.title
                                    }
                                }
                                is MiniAppEngine.PageResult.Error -> DiagStatus.FAIL to r.message
                            }
                        }
                        is MiniAppEngine.ItemAction.OpenPage -> {
                            val routed = engine.routePage(rule, action.pageUrl, action.item)
                            if (routed == null) {
                                DiagStatus.FAIL to "页面路由失败：" + action.pageUrl
                            } else {
                                playRule = routed
                                when (val r = engine.loadHome(routed, 1)) {
                                    is MiniAppEngine.PageResult.ListPage -> {
                                        playCandidates = r.items
                                        if (r.items.isEmpty()) {
                                            DiagStatus.FAIL to "详情列表为空"
                                        } else {
                                            DiagStatus.PASS to "详情列表 " + r.items.size + " 条，首项：" + target.title
                                        }
                                    }
                                    is MiniAppEngine.PageResult.Error -> DiagStatus.FAIL to r.message
                                }
                            }
                        }
                        is MiniAppEngine.ItemAction.Play -> {
                            directPlay = action.url to false
                            DiagStatus.PASS to "首项可直接播放（直链），首项：" + target.title
                        }
                        is MiniAppEngine.ItemAction.SniffPlay -> {
                            directPlay = action.pageUrl to true
                            DiagStatus.PASS to "首项需 WebView 嗅探，首项：" + target.title
                        }
                        is MiniAppEngine.ItemAction.Refresh -> DiagStatus.PASS to "刷新型条目（虚拟页刷新），首项：" + target.title
                        is MiniAppEngine.ItemAction.Nothing -> DiagStatus.FAIL to action.reason
                        is MiniAppEngine.ItemAction.Toast -> DiagStatus.FAIL to action.message
                        is MiniAppEngine.ItemAction.OpenItems -> {
                            playRule = action.rule
                            playCandidates = action.items
                            if (action.items.isEmpty()) {
                                DiagStatus.FAIL to "详情列表为空"
                            } else {
                                DiagStatus.PASS to "详情列表 " + action.items.size + " 条，首项：" + target.title
                            }
                        }
                    }
                }
            }

            timedStep(4) {
                if (rule.searchUrl.isBlank()) {
                    DiagStatus.SKIP to "无搜索地址（字段：search_url）"
                } else {
                    val keyword = homeItems.firstOrNull { it.type != "header" && it.title.isNotBlank() }?.title
                        ?: "测试"
                    when (val r = engine.search(rule, keyword, 1)) {
                        is MiniAppEngine.PageResult.ListPage -> {
                            if (r.items.isEmpty()) {
                                DiagStatus.FAIL to "搜索「" + keyword + "」结果为空（字段：search_url/search_find）"
                            } else {
                                DiagStatus.PASS to "搜索「" + keyword + "」" + r.items.size + " 条"
                            }
                        }
                        is MiniAppEngine.PageResult.Error -> DiagStatus.FAIL to r.message + "（字段：search_url/search_find）"
                    }
                }
            }

            timedStep(5) {
                val direct = directPlay
                if (direct != null) {
                    if (direct.second) {
                        DiagStatus.PASS to "需 WebView 嗅探（与详情步同一条目，未真播）：（" + direct.first.take(120) + "）"
                    } else {
                        DiagStatus.PASS to "直链（与详情步同一条目，未真播）：" + direct.first.take(120)
                    }
                } else {
                    val candidates = (if (playCandidates.isNotEmpty()) playCandidates else homeItems)
                        .filter { it.type != "header" && it.url.isNotBlank() }
                    if (candidates.isEmpty()) {
                        DiagStatus.FAIL to "无可播放条目"
                    } else {
                        val baseRule = playRule ?: homeRule ?: rule
                        var lastReason = "无可播放条目"
                        var found: Pair<String, Boolean>? = null
                        for (candidate in candidates.take(3)) {
                            when (val action = engine.actionForItem(baseRule, candidate)) {
                                is MiniAppEngine.ItemAction.Play -> {
                                    found = action.url to false
                                    break
                                }
                                is MiniAppEngine.ItemAction.SniffPlay -> {
                                    found = action.pageUrl to true
                                    break
                                }
                                is MiniAppEngine.ItemAction.OpenList -> {
                                    val inner = engine.loadHome(action.rule, 1)
                                    if (inner is MiniAppEngine.PageResult.ListPage) {
                                        val innerTarget = inner.items.firstOrNull { it.type != "header" && it.url.isNotBlank() }
                                        if (innerTarget != null) {
                                            when (val innerAction = engine.actionForItem(action.rule, innerTarget)) {
                                                is MiniAppEngine.ItemAction.Play -> {
                                                    found = innerAction.url to false
                                                    break
                                                }
                                                is MiniAppEngine.ItemAction.SniffPlay -> {
                                                    found = innerAction.pageUrl to true
                                                    break
                                                }
                                                is MiniAppEngine.ItemAction.Nothing -> lastReason = innerAction.reason
                                                is MiniAppEngine.ItemAction.Toast -> lastReason = innerAction.message
                                                else -> lastReason = "内层条目无法播放"
                                            }
                                            if (found != null) break
                                        }
                                    }
                                }
                                is MiniAppEngine.ItemAction.Nothing -> lastReason = action.reason
                                is MiniAppEngine.ItemAction.Toast -> lastReason = action.message
                                else -> lastReason = "条目无法播放"
                            }
                        }
                        if (found != null) {
                            if (found.second) {
                                DiagStatus.PASS to "需 WebView 嗅探（只解地址，未真播）：（" + found.first.take(120) + "）"
                            } else {
                                DiagStatus.PASS to "直链（未真播）：" + found.first.take(120)
                            }
                        } else {
                            DiagStatus.FAIL to lastReason
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            cancelled = true
            for (i in steps.indices) {
                if (steps[i].status == DiagStatus.RUNNING) {
                    steps[i] = DiagStepResult(names[i], DiagStatus.CANCELLED, "检测已取消", steps[i].millis)
                }
            }
            publish()
        } finally {
            try {
                synchronized(MiniAppHost.varMap) {
                    MiniAppHost.varMap.clear()
                    MiniAppHost.varMap.putAll(varSnapshot)
                }
            } catch (_: Throwable) {
            }
            try {
                engine.destroyTasks()
            } catch (_: Throwable) {
            }
        }
        return DiagReport(item.name, item.source, ArrayList(steps), cancelled, startedAt, System.currentTimeMillis())
    }
}
