package com.github.tvbox.osc.music

import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * MusicFree 插件订阅。
 *
 * 订阅源是一个 JSON：
 * { "desc": "0.2.0", "plugins": [ { "name": "酷狗", "url": "...js", "version": "0.1.4" } ] }
 *
 * 本类只负责拉清单 + 下载单个脚本正文，真正的导入交给 MusicStore.importScript，
 * 这样去重、健康度、激活态全部复用。
 */
object MusicSubscriptions {

    const val DEFAULT_URL = "https://musicfreepluginshub.2020818.xyz/plugins.json"

    private const val KEY_URL = "music_sub_url"
    private const val KEY_DESC = "music_sub_desc"
    private const val KEY_CACHE = "music_sub_cache"
    private const val TIMEOUT_MS = 20_000

    /** 连接超时：gitee 被污染时是直接 RST，等 20s 毫无意义，4s 够用 */
    private const val CONNECT_TIMEOUT_MS = 4_000

    /** 读取超时：插件正文一般 10~200KB，10s 足够 */
    private const val READ_TIMEOUT_MS = 10_000

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    data class Entry(val name: String, val url: String, val version: String) {
        val subtitle: String
            get() = if (version.isBlank()) "" else "v" + version
    }

    data class Catalog(val desc: String, val entries: List<Entry>) {
        val isEmpty: Boolean get() = entries.isEmpty()
    }

    fun url(): String = KV.get(KEY_URL, DEFAULT_URL)

    fun setUrl(value: String) {
        KV.put(KEY_URL, value.trim().ifEmpty { DEFAULT_URL })
    }

    fun desc(): String = KV.get(KEY_DESC, "")

    suspend fun fetch(force: Boolean = false): Catalog = withContext(Dispatchers.IO) {
        val target = url()
        if (!force) {
            cached()?.let { return@withContext it }
        }
        val body = runCatching { httpGet(target) }.getOrElse { th ->
            LOG.e("music-sub-fetch-failed " + target + " : " + th.message)
            return@withContext cached() ?: Catalog("", emptyList())
        }
        val parsed = runCatching { parse(body) }.getOrElse { th ->
            LOG.e("music-sub-parse-failed : " + th.message)
            Catalog("", emptyList())
        }
        if (!parsed.isEmpty) {
            KV.put(KEY_DESC, parsed.desc)
            KV.put(KEY_CACHE, body)
        }
        parsed
    }

    fun cached(): Catalog? {
        val raw = KV.get(KEY_CACHE, "")
        if (raw.isBlank()) return null
        return runCatching { parse(raw) }.getOrNull()?.takeIf { !it.isEmpty }
    }

