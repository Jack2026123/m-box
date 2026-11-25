package com.github.tvbox.osc.music

import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * 音乐源存储 + 音乐模式开关。
 *
 * 设计要点（都是对照 2.68 之前踩过的坑定的）：
 *  1. 脚本全文存 KV，不依赖外部文件 / 不依赖下载，导入成功就一定还在
 *  2. `activeSourceId` 非空 = 音乐模式打开，MainScreen 据此换首页（与阅读模式同构）
 *  3. 音乐模式和阅读模式互斥，避免两个"接管首页"的开关同时生效
 *  4. 列表序列化用 JSONArray<JSONObject>，不用自定义分隔符（脚本里全是换行和 tab）
 */
object MusicStore {

    private val sourcesFlow = MutableStateFlow(loadSources())

    /** 全部已导入的源（导入顺序） */
    val sources: StateFlow<List<MusicSource>> = sourcesFlow

    private val activeFlow = MutableStateFlow(KV.get(HawkConfig.MUSIC_ACTIVE_SOURCE, ""))

    /** 当前激活的源 id；空串 = 非音乐模式 */
    val activeSourceId: StateFlow<String> = activeFlow

    // ---------------------------------------------------------------- load/persist

    private fun loadSources(): List<MusicSource> {
        val raw = KV.get(HawkConfig.MUSIC_SOURCE_LIST, ArrayList<String>())
        if (raw.isEmpty()) return emptyList()
        val list = ArrayList<MusicSource>(raw.size)
        for (text in raw) {
            try {
                val obj = JSONObject(text)
                val source = MusicSource(
                    id = obj.optString("id"),
                    name = obj.optString("name"),
                    version = obj.optString("version"),
                    description = obj.optString("description"),
                    author = obj.optString("author"),
                    homepage = obj.optString("homepage"),
                    kind = MusicSourceKind.of(obj.optString("kind")),
                    enabled = obj.optBoolean("enabled", true),
                    code = obj.optString("code"),
                )
                if (source.id.isNotEmpty()) list.add(source)
            } catch (th: Throwable) {
                // 单条坏数据不拖垮整个列表
                LOG.e("music-skip-bad-source: " + th.message)
            }
        }
        return list
    }

    private fun persist(next: List<MusicSource>) {
        val array = ArrayList<String>(next.size)
        for (source in next) {
            val obj = JSONObject()
            obj.put("id", source.id)
            obj.put("name", source.name)
            obj.put("version", source.version)
            obj.put("description", source.description)
            obj.put("author", source.author)
            obj.put("homepage", source.homepage)
            obj.put("kind", source.kind.name)
            obj.put("enabled", source.enabled)
            obj.put("code", source.code)
            array.add(obj.toString())
        }
        KV.put(HawkConfig.MUSIC_SOURCE_LIST, array)
        sourcesFlow.value = next
    }

    // ---------------------------------------------------------------- queries

    fun find(id: String?): MusicSource? {
        if (id.isNullOrEmpty()) return null
        return sourcesFlow.value.firstOrNull { it.id == id }
    }

    fun activeSource(): MusicSource? = find(activeFlow.value)

    /** 参与搜索的源：启用且脚本在 */
    fun searchable(): List<MusicSource> =
        sourcesFlow.value.filter { it.enabled && !it.codeMissing }

    /**
     * 按「最近是否成功取过链」排序的可搜索源列表。
     *
     * 动机：19 个脚本里有 2 个是坏的、几个重度混淆可能超时。
     * 搜歌是并行的，坏源会拖慢整体（每个源最多等 20 秒），
     * 而好用源的歌应该先出现。所以把最近成功的排前面。
     */
    fun searchableRanked(): List<MusicSource> {
        val list = searchable()
        if (list.size <= 1) return list
        return list.sortedByDescending { sourceScore(it.id) }
    }

    /** 成功率记录：id -> [成功次数, 失败次数] */
    private val healthStats = java.util.concurrent.ConcurrentHashMap<String, IntArray>()

    private const val HEALTH_KEY_PREFIX = "music_health_"

