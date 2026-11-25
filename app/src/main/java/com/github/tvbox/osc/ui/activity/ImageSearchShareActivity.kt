package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.player.imagesearch.ImageSearchApi
import com.github.tvbox.osc.player.imagesearch.ImageSearchPlay
import com.github.tvbox.osc.ui.components.SheetHostScaffold
import com.github.tvbox.osc.ui.components.WallpaperBackdropPage
import com.github.tvbox.osc.ui.page.openVodCardOrDetail
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge

/**
 * 3.66 分享图片进 AVBox 识图：照用户发的 Vesper TV 视频那套。
 * 微信（或别的 App）分享一张图片 → 选 AVBox → 弹确认“检测到分享的图片，是否用
 * 「识图搜片」识别作品编号？” → 开始识别 → 搜片页（图片＋编号/分数/分档/时间）
 * → 点编号先出找源页“正在找个源来搜…”→“已出 N 源 / 共 M 源”，点一条结果进
 * 详情播。识图本身复用 ImageSearchApi（avscan.cc），找源复用
 * ImageSearchPlay.searchAll（3.65 的并行搜法，边找边回）。
 *
 * 入口在 Manifest：ACTION_SEND + image 类型，AVBox 出现在系统分享列表里。
 * 不走播放页 🔍 那套（那是截当前帧），这里是外面传进来的图。
 */
class ImageSearchShareActivity : BaseActivity() {

    private enum class Stage { CONFIRM, SEARCHING, RESULTS, SOURCES }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var sharedBitmap by mutableStateOf<Bitmap?>(null)
    private var stage by mutableStateOf(Stage.CONFIRM)
    private var results by mutableStateOf<List<ImageSearchApi.VideoResult>?>(null)
    private var searchError by mutableStateOf<String?>(null)
    private var elapsedMs by mutableStateOf(0L)

