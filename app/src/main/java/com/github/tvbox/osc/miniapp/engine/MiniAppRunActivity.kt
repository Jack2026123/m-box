package com.github.tvbox.osc.miniapp.engine

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import kotlinx.coroutines.launch
import org.json.JSONObject

class MiniAppRunActivity : ComponentActivity() {

    private val viewModel: MiniAppRunViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ruleJson = intent.getStringExtra(EXTRA_RULE_JSON) ?: ""
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        com.github.tvbox.osc.miniapp.MiniAppRunRegistry.register(name, this)
        try {
            val ruleTitle = org.json.JSONObject(ruleJson).optString("title")
            if (ruleTitle.isNotBlank() && ruleTitle != name) {
                com.github.tvbox.osc.miniapp.MiniAppRunRegistry.register(ruleTitle, this)
            }
        } catch (_: Throwable) {
        }
        viewModel.open(ruleJson, name)
        setContent {
            AVBoxTheme {
                RunScreen(viewModel) {
                    finish()
                }
            }
        }
        lifecycleScopeLaunchPlayEvents()
        lifecycleScopeLaunchSniffEvents()
    }

    private var activeSniffer: MiniAppWebSniffer? = null

    private fun lifecycleScopeLaunchSniffEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sniffEvents.collect { event ->
                    Toast.makeText(this@MiniAppRunActivity, "正在嗅探播放地址…", Toast.LENGTH_SHORT).show()
                    activeSniffer?.cancel()
                    val sniffer = MiniAppWebSniffer(
                        activity = this@MiniAppRunActivity,
                        onFound = { url, headers ->
                            activeSniffer = null
                            launchMiniAppPlayer(url, headers, event.title)
                        },
                        onFail = { msg ->
                            activeSniffer = null
                            // 优先嗅探、直连也得有：嗅探失败时原地址是直链
                            // 媒体就直接播兜底，播放页则明示失败。
                            if (isMiniAppDirectVideoUrl(event.pageUrl)) {
                                launchMiniAppPlayer(event.pageUrl, event.headers, event.title)
                            } else {
                                Toast.makeText(this@MiniAppRunActivity, msg, Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                    activeSniffer = sniffer
                    sniffer.start(event.pageUrl, event.headers)
                }
            }
        }
    }

    private fun launchMiniAppPlayer(url: String, headers: Map<String, String>, title: String) {
        val intent = Intent(this, MiniAppPlayerActivity::class.java)
        intent.putExtra(MiniAppPlayerActivity.EXTRA_URL, url)
        intent.putExtra(MiniAppPlayerActivity.EXTRA_TITLE, title)
        val headersJson = JSONObject()
        for ((k, v) in headers) {
            headersJson.put(k, v)
        }
        intent.putExtra(MiniAppPlayerActivity.EXTRA_HEADERS_JSON, headersJson.toString())
        startActivity(intent)
    }

    override fun onDestroy() {
        activeSniffer?.cancel()
        activeSniffer = null
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        com.github.tvbox.osc.miniapp.MiniAppRunRegistry.unregister(name, this)
        try {
            val ruleTitle = org.json.JSONObject(intent.getStringExtra(EXTRA_RULE_JSON) ?: "").optString("title")
            if (ruleTitle.isNotBlank() && ruleTitle != name) {
                com.github.tvbox.osc.miniapp.MiniAppRunRegistry.unregister(ruleTitle, this)
            }
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    private fun lifecycleScopeLaunchPlayEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.playEvents.collect { event ->
                    val intent = Intent(this@MiniAppRunActivity, MiniAppPlayerActivity::class.java)
                    intent.putExtra(MiniAppPlayerActivity.EXTRA_URL, event.url)
                    intent.putExtra(MiniAppPlayerActivity.EXTRA_TITLE, event.title)
                    val headersJson = JSONObject()
                    for ((k, v) in event.headers) {
                        headersJson.put(k, v)
                    }
                    intent.putExtra(MiniAppPlayerActivity.EXTRA_HEADERS_JSON, headersJson.toString())
                    startActivity(intent)
                }
            }
        }
    }

    companion object {
        const val EXTRA_RULE_JSON = "miniapp_rule_json"
        const val EXTRA_NAME = "miniapp_name"
    }
}