    fun noteHealth(id: String, success: Boolean) {
        val stats = healthStats.getOrPut(id) {
            val raw = KV.get(HEALTH_KEY_PREFIX + id, "")
            if (raw.isBlank()) {
                intArrayOf(0, 0)
            } else {
                val parts = raw.split(',')
                intArrayOf(
                    parts.getOrNull(0)?.toIntOrNull() ?: 0,
                    parts.getOrNull(1)?.toIntOrNull() ?: 0,
                )
            }
        }
        synchronized(stats) {
            if (success) stats[0]++ else stats[1]++
            KV.put(HEALTH_KEY_PREFIX + id, stats[0].toString() + "," + stats[1])
        }
    }

    /** 分数越高越优先：成功率 × 100 − 失败次数，没记录的排中间 */
    private fun sourceScore(id: String): Int {
        val stats = healthStats[id] ?: run {
            val raw = KV.get(HEALTH_KEY_PREFIX + id, "")
            if (raw.isBlank()) return 50
            val parts = raw.split(',')
            intArrayOf(
                parts.getOrNull(0)?.toIntOrNull() ?: 0,
                parts.getOrNull(1)?.toIntOrNull() ?: 0,
            ).also { healthStats[id] = it }
        }
        val ok = stats[0]
        val bad = stats[1]
        val total = ok + bad
        if (total == 0) return 50
        return (ok * 100 / total) - bad
    }

    /** 清掉健康记录（换源集时用） */
    fun resetHealth() {
        healthStats.clear()
    }

    // ---------------------------------------------------------------- MusicFree 开关

    private val musicFreeEnabledFlow = MutableStateFlow(
        com.github.tvbox.osc.util.KV.get(HawkConfig.MUSIC_MUSICFREE_ENABLED, false),
    )

    /** MusicFree 引擎是否启用（默认 false） */
    val musicFreeEnabled: StateFlow<Boolean> = musicFreeEnabledFlow

    /**
     * 当前是否应该启用 MusicFree。
     *
     * 双重条件：配置里打开 + 确实存在 MusicFree 源。
     * 后者避免用户开了开关但没装插件时白跑一遍引擎初始化。
     */
    val isMusicFreeActive: Boolean
        get() = musicFreeEnabledFlow.value &&
            sourcesFlow.value.any { it.kind == MusicSourceKind.MUSIC_FREE }

    fun setMusicFreeEnabled(enabled: Boolean) {
        com.github.tvbox.osc.util.KV.put(HawkConfig.MUSIC_MUSICFREE_ENABLED, enabled)
        musicFreeEnabledFlow.value = enabled
        if (!enabled) {
            // 关掉时立刻释放所有插件运行时，避免后台线程常驻
            MusicFreeHost.clearAll()
        }
        LOG.i("music-musicfree-enabled=" + enabled)
    }

    val isMusicMode: Boolean get() = activeFlow.value.isNotEmpty()

    // ---------------------------------------------------------------- import