    fun parse(body: String): Catalog {
        val text = body.trim()
        if (text.isEmpty()) return Catalog("", emptyList())
        val array: JSONArray
        var desc = ""
        if (text.startsWith("[")) {
            array = JSONArray(text)
        } else {
            val root = JSONObject(text)
            desc = root.optString("desc", "")
            array = root.optJSONArray("plugins") ?: JSONArray()
        }
        val list = ArrayList<Entry>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name", "").trim()
            val link = obj.optString("url", "").trim()
            if (name.isEmpty() || link.isEmpty()) continue
            list.add(Entry(name, link, obj.optString("version", "").trim()))
        }
        return Catalog(desc, list)
    }

    /**
     * 下载单个插件。
     *
     * 这里曾经非常慢（34 个插件几十分钟），原因是串行的「重试 3 轮 × 最多 3 个镜像」
     * 每一跳都吃满 20s 连接 + 20s 读取，最坏单个插件 360s，乘以 34 就是几小时。
     *
     * 现在改成：
     *   1. 所有候选地址**并发竞速**，谁先给出合法脚本就用谁，其余立刻取消；
     *   2. 单跳超时压到 connect 4s / read 10s，失败快速让位给下一个候选；
     *   3. 整轮最多两遍，第二遍只在前一遍全空时才发生。
     *
     * 结果：正常网络 1~2s，全线路被墙时约 15s 快速失败，不再挂几分钟。
     */
    suspend fun download(entry: Entry): String? = withContext(Dispatchers.IO) {
        repeat(2) { round ->
            val raced = raceCandidates(entry.url)
            if (!raced.isNullOrBlank()) return@withContext raced
            if (round == 0) runCatching { delay(300L) }
        }
        LOG.e("music-sub-download-failed " + entry.name + " : 全部线路均未取到脚本")
        null
    }

    /**
     * 并发打所有候选地址，返回第一个合法脚本。
     *
     * 用 coroutineScope + async 而不是线程池，任意一个成功就 cancel 兄弟任务，
     * 避免"已经拿到了还在后台等超时"。
     */
    private suspend fun raceCandidates(link: String): String? = coroutineScope {
        val urls = candidatesOf(link)
        val winner = CompletableDeferred<String?>()
        val jobs = urls.map { url ->
            async(Dispatchers.IO) {
                if (!winner.isCompleted) {
                    val text = runCatching { httpGetOnce(url) }.getOrNull()
                    if (!text.isNullOrBlank() && looksLikeScript(text)) {
                        winner.complete(text)
                    }
                }
                null
            }
        }
        // 全部失败时也要给 winner 一个结果，否则会一直等
        launch {
            jobs.joinAll()
            winner.complete(null)
        }
        val result = winner.await()
        jobs.forEach { it.cancel() }
        result
    }

    /**
     * 候选地址。
     *
     * 顺序即优先级：原站 → 通用反代 → 公共 CORS 代理。
     * gitee 的 raw 链接只能走原站，ghproxy 类代理只认 github，不能盲目加。
     */
    private fun candidatesOf(link: String): List<String> = buildList {
        add(link)
        if (link.contains("gitee.com/")) {
            // gitee 本体被 DNS 污染时，这两个反代有时能通
            add("https://ghproxy.net/" + link)
            add("https://gh-proxy.com/" + link)
        }
        if (link.contains("raw.githubusercontent.com") || link.contains("github.com/")) {
            add("https://ghproxy.net/" + link)
            add("https://gh-proxy.com/" + link)
            add("https://ghfast.top/" + link)
        }
    }.distinct()

    /** 简单校验：拿到的是脚本正文，而不是错误页或空壳 */
    private fun looksLikeScript(text: String): Boolean {
        val t = text.trim()
        if (t.length < 64) return false
        // 常见错误页特征
        if (t.startsWith("<!DOCTYPE", true) || t.startsWith("<html", true)) return false
        return t.contains("module.exports") || t.contains("exports.") ||
            t.contains("globalThis") || t.contains("function")
    }

    suspend fun install(
        entries: List<Entry>,
        onProgress: (done: Int, total: Int, name: String) -> Unit = { _, _, _ -> },
    ): List<MusicStore.ImportResult?> = withContext(Dispatchers.IO) {
        // 并发装，最多同时 4 个。
        //
        // 之前是 forEach 串行：34 个插件即使每个只要 2s，光是排队也要一分钟以上，
        // 网络一抖动就更久（用户看到的"下载那么慢"就是这个）。
        // 4 路并发既能把总时间压到 1/3 左右，又不会因为连接数太多被对端限流。
        val total = entries.size
        val results = arrayOfNulls<MusicStore.ImportResult?>(total)
        var done = 0
        val lock = Any()
        val semaphore = kotlinx.coroutines.sync.Semaphore(4)

        coroutineScope {
            entries.forEachIndexed { index, entry ->
                launch {
                    semaphore.withPermit {
                        val code = download(entry)
                        results[index] =
                            if (code == null) null else MusicStore.importScript(code, entry.name)
                        val current = synchronized(lock) {
                            done += 1
                            done
                        }
                        onProgress(current, total, entry.name)
                    }
                }
            }
        }
        onProgress(total, total, "")
        results.toList()
    }

    private fun httpGet(link: String): String {
        // 清单拉取用（单次，不竞速）：失败就回退镜像。
        var lastError: Throwable? = null
        for (url in candidatesOf(link)) {
            try {
                return httpGetOnce(url)
            } catch (th: Throwable) {
                lastError = th
            }
        }
        throw lastError ?: IllegalStateException("no candidate")
    }

    /**
     * 单次 GET。
     *
     * 超时从 20s/20s 压到 4s/10s：gitee 在这台设备的网络下要么秒回、
     * 要么直接 RST，等满 20 秒纯属浪费。快速失败才能让并发竞速真正起作用。
     */
    private fun httpGetOnce(link: String): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(link).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "identity")
            }
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP " + code)
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