    private val sourceHits = mutableStateListOf<Movie.Video>()
    private var sourceTotal by mutableStateOf(0)
    private var sourceSearched by mutableStateOf(0)
    private var sourceDone by mutableStateOf(false)
    private var sourceCode by mutableStateOf("")
    // 两栏布局：左边选中的源 key，null = 全部。
    private var selectedSourceKey by mutableStateOf<String?>(null)
    // 3.67：冷启动时源配置还没加载，源列表是空的——搜源前没这一步，
    // 找源页会直接“共 0 源 / 都没搜到这个编号”。
    private var sourcesNotLoaded by mutableStateOf(false)
    private val sourceNameMap = mutableStateMapOf<String, String>()

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val bmp = decodeSharedImage(uri)
        if (bmp == null) {
            Toast.makeText(this, "这张图读不出来", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        sharedBitmap = bmp
        runSearch(bmp)
    }

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        loadSourceNames()
        handleShareIntent(intent)
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                SheetHostScaffold {
                    WallpaperBackdropPage {
                        ImageSearchShareScreen()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        ImageSearchPlay.cancelSearch()
    }

    private fun loadSourceNames() {
        Thread({
            try {
                val map = ApiConfig.get().getSourceBeanList()
                    .mapNotNull { bean ->
                        val key = bean.key ?: return@mapNotNull null
                        key to (bean.name ?: key)
                    }.toMap()
                mainHandler.post { sourceNameMap.putAll(map) }
            } catch (ignored: Exception) {
            }
        }, "avscan-names").start()
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) {
            finish()
            return
        }
        val action = intent.action
        val type = intent.type ?: ""
        val uri: Uri? = when {
            Intent.ACTION_SEND == action && type.startsWith("image/") -> {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            }
            Intent.ACTION_SEND_MULTIPLE == action && type.startsWith("image/") -> {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.firstOrNull()
            }
            else -> null
        }
        if (uri == null) {
            Toast.makeText(this, "没收到图片", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val bmp = decodeSharedImage(uri)
        if (bmp == null) {
            Toast.makeText(this, "这张图读不出来", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        sharedBitmap = bmp
        stage = Stage.CONFIRM
        results = null
        searchError = null
    }

    /** 读分享来的图：长边压到 2048 以内再解，避免大图直接 OOM；搜的时候还会再压到 1024。 */
    private fun decodeSharedImage(uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            var sample = 1
            while (maxOf(w, h) / sample > 2048) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun runSearch(bitmap: Bitmap) {
        stage = Stage.SEARCHING
        searchError = null
        results = null
        val copy = try {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } catch (e: Exception) {
            null
        }
        if (copy == null) {
            mainHandler.post {
                searchError = "这张图读不出来"
                stage = Stage.RESULTS
            }
            return
        }
        // compressFrame 会把 copy recycle 掉，原图留着页面上显示。
        val bytes = ImageSearchApi.compressFrame(copy)
        if (bytes == null) {
            mainHandler.post {
                searchError = "这张图读不出来"
                stage = Stage.RESULTS
            }
            return
        }
        ImageSearchApi.search(bytes, object : ImageSearchApi.SearchCallback {
            override fun onSuccess(list: List<ImageSearchApi.VideoResult>, ms: Long) {
                mainHandler.post {
                    results = list
                    elapsedMs = ms
                    stage = Stage.RESULTS
                }
            }

            override fun onError(message: String) {
                mainHandler.post {
                    searchError = message
                    stage = Stage.RESULTS
                }
            }
        })
    }

    /** 点编号：先把源配置加载出来（走缓存），有源了再出找源页边找边回“已出 N 源 / 共 M 源”，点一条结果进详情播。 */
    private fun openSourceSearch(code: String) {
        sourceCode = code
        sourceHits.clear()
        sourceTotal = 0
        sourceSearched = 0
        sourceDone = false
        sourcesNotLoaded = false
        selectedSourceKey = null
        stage = Stage.SOURCES
        ensureSourcesLoaded { ready ->
            if (!ready) {
                mainHandler.post {
                    sourcesNotLoaded = true
                    sourceDone = true
                }
                return@ensureSourcesLoaded
            }
            ImageSearchPlay.searchAll(
                code = code,
                currentSourceKey = null,
                onStart = { total -> mainHandler.post { sourceTotal = total } },
                onProgress = { searched, _ -> mainHandler.post { sourceSearched = searched } },
                onHit = { video -> mainHandler.post { sourceHits.add(video) } },
                onDone = { mainHandler.post { sourceDone = true } },
            )
        }
    }

    /**
     * 3.67：微信分享是冷启动，ApiConfig 的源列表还是空的（订阅配置要 loadConfig
     * 才装进来），直接搜源会“共 0 源”。先看列表里有没有源，有就直接用；
     * 没有就 loadConfig(true) 走缓存装一次，装完再看；还是空就回 false。
     * 回调在后台线程，ready 的处理由调用方自己贴回主线程。
     */
    private fun ensureSourcesLoaded(onReady: (Boolean) -> Unit) {
        try {
            if (ApiConfig.get().getSourceBeanList().isNotEmpty()) {
                onReady(true)
                return
            }
        } catch (ignored: Exception) {
        }
        ApiConfig.get().loadConfig(true, object : ApiConfig.LoadConfigCallback {
            override fun success() {
                val ready = try {
                    ApiConfig.get().getSourceBeanList().isNotEmpty()
                } catch (ignored: Exception) {
                    false
                }
                if (ready) loadSourceNames()
                onReady(ready)
            }

            override fun error(msg: String?) {
                onReady(false)
            }

            override fun notice(msg: String?) {
            }
        }, null)
    }

    @Composable
    private fun ImageSearchShareScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "识图搜片",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = MaterialTheme.typography.titleLarge.fontSize,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { finish() }) {
                    Text(text = "关闭", color = MaterialTheme.colorScheme.onSurface)
                }
            }
            val bmp = sharedBitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                )
                Spacer(Modifier.height(12.dp))
            }
            when (stage) {
                Stage.CONFIRM -> {
                    // 确认弹框盖在图上，取消就关掉本页。
                    AlertDialog(
                        onDismissRequest = { finish() },
                        title = { Text(text = "识图搜片") },
                        text = { Text(text = "检测到分享的图片，是否用「识图搜片」识别作品编号？") },
                        confirmButton = {
                            TextButton(onClick = {
                                val b = sharedBitmap
                                if (b != null) runSearch(b)
                            }) {
                                Text(text = "开始识别")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { finish() }) {
                                Text(text = "取消")
                            }
                        },
                    )
                }
                Stage.SEARCHING -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "正在识别…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Stage.RESULTS -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(onClick = { pickImage.launch("image/*") }) {
                            Text(text = "选择图片")
                        }
                        Button(onClick = {
                            val b = sharedBitmap
                            if (b != null) runSearch(b)
                        }) {
                            Text(text = "开始搜索")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    val err = searchError
                    if (err != null) {
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        val list = results ?: emptyList()
                        Text(
                            text = "识别到 " + list.size + " 个候选，点击编号可继续播放 · 用时 " + (elapsedMs / 1000) + " 秒",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(list) { video ->
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Button(onClick = { openSourceSearch(video.code) }) {
                                            Text(text = video.code)
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = ImageSearchApi.tierText(video.tier) + " " + video.sim + " 分",
                                            color = when (video.tier) {
                                                ImageSearchApi.Tier.HIGH -> MaterialTheme.colorScheme.primary
                                                ImageSearchApi.Tier.MID -> MaterialTheme.colorScheme.tertiary
                                                ImageSearchApi.Tier.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        video.frames.forEach { frame ->
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                ShareThumbImage(frame.thumb)
                                                Text(
                                                    text = frame.time + " · " + frame.sim + " 分",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Stage.SOURCES -> {
                    val found = sourceHits.distinctBy { it.sourceKey }.size
                    Text(
                        text = sourceCode + "（已出 " + found + " 源 / 共 " + sourceTotal + " 源）",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (!sourceDone) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "正在找个源来搜…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "已搜 " + sourceSearched + " / " + sourceTotal + " 源",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (sourceHits.isEmpty() && sourceDone) {
                        Text(
                            text = if (sourcesNotLoaded) "源还没加载出来，退出重进一次" else "都没搜到这个编号",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (sourceHits.isNotEmpty()) {
                        // 两栏：左边源、右边结果，照正常搜索页 RailResults 的样子。
                        // 左边只列已出结果的源（随到随出），点一个只看它；“全部”看所有结果。
                        val railSources = sourceHits.distinctBy { it.sourceKey }
                        val shownHits = if (selectedSourceKey == null) sourceHits else sourceHits.filter { it.sourceKey == selectedSourceKey }
                        val listState = rememberLazyListState()
                        LaunchedEffect(selectedSourceKey) {
                            if (shownHits.isNotEmpty()) listState.scrollToItem(0)
                        }
                        Row(modifier = Modifier.weight(1f)) {
                            LazyColumn(
                                modifier = Modifier.width(SearchRailWidth),
                                contentPadding = PaddingValues(end = 10.dp, bottom = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                item(key = "rail_all") {
                                    SearchRailItem(
                                        name = stringResource(R.string.common_all),
                                        selected = selectedSourceKey == null,
                                        onClick = { selectedSourceKey = null },
                                    )
                                }
                                items(railSources, key = { "rail_" + (it.sourceKey ?: "") }) { video ->
                                    val key = video.sourceKey.orEmpty()
                                    SearchRailItem(
                                        name = sourceNameMap[key] ?: key,
                                        selected = selectedSourceKey == key,
                                        onClick = { selectedSourceKey = key },
                                    )
                                }
                            }
                            VerticalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                modifier = Modifier.padding(bottom = 12.dp),
                            )
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(start = 10.dp, end = 12.dp, bottom = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(shownHits) { video ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        // 源结果的 pic 是源站海报全地址，走 Coil 的 AsyncImage；
                                        // ShareThumbImage 只认 avscan 的 /thumb/ 路径，这里用它会全空。
                                        val pic = video.pic.orEmpty()
                                        if (pic.isNotEmpty()) {
                                            AsyncImage(
                                                model = pic,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.size(80.dp, 108.dp),
                                            )
                                        } else {
                                            Box(Modifier.size(80.dp, 108.dp))
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = video.name ?: video.id ?: "",
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            val sName = video.sourceKey?.let { sourceNameMap[it] }
                                            // 选了单个源时行里不再重复写源名（左边栏已经标了），照 RailResults。
                                            if (selectedSourceKey == null && !sName.isNullOrEmpty()) {
                                                Text(
                                                    text = sName,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        TextButton(onClick = {
                                            try {
                                                openVodCardOrDetail(video)
                                            } catch (e: Exception) {
                                                Toast.makeText(
                                                    this@ImageSearchShareActivity,
                                                    "打不开详情",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        }) {
                                            Text(text = "播放")
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

/** 缩略图：照 ImageSearchSheet 的 ThumbImage，走 ImageSearchApi.fetchThumb（内存直取，不落盘）。 */
@Composable
private fun ShareThumbImage(path: String) {
    val mainHandler = androidx.compose.runtime.remember { Handler(Looper.getMainLooper()) }
    var bitmap by androidx.compose.runtime.remember(path) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }
    androidx.compose.runtime.LaunchedEffect(path) {
        ImageSearchApi.fetchThumb(path) { bmp ->
            mainHandler.post { bitmap = bmp }
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(160.dp, 90.dp),
        )
    } else {
        Box(Modifier.size(160.dp, 90.dp))
    }
}