    /**
     * 导入一份脚本。
     *
     * 返回 [ImportResult]，让 UI 能给出准确反馈（而不是一句"导入失败"）：
     *  - [ImportResult.Added]        新导入
     *  - [ImportResult.Updated]      同内容已存在，但这次脚本更完整（补回丢失的 code）
     *  - [ImportResult.Duplicated]   同内容已存在，无变化
     *  - [ImportResult.Invalid]      不是洛雪脚本 / 内容为空
     */
    fun importScript(
        code: String?,
        fallbackName: String = "",
        forcedKind: MusicSourceKind? = null,
    ): ImportResult {
        if (code.isNullOrBlank()) return ImportResult.Invalid("脚本内容为空")

        // 手感判定：MusicFree 插件长这样（module.exports = {...}）
        // 洛雪音源长这样（globalThis.lx），必须先判 IG 再判 lx，
        // 因为有些 MF 插件里也会出现字符串 "globalThis"
        var looksMusicFree = MusicFreeScriptProbe.looksMusicFree(code)
        val looksLx = code.contains("globalThis.lx") || code.contains("globalThis['lx']")
        if (fallbackName.contains(".plugin.", ignoreCase = true)) looksMusicFree = true

        if (forcedKind == null && !looksMusicFree && !looksLx) {
            return ImportResult.Invalid("既不是洛雪音源也不是 MusicFree 插件")
        }

        val kind = forcedKind
            ?: if (looksLx && !looksMusicFree) MusicSourceKind.LX else MusicSourceKind.MUSIC_FREE

        val meta = MusicScriptParser.parseMeta(code)
        val id = buildId(kind, code)
        val existing = sourcesFlow.value.firstOrNull { it.id == id }

        if (existing != null) {
            if (existing.code.isBlank()) {
                val next = sourcesFlow.value.map { if (it.id == id) it.copy(code = code) else it }
                persist(next)
                return ImportResult.Updated(existing.copy(code = code))
            }
            return ImportResult.Duplicated(existing)
        }

        // 同名但内容不同（作者更新了版本）：保留新版，清掉旧版，避免列表里两个"星海"
        // 没写 @name 的插件（混淆版）按文件名认名：kugou-source.plugin… → 酷狗音乐，
        // 1_qq-source.plugin… → QQ音乐；都没认出来才用文件名本身，再没有才用默认名。
        val displayName = if (meta.name.isNotBlank()) meta.name
            else MusicPlatformNames.nameFromFilename(fallbackName).ifBlank {
                fallbackName.ifBlank {
                    if (kind == MusicSourceKind.MUSIC_FREE) "MusicFree 插件" else "未命名源"
                }
            }
        val sameName = sourcesFlow.value.filter {
            displayName != "未命名源" && displayName != "MusicFree 插件" &&
                it.name == displayName && it.kind == kind
        }

        val source = MusicSource(
            id = id,
            name = displayName,
            version = meta.version,
            description = meta.description,
            author = meta.author,
            homepage = meta.homepage,
            kind = kind,
            enabled = true,
            code = code,
        )

        val next = sourcesFlow.value.filterNot { old -> sameName.any { it.id == old.id } } + source
        persist(next)

        // 如果被替换掉的旧版正是当前激活源，激活态平移到新版
        if (activeFlow.value.isNotEmpty() && sameName.any { it.id == activeFlow.value }) {
            setActive(source.id)
        }
        return ImportResult.Added(source)
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val next = sourcesFlow.value.map { if (it.id == id) it.withEnabled(enabled) else it }
        persist(next)
        // 停用当前激活源 = 退出音乐模式，避免"激活着但搜不出东西"
        if (!enabled && activeFlow.value == id) setActive("")
    }

    fun remove(id: String) {
        val next = sourcesFlow.value.filterNot { it.id == id }
        persist(next)
        if (activeFlow.value == id) setActive("")
    }

    fun removeAll() {
        persist(emptyList())
        setActive("")
    }

    /** 批量导入（源管理页支持一次选多个文件时用） */
    fun importMany(
        items: List<Pair<String, String>>,
        forcedKind: MusicSourceKind? = null,
    ): List<ImportResult> =
        items.map { (name, code) -> importScript(code, name, forcedKind) }

    // ---------------------------------------------------------------- mode

    fun setActive(id: String) {
        if (activeFlow.value == id) return
        KV.put(HawkConfig.MUSIC_ACTIVE_SOURCE, id)
        activeFlow.value = id
        if (id.isNotEmpty()) {
            // 与阅读模式互斥：同一时间只有一个功能接管首页
            runCatching { com.github.tvbox.osc.reading.ReadingStore.clearActive() }
                .onFailure { LOG.e("music-clear-reading-failed: " + it.message) }
            // 与点播模式互斥：只清当前激活的订阅地址（API_URL），订阅列表
            // （SUBSCRIBE_LIST）本身不动；已打开的配置管理页靠信号刷新选中态
            if (KV.get(HawkConfig.API_URL, "").isNotEmpty()) {
                KV.put(HawkConfig.API_URL, "")
                com.github.tvbox.osc.util.ApiLineSignal.notifyChanged()
            }
        }
    }

    fun clearActive() = setActive("")

    // ---------------------------------------------------------------- misc

    /** 内容指纹：同内容重复导入自动识别为同一个源 */
    private fun buildId(kind: MusicSourceKind, code: String): String {
        val hash = runCatching { com.github.tvbox.osc.util.MD5.encode(code) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: code.hashCode().toString()
        return kind.name + "_" + hash.take(12)
    }

    sealed class ImportResult {
        abstract val source: MusicSource?

        data class Added(override val source: MusicSource) : ImportResult()
        data class Updated(override val source: MusicSource) : ImportResult()
        data class Duplicated(override val source: MusicSource) : ImportResult()
        data class Invalid(val reason: String) : ImportResult() {
            override val source: MusicSource? get() = null
        }

        val ok: Boolean get() = this !is Invalid
    }
}