@Composable
private fun RunScreen(viewModel: MiniAppRunViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val page = state.stack.lastOrNull()
    BackHandler {
        if (!viewModel.back()) {
            onClose()
        }
    }
    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = {
                        if (!viewModel.back()) {
                            onClose()
                        }
                    }) {
                        Text("‹")
                    }
                    Text(
                        text = page?.pageTitle?.ifEmpty { "小程序" } ?: "小程序",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClose) {
                        Text("✕")
                    }
                }
                if (page != null && page.rule.searchUrl.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = state.searchText,
                            onValueChange = { viewModel.onSearchTextChange(it) },
                            placeholder = { Text("搜索") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { viewModel.search() }) {
                            Text("搜索")
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (page != null && !page.loading && page.error == null && page.items.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Button(
                        onClick = { viewModel.prevPage() },
                        enabled = page.page > 1,
                    ) {
                        Text("上一页")
                    }
                    Text("第 ${page.page} 页")
                    Button(onClick = { viewModel.nextPage() }) {
                        Text("下一页")
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                page == null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("无内容")
                    }
                }
                page.loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                page.error != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(page.error ?: "解析失败")
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.retry() }) {
                            Text("重试")
                        }
                    }
                }
                page.items.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("没有内容")
                    }
                }
                else -> {
                    val blocks = remember(page.items) { groupIntoBlocks(page.items) }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(blocks) { block ->
                            when (block) {
                                is MiniAppBlock.Chips -> MiniAppChipRow(
                                    items = block.items,
                                    rule = page.rule,
                                    onFilterChipClick = { item, idx ->
                                        viewModel.onFilterChipClick(item, idx)
                                    },
                                    onItemClick = { item ->
                                        viewModel.openItem(item)
                                    },
                                )
                                is MiniAppBlock.Grid -> Column(
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    for (row in block.rows) {
                                        MiniAppItemGridRow(row) { item ->
                                            viewModel.openItem(item)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniAppItemRow(item: MiniAppListItem, onClick: () -> Unit) {
    val hasPic = item.pic.isNotEmpty() && item.pic != "*"
    val isTextType = item.type.startsWith("text") || item.type == "long_text" ||
        item.type == "rich_text" || item.type == "header" || item.type == "footer"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasPic && !isTextType) {
            AsyncImage(
                model = item.pic,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stripHtml(item.title),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val subLine = stripHtml(if (item.descMore.isNotEmpty()) item.descMore else item.desc)
            if (subLine.isNotEmpty()) {
                Text(
                    text = subLine,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val cleanContent = stripHtml(item.content)
            if (cleanContent.isNotEmpty()) {
                Text(
                    text = cleanContent,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// 海阔 60 格栅格：按 item 的 col_type span 分行，span 之和满 60 换行，
// 不满 60 的行尾补空白权重，保证每格宽度恒为 span/60，与海阔 GridLayoutManager 对齐。
internal fun groupIntoRows(items: List<MiniAppListItem>): List<List<Pair<MiniAppListItem, Int>>> {
    // 同 URL 只留后一次：规则常把同一影片既当大卡（movie_1）又放进网格（movie_3），
    // 留后一次即去掉前面的大卡、网格上移；空 URL（纯文本/提示项）不参与去重。
    val lastIndex = HashMap<String, Int>()
    for (i in items.indices) {
        val u = items[i].url
        if (u.isNotEmpty()) lastIndex[u] = i
    }
    val deduped = items.filterIndexed { i, it -> it.url.isEmpty() || lastIndex[it.url] == i }
    val rows = ArrayList<List<Pair<MiniAppListItem, Int>>>()
    var cur = ArrayList<Pair<MiniAppListItem, Int>>()
    var used = 0
    for (item in deduped) {
        val span = MiniAppColType.getByCode(item.type).spanCount.coerceIn(1, 60)
        if (cur.isNotEmpty() && used + span > 60) {
            rows.add(cur)
            cur = ArrayList()
            used = 0
        }
        cur.add(item to span)
        used += span
        if (used >= 60) {
            rows.add(cur)
            cur = ArrayList()
            used = 0
        }
    }
    if (cur.isNotEmpty()) rows.add(cur)
    return rows
}

sealed class MiniAppBlock {
    data class Chips(val items: List<MiniAppListItem>) : MiniAppBlock()
    data class Grid(val rows: List<List<Pair<MiniAppListItem, Int>>>) : MiniAppBlock()
}

// 简介/副行显示层去 HTML：规则常把 <small><font> 之类标签原样塞进
// desc/content，直接显示就是乱码；只清显示层，不动数据。另去首部成对
// 空引号（规则拼接残留的 "" 前缀）。
internal fun stripHtml(raw: String): String {
    var s = raw.replace(Regex("<[^>]*>"), "")
    s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&#34;", "\"")
    s = s.trim()
    if (s.startsWith("\"\"")) s = s.substring(2).trim()
    return s
}

// 筛选横排：连续的筛选类项聚成一行横向 chip（海阔 header tab /
// scroll_button / flex_button 同款），点 chip 即筛、同页重载；其余项
// 照旧走 60 格栅格。短文本项（无图无副文、标题 ≤8 字）也进 chip，
// 覆盖规则把类型/年份当纯文本项推的情形；长文本提示项不动。
internal fun isChipCandidate(item: MiniAppListItem): Boolean {
    return when (item.type) {
        "header", "scroll_button", "flex_button" -> true
        "text_1", "text_center_1" -> item.pic.isEmpty() && item.desc.isEmpty() &&
            item.descMore.isEmpty() && item.content.isEmpty() &&
            item.title.isNotEmpty() && item.title.length <= 8
        else -> false
    }
}

// 海阔 header tab 行：一项内含 & 分隔的全部选项，自身就是一行。
internal fun isTabRowItem(item: MiniAppListItem): Boolean {
    if (item.type != "header") return false
    if (!item.title.contains("&") || !item.url.contains("&")) return false
    val n = item.title.split("&").size
    val v = item.url.split("&").size
    return n > 1 && n == v
}

internal fun groupIntoBlocks(items: List<MiniAppListItem>): List<MiniAppBlock> {
    val lastIndex = HashMap<String, Int>()
    for (i in items.indices) {
        val u = items[i].url
        if (u.isNotEmpty()) lastIndex[u] = i
    }
    val deduped = items.filterIndexed { i, it -> it.url.isEmpty() || lastIndex[it.url] == i }
    val blocks = ArrayList<MiniAppBlock>()
    var chips = ArrayList<MiniAppListItem>()
    var grid = ArrayList<MiniAppListItem>()
    fun flushChips() {
        if (chips.isNotEmpty()) {
            blocks.add(MiniAppBlock.Chips(chips))
            chips = ArrayList()
        }
    }
    fun flushGrid() {
        if (grid.isNotEmpty()) {
            blocks.add(MiniAppBlock.Grid(groupIntoRows(grid)))
            grid = ArrayList()
        }
    }
    for (item in deduped) {
        // 网站什么样就显示什么：详情数据里带出来的「本地」线路行不要；
        // chip 行里的全空占位项（无标题无图无副文）只占位不显示，一并去掉。
        // 只拦 chip 类项：带图的海报/剧集项不动，避免打乱栅格结构。
        if (isChipCandidate(item) && (item.title == "本地" || (item.title.isEmpty() &&
            item.pic.isEmpty() && item.desc.isEmpty() && item.descMore.isEmpty() &&
            item.content.isEmpty() && item.url.isEmpty()))
        ) {
            continue
        }
        if (!isChipCandidate(item)) {
            flushChips()
            grid.add(item)
            continue
        }
        flushGrid()
        if (isTabRowItem(item)) {
            flushChips()
            blocks.add(MiniAppBlock.Chips(listOf(item)))
            continue
        }
        val prev = chips.lastOrNull()
        val split = prev != null && (
            prev.type != item.type ||
                (prev.type == "header" && item.type == "header" && prev.desc != item.desc) ||
                item.title == "全部"
        )
        if (split) flushChips()
        chips.add(item)
    }
    flushChips()
    flushGrid()
    return blocks
}

@Composable
private fun MiniAppChipRow(
    items: List<MiniAppListItem>,
    rule: MiniAppRule,
    onFilterChipClick: (MiniAppListItem, Int) -> Unit,
    onItemClick: (MiniAppListItem) -> Unit,
) {
    // 筛选换行排满（用户 2026-10-25 定）：chip 不再横滑切边，FlowRow
    // 按宽度换行排满，一眼看全。
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (items.size == 1 && isTabRowItem(items[0])) {
            val item = items[0]
            val names = item.title.split("&")
            val values = item.url.split("&")
            val current = headerFieldValue(rule, item.desc)
            for (i in names.indices) {
                FilterChip(
                    selected = current == values[i],
                    onClick = { onFilterChipClick(item, i) },
                    label = { Text(stripHtml(names[i])) },
                )
            }
        } else {
            for (item in items) {
                val isFilter = item.type == "header" && headerDimension(item.desc) != null &&
                    !item.url.contains("@rule=")
                val selected = isFilter && headerFieldValue(rule, item.desc) == item.url
                FilterChip(
                    selected = selected,
                    onClick = {
                        if (isFilter) onFilterChipClick(item, -1) else onItemClick(item)
                    },
                    label = { Text(stripHtml(item.title)) },
                )
            }
        }
    }
}

@Composable
private fun MiniAppItemGridRow(
    row: List<Pair<MiniAppListItem, Int>>,
    onItemClick: (MiniAppListItem) -> Unit,
) {
    if (row.size == 1 && row[0].second >= 60) {
        MiniAppItemRow(row[0].first) { onItemClick(row[0].first) }
        return
    }
    val used = row.sumOf { it.second }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for ((item, span) in row) {
            Box(
                modifier = Modifier
                    .weight(span.toFloat())
                    .clickable { onItemClick(item) },
                contentAlignment = Alignment.TopCenter,
            ) {
                MiniAppGridCell(item)
            }
        }
        if (used < 60) {
            Spacer(modifier = Modifier.weight((60 - used).toFloat()))
        }
    }
}

@Composable
private fun MiniAppGridCell(item: MiniAppListItem) {
    val hasPic = item.pic.isNotEmpty() && item.pic != "*"
    val isTextType = item.type.startsWith("text") || item.type == "long_text" ||
        item.type == "rich_text" || item.type == "header" || item.type == "footer"
    val isIconType = item.type.startsWith("icon")
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (hasPic && !isTextType) {
            if (isIconType) {
                AsyncImage(
                    model = item.pic,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp),
                )
            } else {
                AsyncImage(
                    model = item.pic,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
        Text(
            text = stripHtml(item.title),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        val subLine = stripHtml(if (item.descMore.isNotEmpty()) item.descMore else item.desc)
        if (subLine.isNotEmpty()) {
            Text(
                text = subLine,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}
